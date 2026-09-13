package com.ayuemin.ymnik

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ayuemin.ymnik.data.BatchJobRepository
import com.ayuemin.ymnik.data.ChatRepository
import com.ayuemin.ymnik.data.SecretStore
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.model.BatchJobStatus
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.network.OpenRouterBatchClient
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Reconnects to already-created OpenRouter jobs after Umnik or Android restarts.
 * It never creates a paid job, therefore WorkManager retries cannot duplicate a request.
 */
class OpenRouterJobWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val batches = BatchJobRepository(applicationContext)
        val active = batches.active()
        if (active.isEmpty()) return Result.success()

        val secrets = SecretStore(applicationContext)
        val chats = ChatRepository(applicationContext)
        val client = OpenRouterBatchClient(applicationContext)
        var stillRunning = false
        var transientFailure = false

        active.forEach { job ->
            val key = secrets.getProfileApiKey(job.connectionProfileId)
            if (key.isNullOrBlank()) {
                DiagnosticLog.record(
                    applicationContext,
                    "BATCH",
                    "background poll skipped id=${job.remoteId}; API key unavailable"
                )
                stillRunning = true
                return@forEach
            }

            val labels = job.items.associate { it.customId to it.label }
            runCatching {
                client.get(key, job.remoteId, labels)
            }.onSuccess { snapshot ->
                val updated = job.copy(
                    status = snapshot.status,
                    items = if (snapshot.items.isNotEmpty()) snapshot.items else job.items,
                    error = snapshot.error,
                    updatedAt = System.currentTimeMillis()
                )
                batches.upsert(updated)

                when (snapshot.status) {
                    BatchJobStatus.COMPLETED -> {
                        val chatId = job.chatId
                        val messageId = job.userMessageId
                        if (!chatId.isNullOrBlank() && !messageId.isNullOrBlank()) {
                            val answer = renderBatchResult(updated)
                            chats.finishRequest(
                                chatId,
                                messageId,
                                ChatMessage(
                                    id = UUID.randomUUID().toString(),
                                    role = "assistant",
                                    text = answer
                                )
                            )
                        }
                    }
                    BatchJobStatus.FAILED,
                    BatchJobStatus.CANCELLED,
                    BatchJobStatus.EXPIRED -> {
                        val chatId = job.chatId
                        val messageId = job.userMessageId
                        if (!chatId.isNullOrBlank() && !messageId.isNullOrBlank()) {
                            chats.finishRequest(chatId, messageId, null)
                        }
                    }
                    else -> stillRunning = true
                }
            }.onFailure { error ->
                transientFailure = true
                stillRunning = true
                DiagnosticLog.record(
                    applicationContext,
                    "BATCH",
                    "background poll failed id=${job.remoteId}",
                    error
                )
            }
        }

        return when {
            stillRunning || transientFailure -> Result.retry()
            else -> Result.success()
        }
    }

    private fun renderBatchResult(job: com.ayuemin.ymnik.model.BatchJob): String {
        if (job.items.size == 1) {
            val item = job.items.single()
            return item.resultText
                ?: item.error?.let { "Пакетное задание завершилось с ошибкой: $it" }
                ?: "Пакетное задание завершено без текста ответа."
        }
        return buildString {
            appendLine("Пакетная обработка завершена: ${job.items.size} заданий.")
            job.items.forEachIndexed { index, item ->
                appendLine()
                appendLine("### ${index + 1}. ${item.label}")
                appendLine(item.resultText ?: item.error?.let { "Ошибка: $it" } ?: "Нет результата")
            }
        }.trim()
    }

    companion object {
        private const val UNIQUE_WORK = "umnik-openrouter-background-jobs"

        fun schedule(context: Context, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<OpenRouterJobWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
