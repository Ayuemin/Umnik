from pathlib import Path

ROOT = Path('.')

def read(path):
    return (ROOT / path).read_text(encoding='utf-8')

def write(path, text):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')

def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{path}: expected exactly one match, found {count}: {old[:120]!r}')
    write(path, text.replace(old, new, 1))

# 1. Android permissions: notification visibility + connectivity state.
replace_once(
    'app/src/main/AndroidManifest.xml',
    '    <uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.RECORD_AUDIO" />',
    '    <uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />\n    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />\n    <uses-permission android:name="android.permission.RECORD_AUDIO" />'
)

# 2. Request-owned clients can opt a direct user chat into crash recovery.
write('app/src/main/java/com/ayuemin/ymnik/RequestNetworkSession.kt', r'''package com.ayuemin.ymnik

import android.content.Context
import com.ayuemin.ymnik.network.OpenRouterClient
import java.util.Collections

/**
 * OpenRouter network clients owned by one top-level request.
 *
 * A session may create several clients for an Orchestrator fan-out. That allows independent
 * specialists to work in parallel while cancellation stays scoped to this one top-level job.
 */
internal class RequestNetworkSession(
    context: Context,
    private val requestId: String
) {
    private val app = context.applicationContext
    private val clients = Collections.synchronizedSet(mutableSetOf<OpenRouterClient>())

    private fun openRouter(
        chatId: String? = null,
        profileId: String? = null,
        recoverable: Boolean = false
    ): OpenRouterClient =
        OpenRouterClient(
            context = app,
            requestId = requestId,
            requestChatId = chatId ?: RequestExecutionManager.snapshotForRequest(requestId)?.chatId,
            requestProfileId = profileId,
            recoveryEnabled = recoverable
        ) { label -> updatePhase(label) }
            .also { clients += it }

    suspend fun <T> call(
        chatId: String? = null,
        profileId: String? = null,
        recoverable: Boolean = false,
        block: suspend (OpenRouterClient) -> T
    ): T = RequestConcurrencyLimiter.withPermit(app) {
        block(openRouter(chatId, profileId, recoverable))
    }

    fun reserveChat(chatId: String): Boolean = RequestExecutionManager.reserveChat(requestId, chatId)

    fun releaseChat(chatId: String) = RequestExecutionManager.releaseChat(requestId, chatId)

    fun updatePhase(label: String) {
        RequestExecutionManager.updatePhase(app, requestId, label)
    }

    fun cancel() {
        val snapshot = synchronized(clients) { clients.toList() }
        snapshot.forEach { runCatching { it.cancelActiveRequest() } }
    }
}
''')

# 3. Temporary exact-payload recovery state. API keys are never persisted here.
write('app/src/main/java/com/ayuemin/ymnik/network/OpenRouterRecoveryStore.kt', r'''package com.ayuemin.ymnik.network

import android.content.Context
import com.google.gson.Gson
import java.io.File

internal data class OpenRouterRecoveryRecord(
    val requestId: String,
    val chatId: String,
    val messageId: String,
    val connectionProfileId: String,
    val baseUrl: String,
    val modelId: String,
    val payloadJson: String,
    val generationId: String? = null,
    val cacheStatus: String? = null,
    val generationSeenAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

internal class OpenRouterRecoveryStore(context: Context) {
    private val gson = Gson()
    private val root = File(context.applicationContext.filesDir, "openrouter_recovery").apply { mkdirs() }

    fun get(requestId: String): OpenRouterRecoveryRecord? = synchronized(lock) {
        val file = fileFor(requestId)
        if (!file.exists()) return@synchronized null
        runCatching { gson.fromJson(file.readText(), OpenRouterRecoveryRecord::class.java) }.getOrNull()
    }

    fun put(record: OpenRouterRecoveryRecord) = synchronized(lock) {
        writeAtomic(record.copy(updatedAt = System.currentTimeMillis()))
    }

    fun updateGeneration(requestId: String, generationId: String, cacheStatus: String?) = synchronized(lock) {
        val current = get(requestId) ?: return@synchronized
        val now = System.currentTimeMillis()
        writeAtomic(
            current.copy(
                generationId = generationId,
                cacheStatus = cacheStatus,
                generationSeenAt = current.generationSeenAt ?: now,
                updatedAt = now
            )
        )
    }

    fun remove(requestId: String) = synchronized(lock) {
        val file = fileFor(requestId)
        if (file.exists()) file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    fun list(): List<OpenRouterRecoveryRecord> = synchronized(lock) {
        root.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .mapNotNull { file -> runCatching { gson.fromJson(file.readText(), OpenRouterRecoveryRecord::class.java) }.getOrNull() }
    }

    private fun writeAtomic(record: OpenRouterRecoveryRecord) {
        root.mkdirs()
        val target = fileFor(record.requestId)
        val temp = File(root, target.name + ".tmp")
        temp.writeText(gson.toJson(record))
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun fileFor(requestId: String): File {
        val safe = requestId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return File(root, "$safe.json")
    }

    private companion object {
        val lock = Any()
    }
}
''')

