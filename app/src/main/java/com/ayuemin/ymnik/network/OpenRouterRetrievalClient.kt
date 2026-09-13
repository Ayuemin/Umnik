package com.ayuemin.ymnik.network

import android.content.Context
import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor
import com.ayuemin.ymnik.diagnostics.DiagnosticNetworkEventListener
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OpenRouterRetrievalClient(private val context: Context) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .addInterceptor(DiagnosticHttpInterceptor(context, "OpenRouter Retrieval"))
        .eventListenerFactory { DiagnosticNetworkEventListener(context, "OpenRouter Retrieval") }
        .retryOnConnectionFailure(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    data class EmbeddingResult(
        val model: String,
        val vectors: List<List<Float>>,
        val promptTokens: Int? = null,
        val totalTokens: Int? = null
    )

    data class RerankItem(
        val index: Int,
        val score: Double,
        val text: String?
    )

    data class RerankResult(
        val model: String,
        val items: List<RerankItem>,
        val totalTokens: Int? = null
    )

    suspend fun embeddings(
        apiKey: String,
        model: String,
        inputs: List<String>,
        inputType: String? = null,
        dimensions: Int? = null,
        baseUrl: String = DEFAULT_BASE_URL
    ): EmbeddingResult = withContext(Dispatchers.IO) {
        require(inputs.isNotEmpty()) { "Нет текста для индексации" }
        val payload = JsonObject().apply {
            addProperty("model", model)
            if (inputs.size == 1) addProperty("input", inputs.single())
            else add("input", JsonArray().apply { inputs.forEach(::add) })
            inputType?.takeIf { it.isNotBlank() }?.let { addProperty("input_type", it) }
            dimensions?.takeIf { it > 0 }?.let { addProperty("dimensions", it) }
            addProperty("encoding_format", "float")
        }
        val root = postJson(apiKey, endpoint(baseUrl, "embeddings"), payload)
        val vectors = root.getAsJsonArray("data")
            ?.mapNotNull { entry ->
                val vector = entry.takeIf { it.isJsonObject }?.asJsonObject
                    ?.getAsJsonArray("embedding")
                    ?: return@mapNotNull null
                vector.mapNotNull { value -> runCatching { value.asFloat }.getOrNull() }
                    .takeIf { it.isNotEmpty() }
            }
            .orEmpty()
        if (vectors.size != inputs.size) {
            error("OpenRouter вернул ${vectors.size} embeddings для ${inputs.size} входов")
        }
        val usage = root.getAsJsonObject("usage")
        EmbeddingResult(
            model = root.string("model") ?: model,
            vectors = vectors,
            promptTokens = usage?.int("prompt_tokens"),
            totalTokens = usage?.int("total_tokens")
        )
    }

    suspend fun embedQuery(
        apiKey: String,
        model: String,
        query: String,
        baseUrl: String = DEFAULT_BASE_URL
    ): List<Float> = embeddings(
        apiKey = apiKey,
        model = model,
        inputs = listOf(query),
        inputType = "search_query",
        baseUrl = baseUrl
    ).vectors.single()

    suspend fun embedDocuments(
        apiKey: String,
        model: String,
        documents: List<String>,
        baseUrl: String = DEFAULT_BASE_URL
    ): List<List<Float>> = embeddings(
        apiKey = apiKey,
        model = model,
        inputs = documents,
        inputType = "search_document",
        baseUrl = baseUrl
    ).vectors

    suspend fun rerank(
        apiKey: String,
        model: String,
        query: String,
        documents: List<String>,
        topN: Int = 5,
        baseUrl: String = DEFAULT_BASE_URL
    ): RerankResult = withContext(Dispatchers.IO) {
        require(documents.isNotEmpty()) { "Нет документов для переранжирования" }
        val payload = JsonObject().apply {
            addProperty("model", model)
            addProperty("query", query)
            add("documents", JsonArray().apply { documents.forEach(::add) })
            addProperty("top_n", topN.coerceIn(1, documents.size))
        }
        val root = postJson(apiKey, endpoint(baseUrl, "rerank"), payload)
        val items = root.getAsJsonArray("results")
            ?.mapNotNull { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val index = item.int("index") ?: return@mapNotNull null
                val score = item.double("relevance_score") ?: return@mapNotNull null
                val text = item.getAsJsonObject("document")?.string("text")
                RerankItem(index, score, text)
            }
            .orEmpty()
        val usage = root.getAsJsonObject("usage")
        RerankResult(
            model = root.string("model") ?: model,
            items = items,
            totalTokens = usage?.int("total_tokens")
        )
    }

    private fun postJson(apiKey: String, url: String, payload: JsonObject): JsonObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Umnik Android")
            .header("HTTP-Referer", "https://github.com/Ayuemin/Umnik")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val root = gson.fromJson(body, JsonObject::class.java)
                    root.getAsJsonObject("error")?.string("message") ?: root.string("message")
                }.getOrNull()
                error("OpenRouter HTTP ${response.code}: ${detail ?: body.take(400)}")
            }
            return gson.fromJson(body, JsonObject::class.java)
        }
    }

    private fun endpoint(baseUrl: String, path: String): String =
        "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    private fun JsonObject.string(name: String): String? = runCatching {
        get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(name: String): Int? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asInt
    }.getOrNull()

    private fun JsonObject.double(name: String): Double? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asDouble
    }.getOrNull()

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
    }
}
