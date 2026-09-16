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
                    description = "Активные запросы Umnik в фоне"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_ONE -> {
                val chatId = intent.getStringExtra(EXTRA_CHAT_ID)
                if (!chatId.isNullOrBlank()) {
                    DiagnosticLog.record(applicationContext, "SERVICE", "Request cancelled from notification chat=${chatId.take(8)}")
                    RequestExecutionManager.fail(chatId, "Запрос остановлен пользователем")
                    RequestExecutionManager.cancel(chatId)
                }
                return START_STICKY
            }
            ACTION_CANCEL_ALL -> {
                DiagnosticLog.record(applicationContext, "SERVICE", "All active requests cancelled from notification")
                RequestExecutionManager.failAll("Запросы остановлены пользователем")
                RequestExecutionManager.cancelAll()
                return START_STICKY
            }
        }

        // START_STICKY may recreate the Service with a null Intent after process death.
        // The old coroutines/sockets do not survive process death, so do not leave an
        // orphan foreground notification or silently replay potentially paid requests.
        if (intent == null && !RequestExecutionManager.hasActiveRequest()) {
            DiagnosticLog.record(
                applicationContext,
                "SERVICE",
                "Sticky service recreated after process death without in-process requests; stopping orphan service"
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val activeIds = RequestExecutionManager.activeChatIds()
        val label = if (activeIds.isNotEmpty()) {
            RequestExecutionManager.notificationLabel()
        } else {
            intent?.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "Модель отвечает…" }
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openChat = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), pendingFlags
        )
        val cancelIntent = if (activeIds.size == 1) {
            Intent(this, RequestKeepAliveService::class.java)
                .setAction(ACTION_CANCEL_ONE)
                .putExtra(EXTRA_CHAT_ID, activeIds.first())
        } else {
            Intent(this, RequestKeepAliveService::class.java).setAction(ACTION_CANCEL_ALL)
        }
        val cancel = PendingIntent.getService(
            this,
            if (activeIds.size == 1) activeIds.first().hashCode() else 4108,
            cancelIntent,
            pendingFlags
        )
        val cancelLabel = if (activeIds.size <= 1) "Остановить" else "Остановить все"
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Umnik")
                .setContentText(label)
                .setContentIntent(openChat)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, cancelLabel, cancel)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Umnik")
                .setContentText(label)
                .setContentIntent(openChat)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, cancelLabel, cancel)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
        startForeground(NOTIFICATION_ID, notification)
        DiagnosticLog.record(
            applicationContext,
            "SERVICE",
            "Foreground request service active; startId=$startId; active=${RequestExecutionManager.activeCount()}"
        )
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DiagnosticLog.record(
            applicationContext,
            "SERVICE",
            "App task removed; active=${RequestExecutionManager.activeCount()}"
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        DiagnosticLog.record(
            applicationContext,
            "SERVICE",
            "Foreground service timeout; startId=$startId; type=$fgsType; active=${RequestExecutionManager.activeCount()}"
        )
        RequestExecutionManager.failAll("Android остановил слишком долгую фоновую работу. Повторите запрос вручную.")
        RequestExecutionManager.cancelAll()
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        DiagnosticLog.record(
            applicationContext,
            "SERVICE",
            "RequestKeepAliveService destroyed; active=${RequestExecutionManager.activeCount()}"
        )
        RequestExecutionManager.serviceStoppedUnexpectedly(applicationContext)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "umnik_active_request"
        private const val NOTIFICATION_ID = 4107
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_CHAT_ID = "chat_id"
        private const val ACTION_CANCEL_ONE = "com.ayuemin.ymnik.CANCEL_REQUEST"
        private const val ACTION_CANCEL_ALL = "com.ayuemin.ymnik.CANCEL_ALL_REQUESTS"

        fun start(context: Context, label: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RequestKeepAliveService::class.java).putExtra(EXTRA_LABEL, label)
            )
        }

        fun update(context: Context, label: String) {
            start(context, label)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RequestKeepAliveService::class.java))
        }
    }
}
