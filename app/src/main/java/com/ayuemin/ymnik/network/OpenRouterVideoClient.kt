package com.ayuemin.ymnik.network

import android.content.Context
import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.diagnostics.DiagnosticNetworkEventListener
import com.ayuemin.ymnik.model.VideoJobStatus
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

class OpenRouterVideoClient(private val context: Context) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .addInterceptor(DiagnosticHttpInterceptor(context, "OpenRouter Video"))
        .eventListenerFactory { DiagnosticNetworkEventListener(context, "OpenRouter Video") }
        .retryOnConnectionFailure(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()

    data class Reference(
        val type: String,
        val dataUrl: String,
        val frameType: String? = null
    )

    data class SubmitOptions(
        val aspectRatio: String? = null,
        val durationSeconds: Int? = null,
        val resolution: String? = null,
        val size: String? = null,
        val generateAudio: Boolean? = null,
        val seed: Long? = null,
        val references: List<Reference> = emptyList()
    )

    data class Snapshot(
        val id: String,
        val status: VideoJobStatus,
        val pollingUrl: String? = null,
        val generationId: String? = null,
        val urls: List<String> = emptyList(),
        val costUsd: Double? = null,
        val error: String? = null
    )

    /** Submit is intentionally never auto-retried because it may spend credits. */
    suspend fun submit(
        apiKey: String,
        model: String,
        prompt: String,
        options: SubmitOptions = SubmitOptions(),
        baseUrl: String = DEFAULT_BASE_URL
    ): Snapshot = withContext(Dispatchers.IO) {
        require(model.isNotBlank()) { "Не выбрана модель видео" }
        require(prompt.isNotBlank()) { "Введите описание видео" }
        val payload = OpenRouterVideoCodec.submitPayload(model, prompt, options)
        val request = Request.Builder()
            .url(endpoint(baseUrl, "videos"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Umnik Android")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code !in 200..299) error(OpenRouterVideoCodec.apiError(response.code, body))
            val snapshot = OpenRouterVideoCodec.parseSnapshot(body)
            require(snapshot.id.isNotBlank()) { "OpenRouter не вернул video job id" }
            DiagnosticLog.record(context, "VIDEO", "created id=${snapshot.id}; model=$model; status=${snapshot.status}")
            snapshot
        }
    }

    suspend fun get(
        apiKey: String,
        jobId: String,
        pollingUrl: String? = null,
        baseUrl: String = DEFAULT_BASE_URL
    ): Snapshot = withContext(Dispatchers.IO) {
        val url = OpenRouterVideoCodec.resolvePollingUrl(baseUrl, jobId, pollingUrl)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Umnik Android")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(OpenRouterVideoCodec.apiError(response.code, body))
            val snapshot = OpenRouterVideoCodec.parseSnapshot(body)
            DiagnosticLog.record(context, "VIDEO", "poll id=$jobId; status=${snapshot.status}")
            if (snapshot.id.isBlank()) snapshot.copy(id = jobId) else snapshot
        }
    }

    suspend fun download(
        apiKey: String,
        jobId: String,
        index: Int = 0,
        baseUrl: String = DEFAULT_BASE_URL
    ): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${endpoint(baseUrl, "videos")}/${jobId.trim()}/content?index=${index.coerceAtLeast(0)}")
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Umnik Android")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                error(OpenRouterVideoCodec.apiError(response.code, body))
            }
            response.body?.bytes()?.takeIf { it.isNotEmpty() }
                ?: error("OpenRouter вернул пустой видеофайл")
        }
    }

    private fun endpoint(baseUrl: String, path: String): String =
        "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
    }
}

internal object OpenRouterVideoCodec {
    private val gson = Gson()

    fun submitPayload(
        model: String,
        prompt: String,
        options: OpenRouterVideoClient.SubmitOptions
    ): JsonObject = JsonObject().apply {
        addProperty("model", model.trim())
        addProperty("prompt", prompt)
        options.aspectRatio?.trim()?.takeIf { it.isNotBlank() }?.let { addProperty("aspect_ratio", it) }
        options.durationSeconds?.takeIf { it > 0 }?.let { addProperty("duration", it) }
        options.resolution?.trim()?.takeIf { it.isNotBlank() }?.let { addProperty("resolution", it) }
        options.size?.trim()?.takeIf { it.isNotBlank() }?.let { addProperty("size", it) }
        options.generateAudio?.let { addProperty("generate_audio", it) }
        options.seed?.let { addProperty("seed", it) }

        val frames = options.references.filter { !it.frameType.isNullOrBlank() }
        if (frames.isNotEmpty()) {
            add("frame_images", JsonArray().apply {
                frames.forEach { ref ->
                    add(JsonObject().apply {
                        addProperty("frame_type", ref.frameType)
                        add("image", JsonObject().apply {
                            addProperty("type", "image_url")
                            add("image_url", JsonObject().apply { addProperty("url", ref.dataUrl) })
                        })
                    })
                }
            })
        }
        val references = options.references.filter { it.frameType.isNullOrBlank() }
        if (references.isNotEmpty()) {
            add("input_references", JsonArray().apply {
                references.forEach { ref ->
                    add(JsonObject().apply {
                        addProperty("type", ref.type)
                        when (ref.type.lowercase()) {
                            "audio" -> add("audio_url", JsonObject().apply { addProperty("url", ref.dataUrl) })
                            "video" -> add("video_url", JsonObject().apply { addProperty("url", ref.dataUrl) })
                            else -> add("image_url", JsonObject().apply { addProperty("url", ref.dataUrl) })
                        }
                    })
                }
            })
        }
    }

    fun parseSnapshot(json: String): OpenRouterVideoClient.Snapshot {
        val root = runCatching { gson.fromJson(json, JsonObject::class.java) }
            .getOrElse { error("OpenRouter вернул некорректный Video JSON") }
        val urls = root.get("unsigned_urls")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }
            .orEmpty()
        return OpenRouterVideoClient.Snapshot(
            id = root.string("id").orEmpty(),
            status = VideoJobStatus.fromApi(root.string("status")),
            pollingUrl = root.string("polling_url"),
            generationId = root.string("generation_id"),
            urls = urls,
            costUsd = root.getAsJsonObject("usage")?.double("cost"),
            error = root.string("error") ?: root.getAsJsonObject("error")?.string("message")
        )
    }

    fun resolvePollingUrl(baseUrl: String, jobId: String, pollingUrl: String?): String {
        val provided = pollingUrl?.trim().orEmpty()
        if (provided.startsWith("https://") || provided.startsWith("http://")) return provided
        if (provided.startsWith('/')) {
            val uri = java.net.URI(baseUrl)
            return "${uri.scheme}://${uri.authority}$provided"
        }
        return "${baseUrl.trimEnd('/')}/videos/${jobId.trim()}"
    }

    fun apiError(code: Int, body: String): String {
        val detail = runCatching {
            val root = gson.fromJson(body, JsonObject::class.java)
            root.getAsJsonObject("error")?.string("message") ?: root.string("error") ?: root.string("message")
        }.getOrNull()
        return "OpenRouter Video HTTP $code: ${detail ?: body.take(400)}"
    }

    private fun JsonObject.string(name: String): String? = runCatching {
        get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonObject.double(name: String): Double? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asDouble
    }.getOrNull()
}
