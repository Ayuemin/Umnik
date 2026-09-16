package com.ayuemin.ymnik.network

import android.content.Context
import com.ayuemin.ymnik.AsyncJobEvents
import com.ayuemin.ymnik.OpenRouterBackgroundWorker
import com.ayuemin.ymnik.RequestExecutionManager
import com.ayuemin.ymnik.data.BatchJobRepository
import com.ayuemin.ymnik.data.ChatRepository
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.model.BatchJob
import com.ayuemin.ymnik.model.BatchJobItem
import com.ayuemin.ymnik.model.BatchJobStatus
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.UUID

/** Makes a :batch model behave like an ordinary Umnik chat request. */
internal class OpenRouterChatBatchRunner(
    private val context: Context,
    private val requestId: String? = null,
    private val requestChatId: String? = null
) {
    private val client = OpenRouterBatchClient(context)
    private val jobs = BatchJobRepository(context)
    private val chats = ChatRepository(context)
    private val lock = Any()

    @Volatile private var generation: Long = 0L
    @Volatile private var activeGeneration: Long? = null
    @Volatile private var activeLocalJobId: String? = null

    fun stopTracking() {
        val wasBatchActive: Boolean
        val jobId: String?
        synchronized(lock) {
            wasBatchActive = activeGeneration != null
            if (!wasBatchActive) return
            generation += 1L
            activeGeneration = null
            jobId = activeLocalJobId
            activeLocalJobId = null
        }
        if (!jobId.isNullOrBlank()) jobs.remove(jobId)
        AsyncJobEvents.notifyChanged()
        requestId?.let { id ->
            RequestExecutionManager.fail(
                id,
                "Отслеживание Batch остановлено. Задание OpenRouter может продолжать выполняться и тарифицироваться на сервере."
            )
        }
        DiagnosticLog.record(context, "BATCH", "Local Batch tracking stopped; remote job can continue")
    }

    suspend fun complete(
        apiKey: String,
        baseUrl: String,
        originalPayload: JsonObject
    ): OpenRouterResponseParser.Completion {
        val batchModelId = originalPayload.get("model")?.asString.orEmpty()
        require(batchModelId.endsWith(":batch", ignoreCase = true)) { "Выбрана не Batch-модель" }
        val myGeneration = synchronized(lock) {
            generation += 1L
            activeLocalJobId = null
            activeGeneration = generation
            generation
        }

        // Batch cannot pause for Umnik's local create_file callback. Keep all normal
        // chat context and provider-side options, but remove the local-only tool schema
        // and its matching instruction from the already-built request.
        val body = originalPayload.deepCopy().apply {
            remove("tools")
            val messages = getAsJsonArray("messages")
            val system = messages?.firstOrNull()
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.takeIf { it.get("role")?.asString == "system" }
            val content = system?.get("content")?.takeIf { it.isJsonPrimitive }?.asString
            if (content != null) {
                system.addProperty(
                    "content",
                    content.replace(
                        "У тебя есть локальный инструмент create_file. Если пользователь просит результат файлом или материал получается слишком длинным для удобного чтения в чате, используй create_file.\n",
                        ""
                    )
                )
            }
        }
        val originSnapshot = requestId
            ?.let(RequestExecutionManager::snapshotForRequest)
            ?: error("Batch-запрос не привязан к активной сессии")
        val originChatId = requestChatId ?: originSnapshot.chatId
        val originChat = chats.list().firstOrNull { it.id == originChatId }
        val originMessageId = if (originChatId == originSnapshot.chatId) {
            originSnapshot.messageId
        } else {
            originChat?.messages?.lastOrNull { it.role == "user" }?.id
        }
        val customId = originMessageId?.let { "chat-$it" } ?: "chat-${UUID.randomUUID()}"
        val label = originChat?.messages
            ?.lastOrNull { it.id == originMessageId }
            ?.text
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.take(80)
            ?: "Запрос"
        val request = OpenRouterBatchClient.BatchRequest(customId = customId, body = body, label = label)

        val created = client.create(apiKey, batchModelId, listOf(request), baseUrl)
        var current = BatchJob(
            id = UUID.randomUUID().toString(),
            remoteId = created.remoteId,
            connectionProfileId = originChat?.connectionProfileId ?: "openrouter",
            chatId = originChatId,
            projectId = originChat?.projectId,
            userMessageId = originMessageId,
            modelId = batchModelId,
            baseModelId = OpenRouterBatchCodec.baseModelId(batchModelId),
            title = "Batch · $label",
            status = created.status,
            items = if (created.items.isNotEmpty()) created.items else listOf(BatchJobItem(customId, label)),
            error = created.error
        )

        // The remote id is durable before any poll starts. The create POST is never replayed.
        jobs.upsert(current)
        synchronized(lock) {
            if (activeGeneration == myGeneration) activeLocalJobId = current.id
        }
        AsyncJobEvents.notifyChanged()
        OpenRouterBackgroundWorker.schedule(context, replace = true)

        try {
            ensureStillTracked(myGeneration, current.id)
            while (!current.status.terminal) {
                delay(5_000L)
                ensureStillTracked(myGeneration, current.id)
                val labels = current.items.associate { it.customId to it.label }
                val snapshot = client.get(apiKey, current.remoteId, labels, baseUrl)
                ensureStillTracked(myGeneration, current.id)
                current = current.copy(
                    status = snapshot.status,
                    items = if (snapshot.items.isNotEmpty()) snapshot.items else current.items,
                    error = snapshot.error,
                    updatedAt = System.currentTimeMillis()
                )
                jobs.upsert(current)
                AsyncJobEvents.notifyChanged()
            }

            markDelivered(current.remoteId)
            if (current.status != BatchJobStatus.COMPLETED) {
                val reason = current.error ?: current.items.firstOrNull()?.error ?: current.status.name.lowercase()
                error("OpenRouter Batch завершился без результата: $reason")
            }
            val item = current.items.firstOrNull()
            val text = item?.resultText?.takeIf { it.isNotBlank() }
                ?: item?.error?.let { error("OpenRouter Batch: $it") }
                ?: error("OpenRouter Batch завершён без текста ответа")
            return completion(current.remoteId, current.modelId, text)
        } finally {
            synchronized(lock) {
                if (activeGeneration == myGeneration) activeGeneration = null
                if (activeLocalJobId == current.id) activeLocalJobId = null
            }
        }
    }

    private fun ensureStillTracked(myGeneration: Long, localJobId: String) {
        if (activeGeneration != myGeneration || jobs.list().none { it.id == localJobId }) {
            jobs.remove(localJobId)
            throw CancellationException(
                "Отслеживание Batch остановлено. Задание OpenRouter может продолжать выполняться на сервере."
            )
        }
    }

    private fun completion(remoteId: String, modelId: String, text: String): OpenRouterResponseParser.Completion {
        val message = JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", text)
        }
        return OpenRouterResponseParser.Completion(
            message = message,
            id = remoteId,
            provider = "OpenRouter",
            model = modelId,
            finishReason = "stop",
            nativeFinishReason = "stop",
            promptTokens = null,
            completionTokens = null,
            totalTokens = null,
            reasoningTokens = null,
            costUsd = null
        )
    }

    private fun markDelivered(remoteId: String) {
        val prefs = context.getSharedPreferences("openrouter_job_delivery", Context.MODE_PRIVATE)
        val values = prefs.getStringSet("batches", emptySet()).orEmpty().toMutableSet()
        values += remoteId
        if (values.size > 500) values.remove(values.first())
        prefs.edit().putStringSet("batches", values).apply()
    }
}
