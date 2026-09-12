package com.ayuemin.ymnik

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

class RequestKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
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
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "Модель отвечает…" }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Umnik")
                .setContentText(label)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Umnik")
                .setContentText(label)
                .setOngoing(true)
                .build()
        }
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "umnik_active_request"
        private const val NOTIFICATION_ID = 4107
        private const val EXTRA_LABEL = "label"

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