# 4. A WorkManager fallback survives process death and only replays an exact cached request.
write('app/src/main/java/com/ayuemin/ymnik/OpenRouterRecoveryWorker.kt', r'''package com.ayuemin.ymnik

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
''')

# 5. OpenRouter client: stronger network recovery and persistent exact-payload handoff.
path = 'app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt'
replace_once(path,
    'import android.content.Context\nimport android.net.Uri\nimport android.provider.OpenableColumns',
    'import android.content.Context\nimport android.net.ConnectivityManager\nimport android.net.NetworkCapabilities\nimport android.net.Uri\nimport android.os.SystemClock\nimport android.provider.OpenableColumns')
replace_once(path,
    'import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor',
    'import com.ayuemin.ymnik.OpenRouterRecoveryWorker\nimport com.ayuemin.ymnik.RequestExecutionManager\nimport com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor')
replace_once(path,
    '    private val requestId: String? = null,\n    private val requestChatId: String? = null,\n    private val phaseCallback: (String) -> Unit = {}',
    '    private val requestId: String? = null,\n    private val requestChatId: String? = null,\n    private val requestProfileId: String? = null,\n    private val recoveryEnabled: Boolean = false,\n    private val phaseCallback: (String) -> Unit = {}')
replace_once(path, '.pingInterval(5, TimeUnit.SECONDS)', '.pingInterval(30, TimeUnit.SECONDS)')
replace_once(path,
    '    private val chatBatchRunner = OpenRouterChatBatchRunner(context, requestId, requestChatId)\n    private val activeCallLock = Any()',
    '    private val chatBatchRunner = OpenRouterChatBatchRunner(context, requestId, requestChatId)\n    private val recoveryStore by lazy { OpenRouterRecoveryStore(context.applicationContext) }\n    private val activeCallLock = Any()')

