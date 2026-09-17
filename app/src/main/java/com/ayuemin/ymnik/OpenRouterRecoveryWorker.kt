package com.ayuemin.ymnik

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ayuemin.ymnik.data.ChatRepository
import com.ayuemin.ymnik.data.SecretStore
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.network.OpenRouterRecoveryRecord
import com.ayuemin.ymnik.network.OpenRouterRecoveryStore
import com.ayuemin.ymnik.network.OpenRouterResponseParser
import com.ayuemin.ymnik.network.openRouterApiKeyFingerprint
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class OpenRouterRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private enum class GenerationState { PENDING, COMPLETED, CANCELLED, TERMINAL_FAILURE }
    private class TerminalRecoveryException(message: String) : IOException(message)

    override suspend fun doWork(): Result {
        val requestId = inputData.getString(KEY_REQUEST_ID)?.takeIf { it.isNotBlank() } ?: return Result.success()
        val store = OpenRouterRecoveryStore(applicationContext)
        val record = store.get(requestId) ?: return Result.success()

        // The live foreground request owns delivery while its process is healthy.
        if (RequestExecutionManager.snapshotForRequest(requestId) != null) return Result.retry()

        val generationId = record.generationId
        if (generationId.isNullOrBlank()) {
            if (System.currentTimeMillis() - record.createdAt < NO_GENERATION_GRACE_MS) return Result.retry()
            failPending(record, "Запрос был прерван системой до получения идентификатора генерации. Повторите его вручную.")
            store.remove(requestId)
            return Result.success()
        }

        val seenAt = record.generationSeenAt ?: record.updatedAt
        if (System.currentTimeMillis() - seenAt > CACHE_RECOVERY_MAX_AGE_MS) {
            failPending(record, "Не удалось восстановить ответ после перезапуска приложения. Повторите запрос вручную.")
            store.remove(requestId)
            return Result.success()
        }

        val apiKey = SecretStore(applicationContext).getProfileApiKey(record.connectionProfileId)
        if (apiKey.isNullOrBlank()) return Result.retry()
        if (openRouterApiKeyFingerprint(apiKey) != record.apiKeyFingerprint) {
            failPending(record, "API-ключ OpenRouter изменился после отправки запроса. Автоматический повтор отменён, чтобы исключить двойную оплату.")
            store.remove(requestId)
            return Result.success()
        }

        return runCatching { recover(record, apiKey) }
            .fold(
                onSuccess = { completion ->
                    if (store.get(requestId) == null) {
                        DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Recovered result discarded after manual cancellation request=${requestId.take(8)}")
                        return@fold Result.success()
                    }
                    val toolCalls = completion.message.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray
                    if (toolCalls != null && toolCalls.size() > 0) {
                        DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Recovered completion requires local tool continuation; request=${requestId.take(8)}")
                        failPending(record, "Ответ модели восстановлен, но он требует продолжения локального инструмента. Повторите запрос вручную.")
                        store.remove(requestId)
                        return@fold Result.success()
                    }
                    val text = contentText(completion.message.get("content"))
                    if (text.isBlank()) return@fold Result.retry()

                    if (store.get(requestId) == null) {
                        DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Recovery result discarded after manual cancel/completion; request=${requestId.take(8)}")
                        return@fold Result.success()
                    }
                    val assistant = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = "assistant",
                        text = text,
                        modelId = completion.model.ifBlank { record.modelId },
                        providerName = completion.provider.takeIf { it.isNotBlank() } ?: "OpenRouter",
                        costUsd = completion.costUsd,
                        inputTokens = completion.promptTokens,
                        outputTokens = completion.completionTokens
                    )
                    ChatRepository(applicationContext).finishRequest(record.chatId, record.messageId, assistant)
                    AsyncJobEvents.notifyChanged()
                    store.remove(requestId)
                    DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Recovered after process loss; request=${requestId.take(8)} chat=${record.chatId.take(8)}")
                    Result.success()
                },
                onFailure = { error ->
                    if (store.get(requestId) == null) {
                        DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Background recovery stopped after manual cancellation request=${requestId.take(8)}")
                        return@fold Result.success()
                    }
                    if (error is TerminalRecoveryException) {
                        failPending(record, error.message ?: "OpenRouter завершил генерацию без доступного ответа. Повторите запрос вручную.")
                        store.remove(requestId)
                        return@fold Result.success()
                    }
                    DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Background recovery attempt failed request=${requestId.take(8)}", error)
                    Result.retry()
                }
            )
    }

    private suspend fun recover(record: OpenRouterRecoveryRecord, apiKey: String): OpenRouterResponseParser.Completion = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .writeTimeout(240, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .build()
        val gson = Gson()
        val generationId = record.generationId ?: throw IOException("Missing generation id")
        val seenAt = record.generationSeenAt ?: record.updatedAt
        val deadline = SystemClock.elapsedRealtime() + RECOVERY_WINDOW_MS
        var poll = 0
        var ready = false

        while (SystemClock.elapsedRealtime() < deadline && !ready) {
            if (!networkAvailable()) {
                delay(1_000L)
                continue
            }
            if (poll > 0) delay(minOf(10_000L, 1_500L + poll * 1_000L))
            val statusRequest = Request.Builder()
                .url(endpoint(record.baseUrl, "generation") + "?id=" + Uri.encode(generationId))
                .header("Authorization", "Bearer $apiKey")
                .header("X-Title", "Umnik Android")
                .get()
                .build()
            val state = try {
                client.newCall(statusRequest).execute().use { response ->
                    when {
                        response.code == 404 -> GenerationState.PENDING
                        response.code == 401 || response.code == 403 -> GenerationState.TERMINAL_FAILURE
                        !response.isSuccessful -> GenerationState.PENDING
                        else -> {
                            val root = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
                            val data = root?.getAsJsonObject("data") ?: return@use GenerationState.PENDING
                            val cancelled = runCatching { data.get("cancelled")?.asBoolean }.getOrNull() == true
                            val finish = data.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                            DiagnosticLog.record(
                                applicationContext,
                                "REQUEST_RECOVERY",
                                "background generation poll id=${generationId.take(12)} http=${response.code} cancelled=$cancelled finish=${finish.ifBlank { "pending" }} poll=${poll + 1}"
                            )
                            when {
                                cancelled -> GenerationState.CANCELLED
                                finish.isNotBlank() -> GenerationState.COMPLETED
                                else -> GenerationState.PENDING
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                GenerationState.PENDING
            }
            when (state) {
                GenerationState.COMPLETED -> ready = true
                GenerationState.CANCELLED -> throw TerminalRecoveryException("OpenRouter отменил генерацию после обрыва связи. Повторите запрос вручную.")
                GenerationState.TERMINAL_FAILURE -> throw TerminalRecoveryException("OpenRouter не разрешил проверить генерацию. Повторите запрос вручную.")
                GenerationState.PENDING -> Unit
            }
            poll += 1
        }
        if (!ready) throw IOException("Generation is not complete yet")

        // If the account has opted into OpenRouter input/output logging, this read-only endpoint
        // can return the completed text directly. It costs nothing and avoids any replay at all.
        storedGenerationCompletion(client, gson, record, apiKey, generationId)?.let { return@withContext it }

        // There is intentionally no fallback POST here. A cache status is only known after
        // sending that POST, so even a byte-identical replay could become a second paid request.
        throw TerminalRecoveryException(
            "OpenRouter завершил генерацию, но не предоставил её текст через безопасный read-only endpoint. " +
                "Umnik не повторяет запрос автоматически, чтобы исключить двойную оплату."
        )
    }

    private fun storedGenerationCompletion(
        client: OkHttpClient,
        gson: Gson,
        record: OpenRouterRecoveryRecord,
        apiKey: String,
        generationId: String
    ): OpenRouterResponseParser.Completion? {
        val request = Request.Builder()
            .url(endpoint(record.baseUrl, "generation/content") + "?id=" + Uri.encode(generationId))
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Umnik Android")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return@use null
                if (!response.isSuccessful) return@use null
                val root = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
                val completion = root?.getAsJsonObject("data")
                    ?.getAsJsonObject("output")
                    ?.get("completion")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?: return@use null
                DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Recovered directly from generation/content request=${record.requestId.take(8)}")
                OpenRouterResponseParser.Completion(
                    message = JsonObject().apply {
                        addProperty("role", "assistant")
                        addProperty("content", completion)
                    },
                    id = generationId,
                    provider = "",
                    model = record.modelId,
                    finishReason = "stop",
                    nativeFinishReason = "",
                    promptTokens = null,
                    completionTokens = null,
                    totalTokens = null,
                    reasoningTokens = null,
                    costUsd = null
                )
            }
        }.getOrNull()
    }

    private fun networkAvailable(): Boolean {
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun failPending(record: OpenRouterRecoveryRecord, message: String) {
        ChatRepository(applicationContext).updateMessage(record.chatId, record.messageId) { current ->
            if (current.deliveryState == "pending") current.copy(deliveryState = "failed") else current
        }
        AsyncJobEvents.notifyChanged()
        DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "$message request=${record.requestId.take(8)}")
    }

    private fun contentText(content: JsonElement?): String = when {
        content == null || content.isJsonNull -> ""
        content.isJsonPrimitive -> content.asString
        content.isJsonArray -> content.asJsonArray.mapNotNull { part ->
            when {
                part.isJsonPrimitive -> part.asString
                part.isJsonObject -> part.asJsonObject.get("text")?.takeIf { it.isJsonPrimitive }?.asString
                else -> null
            }
        }.joinToString("\n")
        else -> content.toString()
    }

    private fun endpoint(baseUrl: String, path: String): String =
        baseUrl.trim().trimEnd('/').ifBlank { "https://openrouter.ai/api/v1" } + "/" + path.trimStart('/')

    companion object {
        private const val KEY_REQUEST_ID = "request_id"
        private const val NO_GENERATION_GRACE_MS = 120_000L
        private const val CACHE_RECOVERY_MAX_AGE_MS = 180_000L
        private const val RECOVERY_WINDOW_MS = 60_000L

        private fun uniqueName(requestId: String) = "umnik-openrouter-recovery-$requestId"

        fun schedule(
            context: Context,
            requestId: String,
            initialDelaySeconds: Long = 45L,
            replaceExisting: Boolean = false,
            expedited: Boolean = false
        ) {
            val builder = OneTimeWorkRequestBuilder<OpenRouterRecoveryWorker>()
                .setInputData(workDataOf(KEY_REQUEST_ID to requestId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            val delaySeconds = initialDelaySeconds.coerceAtLeast(0L)
            if (expedited && delaySeconds == 0L) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            } else if (delaySeconds > 0L) {
                builder.setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            }
            val request = builder.build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                uniqueName(requestId),
                if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context, requestId: String) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName(requestId))
        }
    }
}
