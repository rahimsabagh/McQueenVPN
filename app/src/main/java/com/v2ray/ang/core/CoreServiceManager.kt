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
import android.os.PowerManager
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
import java.util.concurrent.atomic.AtomicLong

object CoreServiceManager {
    private const val AETHER_WARMUP_TIMEOUT_MS = 30_000L

    private val coreControllerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CoreNativeManager.newCoreController(CoreCallback())
    }
    private val coreController: CoreController
        get() = coreControllerDelegate.value
    private val mMsgReceive = ReceiveMessageHandler()
    private val coreLifecycleLock = Any()
    private val receiverLock = Any()
    private val sessionSeq = AtomicLong(0L)
    private val daemonScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentConfig: ProfileItem? = null
    private var currentVpnInterface: ParcelFileDescriptor? = null
    private var networkMonitor: NetworkMonitor? = null
    private var dialerService: IDialerService? = null
    private var registeredReceiverContext: Context? = null

    @Volatile
    private var activeMeasureJob: Job? = null
    @Volatile
    private var aetherWarmUpJob: Job? = null
    @Volatile
    private var hasActiveSession: Boolean = false
    @Volatile
    private var isReloading: Boolean = false
    @Volatile
    private var stoppedDuringReload: Boolean = false
    @Volatile
    private var isAetherWarmingUp: Boolean = false
    @Volatile
    private var sessionStartedAtMs: Long = 0L

    @Volatile
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
        val appContext = service.applicationContext
        val mySeq = sessionSeq.incrementAndGet()

        val guid = MmkvManager.getSelectServer()
        if (guid == null) {
            if (sessionSeq.get() == mySeq) {
                MessageHelper.sendMsg2UI(appContext, AppConfig.MSG_STATE_START_FAILURE, "")
            }
            return false
        }
        val config = MmkvManager.decodeServerConfig(guid)
        if (config == null) {
            if (sessionSeq.get() == mySeq) {
                MessageHelper.sendMsg2UI(appContext, AppConfig.MSG_STATE_START_FAILURE, "")
            }
            return false
        }

        try {
            CoreNativeManager.initCoreEnv(appContext)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to initialize core env before startCoreLoop", e)
            if (sessionSeq.get() == mySeq) {
                MessageHelper.sendMsg2UI(appContext, AppConfig.MSG_STATE_START_FAILURE, "")
            }
            return false
        }

        if (sessionSeq.get() != mySeq || serviceControl?.get()?.getService() !== service) {
            LogUtil.i(AppConfig.TAG, "CoreServiceManager: Aborting superseded startCoreLoop after initCoreEnv seq=$mySeq")
            return false
        }

        val result = CoreConfigManager.getV2rayConfig(service, guid)
        if (!result.status) {
            if (sessionSeq.get() == mySeq) {
                val errorMsg = result.errorMessage.takeIf { result.localizedError } ?: ""
                MessageHelper.sendMsg2UI(appContext, AppConfig.MSG_STATE_START_FAILURE, errorMsg)
            }
            return false
        }

        if (sessionSeq.get() != mySeq || serviceControl?.get()?.getService() !== service) {
            LogUtil.i(AppConfig.TAG, "CoreServiceManager: Aborting superseded startCoreLoop after config build seq=$mySeq")
            return false
        }

        val aetherCore = result.aetherCore
        if (aetherCore != null) {
            AetherCoreManager.refusalOf(aetherCore, result.content)?.let { refusal ->
                LogUtil.e(AppConfig.TAG, "CoreServiceManager: refusing Aether session: ${refusal.reason}")
                if (sessionSeq.get() == mySeq) {
                    val msg = service.getString(refusal.message, refusal.argument)
                    MessageHelper.sendMsg2UI(appContext, AppConfig.MSG_STATE_START_FAILURE, msg)
                }
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
                if (sessionSeq.get() == mySeq) {
                    val msg = service.getString(AetherCoreManager.takenMessage(portCheck), portCheck.port)
                    MessageHelper.sendMsg2UI(appContext, AppConfig.MSG_STATE_START_FAILURE, msg)
                }
                return false
            }
        }

        synchronized(coreLifecycleLock) {
            if (sessionSeq.get() != mySeq || serviceControl?.get()?.getService() !== service) {
                LogUtil.i(AppConfig.TAG, "CoreServiceManager: Aborting superseded startCoreLoop before receiver register seq=$mySeq")
                return false
            }
            currentConfig = config
            currentVpnInterface = vpnInterface
            stoppedDuringReload = false
            isReloading = false
            hasActiveSession = true
        }

        registerServiceReceiver(appContext)

        val started = startCoreInstance(
            service = service,
            result = result,
            vpnInterface = vpnInterface,
            isReload = false,
            expectedSessionSeq = mySeq,
        )
        if (!started) {
            synchronized(coreLifecycleLock) {
                if (sessionSeq.get() == mySeq) {
                    hasActiveSession = false
                    currentVpnInterface = null
                    currentConfig = null
                }
            }
            if (sessionSeq.get() == mySeq) {
                unregisterServiceReceiver()
            }
            return false
        }

        if (sessionSeq.get() != mySeq || serviceControl?.get()?.getService() !== service) {
            LogUtil.i(AppConfig.TAG, "CoreServiceManager: Session seq=$mySeq superseded immediately after startCoreInstance")
            return false
        }

        startNetworkMonitor(service, mySeq)
        return true
    }

    private fun startCoreInstance(
        service: Service,
        result: ConfigResult,
        vpnInterface: ParcelFileDescriptor?,
        isReload: Boolean,
        expectedSessionSeq: Long,
    ): Boolean {
        try {
            val fd = vpnInterface?.fd ?: 0
            synchronized(coreLifecycleLock) {
                if (sessionSeq.get() != expectedSessionSeq) {
                    return false
                }
                if (isReload && (!hasActiveSession || stoppedDuringReload)) {
                    return false
                }
                coreController.startLoop(result.content, fd)
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to start Core loop", e)
            if (!isReload && sessionSeq.get() == expectedSessionSeq) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            }
            return false
        }

        if (sessionSeq.get() != expectedSessionSeq) {
            return false
        }

        if (!isRunning()) {
            if (!isReload && sessionSeq.get() == expectedSessionSeq) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "")
            }
            return false
        }

        val aetherCore = result.aetherCore
        if (!isReload) {
            sessionStartedAtMs = System.currentTimeMillis()
            NotificationManager.onSessionStart(sessionStartedAtMs, isScreenInteractive(service))
            NotificationManager.showNotification(currentConfig, service)
            NotificationManager.startSpeedNotification(currentConfig)

            if (aetherCore != null) {
                startAetherSessionAndWarmUp(service, aetherCore, expectedSessionSeq)
            } else if (sessionSeq.get() == expectedSessionSeq) {
                isAetherWarmingUp = false
                MessageHelper.sendMsg2UI(
                    service,
                    AppConfig.MSG_STATE_START_SUCCESS,
                    "",
                    sessionStartMs = sessionStartedAtMs,
                )
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

    private fun startAetherSessionAndWarmUp(
        service: Service,
        aetherCore: AetherCore,
        expectedSessionSeq: Long,
    ) {
        if (sessionSeq.get() != expectedSessionSeq) return
        isAetherWarmingUp = true
        MessageHelper.sendMsg2UI(
            service,
            AppConfig.MSG_STATE_CONNECTING,
            service.getString(R.string.aether_core_connecting)
        )

        aetherWarmUpJob?.cancel()
        AetherCoreManager.start(service, aetherCore, afterProbes = true) {
            if (sessionSeq.get() == expectedSessionSeq && isServiceRunning()) {
                val reason = AetherCoreManager.stoppedMessage(service)
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, reason)
                serviceControl?.get()?.stopService()
            }
        }

        aetherWarmUpJob = daemonScope.launch {
            val listening = AetherCoreManager.awaitListening(AETHER_WARMUP_TIMEOUT_MS)
            if (sessionSeq.get() != expectedSessionSeq) {
                isAetherWarmingUp = false
                return@launch
            }
            val outcome = AetherCoreManager.warmUpOutcome(
                listening = listening,
                active = isActiveJob(),
                serviceRunning = isServiceRunning()
            )
            when (outcome) {
                AetherCoreManager.WarmUpOutcome.LISTENING -> {
                    isAetherWarmingUp = false
                    if (sessionSeq.get() == expectedSessionSeq) {
                        MessageHelper.sendMsg2UI(
                            service,
                            AppConfig.MSG_STATE_START_SUCCESS,
                            "",
                            sessionStartMs = sessionStartedAtMs,
                        )
                    }
                }
                AetherCoreManager.WarmUpOutcome.CORE_EXITED -> {
                    isAetherWarmingUp = false
                    if (sessionSeq.get() == expectedSessionSeq) {
                        val reason = AetherCoreManager.stoppedMessage(service)
                        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, reason)
                        serviceControl?.get()?.stopService()
                    }
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
    private fun reloadCoreOnHandover(askedBy: NetworkMonitor, reloadSeq: Long) {
        var coreRunningAfterReload = false
        try {
            val service = getService() ?: return
            val guid = MmkvManager.getSelectServer() ?: return
            val config = MmkvManager.decodeServerConfig(guid) ?: return

            if (!ReloadOutcome.goesOn(askedBy, networkMonitor) || sessionSeq.get() != reloadSeq) return

            val result = CoreConfigManager.getV2rayConfig(service, guid)
            if (!result.status) {
                LogUtil.e(AppConfig.TAG, "Reload config build failed: ${result.errorMessage}")
                return
            }

            val vpnFd: ParcelFileDescriptor?
            synchronized(coreLifecycleLock) {
                if (!ReloadOutcome.goesOn(askedBy, networkMonitor) ||
                    stoppedDuringReload ||
                    sessionSeq.get() != reloadSeq
                ) {
                    return
                }
                if (coreControllerDelegate.isInitialized() && coreController.isRunning) {
                    try {
                        coreController.stopLoop()
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "Failed to stop V2Ray loop during reload", e)
                    }
                }
                if (!ReloadOutcome.goesOn(askedBy, networkMonitor) ||
                    stoppedDuringReload ||
                    sessionSeq.get() != reloadSeq
                ) {
                    return
                }
                vpnFd = currentVpnInterface
                currentConfig = config
            }

            coreRunningAfterReload = startCoreInstance(
                service = service,
                result = result,
                vpnInterface = vpnFd,
                isReload = true,
                expectedSessionSeq = reloadSeq,
            )
        } finally {
            val outcome = synchronized(coreLifecycleLock) {
                if (sessionSeq.get() != reloadSeq) {
                    isReloading = false
                    null
                } else {
                    val running = coreControllerDelegate.isInitialized() &&
                        coreController.isRunning &&
                        coreRunningAfterReload
                    isReloading = false
                    ReloadOutcome.of(
                        coreRunning = running,
                        stoppedMeanwhile = stoppedDuringReload
                    )
                }
            }
            when (outcome) {
                null, ReloadOutcome.KEEP_RUNNING -> Unit
                ReloadOutcome.STOP_SERVICE -> {
                    serviceControl?.get()?.stopService()
                }
                ReloadOutcome.RELEASE_CORES -> {
                    synchronized(coreLifecycleLock) {
                        if (sessionSeq.get() == reloadSeq) {
                            runCatching {
                                if (coreControllerDelegate.isInitialized() && coreController.isRunning) {
                                    coreController.stopLoop()
                                }
                            }
                        }
                    }
                    if (sessionSeq.get() == reloadSeq) {
                        AetherCoreManager.stop()
                    }
                }
            }
        }
    }

    /**
     * Stops the V2Ray and Aether cores synchronously before emitting MSG_STATE_STOP_SUCCESS.
     * This prevents port or instance collisions when switching countries while connected.
     */
    fun stopCoreLoop(
        cancelForeground: Boolean = true,
        fallbackContext: Context? = null,
        emitStopBroadcast: Boolean = true,
        notifyEvenIfIdle: Boolean = true,
    ): Boolean {
        val service = getService()
        val msgContext = service
            ?: fallbackContext
            ?: synchronized(receiverLock) { registeredReceiverContext }

        val hadActive = synchronized(coreLifecycleLock) {
            sessionSeq.incrementAndGet()
            stoppedDuringReload = true
            val wasActive = hasActiveSession ||
                isAetherWarmingUp ||
                (coreControllerDelegate.isInitialized() && coreController.isRunning)
            hasActiveSession = false
            wasActive
        }

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
            currentVpnInterface = null
            currentConfig = null
            sessionStartedAtMs = 0L
        }

        if (emitStopBroadcast && (hadActive || notifyEvenIfIdle) && msgContext != null) {
            MessageHelper.sendMsg2UI(msgContext, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        }
        if (cancelForeground) {
            NotificationManager.cancelNotification(service)
        } else {
            NotificationManager.stopSessionStats()
        }

        unregisterServiceReceiver()

        return hadActive
    }

    private fun registerServiceReceiver(appContext: Context) {
        synchronized(receiverLock) {
            if (registeredReceiverContext != null) {
                return
            }
            val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
            mFilter.addAction(Intent.ACTION_SCREEN_ON)
            mFilter.addAction(Intent.ACTION_SCREEN_OFF)
            mFilter.addAction(Intent.ACTION_USER_PRESENT)
            ContextCompat.registerReceiver(appContext, mMsgReceive, mFilter, Utils.receiverFlags())
            registeredReceiverContext = appContext
        }
    }

    private fun unregisterServiceReceiver() {
        synchronized(receiverLock) {
            val ctx = registeredReceiverContext ?: return
            registeredReceiverContext = null
            try {
                ctx.unregisterReceiver(mMsgReceive)
            } catch (e: Exception) {
                LogUtil.d(AppConfig.TAG, "Broadcast receiver already unregistered or not registered")
            }
        }
    }

    private fun startNetworkMonitor(service: Service, ownerSeq: Long) {
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
                    if (sessionSeq.get() != ownerSeq || stoppedDuringReload) {
                        false
                    } else {
                        ReloadOutcome.handoverReloads(
                            askedBy = askedBy,
                            current = networkMonitor,
                            coreRunning = coreControllerDelegate.isInitialized() && coreController.isRunning,
                            reloading = isReloading
                        ).also { claimed ->
                            if (claimed) isReloading = true
                        }
                    }
                }
            },
            onHandover = { askedBy ->
                reloadCoreOnHandover(askedBy, ownerSeq)
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

    private fun isScreenInteractive(context: Context): Boolean {
        return runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        }.getOrDefault(true)
    }

    private fun measureV2rayDelay(requestId: String = "") {
        val currentService = getService() ?: return
        val expectedSeq = sessionSeq.get()
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
            if (time == -1L && sessionSeq.get() == expectedSeq && isRunning()) {
                if (requestId.startsWith("verify_")) {
                    delay(250L)
                }
                if (sessionSeq.get() == expectedSeq && isRunning()) {
                    try {
                        time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "Failed to measure delay with alternative URL", e)
                        errorStr = e.message ?: "empty message"
                    }
                }
            }

            if (sessionSeq.get() != expectedSeq || !isRunning()) {
                return@launch
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
                sessionSeq.get() == expectedSeq &&
                TunnelProbePolicy.shouldFetchRemoteIpInfo(
                    requestId = requestId,
                    coreRunning = isRunning(),
                    localProxyKept = SettingsManager.isLocalProxyKept(),
                    httpPort = SettingsManager.getHttpPort(),
                )
            ) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    if (sessionSeq.get() == expectedSeq && isRunning()) {
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
                            val daemonStartMs = getSessionStartTimeMs()
                            MessageHelper.sendMsg2UI(
                                service,
                                AppConfig.MSG_STATE_RUNNING,
                                "",
                                sessionStartMs = daemonStartMs,
                            )
                            if (isScreenInteractive(service)) {
                                NotificationManager.onScreenOn(currentConfig)
                            }
                            MessageHelper.sendMsg2UI(
                                service,
                                AppConfig.MSG_TRAFFIC_STATS,
                                NotificationManager.currentTrafficSnapshot(daemonStartMs)
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
                    serviceControl.restartService()
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
                    NotificationManager.onScreenOn(currentConfig)
                }
            }
        }
    }
}