old_method = r'''    private suspend fun requestCompletion(apiKey: String, baseUrl: String, payload: JsonObject, allowEmpty: Boolean): OpenRouterResponseParser.Completion {
        val model = payload.get("model")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        if (model.endsWith(":batch", ignoreCase = true)) {
            return chatBatchRunner.complete(apiKey, baseUrl, payload)
        }
        val request = Request.Builder()
            .url(endpoint(baseUrl, "chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Umnik Android")
            .header("HTTP-Referer", "https://github.com/Ayuemin/Umnik")
            .header("X-OpenRouter-Metadata", "enabled")
            // A short-lived response cache lets Umnik recover the exact already-paid
            // completion after a mobile HTTP/2 interruption instead of blindly paying twice.
            .header("X-OpenRouter-Cache", "true")
            .header("X-OpenRouter-Cache-TTL", "300")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        var recoveryAttempt = 0
        while (true) {
            var generationId: String? = null
            var cacheStatus: String? = null
            try {
                phaseCallback(
                    if (recoveryAttempt == 0) "Запрос отправлен · модель отвечает…" else "Забираю восстановленный ответ…"
                )
                executeActive(request).use { response ->
                    generationId = response.header("X-Generation-Id")
                    cacheStatus = response.header("X-OpenRouter-Cache-Status")
                    phaseCallback(
                        if (cacheStatus.equals("HIT", ignoreCase = true))
                            "Готовый ответ найден · загружаю…"
                        else
                            "Модель формирует ответ…"
                    )
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error(apiError(response.code, body))
                    val completion = OpenRouterResponseParser.parse(body, allowEmpty)
                    DiagnosticLog.record(
                        context,
                        "COMPLETION",
                        "OpenRouter id=${completion.id}; provider=${completion.provider}; finish=${completion.finishReason}; nativeFinish=${completion.nativeFinishReason}; completionTokens=${completion.completionTokens}; reasoningTokens=${completion.reasoningTokens}; cache=${cacheStatus ?: "off"}"
                    )
                    return completion
                }
            } catch (error: IOException) {
                val locallyCancelled = synchronized(activeCallLock) { activeCall?.isCanceled() == true }
                val recover = shouldRecoverOpenRouterBodyFailure(
                    locallyCancelled = locallyCancelled,
                    generationId = generationId,
                    cacheStatus = cacheStatus,
                    recoveryAttempt = recoveryAttempt
                )
                DiagnosticLog.record(
                    context,
                    "REQUEST_RECOVERY",
                    "body failure; localCancel=$locallyCancelled; generation=${generationId ?: "none"}; cache=${cacheStatus ?: "off"}; recover=$recover; error=${error::class.java.simpleName}: ${error.message}"
                )
                if (!recover) throw error

                clearActiveCall()
                phaseCallback("Связь прервалась · проверяю готовый ответ…")
                val ready = if (cacheStatus.equals("HIT", ignoreCase = true)) {
                    true
                } else {
                    awaitGenerationFinished(apiKey, baseUrl, generationId.orEmpty())
                }
                if (!ready) {
                    DiagnosticLog.record(context, "REQUEST_RECOVERY", "generation not completed in recovery window; id=${generationId ?: "none"}")
                    throw error
                }
                recoveryAttempt += 1
                phaseCallback("Ответ готов · восстанавливаю соединение…")
                delay(700L)
            } finally {
                clearActiveCall()
            }
        }
    }

    private suspend fun awaitGenerationFinished(apiKey: String, baseUrl: String, generationId: String): Boolean {
        if (generationId.isBlank()) return false
        repeat(12) { attempt ->
            if (attempt > 0) delay(2_500L)
            val request = Request.Builder()
                .url(endpoint(baseUrl, "generation") + "?id=" + Uri.encode(generationId))
                .header("Authorization", "Bearer $apiKey")
                .header("X-Title", "Umnik Android")
                .get()
                .build()
            val result = runCatching {
                http.newCall(request).execute().use { response ->
                    if (response.code == 404) return@use null
                    if (!response.isSuccessful) return@use false
                    val root = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
                    val data = root.getAsJsonObject("data") ?: return@use null
                    if (runCatching { data.get("cancelled")?.asBoolean }.getOrNull() == true) return@use false
                    val finish = data.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                    if (finish.isNotBlank()) true else null
                }
            }.getOrNull()
            if (result == true) {
                DiagnosticLog.record(context, "REQUEST_RECOVERY", "generation completed; id=$generationId; poll=${attempt + 1}")
                return true
            }
            if (result == false) return false
        }
        return false
    }
'''
new_method = r'''    private suspend fun requestCompletion(apiKey: String, baseUrl: String, payload: JsonObject, allowEmpty: Boolean): OpenRouterResponseParser.Completion {
        val model = payload.get("model")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        if (model.endsWith(":batch", ignoreCase = true)) {
            return chatBatchRunner.complete(apiKey, baseUrl, payload)
        }
        val payloadJson = gson.toJson(payload)
        val recoveryRecord = recoveryRecord(baseUrl, model, payloadJson)
        recoveryRecord?.let(recoveryStore::put)
        val request = Request.Builder()
            .url(endpoint(baseUrl, "chat/completions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Umnik Android")
            .header("HTTP-Referer", "https://github.com/Ayuemin/Umnik")
            .header("X-OpenRouter-Metadata", "enabled")
            // A short-lived response cache lets Umnik recover the exact already-paid
            // completion after a mobile HTTP/2 interruption instead of blindly paying twice.
            .header("X-OpenRouter-Cache", "true")
            .header("X-OpenRouter-Cache-TTL", "300")
            .post(payloadJson.toRequestBody("application/json".toMediaType()))
            .build()

        var recoveryAttempt = 0
        while (true) {
            var generationId: String? = null
            var cacheStatus: String? = null
            try {
                phaseCallback(
                    if (recoveryAttempt == 0) "Запрос отправлен · модель отвечает…" else "Забираю восстановленный ответ…"
                )
                executeActive(request).use { response ->
                    generationId = response.header("X-Generation-Id")
                    cacheStatus = response.header("X-OpenRouter-Cache-Status")
                    if (recoveryRecord != null && !generationId.isNullOrBlank()) {
                        recoveryStore.updateGeneration(recoveryRecord.requestId, generationId.orEmpty(), cacheStatus)
                        OpenRouterRecoveryWorker.schedule(context, recoveryRecord.requestId)
                    }
                    phaseCallback(
                        if (cacheStatus.equals("HIT", ignoreCase = true))
                            "Готовый ответ найден · загружаю…"
                        else
                            "Модель формирует ответ…"
                    )
                    // Do not clear recovery state until the whole response body has arrived.
                    val body = response.body?.string().orEmpty()
                    clearRecovery(recoveryRecord)
                    if (!response.isSuccessful) error(apiError(response.code, body))
                    val completion = OpenRouterResponseParser.parse(body, allowEmpty)
                    DiagnosticLog.record(
                        context,
                        "COMPLETION",
                        "OpenRouter id=${completion.id}; provider=${completion.provider}; finish=${completion.finishReason}; nativeFinish=${completion.nativeFinishReason}; completionTokens=${completion.completionTokens}; reasoningTokens=${completion.reasoningTokens}; cache=${cacheStatus ?: "off"}"
                    )
                    return completion
                }
            } catch (error: IOException) {
                val locallyCancelled = synchronized(activeCallLock) { activeCall?.isCanceled() == true }
                val recover = shouldRecoverOpenRouterBodyFailure(
                    locallyCancelled = locallyCancelled,
                    generationId = generationId,
                    cacheStatus = cacheStatus,
                    recoveryAttempt = recoveryAttempt
                )
                DiagnosticLog.record(
                    context,
                    "REQUEST_RECOVERY",
                    "body failure; localCancel=$locallyCancelled; generation=${generationId ?: "none"}; cache=${cacheStatus ?: "off"}; recover=$recover; attempt=$recoveryAttempt; error=${error::class.java.simpleName}: ${error.message}"
                )
                if (!recover) {
                    clearRecovery(recoveryRecord)
                    throw error
                }

                clearActiveCall()
                phaseCallback("Связь прервалась · жду сеть…")
                val deadline = SystemClock.elapsedRealtime() + RECOVERY_WINDOW_MS
                if (!awaitNetworkAvailable(deadline)) {
                    clearRecovery(recoveryRecord)
                    throw error
                }
                phaseCallback("Связь доступна · проверяю готовый ответ…")
                val ready = if (cacheStatus.equals("HIT", ignoreCase = true)) {
                    true
                } else {
                    awaitGenerationFinished(apiKey, baseUrl, generationId.orEmpty(), deadline)
                }
                if (!ready) {
                    DiagnosticLog.record(context, "REQUEST_RECOVERY", "generation not completed in recovery window; id=${generationId ?: "none"}")
                    clearRecovery(recoveryRecord)
                    throw error
                }
                recoveryAttempt += 1
                phaseCallback("Ответ готов · восстанавливаю соединение…")
                delay(900L)
            } finally {
                clearActiveCall()
            }
        }
    }

    private fun recoveryRecord(baseUrl: String, model: String, payloadJson: String): OpenRouterRecoveryRecord? {
        if (!recoveryEnabled) return null
        val id = requestId?.takeIf { it.isNotBlank() } ?: return null
        val snapshot = RequestExecutionManager.snapshotForRequest(id) ?: return null
        val chatId = requestChatId?.takeIf { it.isNotBlank() } ?: snapshot.chatId
        // Only a direct user-facing request may be delivered automatically after process death.
        // Orchestrator worker calls have their own continuation graph and must not be injected here.
        if (chatId != snapshot.chatId) return null
        val profileId = requestProfileId?.takeIf { it.isNotBlank() } ?: return null
        return OpenRouterRecoveryRecord(
            requestId = id,
            chatId = chatId,
            messageId = snapshot.messageId,
            connectionProfileId = profileId,
            baseUrl = baseUrl,
            modelId = model,
            payloadJson = payloadJson
        )
    }

    private fun clearRecovery(record: OpenRouterRecoveryRecord?) {
        val id = record?.requestId ?: return
        recoveryStore.remove(id)
        OpenRouterRecoveryWorker.cancel(context, id)
    }

    private suspend fun awaitNetworkAvailable(deadlineElapsed: Long): Boolean {
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return true
        while (SystemClock.elapsedRealtime() < deadlineElapsed) {
            val network = connectivity.activeNetwork
            val capabilities = network?.let(connectivity::getNetworkCapabilities)
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) return true
            delay(1_000L)
        }
        return false
    }

    private suspend fun awaitGenerationFinished(
        apiKey: String,
        baseUrl: String,
        generationId: String,
        deadlineElapsed: Long = SystemClock.elapsedRealtime() + RECOVERY_WINDOW_MS
    ): Boolean {
        if (generationId.isBlank()) return false
        var attempt = 0
        while (SystemClock.elapsedRealtime() < deadlineElapsed) {
            if (!awaitNetworkAvailable(deadlineElapsed)) return false
            if (attempt > 0) delay(minOf(10_000L, 1_500L + attempt * 1_000L))
            val request = Request.Builder()
                .url(endpoint(baseUrl, "generation") + "?id=" + Uri.encode(generationId))
                .header("Authorization", "Bearer $apiKey")
                .header("X-Title", "Umnik Android")
                .get()
                .build()
            val result = runCatching {
                http.newCall(request).execute().use { response ->
                    if (response.code == 404) return@use null
                    if (response.code == 401 || response.code == 403) return@use false
                    if (!response.isSuccessful) return@use null
                    val root = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
                    val data = root.getAsJsonObject("data") ?: return@use null
                    if (runCatching { data.get("cancelled")?.asBoolean }.getOrNull() == true) return@use false
                    val finish = data.get("finish_reason")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                    if (finish.isNotBlank()) true else null
                }
            }.getOrNull()
            if (result == true) {
                DiagnosticLog.record(context, "REQUEST_RECOVERY", "generation completed; id=$generationId; poll=${attempt + 1}")
                return true
            }
            if (result == false) return false
            attempt += 1
        }
        return false
    }
'''
replace_once(path, old_method, new_method)
replace_once(path,
    '    companion object {\n        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"\n    }',
    '    companion object {\n        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"\n        private const val RECOVERY_WINDOW_MS = 120_000L\n    }')

