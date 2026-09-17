package com.ayuemin.ymnik

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.network.OpenRouterRecoveryStore
import com.ayuemin.ymnik.network.ServerJobRecoveryStore
import java.util.concurrent.ConcurrentHashMap

/** Android 14+ execution host for requests explicitly started by the user. */
class RequestUidtJobService : JobService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val active = ConcurrentHashMap<Int, JobParameters>()

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Работа моделей",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Запросы Umnik, запущенные пользователем"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val requestId = params.extras.getString(EXTRA_REQUEST_ID)?.takeIf { it.isNotBlank() } ?: return false
        val snapshot = RequestExecutionManager.snapshotForRequest(requestId)
        if (snapshot == null) {
            // The process may have been recreated after a request reached OpenRouter or the
            // personal server. Recover only existing work; never recreate a paid direct POST.
            val serverRecovery = ServerJobRecoveryStore(applicationContext).get(requestId) != null
            val directRecovery = OpenRouterRecoveryStore(applicationContext).get(requestId) != null
            when {
                serverRecovery -> ServerJobRecoveryWorker.schedule(
                    applicationContext,
                    requestId,
                    initialDelaySeconds = 0L,
                    replace = true
                )
                directRecovery -> OpenRouterRecoveryWorker.schedule(
                    applicationContext,
                    requestId,
                    initialDelaySeconds = 0L,
                    replaceExisting = true,
                    expedited = true
                )
            }
            DiagnosticLog.record(
                applicationContext,
                "UIDT",
                "Job started without live runtime request=${requestId.take(8)}; serverRecovery=$serverRecovery directRecovery=$directRecovery"
            )
            return false
        }

        setNotification(
            params,
            notificationId(params.jobId),
            notification(snapshot.label),
            JOB_END_NOTIFICATION_POLICY_REMOVE
        )
        active[params.jobId] = params
        val job = RequestExecutionManager.startUidtRequest(requestId)
        if (job == null) {
            active.remove(params.jobId)
            return false
        }
        job.invokeOnCompletion {
            mainHandler.post {
                val running = active.remove(params.jobId)
                if (running != null) {
                    runCatching { jobFinished(running, false) }
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val requestId = params.extras.getString(EXTRA_REQUEST_ID).orEmpty()
        active.remove(params.jobId)
        if (requestId.isNotBlank()) {
            RequestExecutionManager.stopUidtFromSystem(requestId, params.stopReason)
        }
        // Never ask JobScheduler to retry a potentially accepted paid POST.
        return false
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification_umnik)
            .setContentTitle("Umnik · модель работает")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notificationId(jobId: Int): Int = NOTIFICATION_ID_BASE + (jobId and 0x0FFF)

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        private const val CHANNEL_ID = "umnik_active_request"
        private const val NOTIFICATION_ID_BASE = 5200
    }
}
