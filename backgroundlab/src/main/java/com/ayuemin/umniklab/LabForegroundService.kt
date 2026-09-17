package com.ayuemin.umniklab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.Executors

class LabForegroundService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        LabState.log(this, "FGS", "service created pid=${android.os.Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground("FGS test выполняется")
        if (running) return START_NOT_STICKY
        running = true
        acquireWakeLock()
        LabState.setStatus(this, "FGS test: запрос выполняется")
        LabState.log(this, "FGS", "started startId=$startId wake=${wakeLock?.isHeld == true}")

        executor.execute {
            try {
                val result = LabNetwork.execute(this, "FGS")
                LabState.setStatus(
                    this,
                    "FGS SUCCESS · ${result.elapsedMs / 1000}s · ${result.textChars} символов"
                )
                LabState.log(this, "FGS", "SUCCESS generation=${result.generationId ?: "none"}")
            } catch (t: Throwable) {
                LabState.setStatus(this, "FGS FAIL · ${t::class.java.simpleName}: ${t.message.orEmpty()}")
                LabState.log(this, "FGS", "FAIL", t)
            } finally {
                running = false
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(text: String) {
        val notification = notification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Umnik Background Lab")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background Lab", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:fgs-test").apply {
            setReferenceCounted(false)
            acquire(10L * 60L * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        LabState.log(this, "FGS", "task removed running=$running wake=${wakeLock?.isHeld == true}")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        LabState.log(this, "FGS", "service destroyed running=$running")
        if (running) LabNetwork.cancel()
        releaseWakeLock()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "lab_fgs"
        private const val NOTIFICATION_ID = 7101
    }
}
