package com.ayuemin.ymnik

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ayuemin.ymnik.data.KnowledgeBaseRepository
import com.ayuemin.ymnik.data.SecretStore
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.model.KnowledgeIndexTask
import com.ayuemin.ymnik.network.OpenRouterEmbeddingClient
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class KnowledgeIndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)?.takeIf { it.isNotBlank() } ?: return Result.success()
        val repository = KnowledgeBaseRepository(applicationContext)
        val task = repository.indexTask(taskId) ?: return Result.success()

        setForeground(foregroundInfo(task))
        val apiKey = SecretStore(applicationContext).getProfileApiKey(task.connectionProfileId)
        if (apiKey.isNullOrBlank()) {
            repository.markIndexTaskError(taskId, "Не найден API-ключ OpenRouter для embeddings", terminal = true)
            AsyncJobEvents.notifyChanged()
            return Result.failure()
        }

        return try {
            repository.resumeIndexTask(
                taskId = taskId,
                apiKey = apiKey,
                embeddings = OpenRouterEmbeddingClient(applicationContext)
            ) { progress ->
                setProgressAsync(
                    workDataOf(
                        KEY_DONE to progress.completedChunks,
                        KEY_TOTAL to progress.totalChunks
                    )
                )
                AsyncJobEvents.notifyChanged()
            }
            DiagnosticLog.record(applicationContext, "KNOWLEDGE", "background index completed task=${taskId.take(8)}")
            AsyncJobEvents.notifyChanged()
            Result.success()
        } catch (cancelled: CancellationException) {
            DiagnosticLog.record(applicationContext, "KNOWLEDGE", "background index paused task=${taskId.take(8)}", cancelled)
            throw cancelled
        } catch (error: Throwable) {
            val terminal = runAttemptCount >= MAX_RETRIES - 1
            repository.markIndexTaskError(
                taskId,
                error.message ?: "Ошибка фоновой индексации",
                terminal = terminal
            )
            DiagnosticLog.record(
                applicationContext,
                "KNOWLEDGE",
                "background index failed task=${taskId.take(8)} attempt=$runAttemptCount terminal=$terminal",
                error
            )
            AsyncJobEvents.notifyChanged()
            if (terminal) Result.failure() else Result.retry()
        }
    }

    private fun foregroundInfo(task: KnowledgeIndexTask): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Индексация базы знаний",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Фоновая индексация документов базы знаний"
                    setShowBadge(false)
                }
            )
        }

        val openApp = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val total = task.totalChunks
        val done = task.completedChunks.coerceAtLeast(0)
        val percent = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
        val text = if (total > 0) {
            "${task.name} · $percent% ($done/$total)"
        } else {
            "${task.name} · подготовка"
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(applicationContext, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(applicationContext)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_notification_umnik)
            .setContentTitle("Umnik · индексируется база знаний")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        return ForegroundInfo(
            NOTIFICATION_ID_BASE + (task.id.hashCode() and 0x0FFF),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    companion object {
        private const val KEY_TASK_ID = "task_id"
        private const val KEY_DONE = "done"
        private const val KEY_TOTAL = "total"
        private const val CHANNEL_ID = "umnik_knowledge_index"
        private const val NOTIFICATION_ID_BASE = 6400
        private const val MAX_RETRIES = 5
        private fun uniqueName(ownerKey: String) =
            "umnik-knowledge-index-" + ownerKey.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

        fun schedule(context: Context, task: KnowledgeIndexTask) {
            val request = OneTimeWorkRequestBuilder<KnowledgeIndexWorker>()
                .setInputData(workDataOf(KEY_TASK_ID to task.id))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                uniqueName("${task.ownerKind.name}::${task.ownerId}"),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }

    }
}