# 6. Recovery policy allows a few cache-body retries, while user cancellation remains final.
write('app/src/main/java/com/ayuemin/ymnik/network/OpenRouterRecoveryPolicy.kt', r'''package com.ayuemin.ymnik.network

internal fun shouldRecoverOpenRouterBodyFailure(
    locallyCancelled: Boolean,
    generationId: String?,
    cacheStatus: String?,
    recoveryAttempt: Int
): Boolean {
    if (locallyCancelled || recoveryAttempt >= 3 || generationId.isNullOrBlank()) return false
    return cacheStatus.equals("MISS", ignoreCase = true) || cacheStatus.equals("HIT", ignoreCase = true)
}
''')
write('app/src/test/java/com/ayuemin/ymnik/network/OpenRouterRecoveryPolicyTest.kt', r'''package com.ayuemin.ymnik.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterRecoveryPolicyTest {
    @Test fun recoversCachedGenerationAfterRemoteBodyFailure() {
        assertTrue(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "MISS", 0))
        assertTrue(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "HIT", 0))
        assertTrue(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "HIT", 2))
    }

    @Test fun neverRetriesLocalCancelUnknownGenerationOrPastRetryBudget() {
        assertFalse(shouldRecoverOpenRouterBodyFailure(true, "gen-123", "MISS", 0))
        assertFalse(shouldRecoverOpenRouterBodyFailure(false, null, "MISS", 0))
        assertFalse(shouldRecoverOpenRouterBodyFailure(false, "gen-123", null, 0))
        assertFalse(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "MISS", 3))
    }
}
''')

