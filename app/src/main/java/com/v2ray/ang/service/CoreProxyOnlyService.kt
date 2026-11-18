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
import java.lang.ref.SoftReference
import java.util.concurrent.Executors

class CoreProxyOnlyService : Service(), ServiceControl {
    private val startGate = DaemonStartGate()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mcqueen-proxy-service").apply { isDaemon = true }
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

        if (wasRunning) {
            runCatching {
                serviceExecutor.execute {
                    if (CoreServiceManager.isServiceRunning()) {
                        CoreServiceManager.stopCoreLoop(
                            cancelForeground = false,
                            fallbackContext = appCtx,
                            emitStopBroadcast = true,
                            notifyEvenIfIdle = true,
                        )
                    }
                }
            }
        }
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
            CoreServiceManager.stopCoreLoop(
                cancelForeground = false,
                fallbackContext = appCtx,
                emitStopBroadcast = true,
                notifyEvenIfIdle = true,
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
            CoreServiceManager.stopCoreLoop(
                cancelForeground = false,
                fallbackContext = applicationContext,
                emitStopBroadcast = false,
                notifyEvenIfIdle = false,
            )
        }
        if (!startGate.shouldContinueStart(opGen)) return
        if (!CoreServiceManager.startCoreLoop(null)) {
            if (CoreServiceManager.isServiceRunning()) {
                CoreServiceManager.stopCoreLoop(
                    cancelForeground = false,
                    fallbackContext = applicationContext,
                    emitStopBroadcast = false,
                    notifyEvenIfIdle = false,
                )
            }
            scheduleStopForegroundAndSelf(opGen, startId)
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
