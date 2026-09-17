package com.ayuemin.ymnik

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.content.ContextCompat
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Experimental isolated process used to validate whether a dedicated Android runtime
 * survives UI/process churn better than the main application process.
 *
 * This service intentionally does not own model/network execution yet. It is a probe only.
 * Once physical-device tests confirm the lifetime benefit, the transport can be moved behind
 * this process boundary without changing projects, skills or the chat feature surface.
 */
class RuntimeProbeService : Service() {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        appendProbe("created")
        DiagnosticLog.record(applicationContext, "RUNTIME_PROBE", "runtime process created pid=${Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            appendProbe("explicit-stop")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(R.drawable.ic_notification_umnik)
            .setContentTitle("Umnik · runtime test")
            .setContentText("Проверка отдельного фонового процесса")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        startForeground(NOTIFICATION_ID, builder.build())

        if (!heartbeatStarted) {
            heartbeatStarted = true
            scheduler.scheduleAtFixedRate(
                { appendProbe("heartbeat") },
                15L,
                15L,
                TimeUnit.SECONDS
            )
        }
        appendProbe("started")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        appendProbe("task-removed")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        appendProbe("destroyed")
        scheduler.shutdownNow()
        DiagnosticLog.record(applicationContext, "RUNTIME_PROBE", "runtime process destroyed pid=${Process.myPid()}")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Runtime test",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = "Экспериментальный отдельный процесс Umnik"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun appendProbe(event: String) {
        runCatching {
            val file = File(applicationContext.filesDir, "runtime_probe.log")
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
            file.appendText("$timestamp pid=${Process.myPid()} event=$event\n")
            if (file.length() > MAX_LOG_BYTES) {
                val tail = file.readLines().takeLast(MAX_LOG_LINES).joinToString("\n", postfix = "\n")
                file.writeText(tail)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "umnik_runtime_probe"
        private const val NOTIFICATION_ID = 4111
        private const val ACTION_STOP = "com.ayuemin.ymnik.RUNTIME_PROBE_STOP"
        private const val MAX_LOG_BYTES = 256L * 1024L
        private const val MAX_LOG_LINES = 1500
        @Volatile private var heartbeatStarted = false

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, RuntimeProbeService::class.java)
            )
        }

        fun stop(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, RuntimeProbeService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