# 7. Only the direct ordinary text request is eligible for automatic chat delivery after process death.
replace_once(
    'app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt',
    '                        require(profile.type == ProviderType.OPENROUTER) { "Umnik использует только OpenRouter" }\n                        network.call { requestApi ->\n                            val preparedContext = chatMemoryManager.prepare(',
    '                        require(profile.type == ProviderType.OPENROUTER) { "Umnik использует только OpenRouter" }\n                        network.call(profileId = profile.id, recoverable = true) { requestApi ->\n                            val preparedContext = chatMemoryManager.prepare('
)

# 8. On next process start, keep recoverable user messages pending and let WorkManager take over.
replace_once(
    'app/src/main/java/com/ayuemin/ymnik/RequestExecutionManager.kt',
    'import com.ayuemin.ymnik.diagnostics.DiagnosticLog\n',
    'import com.ayuemin.ymnik.diagnostics.DiagnosticLog\nimport com.ayuemin.ymnik.network.OpenRouterRecoveryStore\n'
)
old_recovery = r'''        val batches = runCatching { BatchJobRepository(app).list() }.getOrDefault(emptyList())
        val chatRepository = ChatRepository(app)
        val chats = runCatching { chatRepository.list() }.getOrDefault(emptyList())
        var batchCount = 0
        var interruptedCount = 0

        persisted.distinctBy { it.requestId }.forEach { saved ->
            val messageId = saved.messageId
            val savedBatch = messageId?.let { id ->
                batches.firstOrNull { it.chatId == saved.chatId && it.userMessageId == id }
            }
            if (savedBatch != null) {
                batchCount += 1
                return@forEach
            }

            val completed = messageId?.let { id ->
                val messages = chats.firstOrNull { it.id == saved.chatId }?.messages.orEmpty()
                val index = messages.indexOfFirst { it.id == id }
                index >= 0 && messages.drop(index + 1)
                    .firstOrNull { it.role == "assistant" || it.role == "user" }
                    ?.role == "assistant"
            } == true

            if (messageId != null && !completed) {
                interruptedCount += 1
                runCatching {
                    chatRepository.updateMessage(saved.chatId, messageId) {
                        if (it.deliveryState == "pending") it.copy(deliveryState = "failed") else it
                    }
                }
            }
        }

        prefs.edit().clear().commit()
        if (batchCount > 0) OpenRouterBackgroundWorker.schedule(app, replace = true)

        return when {
            interruptedCount > 0 && batchCount > 0 ->
                "$interruptedCount запрос(а) были прерваны системой; $batchCount batch-запрос(а) продолжаются на OpenRouter и будут получены автоматически."
            interruptedCount > 0 ->
                "$interruptedCount предыдущих запрос(а) были прерваны системой. Они не отправлены повторно во избежание повторной оплаты; при необходимости повторите их вручную."
            batchCount > 0 ->
                "$batchCount batch-запрос(а) продолжаются на OpenRouter. Umnik заберёт результаты автоматически."
            else -> null
        }
'''
new_recovery = r'''        val batches = runCatching { BatchJobRepository(app).list() }.getOrDefault(emptyList())
        val chatRepository = ChatRepository(app)
        val chats = runCatching { chatRepository.list() }.getOrDefault(emptyList())
        val recoveryStore = OpenRouterRecoveryStore(app)
        var batchCount = 0
        var recoveryCount = 0
        var interruptedCount = 0

        persisted.distinctBy { it.requestId }.forEach { saved ->
            val messageId = saved.messageId
            val savedBatch = messageId?.let { id ->
                batches.firstOrNull { it.chatId == saved.chatId && it.userMessageId == id }
            }
            if (savedBatch != null) {
                batchCount += 1
                return@forEach
            }

            val completed = messageId?.let { id ->
                val messages = chats.firstOrNull { it.id == saved.chatId }?.messages.orEmpty()
                val index = messages.indexOfFirst { it.id == id }
                index >= 0 && messages.drop(index + 1)
                    .firstOrNull { it.role == "assistant" || it.role == "user" }
                    ?.role == "assistant"
            } == true
            if (completed) {
                recoveryStore.remove(saved.requestId)
                return@forEach
            }

            if (messageId != null && recoveryStore.get(saved.requestId) != null) {
                recoveryCount += 1
                OpenRouterRecoveryWorker.schedule(app, saved.requestId, initialDelaySeconds = 0L)
                return@forEach
            }

            if (messageId != null) {
                interruptedCount += 1
                runCatching {
                    chatRepository.updateMessage(saved.chatId, messageId) {
                        if (it.deliveryState == "pending") it.copy(deliveryState = "failed") else it
                    }
                }
            }
        }

        prefs.edit().clear().commit()
        if (batchCount > 0) OpenRouterBackgroundWorker.schedule(app, replace = true)

        return buildList {
            if (recoveryCount > 0) add("$recoveryCount запрос(а) восстанавливаются в фоне после перезапуска приложения.")
            if (batchCount > 0) add("$batchCount batch-запрос(а) продолжаются на OpenRouter и будут получены автоматически.")
            if (interruptedCount > 0) add("$interruptedCount запрос(а) были прерваны системой до безопасной точки восстановления; при необходимости повторите их вручную.")
        }.joinToString(" ").takeIf { it.isNotBlank() }
'''
replace_once('app/src/main/java/com/ayuemin/ymnik/RequestExecutionManager.kt', old_recovery, new_recovery)

