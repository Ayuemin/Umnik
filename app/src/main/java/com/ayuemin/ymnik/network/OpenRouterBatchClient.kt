package com.ayuemin.ymnik.network

import android.content.Context
import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.diagnostics.DiagnosticNetworkEventListener
import com.ayuemin.ymnik.model.BatchJobItem
import com.ayuemin.ymnik.model.BatchJobStatus
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OpenRouterBatchClient(private val context: Context) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .addInterceptor(DiagnosticHttpInterceptor(context, "OpenRouter Batch"))
        .eventListenerFactory { DiagnosticNetworkEventListener(context, "OpenRouter Batch") }
        .retryOnConnectionFailure(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    data class BatchRequest(
        val customId: String,
        val body: JsonObject,
        val label: String = customId
    )

    data class Snapshot(
        val remoteId: String,
        val status: BatchJobStatus,
        val items: List<BatchJobItem>,
        val error: String? = null
    )

    /**
     * Creates a paid asynchronous batch exactly once.
     *
     * Callers must persist the returned remote id before doing any other work.
     * This method intentionally does not retry ambiguous network failures: a
     * retry could create a second paid batch if the first POST reached OpenRouter.
     */
    suspend fun create(
        apiKey: String,
        batchModelId: String,
        requests: List<BatchRequest>,
        baseUrl: String = DEFAULT_BASE_URL
    ): Snapshot = withContext(Dispatchers.IO) {
        require(requests.isNotEmpty()) { "Пакетное задание не содержит запросов" }
        val payload = OpenRouterBatchCodec.createPayload(batchModelId, requests)
        val request = Request.Builder()
            .url(OpenRouterBatchCodec.batchesUrl(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Umnik Android")
            .header("HTTP-Referer", "https://github.com/Ayuemin/Umnik")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(OpenRouterBatchCodec.apiError(response.code, body))
            val snapshot = OpenRouterBatchCodec.parseSnapshot(body, requests.associate { it.customId to it.label })
            require(snapshot.remoteId.isNotBlank()) { "OpenRouter не вернул batch_id" }
            DiagnosticLog.record(
                context,
                "BATCH",
                "created id=${snapshot.remoteId}; model=${OpenRouterBatchCodec.baseModelId(batchModelId)}; items=${requests.size}; status=${snapshot.status}"
            )
            snapshot
        }
    }

    suspend fun get(
        apiKey: String,
        remoteId: String,
        labels: Map<String, String> = emptyMap(),
        baseUrl: String = DEFAULT_BASE_URL
    ): Snapshot = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${OpenRouterBatchCodec.batchesUrl(baseUrl)}/${remoteId.trim()}")
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Umnik Android")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(OpenRouterBatchCodec.apiError(response.code, body))
            val snapshot = OpenRouterBatchCodec.parseSnapshot(body, labels)
            DiagnosticLog.record(
                context,
                "BATCH",
                "poll id=${snapshot.remoteId.ifBlank { remoteId }}; status=${snapshot.status}; completed=${snapshot.items.count { it.resultText != null || it.error != null }}/${snapshot.items.size}"
            )
            if (snapshot.remoteId.isBlank()) snapshot.copy(remoteId = remoteId) else snapshot
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
    }
}

/** Pure codec so payload/response behavior can be unit-tested without Android. */
internal object OpenRouterBatchCodec {
    private val gson = Gson()

    fun baseModelId(modelId: String): String {
        var value = modelId.trim()
        if (value.endsWith(":batch", ignoreCase = true)) {
            value = value.dropLast(":batch".length)
        }
        return value
    }

    fun batchesUrl(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return when {
            clean.endsWith("/v1", ignoreCase = true) -> clean.dropLast(3) + "/beta/batches"
            clean.endsWith("/api", ignoreCase = true) -> "$clean/beta/batches"
            clean.endsWith("/api/beta", ignoreCase = true) -> "$clean/batches"
            else -> "$clean/beta/batches"
        }
    }

