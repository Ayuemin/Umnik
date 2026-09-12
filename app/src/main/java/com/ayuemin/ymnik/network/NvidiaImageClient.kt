package com.ayuemin.ymnik.network

import android.content.Context
import android.util.Base64
import com.ayuemin.ymnik.model.GeneratedFile
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
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
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(240, TimeUnit.SECONDS)
        .build()
    private val activeCallLock = Any()
    @Volatile private var activeCall: Call? = null

    private data class RawResponse(
        val successful: Boolean,
        val code: Int,
        val body: String
    )

    fun cancelActiveRequest() {
        synchronized(activeCallLock) { activeCall?.cancel() }
        http.dispatcher.cancelAll()
    }

    suspend fun generateImage(
        apiKey: String,
        baseUrl: String,
        model: String,
        prompt: String,
        aspectRatio: String? = null
    ): OpenRouterClient.Result = withContext(Dispatchers.IO) {
        val url = modelEndpoint(baseUrl, model)
        val primaryPayload = payload(model, prompt, aspectRatio)
        val minimalPayload = minimalPayload(model, prompt)

        try {
            var response = execute(url, apiKey, primaryPayload)

            // NIM schemas occasionally differ between the published reference and the
            // deployed validation layer. If an optional field is rejected as an extra
            // input, retry once with only the model's required fields. This keeps old
            // app versions resilient to provider-side schema tightening without hiding
            // unrelated validation errors.
            if (
                !response.successful &&
                response.code == 422 &&
                isExtraInputValidation(response.body) &&
                gson.toJson(primaryPayload) != gson.toJson(minimalPayload)
            ) {
                response = execute(url, apiKey, minimalPayload)
            }

            if (!response.successful) error(apiError(response.code, response.body))
            val root = gson.fromJson(response.body, JsonObject::class.java)
            val files = extractImages(root)
            if (files.isEmpty()) error("NVIDIA NIM вернул ответ без изображения")
            OpenRouterClient.Result("Изображение создано.", files)
        } finally {
            synchronized(activeCallLock) { activeCall = null }
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
        return call.execute().use { response ->
            RawResponse(
                successful = response.isSuccessful,
                code = response.code,
                body = response.body?.string().orEmpty()
            )
        }
    }

    private fun payload(model: String, prompt: String, aspectRatio: String?): JsonObject = when {
        model.endsWith("stable-diffusion-3-medium") -> JsonObject().apply {
            addProperty("prompt", prompt)
            addProperty("output_format", "jpeg")
            aspectRatio?.takeIf { it in COMMON_RATIOS }?.let { addProperty("aspect_ratio", it) }
        }
        model.endsWith("stable-diffusion-xl") -> stableDiffusionXlPayload(prompt)
        model.contains("flux.1-schnell") || model.contains("flux.1-dev") -> JsonObject().apply {
            addProperty("prompt", prompt)
            flux1Dimensions(aspectRatio)?.let { (width, height) ->
                addProperty("width", width)
                addProperty("height", height)
            }
        }
        else -> JsonObject().apply {
            // For FLUX.2 Klein and future text-to-image NIMs, send only the required
            // prompt. Optional mode/seed/steps fields have defaults and are the most
            // likely fields to drift between deployed NIM schema revisions.
            addProperty("prompt", prompt)
        }
    }

    private fun minimalPayload(model: String, prompt: String): JsonObject =
        if (model.endsWith("stable-diffusion-xl")) {
            stableDiffusionXlPayload(prompt)
        } else {
            JsonObject().apply { addProperty("prompt", prompt) }
        }

    private fun stableDiffusionXlPayload(prompt: String): JsonObject = JsonObject().apply {
        add("text_prompts", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("text", prompt)
                addProperty("weight", 1)
            })
        })
    }

    private fun isExtraInputValidation(body: String): Boolean {
        val normalized = body.lowercase()
        return "extra_forbidden" in normalized ||
            "extra inputs are not permitted" in normalized ||
            "extra fields not permitted" in normalized
    }

    private fun flux1Dimensions(aspectRatio: String?): Pair<Int, Int>? = when (aspectRatio) {
        "1:1" -> 1024 to 1024
        "16:9" -> 1344 to 768
        "9:16" -> 768 to 1344
        "5:4" -> 1152 to 896
        "4:5" -> 896 to 1152
        "3:2" -> 1216 to 832
        "2:3" -> 832 to 1216
        else -> null
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
        val bytes = Base64.decode(cleaned, Base64.DEFAULT)
        return saveImageBytes(bytes, mimeHint, index)
    }

    private fun downloadImage(url: String, index: Int): GeneratedFile {
        val connection = URL(url).openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 120_000
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
            else -> "png"
        }
        val dir = File(context.filesDir, "generated").apply { mkdirs() }
        val file = File(dir, "nvidia_${System.currentTimeMillis()}_${index}.$ext")
        file.writeBytes(bytes)
        return GeneratedFile(
            id = UUID.randomUUID().toString(),
            name = file.name,
            mimeType = mime,
            localPath = file.absolutePath,
            size = file.length()
        )
    }

    private fun detectMime(bytes: ByteArray, hint: String?): String {
        val normalized = hint?.substringBefore(';')?.lowercase()?.takeIf { it.startsWith("image/") }
        if (normalized != null) return normalized
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return "image/jpeg"
        if (bytes.size >= 12 && String(bytes.copyOfRange(0, 4), Charsets.US_ASCII) == "RIFF" &&
            String(bytes.copyOfRange(8, 12), Charsets.US_ASCII) == "WEBP") return "image/webp"
        return "image/png"
    }

    private fun modelEndpoint(baseUrl: String, model: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return if (clean.endsWith(model)) clean else "$clean/${model.trimStart('/')}"
    }

    private fun apiError(code: Int, body: String): String {
        val detail = runCatching {
            val root = gson.fromJson(body, JsonObject::class.java)
            root.get("detail")?.let { detail ->
                when {
                    detail.isJsonPrimitive -> detail.asString
                    else -> detail.toString()
                }
            } ?: root.get("message")?.asString
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return "NVIDIA NIM: HTTP $code${detail?.let { " · $it" }.orEmpty()}"
    }

    private companion object {
        val COMMON_RATIOS = setOf("1:1", "16:9", "9:16", "5:4", "4:5", "3:2", "2:3")
    }
}
