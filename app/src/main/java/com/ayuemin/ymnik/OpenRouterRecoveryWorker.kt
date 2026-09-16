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
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class OpenRouterRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
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

        return runCatching { recover(record, apiKey) }
            .fold(
                onSuccess = { completion ->
                    val toolCalls = completion.message.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray
                    if (toolCalls != null && toolCalls.size() > 0) {
                        DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Recovered completion requires tool continuation; request=${requestId.take(8)}")
                        return@fold Result.retry()
                    }
                    val text = contentText(completion.message.get("content"))
                    if (text.isBlank()) return@fold Result.retry()

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
            val status = runCatching {
                client.newCall(statusRequest).execute().use { response ->
                    if (response.code == 404) return@use null
                    if (response.code == 401 || response.code == 403) throw IOException("OpenRouter ${response.code}")
                    if (!response.isSuccessful) return@use null
                    val root = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
                    val data = root?.getAsJsonObject("data") ?: return@use null
                    if (runCatching { data.get("cancelled")?.asBoolean }.getOrNull() == true) throw IOException("Generation cancelled")
                    data.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString?.isNotBlank() == true
                }
            }.getOrNull()
            ready = status == true
            poll += 1
        }
        if (!ready) throw IOException("Generation is not complete yet")

        // Give OpenRouter a brief moment to make the just-completed response cache-visible.
        delay(900L)
        val request = Request.Builder()
            .url(endpoint(record.baseUrl, "chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Umnik Android")
            .header("HTTP-Referer", "https://github.com/Ayuemin/Umnik")
            .header("X-OpenRouter-Metadata", "enabled")
            .header("X-OpenRouter-Cache", "true")
            .header("X-OpenRouter-Cache-TTL", "300")
            .post(record.payloadJson.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("OpenRouter ${response.code}")
            val cache = response.header("X-OpenRouter-Cache-Status")
            if (!cache.equals("HIT", ignoreCase = true)) {
                DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Expected cache HIT but got ${cache ?: "unknown"}; request=${record.requestId.take(8)}")
            }
            OpenRouterResponseParser.parse(body, allowEmpty = false)
        }
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
        private const val CACHE_RECOVERY_MAX_AGE_MS = 240_000L
        private const val RECOVERY_WINDOW_MS = 90_000L

        private fun uniqueName(requestId: String) = "umnik-openrouter-recovery-$requestId"

        fun schedule(context: Context, requestId: String, initialDelaySeconds: Long = 45L) {
            val request = OneTimeWorkRequestBuilder<OpenRouterRecoveryWorker>()
                .setInputData(workDataOf(KEY_REQUEST_ID to requestId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(initialDelaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                uniqueName(requestId), ExistingWorkPolicy.KEEP, request
            )
        }

        fun cancel(context: Context, requestId: String) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(uniqueName(requestId))
        }
    }
}
