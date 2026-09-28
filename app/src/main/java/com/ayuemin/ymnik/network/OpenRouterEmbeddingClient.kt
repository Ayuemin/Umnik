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

class OpenRouterEmbeddingClient(
    context: Context,
    private val costSink: ((String?) -> Unit)? = null
) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .addInterceptor(DiagnosticHttpInterceptor(context, "OpenRouter embeddings"))
        .eventListenerFactory { DiagnosticNetworkEventListener(context, "OpenRouter embeddings") }
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun embed(
        apiKey: String,
        modelId: String,
        inputs: List<String>,
        inputType: String? = null,
        baseUrl: String = "https://openrouter.ai/api/v1"
    ): List<FloatArray> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Не задан API-ключ OpenRouter для embeddings" }
        require(modelId.isNotBlank()) { "Не выбрана embedding-модель" }
        require(inputs.isNotEmpty()) { "Нет текста для индексации" }

        val payload = JsonObject().apply {
            addProperty("model", modelId)
            add("input", JsonArray().apply { inputs.forEach(::add) })
            addProperty("encoding_format", "float")
            inputType?.takeIf { it.isNotBlank() }?.let { addProperty("input_type", it) }
        }
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/embeddings")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/Ayuemin/Umnik")
            .header("X-Title", "Umnik Android")
            .post(gson.toJson(payload).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("OpenRouter embeddings HTTP ${response.code}: ${body.take(700)}")
            }
            val root = gson.fromJson(body, JsonObject::class.java)
            val usage = root.getAsJsonObject("usage")
            val costExact = runCatching {
                (usage?.get("cost")?.takeUnless { it.isJsonNull }
                    ?: root.get("cost")?.takeUnless { it.isJsonNull })
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()
            costSink?.invoke(costExact)
            val rows = root.getAsJsonArray("data")?.mapNotNull { element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val index = obj.get("index")?.asInt ?: return@mapNotNull null
                val vector = obj.getAsJsonArray("embedding")?.map { it.asFloat }?.toFloatArray()
                    ?: return@mapNotNull null
                index to vector
            }.orEmpty().sortedBy { it.first }
            require(rows.size == inputs.size) {
                "OpenRouter вернул ${rows.size} embeddings вместо ${inputs.size}"
            }
            rows.map { it.second }
        }
    }
}
