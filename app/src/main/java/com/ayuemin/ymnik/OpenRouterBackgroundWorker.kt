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
import com.ayuemin.ymnik.data.VideoJobRepository
import com.ayuemin.ymnik.model.BatchJob
import com.ayuemin.ymnik.model.BatchJobStatus
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.GeneratedFile
import com.ayuemin.ymnik.model.VideoJob
import com.ayuemin.ymnik.model.VideoJobStatus
import com.ayuemin.ymnik.network.OpenRouterBatchClient
import com.ayuemin.ymnik.network.OpenRouterVideoClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class OpenRouterBackgroundWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val delivery = context.getSharedPreferences("openrouter_job_delivery", Context.MODE_PRIVATE)

    override suspend fun doWork(): Result {
        val batches = BatchJobRepository(applicationContext)
        val videos = VideoJobRepository(applicationContext)
        val batchDelivered = delivery.getStringSet("batches", emptySet()).orEmpty()
        val videoDelivered = delivery.getStringSet("videos", emptySet()).orEmpty()
        val batchJobs = batches.list().filter { !it.status.terminal || it.remoteId !in batchDelivered }
        val videoJobs = videos.list().filter { !it.status.terminal || it.localPath.isNullOrBlank() || it.remoteId !in videoDelivered }
        if (batchJobs.isEmpty() && videoJobs.isEmpty()) return Result.success()

        val secrets = SecretStore(applicationContext)
        val chats = ChatRepository(applicationContext)
        val batchClient = OpenRouterBatchClient(applicationContext)
        val videoClient = OpenRouterVideoClient(applicationContext)
        var retry = false

        batchJobs.forEach { stored ->
            // A single Batch submitted from ordinary chat is polled by the live chat request.
            // WorkManager takes over only after that foreground request/process disappears.
            if (RequestExecutionManager.hasActiveRequest() &&
                stored.chatId != null &&
                stored.chatId == RequestExecutionManager.snapshots.value.activeChatId
            ) {
                retry = true
                return@forEach
            }
            if (batches.list().none { it.id == stored.id }) return@forEach
            val key = secrets.getProfileApiKey(stored.connectionProfileId)
            if (key.isNullOrBlank()) { retry = true; return@forEach }
            runCatching {
                val current = if (stored.status.terminal) stored else {
                    val labels = stored.items.associate { it.customId to it.label }
                    val snapshot = batchClient.get(key, stored.remoteId, labels)
                    stored.copy(
                        status = snapshot.status,
                        items = if (snapshot.items.isNotEmpty()) snapshot.items else stored.items,
                        error = snapshot.error,
                        updatedAt = System.currentTimeMillis()
                    )
                }
                // Stop in the main chat removes local tracking. Do not resurrect a removed job
                // if a network poll happened to finish after the user pressed Stop.
                if (batches.list().none { it.id == stored.id }) return@runCatching
                if (current.status.terminal) {
                    current.chatId?.let { chatId ->
                        current.userMessageId?.let { messageId ->
                            chats.updateMessage(chatId, messageId) { message ->
                                if (message.deliveryState == "pending") message.copy(deliveryState = null) else message
                            }
                        }
                        chats.appendAssistantIfMissing(
                            chatId,
                            "batch:${current.remoteId}",
                            ChatMessage(
                                id = UUID.randomUUID().toString(),
                                role = "assistant",
                                text = renderBatch(current),
                                modelId = current.modelId,
                                providerName = "OpenRouter"
                            )
                        )
                    }
                    markDelivered("batches", current.remoteId)
                } else retry = true
                batches.upsert(current)
                AsyncJobEvents.notifyChanged()
            }.onFailure { retry = true }
        }

        videoJobs.forEach { stored ->
            val key = secrets.getProfileApiKey(stored.connectionProfileId)
            if (key.isNullOrBlank()) { retry = true; return@forEach }
            runCatching {
                var current = if (stored.status.terminal) stored else {
                    val snapshot = videoClient.get(key, stored.remoteId, stored.pollingUrl)
                    stored.copy(
                        status = snapshot.status,
                        generationId = snapshot.generationId ?: stored.generationId,
                        pollingUrl = snapshot.pollingUrl ?: stored.pollingUrl,
                        remoteUrls = if (snapshot.urls.isNotEmpty()) snapshot.urls else stored.remoteUrls,
                        costUsd = snapshot.costUsd ?: stored.costUsd,
                        error = snapshot.error,
                        updatedAt = System.currentTimeMillis()
                    )
                }
                if (current.status == VideoJobStatus.COMPLETED && current.localPath.isNullOrBlank()) {
                    val bytes = videoClient.download(key, current.remoteId)
                    current = current.copy(localPath = saveVideo(current.remoteId, bytes).absolutePath, updatedAt = System.currentTimeMillis())
                }
                if (current.status.terminal) {
                    current.chatId?.let { chatId -> deliverVideo(chats, chatId, current) }
                    markDelivered("videos", current.remoteId)
                } else retry = true
                videos.upsert(current)
            }.onFailure { retry = true }
        }

        return if (retry) Result.retry() else Result.success()
    }

    private fun renderBatch(job: BatchJob): String {
        if (job.status != BatchJobStatus.COMPLETED) return "Пакетное задание завершилось со статусом ${job.status.name.lowercase()}: ${job.error ?: "результат не получен"}"
        if (job.items.size == 1) return job.items.single().resultText ?: job.items.single().error?.let { "Ошибка: $it" } ?: "Пакетное задание завершено без текста ответа."
        return buildString {
            appendLine("Пакетная обработка завершена: ${job.items.size} заданий.")
            job.items.forEachIndexed { index, item ->
                appendLine(); appendLine("### ${index + 1}. ${item.label}")
                appendLine(item.resultText ?: item.error?.let { "Ошибка: $it" } ?: "Нет результата")
            }
        }.trim()
    }

    private fun deliverVideo(chats: ChatRepository, chatId: String, job: VideoJob) {
        val path = job.localPath
        if (job.status == VideoJobStatus.COMPLETED && !path.isNullOrBlank()) {
            val file = File(path)
            val generated = GeneratedFile("video-${job.remoteId}", file.name, "video/mp4", file.absolutePath, file.length())
            chats.appendAssistantIfMissing(chatId, "video:${job.remoteId}", ChatMessage(UUID.randomUUID().toString(), "assistant", "Видео готово.", generatedFiles = listOf(generated)))
        } else {
            chats.appendAssistantIfMissing(chatId, "video:${job.remoteId}", ChatMessage(UUID.randomUUID().toString(), "assistant", "Генерация видео завершилась со статусом ${job.status.name.lowercase()}: ${job.error ?: "результат не получен"}"))
        }
    }

    private fun saveVideo(id: String, bytes: ByteArray): File {
        val safe = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val dir = File(applicationContext.filesDir, "generated").apply { mkdirs() }
        return File(dir, "umnik_video_$safe.mp4").apply { writeBytes(bytes) }
    }

    private fun markDelivered(key: String, id: String) {
        val values = delivery.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        values += id
        if (values.size > 500) values.remove(values.first())
        delivery.edit().putStringSet(key, values).apply()
    }

    companion object {
        private const val UNIQUE = "umnik-openrouter-v16-jobs"
        fun schedule(context: Context, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<OpenRouterBackgroundWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
