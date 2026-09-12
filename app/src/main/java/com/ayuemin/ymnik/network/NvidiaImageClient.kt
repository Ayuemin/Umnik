package com.ayuemin.ymnik.network

import android.content.Context
import android.util.Base64
import com.ayuemin.ymnik.RequestKeepAliveService
import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.diagnostics.DiagnosticNetworkEventListener
import com.ayuemin.ymnik.model.GeneratedFile
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

class NvidiaImageClient(private val context: Context) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .addInterceptor(DiagnosticHttpInterceptor(context, "NVIDIA image"))
        .eventListenerFactory { DiagnosticNetworkEventListener(context, "NVIDIA image") }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS)
        .build()
    private val activeCallLock = Any()
    @Volatile private var activeCall: Call? = null

    private data class RawResponse(val code: Int, val body: String) {
        val successful: Boolean get() = code in 200..299
    }

    fun cancelActiveRequest() {
        synchronized(activeCallLock) { activeCall?.cancel() }
        http.dispatcher.cancelAll()
        RequestKeepAliveService.stop(context)
    }

    suspend fun generateImage(
        apiKey: String,
        baseUrl: String,
        model: String,
        prompt: String,
        aspectRatio: String? = null
    ): OpenRouterClient.Result = withContext(Dispatchers.IO) {
        runCatching { RequestKeepAliveService.start(context, "NVIDIA · ${model.substringAfterLast('/')}") }
        val url = modelEndpoint(baseUrl, model)
        val primaryPayload = payload(model, prompt, aspectRatio)
        val minimalPayload = minimalPayload(model, prompt)
        DiagnosticLog.record(context, "NVIDIA IMAGE", "start model=$model; aspect=${aspectRatio ?: "auto"}")

        try {
            var response = execute(url, apiKey, primaryPayload)
            if (response.code == 202) response = pollUntilReady(apiKey, baseUrl, response.body)

            if (!response.successful && response.code in setOf(400, 422) && gson.toJson(primaryPayload) != gson.toJson(minimalPayload)) {
                DiagnosticLog.record(context, "NVIDIA IMAGE", "validation retry model=$model with minimal payload")
                response = execute(url, apiKey, minimalPayload)
                if (response.code == 202) response = pollUntilReady(apiKey, baseUrl, response.body)
            }

            if (!response.successful) error(apiError(response.code, response.body))
            val root = gson.fromJson(response.body, JsonObject::class.java)
            val files = extractImages(root)
            if (files.isEmpty()) error("NVIDIA NIM вернул успешный ответ без изображения")
            DiagnosticLog.record(context, "NVIDIA IMAGE", "success model=$model; files=${files.size}")
            OpenRouterClient.Result("Изображение создано.", files)
        } finally {
            synchronized(activeCallLock) { activeCall = null }
            RequestKeepAliveService.stop(context)
        }
    }

    private fun execute(url: String, apiKey: String, payload: JsonObject): RawResponse {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()
        val call = http.newCall(request)
        synchronized(activeCallLock) { activeCall = call }
        return call.execute().use { RawResponse(it.code, it.body?.string().orEmpty()) }
    }

    private suspend fun pollUntilReady(apiKey: String, baseUrl: String, firstBody: String): RawResponse {
        val requestId = extractRequestId(firstBody) ?: error("NVIDIA Image API вернул HTTP 202 без requestId")
        val statusUrl = statusEndpoint(baseUrl, requestId)
        DiagnosticLog.record(context, "NVIDIA IMAGE", "pending requestId=${requestId.take(8)}…")
        repeat(500) { attempt ->
            delay(1200L)
            val request = Request.Builder()
                .url(statusUrl)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()
            val call = http.newCall(request)
            synchronized(activeCallLock) { activeCall = call }
            val response = call.execute().use { RawResponse(it.code, it.body?.string().orEmpty()) }
            when (response.code) {
                200 -> return response
                202 -> if ((attempt + 1) % 10 == 0) {
                    DiagnosticLog.record(context, "NVIDIA IMAGE", "still pending attempt=${attempt + 1}")
                }
                else -> return response
            }
        }
        error("NVIDIA слишком долго не завершает генерацию изображения")
    }

    private fun payload(model: String, prompt: String, aspectRatio: String?): JsonObject {
        val (width, height) = dimensions(aspectRatio)
        return when {
            model.contains("flux.1-schnell") -> JsonObject().apply {
                addProperty("prompt", prompt)
                addProperty("height", height)
                addProperty("width", width)
                addProperty("cfg_scale", 0)
                addProperty("mode", "base")
                addProperty("samples", 1)
                addProperty("seed", 0)
                addProperty("steps", 4)
            }
            model.contains("flux.1-dev") -> JsonObject().apply {
                addProperty("prompt", prompt)
                addProperty("height", height)
                addProperty("width", width)
                addProperty("cfg_scale", 5)
                addProperty("mode", "base")
                addProperty("samples", 1)
                addProperty("seed", 0)
                addProperty("steps", 50)
            }
            model.contains("flux.2-klein-4b") -> JsonObject().apply {
                addProperty("mode", "Image Generation")
                addProperty("prompt", prompt)
                addProperty("height", height)
                addProperty("width", width)
                addProperty("cfg_scale", 0)
                addProperty("samples", 1)
                addProperty("seed", 0)
                addProperty("steps", 4)
            }
            else -> JsonObject().apply { addProperty("prompt", prompt) }
        }
    }

    private fun minimalPayload(model: String, prompt: String): JsonObject = when {
        model.contains("flux.1-schnell") -> JsonObject().apply {
            addProperty("prompt", prompt)
            addProperty("mode", "base")
            addProperty("seed", 0)
            addProperty("steps", 4)
        }
        model.contains("flux.1-dev") -> JsonObject().apply {
            addProperty("prompt", prompt)
            addProperty("mode", "base")
        }
        model.contains("flux.2-klein-4b") -> JsonObject().apply {
            addProperty("mode", "Image Generation")
            addProperty("prompt", prompt)
        }
        else -> JsonObject().apply { addProperty("prompt", prompt) }
    }

    private fun dimensions(aspectRatio: String?): Pair<Int, Int> = when (aspectRatio) {
        "16:9" -> 1344 to 768
        "9:16" -> 768 to 1344
        "5:4" -> 1152 to 896
        "4:5" -> 896 to 1152
        "3:2" -> 1216 to 832
        "2:3" -> 832 to 1216
        else -> 1024 to 1024
    }

    private fun extractImages(root: JsonObject): List<GeneratedFile> {
        val out = mutableListOf<GeneratedFile>()

        fun consume(element: JsonElement, index: Int) {
            when {
                element.isJsonPrimitive -> {
                    val raw = element.asString
                    if (raw.startsWith("http://") || raw.startsWith("https://")) {
                        out += downloadImage(raw, index)
                    } else if (raw.isNotBlank()) {
                        out += saveEncodedImage(raw, null, index)
                    }
                }
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    val encoded = listOf("base64", "b64_json", "image", "data")
                        .asSequence()
                        .mapNotNull { name -> obj.get(name)?.takeIf { it.isJsonPrimitive }?.asString }
                        .firstOrNull { it.isNotBlank() && !it.startsWith("http") }
                    val url = obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                    val mime = listOf("media_type", "mime_type", "mimeType", "content_type")
                        .asSequence()
                        .mapNotNull { name -> obj.get(name)?.takeIf { it.isJsonPrimitive }?.asString }
                        .firstOrNull()
                    when {
                        !encoded.isNullOrBlank() -> out += saveEncodedImage(encoded, mime, index)
                        !url.isNullOrBlank() -> out += downloadImage(url, index)
                    }
                }
            }
        }

        listOf("artifacts", "images", "data").forEach { name ->
            root.get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.forEachIndexed { index, item -> consume(item, index) }
        }
        if (out.isEmpty()) {
            listOf("base64", "b64_json", "image").forEachIndexed { index, name ->
                root.get(name)?.let { consume(it, index) }
            }
        }
        return out.distinctBy { it.localPath }
    }

    private fun saveEncodedImage(raw: String, mimeHint: String?, index: Int): GeneratedFile {
        val cleaned = raw.substringAfter("base64,", raw).trim()
        return saveImageBytes(Base64.decode(cleaned, Base64.DEFAULT), mimeHint, index)
    }

    private fun downloadImage(url: String, index: Int): GeneratedFile {
        val connection = URL(url).openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 300_000
        }
        val mime = connection.contentType
        val bytes = connection.getInputStream().use { it.readBytes() }
        return saveImageBytes(bytes, mime, index)
    }

    private fun saveImageBytes(bytes: ByteArray, mimeHint: String?, index: Int): GeneratedFile {
        if (bytes.isEmpty()) error("NVIDIA NIM вернул пустое изображение")
        val mime = detectMime(bytes, mimeHint)
        val ext = when (mime) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/svg+xml" -> "svg"
            else -> "png"
        }
        val dir = File(context.filesDir, "generated").apply { mkdirs() }
        val file = File(dir, "nvidia_${System.currentTimeMillis()}_${index}.$ext")
        file.writeBytes(bytes)
        return GeneratedFile(UUID.randomUUID().toString(), file.name, mime, file.absolutePath, file.length())
    }

    private fun detectMime(bytes: ByteArray, hint: String?): String {
        val normalized = hint?.substringBefore(';')?.lowercase()?.takeIf { it.startsWith("image/") }
        if (normalized != null) return normalized
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return "image/jpeg"
        if (bytes.size >= 12 && String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) == "RIFF" &&
            String(bytes.copyOfRange(8, 12), Charsets.US_ASCII) == "WEBP") return "image/webp"
        val prefix = runCatching { String(bytes.take(200).toByteArray(), Charsets.UTF_8).trimStart() }.getOrDefault("")
        if (prefix.startsWith("<svg") || (prefix.startsWith("<?xml") && "<svg" in prefix)) return "image/svg+xml"
        return "image/png"
    }

    private fun modelEndpoint(baseUrl: String, model: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return if (clean.endsWith(model)) clean else "$clean/${model.trimStart('/')}"
    }

    private fun statusEndpoint(baseUrl: String, requestId: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return when {
            "/v1/genai" in clean -> clean.substringBefore("/v1/genai") + "/v1/status/$requestId"
            clean.endsWith("/v1") -> "$clean/status/$requestId"
            else -> "$clean/status/$requestId"
        }
    }

    private fun extractRequestId(body: String): String? = runCatching {
        val root = gson.fromJson(body, JsonObject::class.java)
        listOf("requestId", "request_id", "id")
            .asSequence()
            .mapNotNull { root.get(it)?.takeIf { value -> value.isJsonPrimitive }?.asString }
            .firstOrNull { it.isNotBlank() }
    }.getOrNull()

    private fun apiError(code: Int, body: String): String {
        val detail = runCatching {
            val root = gson.fromJson(body, JsonObject::class.java)
            root.getAsJsonObject("error")?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                ?: root.get("detail")?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
                ?: root.get("message")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return "NVIDIA NIM: HTTP $code${detail?.let { " · $it" }.orEmpty()}"
    }
}