# 9. Foreground service notification uses Umnik icon and requests immediate foreground display.
write('app/src/main/java/com/ayuemin/ymnik/RequestKeepAliveService.kt', r'''package com.ayuemin.ymnik

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
            DiagnosticLog.record(applicationContext, "SERVICE", "All active requests cancelled from notification")
            RequestExecutionManager.cancelAll()
            return START_NOT_STICKY
        }

        val active = RequestExecutionManager.snapshots.value
        if (active.isEmpty()) {
            DiagnosticLog.record(applicationContext, "SERVICE", "Foreground service has no in-process requests; stopping orphan service")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openChat = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), pendingFlags)
        val cancelAll = PendingIntent.getService(
            this, 1, Intent(this, RequestKeepAliveService::class.java).setAction(ACTION_CANCEL_ALL), pendingFlags
        )
        val title = if (active.size == 1) "Umnik · модель работает" else "Umnik · работают ${active.size} чата"
        val text = if (active.size == 1) {
            active.first().label
        } else {
            active.take(2).joinToString(" · ") { it.label }.let { labels ->
                if (active.size > 2) "$labels · ещё ${active.size - 2}" else labels
            }
        }
        val cancelLabel = if (active.size == 1) "Остановить" else "Остановить все"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(R.drawable.ic_umnik)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openChat)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, cancelLabel, cancelAll)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        startForeground(NOTIFICATION_ID, builder.build())
        DiagnosticLog.record(applicationContext, "SERVICE", "Foreground request service active; startId=$startId; active=${active.size}")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DiagnosticLog.record(applicationContext, "SERVICE", "App task removed; active=${RequestExecutionManager.activeCount()}")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        DiagnosticLog.record(applicationContext, "SERVICE", "Foreground service timeout; startId=$startId; type=$fgsType; active=${RequestExecutionManager.activeCount()}")
        RequestExecutionManager.cancelAll()
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        DiagnosticLog.record(applicationContext, "SERVICE", "RequestKeepAliveService destroyed; active=${RequestExecutionManager.activeCount()}")
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
            if (RequestExecutionManager.hasActiveRequest()) start(context) else stop(context)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RequestKeepAliveService::class.java))
        }
    }
}
''')

