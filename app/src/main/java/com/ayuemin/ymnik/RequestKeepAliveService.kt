package com.ayuemin.ymnik

import android.app.Notification
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
                    "Работа моделей",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Активные запросы Umnik в фоне"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) {
            DiagnosticLog.record(applicationContext, "SERVICE", "All active chat requests cancelled from notification")
            RequestExecutionManager.cancelAll()
        }

        val active = RequestExecutionManager.snapshots.value
        val shell = AsyncJobEvents.shellActivity.value
        val localShell = AsyncJobEvents.localShellActivity.value
        if (active.isEmpty() && shell == null && localShell == null) {
            DiagnosticLog.record(applicationContext, "SERVICE", "Foreground service has no active work; stopping orphan service")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openChat = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), pendingFlags)
        val cancelAll = PendingIntent.getService(
            this, 1, Intent(this, RequestKeepAliveService::class.java).setAction(ACTION_CANCEL_ALL), pendingFlags
        )

        val workCount = active.size + (if (shell != null) 1 else 0) + (if (localShell != null) 1 else 0)
        val title = when {
            localShell != null && shell == null && active.isEmpty() -> "Umnik · Local Shell работает"
            shell != null && localShell == null && active.isEmpty() -> "Umnik · Shell работает"
            workCount == 1 -> "Umnik · модель работает"
            else -> "Umnik · активных задач: $workCount"
        }
        val labels = buildList {
            active.take(2).forEach { add(it.label) }
            shell?.let { add("Shell · ${it.status}") }
            localShell?.let { add("Local Shell · ${it.status}") }
        }
        val text = labels.joinToString(" · ").ifBlank { "Umnik выполняет задачу" }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(R.drawable.ic_notification_umnik)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openChat)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (active.isNotEmpty()) {
            val cancelLabel = if (active.size == 1) "Остановить запрос" else "Остановить запросы"
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, cancelLabel, cancelAll)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        startForeground(NOTIFICATION_ID, builder.build())
        DiagnosticLog.record(
            applicationContext,
            "SERVICE",
            "Foreground request service active; startId=$startId; chats=${active.size}; shell=${shell != null}; localShell=${localShell != null}"
        )
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DiagnosticLog.record(
            applicationContext,
            "SERVICE",
            "App task removed; chats=${RequestExecutionManager.activeCount()}; shell=${AsyncJobEvents.shellActivity.value != null}; localShell=${AsyncJobEvents.localShellActivity.value != null}"
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        DiagnosticLog.record(
            applicationContext,
            "SERVICE",
            "Foreground service timeout; startId=$startId; type=$fgsType; chats=${RequestExecutionManager.activeCount()}; shell=${AsyncJobEvents.shellActivity.value != null}; localShell=${AsyncJobEvents.localShellActivity.value != null}"
        )
        RequestExecutionManager.cancelAll()
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        DiagnosticLog.record(
            applicationContext,
            "SERVICE",
            "RequestKeepAliveService destroyed; chats=${RequestExecutionManager.activeCount()}; shell=${AsyncJobEvents.shellActivity.value != null}; localShell=${AsyncJobEvents.localShellActivity.value != null}"
        )
        RequestExecutionManager.serviceStoppedUnexpectedly(applicationContext)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "umnik_active_request"
        private const val NOTIFICATION_ID = 4107
        private const val ACTION_CANCEL_ALL = "com.ayuemin.ymnik.CANCEL_ALL_REQUESTS"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RequestKeepAliveService::class.java))
        }

        fun update(context: Context) {
            if (RequestExecutionManager.hasActiveRequest() || AsyncJobEvents.shellActivity.value != null || AsyncJobEvents.localShellActivity.value != null) {
                start(context)
            } else {
                stop(context)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RequestKeepAliveService::class.java))
        }
    }
}