    fun createPayload(
        batchModelId: String,
        requests: List<OpenRouterBatchClient.BatchRequest>
    ): JsonObject {
        val model = baseModelId(batchModelId)
        require(model.isNotBlank()) { "Не выбрана Batch-модель" }
        require(requests.isNotEmpty()) { "Пакетное задание не содержит запросов" }

        return JsonObject().apply {
            addProperty("endpoint", "/v1/chat/completions")
            addProperty("model", model)
            add("requests", JsonArray().apply {
                requests.forEach { item ->
                    add(JsonObject().apply {
                        addProperty("custom_id", item.customId)
                        add("body", item.body.deepCopy().apply {
                            addProperty("model", model)
                            remove("stream")
                        })
                    })
                }
            })
        }
    }

    fun parseSnapshot(json: String, labels: Map<String, String> = emptyMap()): OpenRouterBatchClient.Snapshot {
        val root = runCatching { gson.fromJson(json, JsonObject::class.java) }
            .getOrElse { error("OpenRouter вернул некорректный Batch JSON") }
        val remoteId = root.string("id").orEmpty()
        val status = BatchJobStatus.fromApi(root.string("status"))
        val rootError = extractError(root.get("error"))
        val results = root.get("results")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { element -> parseResult(element, labels) }
            .orEmpty()
        return OpenRouterBatchClient.Snapshot(
            remoteId = remoteId,
            status = status,
            items = results,
            error = rootError
        )
    }

    fun apiError(code: Int, body: String): String {
        val detail = runCatching {
            val root = gson.fromJson(body, JsonObject::class.java)
            extractError(root.get("error")) ?: root.string("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return if (detail != null) "OpenRouter Batch HTTP $code: $detail"
        else "OpenRouter Batch HTTP $code${body.take(300).takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
    }

    private fun parseResult(element: JsonElement, labels: Map<String, String>): BatchJobItem? {
        val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val customId = item.string("custom_id")
            ?: item.string("customId")
            ?: return null
        val error = extractError(item.get("error"))
            ?: item.getAsJsonObject("response")?.let { response ->
                val statusCode = response.get("status_code")?.runCatching { asInt }?.getOrNull()
                if (statusCode != null && statusCode !in 200..299) {
                    extractError(response.get("body")) ?: "HTTP $statusCode"
                } else null
            }
        val responseBody = when {
            item.get("response")?.isJsonObject == true -> item.getAsJsonObject("response").get("body")
            item.has("body") -> item.get("body")
            item.has("result") -> item.get("result")
            else -> null
        }
        val text = if (error == null) extractCompletionText(responseBody) else null
        return BatchJobItem(
            customId = customId,
            label = labels[customId] ?: customId,
            resultText = text?.takeIf { it.isNotBlank() },
            error = error
        )
    }

    internal fun extractCompletionText(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) return element.asString
        if (!element.isJsonObject) return null
        val root = element.asJsonObject

        root.string("output_text")?.takeIf { it.isNotBlank() }?.let { return it }

        val choices = root.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
        val firstChoice = choices?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        val message = firstChoice?.getAsJsonObject("message")
        extractContent(message?.get("content"))?.takeIf { it.isNotBlank() }?.let { return it }
        extractContent(firstChoice?.get("text"))?.takeIf { it.isNotBlank() }?.let { return it }

        val output = root.get("output")?.takeIf { it.isJsonArray }?.asJsonArray
        val outputText = output?.flatMap { item ->
            item.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("content")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { part ->
                    val obj = part.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    obj.string("text") ?: obj.string("output_text")
                }.orEmpty()
        }?.joinToString("\n")?.trim()
        return outputText?.takeIf { it.isNotBlank() }
    }

    private fun extractContent(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive) return runCatching { element.asString }.getOrNull()
        if (!element.isJsonArray) return null
        return element.asJsonArray.mapNotNull { part ->
            when {
                part.isJsonPrimitive -> runCatching { part.asString }.getOrNull()
                part.isJsonObject -> part.asJsonObject.string("text")
                    ?: part.asJsonObject.string("content")
                else -> null
            }
        }.joinToString("\n").trim().takeIf { it.isNotBlank() }
    }

    private fun extractError(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive) return runCatching { element.asString }.getOrNull()
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        return obj.string("message")
            ?: obj.string("detail")
            ?: obj.getAsJsonObject("error")?.string("message")
            ?: obj.getAsJsonObject("body")?.let { extractError(it) }
    }

    private fun JsonObject.string(name: String): String? = runCatching {
        get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
