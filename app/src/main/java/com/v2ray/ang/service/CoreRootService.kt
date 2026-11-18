package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.root.RootProxyManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.SoftReference
import java.util.concurrent.Executors

class CoreRootService : Service(), ServiceControl {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var rootStartupJob: Job? = null
    private val startGate = DaemonStartGate()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mcqueen-root-service").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        startGate.resetOnCreate()
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground(this)
        val opGen = startGate.beginStart(startId)
        if (opGen < 0L) {
            scheduleStopForegroundAndSelf(startGate.currentGeneration(), startId)
            return START_NOT_STICKY
        }
        serviceExecutor.execute {
            performStart(opGen, startId)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        val wasRunning = CoreServiceManager.isServiceRunning()
        startGate.markDestroyed()
        mainHandler.removeCallbacksAndMessages(null)
        NotificationManager.cancelNotification(this)

        val appCtx = applicationContext
        if (CoreServiceManager.serviceControl?.get() === this) {
            CoreServiceManager.serviceControl = null
        }

        val submitted = runCatching {
            serviceExecutor.execute {
                stopInternal(
                    forceRootStop = wasRunning || CoreServiceManager.isServiceRunning(),
                    notifyUiStop = wasRunning,
                    fallbackContext = appCtx,
                )
            }
            true
        }.getOrDefault(false)

        if (!submitted && wasRunning) {
            runCatching { RootProxyManager.stop(appCtx) }
        }

        serviceScope.cancel()
        serviceExecutor.shutdown()
        super.onDestroy()
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        NotificationManager.ensureForeground(this)
        val startId = startGate.currentStartId()
        val opGen = startGate.beginStart(startId)
        if (opGen < 0L) return
        serviceExecutor.execute {
            performStart(opGen, startId)
        }
    }

    override fun stopService() {
        val stopGen = startGate.beginStop()
        val stopStartId = startGate.currentStartId()
        val appCtx = applicationContext
        serviceExecutor.execute {
            stopInternal(
                forceRootStop = true,
                notifyUiStop = true,
                fallbackContext = appCtx,
            )
            scheduleStopForegroundAndSelf(stopGen, stopStartId)
        }
    }

    override fun restartService() {
        NotificationManager.ensureForeground(this)
        val startId = startGate.currentStartId()
        val opGen = startGate.beginStart(startId)
        if (opGen < 0L) return
        MessageHelper.sendMsg2UI(this, AppConfig.MSG_STATE_CONNECTING, "")
        serviceExecutor.execute {
            performStart(opGen, startId)
        }
    }

    private fun performStart(opGen: Long, startId: Int) {
        if (!startGate.shouldContinueStart(opGen)) return
        if (CoreServiceManager.isServiceRunning()) {
            stopInternal(forceRootStop = true, notifyUiStop = false)
        }
        if (!startGate.shouldContinueStart(opGen)) return

        rootStartupJob?.cancel()
        if (!CoreServiceManager.startCoreLoop(null)) {
            stopInternal(forceRootStop = false, notifyUiStop = false)
            scheduleStopForegroundAndSelf(opGen, startId)
            return
        }

        if (!startGate.shouldContinueStart(opGen)) {
            stopInternal(forceRootStop = true, notifyUiStop = false)
            return
        }

        rootStartupJob = serviceScope.launch {
            if (!RootProxyManager.start(this@CoreRootService)) {
                if (startGate.shouldContinueStart(opGen)) {
                    LogUtil.e(AppConfig.TAG, "RootProxyManager failed to start, stopping CoreRootService")
                    MessageHelper.sendMsg2UI(this@CoreRootService, AppConfig.MSG_STATE_START_FAILURE, "")
                    stopService()
                }
            }
        }
    }

    private fun stopInternal(
        forceRootStop: Boolean,
        notifyUiStop: Boolean = false,
        fallbackContext: Context = applicationContext,
    ) {
        rootStartupJob?.cancel()
        rootStartupJob = null
        if (forceRootStop || CoreServiceManager.isServiceRunning()) {
            RootProxyManager.stop(fallbackContext)
            CoreServiceManager.stopCoreLoop(
                cancelForeground = false,
                fallbackContext = fallbackContext,
                emitStopBroadcast = notifyUiStop,
                notifyEvenIfIdle = notifyUiStop,
            )
        }
    }

    private fun scheduleStopForegroundAndSelf(expectedGen: Long, startId: Int) {
        mainHandler.post {
            if (startGate.shouldStopSelf(expectedGen)) {
                NotificationManager.cancelNotification(this)
                if (startId > 0) {
                    stopSelfResult(startId)
                } else {
                    stopSelf()
                }
            }
        }
    }

    override fun vpnProtect(socket: Int): Boolean {
        return true
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }
}
