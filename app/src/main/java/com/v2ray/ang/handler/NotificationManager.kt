package com.v2ray.ang.handler

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.dto.TrafficStatsSnapshot
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object NotificationManager {
    private const val NOTIFICATION_ID = 1
    private const val NOTIFICATION_PENDING_INTENT_CONTENT = 0
    private const val NOTIFICATION_PENDING_INTENT_STOP_V2RAY = 1
    private const val NOTIFICATION_PENDING_INTENT_RESTART_V2RAY = 2
    private const val NOTIFICATION_ICON_THRESHOLD = 3000
    private const val SPEED_UPDATE_INTERVAL_MS = 1000L

    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trafficTracker = VpnTrafficTracker(
        source = { CoreServiceManager.getAllOutboundTraffic() }
    )
    private val speedController = SpeedNotificationController(
        scope = notificationScope,
        trafficTracker = trafficTracker,
        isServiceRunning = { CoreServiceManager.isServiceRunning() },
        sessionStartMs = { CoreServiceManager.getSessionStartTimeMs() },
        isSpeedNotificationEnabled = {
            MmkvManager.decodeSettingsBool(
                AppConfig.PREF_SPEED_ENABLED,
                AppConfig.DEFAULT_SPEED_ENABLED
            )
        },
        onEmitTrafficStats = { snapshot ->
            getService()?.let { service ->
                MessageHelper.sendMsg2UI(
                    service,
                    AppConfig.MSG_TRAFFIC_STATS,
                    snapshot
                )
            }
        },
        onUpdateNotification = { contentText, proxyTraffic, directTraffic ->
            updateNotification(contentText, proxyTraffic, directTraffic)
        },
    )

    private val builderLock = Any()
    private var mBuilder: NotificationCompat.Builder? = null
    private var mNotificationManager: android.app.NotificationManager? = null

    fun onSessionStart(sessionStartMs: Long, isInteractive: Boolean = true) {
        speedController.onSessionStart(sessionStartMs, isInteractive)
    }

    fun onCoreReloaded() {
        speedController.onCoreReloaded()
    }

    fun onScreenOff() {
        speedController.onScreenOff()
    }

    fun onScreenOn(currentConfig: ProfileItem? = null) {
        speedController.onScreenOn(outboundLabelOrNull(currentConfig))
    }

    fun currentTrafficSnapshot(sessionStartMs: Long = CoreServiceManager.getSessionStartTimeMs()): TrafficStatsSnapshot {
        return trafficTracker.currentSnapshot(sessionStartMs)
    }

    /**
     * Ensures the service enters foreground state immediately upon `onStartCommand`.
     */
    fun ensureForeground(service: Service? = null) {
        showNotification(null, service)
    }

    /**
     * Starts the speed notification and live traffic counter polling loop when interactive.
     * Lifecycle-safe synchronization ensures at most one active polling coroutine at any time.
     */
    fun startSpeedNotification(currentConfig: ProfileItem?) {
        val outboundLabel = outboundLabelOrNull(currentConfig) ?: AppConfig.TAG_PROXY
        speedController.startSpeedNotification(outboundLabel)
    }

    private fun outboundLabelOrNull(currentConfig: ProfileItem?): String? {
        return currentConfig?.remarks?.takeIf { it.isNotBlank() }
    }

    /**
     * Shows the foreground service notification.
     */
    @Synchronized
    fun showNotification(currentConfig: ProfileItem?, explicitService: Service? = null) {
        val service = explicitService ?: getService() ?: return
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val startMainIntent = Intent(service, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(service, NOTIFICATION_PENDING_INTENT_CONTENT, startMainIntent, flags)

        val stopV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        stopV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        stopV2RayIntent.putExtra("key", AppConfig.MSG_STATE_STOP)
        val stopV2RayPendingIntent = PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_STOP_V2RAY, stopV2RayIntent, flags)

        val restartV2RayIntent = Intent(AppConfig.BROADCAST_ACTION_SERVICE)
        restartV2RayIntent.`package` = AppConfig.ANG_PACKAGE
        restartV2RayIntent.putExtra("key", AppConfig.MSG_STATE_RESTART)
        val restartV2RayPendingIntent = PendingIntent.getBroadcast(service, NOTIFICATION_PENDING_INTENT_RESTART_V2RAY, restartV2RayIntent, flags)

        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(service)
        } else {
            ""
        }

        val selectedCountry = MmkvManager.decodeSettingsString(AppConfig.PREF_MCQUEEN_SELECTED_COUNTRY)
        val titleText = selectedCountry?.takeIf { it.isNotBlank() }
            ?: currentConfig?.remarks?.takeIf { it.isNotBlank() }
            ?: service.getString(R.string.app_name)

        val builder = NotificationCompat.Builder(service, channelId)
            .setSmallIcon(R.drawable.ic_stat_mcqueen)
            .setColor(0xFF941E29.toInt())
            .setContentTitle(titleText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.drawable.ic_stop_24dp,
                service.getString(R.string.notification_action_stop_v2ray),
                stopV2RayPendingIntent
            )
            .addAction(
                R.drawable.ic_restore_24dp,
                service.getString(R.string.title_service_restart),
                restartV2RayPendingIntent
            )

        synchronized(builderLock) {
            mBuilder = builder
        }
        speedController.resetNotificationTextCache()
        val notification = builder.build()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(service, NOTIFICATION_ID, notification, fgsType)
    }

    /**
     * Cancels the notification and stops the speed polling job.
     */
    @Synchronized
    fun cancelNotification(explicitService: Service? = null) {
        val service = explicitService ?: getService()
        synchronized(builderLock) {
            mBuilder = null
            speedController.stopSessionStats()
            if (service != null) {
                runCatching {
                    ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
                }
                runCatching {
                    getNotificationManager(service)?.cancel(NOTIFICATION_ID)
                }
            }
            mNotificationManager = null
        }
    }

    /**
     * Stops speed polling and resets session traffic tracker without removing the foreground state.
     */
    @Synchronized
    fun stopSessionStats() {
        speedController.stopSessionStats()
    }

    /**
     * Stops the speed notification coroutine job without clearing session totals.
     */
    @Synchronized
    fun stopSpeedNotification() {
        speedController.stopSpeedNotification()
    }

    private fun createNotificationChannel(service: Service): String {
        val channelId = AppConfig.RAY_NG_CHANNEL_ID
        val channelName = service.getString(R.string.app_name)
        val chan = NotificationChannel(
            channelId,
            channelName,
            android.app.NotificationManager.IMPORTANCE_LOW
        )
        chan.lightColor = Color.DKGRAY
        chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        getNotificationManager(service)?.createNotificationChannel(chan)
        return channelId
    }

    private fun updateNotification(contentText: String?, proxyTraffic: Long, directTraffic: Long) {
        synchronized(builderLock) {
            val builder = mBuilder ?: return
            if (!speedController.isSessionActiveState()) return
            builder.setSmallIcon(R.drawable.ic_stat_mcqueen)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            builder.setContentText(contentText)
            val notification = builder.build()
            runCatching {
                getNotificationManager()?.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun getService(): Service? {
        return CoreServiceManager.serviceControl?.get()?.getService()
    }

    private fun getNotificationManager(explicitService: Service? = null): android.app.NotificationManager? {
        if (mNotificationManager == null) {
            val service = explicitService ?: getService() ?: return null
            mNotificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        }
        return mNotificationManager
    }
}

internal class SpeedNotificationController(
    private val scope: CoroutineScope,
    private val trafficTracker: VpnTrafficTracker,
    private val isServiceRunning: () -> Boolean,
    private val sessionStartMs: () -> Long,
    private val isSpeedNotificationEnabled: () -> Boolean,
    private val onEmitTrafficStats: (TrafficStatsSnapshot) -> Unit,
    private val onUpdateNotification: (contentText: String?, proxyTraffic: Long, directTraffic: Long) -> Unit,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
    private val lock = Any()

    private var pollingJob: Job? = null
    private var pollingGeneration: Long = 0L
    private var isSessionActive: Boolean = false
    private var isScreenInteractive: Boolean = true
    private var outboundLabel: String = AppConfig.TAG_PROXY
    private var lastNotifiedText: String? = null
    private var hasNotificationContent: Boolean = false

    fun onSessionStart(sessionStartMs: Long, isInteractive: Boolean = true) {
        synchronized(lock) {
            cancelPollingLocked(clearNotificationContent = true)
            isSessionActive = true
            isScreenInteractive = isInteractive
            trafficTracker.onSessionStart(sessionStartMs)
        }
    }

    fun onCoreReloaded() {
        trafficTracker.onCoreReloaded()
    }

    fun resetNotificationTextCache() {
        synchronized(lock) {
            lastNotifiedText = null
            hasNotificationContent = false
        }
    }

    fun startSpeedNotification(outboundLabel: String) {
        synchronized(lock) {
            this.outboundLabel = outboundLabel.ifBlank { AppConfig.TAG_PROXY }
            isSessionActive = true
            ensurePollingLocked()
        }
    }

    fun onScreenOff() {
        synchronized(lock) {
            if (!isScreenInteractive && pollingJob == null) {
                lastNotifiedText = null
                return
            }
            isScreenInteractive = false
            cancelPollingLocked(clearNotificationContent = false)
        }
    }

    fun onScreenOn(outboundLabel: String? = null) {
        synchronized(lock) {
            if (!outboundLabel.isNullOrBlank()) {
                this.outboundLabel = outboundLabel
            }
            val wasInteractive = isScreenInteractive
            isScreenInteractive = true
            if (!wasInteractive) {
                lastNotifiedText = null
            }
            ensurePollingLocked()
        }
    }

    fun stopSpeedNotification() {
        val shouldClearNotification: Boolean
        synchronized(lock) {
            shouldClearNotification = pollingJob != null || hasNotificationContent || lastNotifiedText != null
            cancelPollingLocked(clearNotificationContent = true)
        }
        if (shouldClearNotification) {
            onUpdateNotification(null, 0L, 0L)
        }
    }

    fun stopSessionStats() {
        val shouldClearNotification: Boolean
        synchronized(lock) {
            shouldClearNotification = pollingJob != null || hasNotificationContent || lastNotifiedText != null
            isSessionActive = false
            outboundLabel = AppConfig.TAG_PROXY
            cancelPollingLocked(clearNotificationContent = true)
            trafficTracker.onSessionStop()
        }
        if (shouldClearNotification) {
            onUpdateNotification(null, 0L, 0L)
        }
    }

    internal fun isPollingActive(): Boolean = synchronized(lock) {
        pollingJob?.isActive == true
    }

    internal fun isSessionActiveState(): Boolean = synchronized(lock) {
        isSessionActive
    }

    internal fun isInteractiveState(): Boolean = synchronized(lock) {
        isScreenInteractive
    }

    private fun ensurePollingLocked() {
        if (!isSessionActive || !isScreenInteractive || !isServiceRunning()) {
            return
        }
        if (pollingJob?.isActive == true) {
            return
        }
        val gen = ++pollingGeneration
        pollingJob = scope.launch {
            runPollingLoop(gen)
        }
    }

    private fun cancelPollingLocked(clearNotificationContent: Boolean) {
        pollingGeneration++
        pollingJob?.cancel()
        pollingJob = null
        lastNotifiedText = null
        if (clearNotificationContent) {
            hasNotificationContent = false
        }
    }

    private fun shouldPollLocked(expectedGen: Long): Boolean =
        isSessionActive &&
            isScreenInteractive &&
            pollingGeneration == expectedGen &&
            isServiceRunning()

    private suspend fun runPollingLoop(expectedGen: Long) {
        while (currentCoroutineContext().isActive) {
            val activeLabel = synchronized(lock) {
                if (!shouldPollLocked(expectedGen)) return
                outboundLabel
            }

            val sampleResult = trafficTracker.sample(sessionStartMs())

            val canEmit = synchronized(lock) {
                shouldPollLocked(expectedGen)
            }
            if (!canEmit || !currentCoroutineContext().isActive) {
                break
            }

            onEmitTrafficStats(sampleResult.snapshot)

            if (isSpeedNotificationEnabled()) {
                val renderedText = formatNotificationText(activeLabel, sampleResult)
                val shouldNotify = synchronized(lock) {
                    if (!shouldPollLocked(expectedGen) || renderedText == lastNotifiedText) {
                        false
                    } else {
                        lastNotifiedText = renderedText
                        hasNotificationContent = true
                        true
                    }
                }
                if (shouldNotify) {
                    onUpdateNotification(
                        renderedText,
                        sampleResult.proxyUplinkBps,
                        sampleResult.proxyDownlinkBps
                    )
                }
            } else {
                val shouldClear = synchronized(lock) {
                    if (shouldPollLocked(expectedGen) && (hasNotificationContent || lastNotifiedText != null)) {
                        lastNotifiedText = null
                        hasNotificationContent = false
                        true
                    } else {
                        false
                    }
                }
                if (shouldClear) {
                    onUpdateNotification(null, 0L, 0L)
                }
            }

            delay(pollIntervalMs)
        }
    }

    companion object {
        private const val DEFAULT_POLL_INTERVAL_MS = 1000L

        internal fun formatNotificationText(
            outboundLabel: String,
            sampleResult: TrafficSampleResult,
        ): String {
            val text = StringBuilder()
            text.append(
                "• $outboundLabel ↑ ${sampleResult.proxyUplinkBps.toSpeedString()} ↓ ${sampleResult.proxyDownlinkBps.toSpeedString()}\n"
            )
            val directUp = sampleResult.directUplinkBps
            val directDown = sampleResult.directDownlinkBps
            if (directUp > 0L || directDown > 0L) {
                text.append("• ${AppConfig.TAG_DIRECT} ↑ ${directUp.toSpeedString()} ↓ ${directDown.toSpeedString()}\n")
            }
            val totalBytes = sampleResult.snapshot.sessionTotalBytes
            if (totalBytes > 0L) {
                text.append(
                    "↑ ${sampleResult.snapshot.sessionUplinkBytes.toTrafficString()}  ↓ ${sampleResult.snapshot.sessionDownlinkBytes.toTrafficString()}"
                )
            }
            return text.toString().trim()
        }
    }
}
