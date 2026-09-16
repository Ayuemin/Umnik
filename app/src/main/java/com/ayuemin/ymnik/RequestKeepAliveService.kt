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
            DiagnosticLog.record(applicationContext, "SERVICE", "All active requests cancelled from notification")
            RequestExecutionManager.cancelAll()
            return START_NOT_STICKY
        }

        val active = RequestExecutionManager.snapshots.value
        // START_STICKY can recreate the service after process death, but the old sockets
        // do not survive that death. Do not keep an orphan foreground notification.
        if (active.isEmpty()) {
            DiagnosticLog.record(
                applicationContext,
                "SERVICE",
                "Foreground service has no in-process requests; stopping orphan service"
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openChat = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), pendingFlags
        )
        val cancelAll = PendingIntent.getService(
            this, 1, Intent(this, RequestKeepAliveService::class.java).setAction(ACTION_CANCEL_ALL), pendingFlags
        )
        val title = if (active.size == 1) "Umnik" else "Umnik · ${active.size} запроса"
        val text = if (active.size == 1) {
            active.first().label
        } else {
            active.take(2).joinToString(" · ") { it.label }.let { labels ->
                if (active.size > 2) "$labels · ещё ${active.size - 2}" else labels
            }
        }
        val cancelLabel = if (active.size == 1) "Остановить" else "Остановить все"
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setContentIntent(openChat)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, cancelLabel, cancelAll)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openChat)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, cancelLabel, cancelAll)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
        startForeground(NOTIFICATION_ID, notification)
        DiagnosticLog.record(
            applicationContext,
            "SERVICE",
            "Foreground request service active; startId=$startId; active=${active.size}"
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
        private const val ACTION_CANCEL_ALL = "com.ayuemin.ymnik.CANCEL_ALL_REQUESTS"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RequestKeepAliveService::class.java)
            )
        }

        fun update(context: Context) {
            if (RequestExecutionManager.hasActiveRequest()) start(context) else stop(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RequestKeepAliveService::class.java))
        }
    }
}