# 10. Ask for notification permission once, only when the first active model request appears.
replace_once(
    'app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt',
    'import android.net.Uri\nimport android.widget.Toast',
    'import android.net.Uri\nimport android.os.Build\nimport android.widget.Toast'
)
replace_once(
    'app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt',
    '    val context = LocalContext.current\n    val tts = remember { TtsController(context) }',
    '''    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val tts = remember { TtsController(context) }'''
)
replace_once(
    'app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt',
    '    var screen by remember { mutableIntStateOf(0) }\n\n    DisposableEffect(tts, openRouterSpeech) {',
    '''    var screen by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.requestActive) {
        if (state.requestActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = context.getSharedPreferences("ymnik", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("notification_permission_prompted_v1171", false)) {
                prefs.edit().putBoolean("notification_permission_prompted_v1171", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    DisposableEffect(tts, openRouterSpeech) {'''
)
replace_once(
    'app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt',
    '    val requestActiveHere = vm.isChatRequestActive(state.currentChatId)\n    val requestActiveElsewhere = state.requestActive && !requestActiveHere\n    val nonRequestBusy = state.isLoading && !state.requestActive',
    '    val requestActiveHere = vm.isChatRequestActive(state.currentChatId)\n    val nonRequestBusy = state.isLoading && !state.requestActive'
)
replace_once(
    'app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt',
    '                            requestActiveElsewhere -> Text("Другой чат отвечает · здесь можно отправить новый запрос")\n                            imagePromptMode -> Text("Опишите изображение")',
    '                            imagePromptMode -> Text("Опишите изображение")'
)

print('v1.17.1 background recovery patch applied successfully')
