package com.ayuemin.ymnik

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.ayuemin.ymnik.diagnostics.DiagnosticLog

class RequestKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLog.record(applicationContext, "SERVICE", "RequestKeepAliveService created")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Работа модели",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Активный запрос Umnik в фоне"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            DiagnosticLog.record(applicationContext, "SERVICE", "Active request cancelled from notification")
            RequestExecutionManager.fail("Запрос остановлен пользователем")
            RequestExecutionManager.cancel()
            return START_NOT_STICKY
        }

        // START_STICKY may recreate the Service with a null Intent after process death.
        // The old coroutine/socket does not survive process death, so do not leave an
        // orphan foreground notification or silently replay a potentially paid request.
        if (intent == null && !RequestExecutionManager.hasActiveRequest()) {
            DiagnosticLog.record(
                applicationContext,
                "SERVICE",
                "Sticky service recreated after process death without in-process request; stopping orphan service"
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "Модель отвечает…" }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openChat = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), pendingFlags
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, RequestKeepAliveService::class.java).setAction(ACTION_CANCEL), pendingFlags
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Umnik")
                .setContentText(label)
                .setContentIntent(openChat)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", cancel)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Umnik")
                .setContentText(label)
                .setContentIntent(openChat)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", cancel)
                .setOngoing(true)
                .build()
        }
        startForeground(NOTIFICATION_ID, notification)
        DiagnosticLog.record(
            applicationContext,
            "SERVICE",
            "Foreground request service active; startId=$startId; active=${RequestExecutionManager.hasActiveRequest()}"
        )
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DiagnosticLog.record(
            applicationContext,
            "SERVICE",
            "App task removed; active=${RequestExecutionManager.hasActiveRequest()}"
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        DiagnosticLog.record(
            applicationContext,
            "SERVICE",
            "Foreground service timeout; startId=$startId; type=$fgsType"
        )
        RequestExecutionManager.fail("Android остановил слишком долгую фоновую работу. Повторите запрос вручную.")
        RequestExecutionManager.cancel()
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        DiagnosticLog.record(
            applicationContext,
            "SERVICE",
            "RequestKeepAliveService destroyed; active=${RequestExecutionManager.hasActiveRequest()}"
        )
        RequestExecutionManager.serviceStoppedUnexpectedly(applicationContext)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "umnik_active_request"
        private const val NOTIFICATION_ID = 4107
        private const val EXTRA_LABEL = "label"
        private const val ACTION_CANCEL = "com.ayuemin.ymnik.CANCEL_REQUEST"

        fun start(context: Context, label: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RequestKeepAliveService::class.java).putExtra(EXTRA_LABEL, label)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RequestKeepAliveService::class.java))
        }
    }
}
