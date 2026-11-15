package com.v2ray.ang.core

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import java.lang.ref.SoftReference
import java.util.Locale

object CoreServiceManager {
    private const val AETHER_WARMUP_TIMEOUT_MS = 30_000L

    private val coreControllerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CoreNativeManager.newCoreController(CoreCallback())
    }
    private val coreController: CoreController
        get() = coreControllerDelegate.value
    private val mMsgReceive = ReceiveMessageHandler()
    private val coreLifecycleLock = Any()
    private val daemonScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentConfig: ProfileItem? = null
    private var currentVpnInterface: ParcelFileDescriptor? = null
    private var networkMonitor: NetworkMonitor? = null
    private var dialerService: IDialerService? = null
    private var activeMeasureJob: Job? = null
    private var aetherWarmUpJob: Job? = null

    @Volatile
    private var isReloading: Boolean = false
    @Volatile
    private var stoppedDuringReload: Boolean = false
    @Volatile
    private var isAetherWarmingUp: Boolean = false
    @Volatile
    private var sessionStartedAtMs: Long = 0L

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService() ?: return
            val appContext = service.applicationContext
            daemonScope.launch {
                runCatching { CoreNativeManager.initCoreEnv(appContext) }
                    .onFailure { LogUtil.e(AppConfig.TAG, "Async core env init failed", it) }
            }
        }

    /**
     * Starts the V2Ray / Aether core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (isServiceRunning()) {
            return false
        }

        val service = getService() ?: return false
        val guid = MmkvManager.getSelectServer() ?: return false
        val config = MmkvManager.decodeServerConfig(guid) ?: return false

        try {
            CoreNativeManager.initCoreEnv(service.applicationContext)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to initialize core env before startCoreLoop", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            return false
        }

        val result = CoreConfigManager.getV2rayConfig(service, guid)
        if (!result.status) {
            val errorMsg = result.errorMessage.takeIf { result.localizedError } ?: ""
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, errorMsg)
            return false
        }

        val aetherCore = result.aetherCore
        if (aetherCore != null) {
            AetherCoreManager.refusalOf(aetherCore, result.content)?.let { refusal ->
                LogUtil.e(AppConfig.TAG, "CoreServiceManager: refusing Aether session: ${refusal.reason}")
                val msg = service.getString(refusal.message, refusal.argument)
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, msg)
                return false
            }

            val ports = AetherCoreManager.sessionPorts(aetherCore, result.content)
            val portCheck = AetherCoreManager.checkSessionPorts(
                context = service,
                core = aetherCore,
                ports = ports,
                afterProbes = true,
                timeoutMs = AetherCoreManager.PORT_CHECK_WAIT_MS
            )
            if (portCheck is AetherCoreManager.PortCheck.Taken) {
                val msg = service.getString(AetherCoreManager.takenMessage(portCheck), portCheck.port)
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, msg)
                return false
            }
        }

        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        mFilter.addAction(Intent.ACTION_SCREEN_ON)
        mFilter.addAction(Intent.ACTION_SCREEN_OFF)
        mFilter.addAction(Intent.ACTION_USER_PRESENT)
        ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())

        currentConfig = config
        currentVpnInterface = vpnInterface
        stoppedDuringReload = false
        isReloading = false

        val started = startCoreInstance(service, result, vpnInterface, isReload = false)
        if (!started) {
            try {
                service.unregisterReceiver(mMsgReceive)
            } catch (_: Exception) {
            }
            return false
        }

        startNetworkMonitor(service)
        return true
    }

    private fun startCoreInstance(
        service: Service,
        result: ConfigResult,
        vpnInterface: ParcelFileDescriptor?,
        isReload: Boolean
    ): Boolean {
        try {
            val fd = vpnInterface?.fd ?: 0
            synchronized(coreLifecycleLock) {
                coreController.startLoop(result.content, fd)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to start Core loop", e)
            if (!isReload) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            }
            return false
        }

        if (!isRunning()) {
            if (!isReload) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            }
            return false
        }

        val aetherCore = result.aetherCore
        if (!isReload) {
            sessionStartedAtMs = System.currentTimeMillis()
            NotificationManager.onSessionStart(sessionStartedAtMs)
            NotificationManager.showNotification(currentConfig, service)
            NotificationManager.startSpeedNotification(currentConfig)

            if (aetherCore != null) {
                startAetherSessionAndWarmUp(service, aetherCore)
            } else {
                isAetherWarmingUp = false
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            }
        } else {
            if (sessionStartedAtMs == 0L) {
                sessionStartedAtMs = System.currentTimeMillis()
            }
            NotificationManager.onCoreReloaded()
            NotificationManager.startSpeedNotification(currentConfig)
        }

        return true
    }

    private fun startAetherSessionAndWarmUp(service: Service, aetherCore: AetherCore) {
        isAetherWarmingUp = true
        MessageHelper.sendMsg2UI(
            service,
            AppConfig.MSG_STATE_CONNECTING,
            service.getString(R.string.aether_core_connecting)
        )

        aetherWarmUpJob?.cancel()
        AetherCoreManager.start(service, aetherCore, afterProbes = true) {
            if (isServiceRunning()) {
                val reason = AetherCoreManager.stoppedMessage(service)
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, reason)
                serviceControl?.get()?.stopService()
            }
        }

        aetherWarmUpJob = daemonScope.launch {
            val listening = AetherCoreManager.awaitListening(AETHER_WARMUP_TIMEOUT_MS)
            val outcome = AetherCoreManager.warmUpOutcome(
                listening = listening,
                active = isActiveJob(),
                serviceRunning = isServiceRunning()
            )
            when (outcome) {
                AetherCoreManager.WarmUpOutcome.LISTENING -> {
                    isAetherWarmingUp = false
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
                }
                AetherCoreManager.WarmUpOutcome.CORE_EXITED -> {
                    isAetherWarmingUp = false
                    val reason = AetherCoreManager.stoppedMessage(service)
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, reason)
                    serviceControl?.get()?.stopService()
                }
                AetherCoreManager.WarmUpOutcome.ABANDONED -> {
                    isAetherWarmingUp = false
                }
            }
        }
    }

    private fun isActiveJob(): Boolean = aetherWarmUpJob?.isActive == true

    /**
     * Reloads Xray on network handover while keeping the service, tunnel, and Aether core running.
     */
    private fun reloadCoreOnHandover(askedBy: NetworkMonitor) {
        val service = getService() ?: return
        val guid = MmkvManager.getSelectServer() ?: return
        val config = MmkvManager.decodeServerConfig(guid) ?: return

        isReloading = true
        var coreRunningAfterReload = false
        try {
            if (!ReloadOutcome.goesOn(askedBy, networkMonitor)) return

            val result = CoreConfigManager.getV2rayConfig(service, guid)
            if (!result.status) {
                LogUtil.e(AppConfig.TAG, "Reload config build failed: ${result.errorMessage}")
                return
            }

            synchronized(coreLifecycleLock) {
                if (coreController.isRunning) {
                    try {
                        coreController.stopLoop()
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "Failed to stop V2Ray loop during reload", e)
                    }
                }
            }

            if (!ReloadOutcome.goesOn(askedBy, networkMonitor) || stoppedDuringReload) {
                return
            }

            currentConfig = config
            coreRunningAfterReload = startCoreInstance(service, result, currentVpnInterface, isReload = true)
        } finally {
            val outcome = ReloadOutcome.of(
                coreRunning = coreController.isRunning && coreRunningAfterReload,
                stoppedMeanwhile = stoppedDuringReload
            )
            isReloading = false
            when (outcome) {
                ReloadOutcome.KEEP_RUNNING -> Unit
                ReloadOutcome.STOP_SERVICE -> {
                    serviceControl?.get()?.stopService()
                }
                ReloadOutcome.RELEASE_CORES -> {
                    synchronized(coreLifecycleLock) {
                        runCatching {
                            if (coreController.isRunning) coreController.stopLoop()
                        }
                    }
                    AetherCoreManager.stop()
                }
            }
        }
    }

    /**
     * Stops the V2Ray and Aether cores synchronously before emitting MSG_STATE_STOP_SUCCESS.
     * This prevents port or instance collisions when switching countries while connected.
     */
    fun stopCoreLoop(cancelForeground: Boolean = true): Boolean {
        val service = getService() ?: return false

        stoppedDuringReload = true
        stopNetworkMonitor()

        activeMeasureJob?.cancel()
        activeMeasureJob = null
        aetherWarmUpJob?.cancel()
        aetherWarmUpJob = null
        isAetherWarmingUp = false

        stopBrowserDialer()
        AetherCoreManager.stop()

        synchronized(coreLifecycleLock) {
            if (coreControllerDelegate.isInitialized() && coreController.isRunning) {
                try {
                    coreController.stopLoop()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to stop V2Ray loop", e)
                }
            }
        }

        currentVpnInterface = null
        sessionStartedAtMs = 0L
        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        if (cancelForeground) {
            NotificationManager.cancelNotification(service)
        } else {
            NotificationManager.stopSessionStats()
        }

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            LogUtil.d(AppConfig.TAG, "Broadcast receiver already unregistered or not registered")
        }

        return true
    }

    private fun startNetworkMonitor(service: Service) {
        stopNetworkMonitor()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val monitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks ->
                serviceControl?.get()?.setUnderlyingNetworks(networks)
            },
            claimHandover = { askedBy ->
                synchronized(coreLifecycleLock) {
                    ReloadOutcome.handoverReloads(
                        askedBy = askedBy,
                        current = networkMonitor,
                        coreRunning = coreController.isRunning,
                        reloading = isReloading
                    ).also { claimed ->
                        if (claimed) isReloading = true
                    }
                }
            },
            onHandover = { askedBy ->
                reloadCoreOnHandover(askedBy)
            },
            describe = {
                "mode=${MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: AppConfig.VPN}, guid=${MmkvManager.getSelectServer()}"
            }
        )
        networkMonitor = monitor
        monitor.register()
    }

    private fun stopNetworkMonitor() {
        val current = networkMonitor
        networkMonitor = null
        current?.unregister()
    }

    private fun stopBrowserDialer() {
        dialerService?.stop()
        dialerService = null
    }

    /**
     * Queries all outbound traffic statistics from Xray.
     * Go side format: `tag,direction,value;tag,direction,value;`
     */
    fun getAllOutboundTraffic(): List<OutboundTrafficStat> {
        if (!isRunning()) {
            return emptyList()
        }
        val raw = try {
            coreController.queryAllOutboundTrafficStats()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to query all outbound traffic stats", e)
            return emptyList()
        }
        return parseOutboundTrafficStats(raw)
    }

    internal fun parseOutboundTrafficStats(raw: String): List<OutboundTrafficStat> {
        if (raw.isBlank()) {
            return emptyList()
        }
        return raw.split(';', '\n')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { entry ->
                val parts = if (entry.contains('\t')) {
                    entry.split('\t', limit = 3)
                } else {
                    entry.split(',', limit = 3)
                }
                if (parts.size != 3) {
                    return@mapNotNull null
                }
                val tag = parts[0].trim()
                val direction = parts[1].trim()
                val value = parts[2].trim().toLongOrNull() ?: return@mapNotNull null
                if (tag.isEmpty() || direction.isEmpty()) {
                    return@mapNotNull null
                }
                OutboundTrafficStat(
                    tag = tag,
                    direction = direction,
                    value = value.coerceAtLeast(0L),
                )
            }
            .toList()
    }

    /**
     * Checks if the Xray core controller is currently running without eagerly loading native libraries.
     */
    fun isRunning(): Boolean = coreControllerDelegate.isInitialized() && coreController.isRunning

    /**
     * Checks if the overall VPN/Proxy service is running (including while reloading on handover).
     */
    fun isServiceRunning(): Boolean =
        ReloadOutcome.serviceRuns(isRunning(), isReloading, stoppedDuringReload)

    /**
     * Returns the wall-clock timestamp when the current VPN session started, or 0L if stopped.
     */
    fun getSessionStartTimeMs(): Long = if (isServiceRunning()) sessionStartedAtMs else 0L

    /**
     * Gets the name of the currently running config.
     */
    fun getRunningServerName(): String = currentConfig?.remarks.orEmpty()

    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    private fun measureV2rayDelay(requestId: String = "") {
        val currentService = getService() ?: return
        if (!isRunning()) {
            if (requestId.isNotEmpty()) {
                MessageHelper.sendMsg2UI(
                    currentService,
                    AppConfig.MSG_MEASURE_DELAY_RESULT,
                    currentService.getString(R.string.connection_test_fail),
                    requestId,
                    delayMs = -1L,
                )
            }
            return
        }

        activeMeasureJob?.cancel()
        activeMeasureJob = daemonScope.launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to measure delay with primary URL", e)
                errorStr = e.message ?: "empty message"
            }
            if (time == -1L) {
                if (requestId.startsWith("verify_")) {
                    delay(250L)
                }
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Failed to measure delay with alternative URL", e)
                    errorStr = e.message ?: "empty message"
                }
            }

            val result = if (time >= 0) {
                val formattedDelay = String.format(Locale.US, "%dms", time)
                service.getString(R.string.connection_test_available, formattedDelay)
            } else {
                service.getString(R.string.connection_test_error, errorStr)
            }
            MessageHelper.sendMsg2UI(
                service,
                AppConfig.MSG_MEASURE_DELAY_RESULT,
                result,
                requestId,
                delayMs = time,
            )

            if (time >= 0 &&
                TunnelProbePolicy.shouldFetchRemoteIpInfo(
                    requestId = requestId,
                    coreRunning = isRunning(),
                    localProxyKept = SettingsManager.isLocalProxyKept(),
                    httpPort = SettingsManager.getHttpPort(),
                )
            ) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    MessageHelper.sendMsg2UI(
                        service,
                        AppConfig.MSG_MEASURE_DELAY_RESULT,
                        "$result\n$ip",
                        requestId,
                        delayMs = time,
                    )
                }
            }
        }
    }

    private class CoreCallback : CoreCallbackHandler {
        override fun startup(): Long {
            return 0
        }

        override fun shutdown(): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            return try {
                serviceControl.stopService()
                0
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to stop service in callback", e)
                -1
            }
        }

        override fun onEmitStatus(l: Long, s: String?): Long {
            return 0
        }
    }

    private class ReceiveMessageHandler : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            val service = serviceControl.getService()
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (isServiceRunning()) {
                        if (isAetherWarmingUp) {
                            MessageHelper.sendMsg2UI(
                                service,
                                AppConfig.MSG_STATE_CONNECTING,
                                service.getString(R.string.aether_core_connecting)
                            )
                        } else {
                            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_RUNNING, "")
                            MessageHelper.sendMsg2UI(
                                service,
                                AppConfig.MSG_TRAFFIC_STATS,
                                NotificationManager.currentTrafficSnapshot(getSessionStartTimeMs())
                            )
                        }
                    } else {
                        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> Unit

                AppConfig.MSG_STATE_START -> Unit

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "Stop Service")
                    serviceControl.stopService()
                    if (isOrderedBroadcast) {
                        resultCode = Activity.RESULT_OK
                    }
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "Restart Service")
                    serviceControl.stopService()
                    serviceControl.startService()
                    if (isOrderedBroadcast) {
                        resultCode = Activity.RESULT_OK
                    }
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    val reqId = intent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID)
                        ?: intent.getStringExtra("content")
                        ?: ""
                    measureV2rayDelay(reqId)
                }

                AppConfig.MSG_MEASURE_DELAY_CANCEL -> {
                    activeMeasureJob?.cancel()
                    activeMeasureJob = null
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "SCREEN_OFF, pausing notification UI updates while preserving traffic tracking")
                    NotificationManager.onScreenOff()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "SCREEN_ON, resuming notification UI updates")
                    NotificationManager.onScreenOn()
                    NotificationManager.startSpeedNotification(currentConfig)
                }
            }
        }
    }
}
