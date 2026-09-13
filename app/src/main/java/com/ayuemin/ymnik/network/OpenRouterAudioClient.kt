package com.ayuemin.ymnik.network

import android.content.Context
import android.util.Base64
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

class OpenRouterAudioClient(private val context: Context) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .addInterceptor(DiagnosticHttpInterceptor(context, "OpenRouter Audio"))
        .eventListenerFactory { DiagnosticNetworkEventListener(context, "OpenRouter Audio") }
        .retryOnConnectionFailure(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    data class TranscriptionResult(
        val text: String,
        val language: String? = null,
        val durationSeconds: Double? = null,
        val costUsd: Double? = null,
        val generationId: String? = null
    )

    data class SpeechResult(
        val bytes: ByteArray,
        val mimeType: String,
        val format: String,
        val generationId: String? = null
    )

    suspend fun transcribe(
        apiKey: String,
        model: String,
        audio: ByteArray,
        format: String,
        language: String? = null,
        verbose: Boolean = false,
        baseUrl: String = DEFAULT_BASE_URL
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        require(audio.isNotEmpty()) { "Аудиофайл пуст" }
        val cleanFormat = normalizeAudioFormat(format)
        val payload = JsonObject().apply {
            addProperty("model", model)
            add("input_audio", JsonObject().apply {
                addProperty("data", Base64.encodeToString(audio, Base64.NO_WRAP))
                addProperty("format", cleanFormat)
            })
            language?.trim()?.takeIf { it.isNotBlank() }?.let { addProperty("language", it) }
            addProperty("response_format", if (verbose) "verbose_json" else "json")
            if (verbose) {
                add("timestamp_granularities", JsonArray().apply {
                    add("segment")
                    add("word")
                })
            }
        }
        val request = Request.Builder()
            .url(endpoint(baseUrl, "audio/transcriptions"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Umnik Android")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiError(response.code, body))
            val root = gson.fromJson(body, JsonObject::class.java)
            val text = root.string("text") ?: error("OpenRouter не вернул текст расшифровки")
            val usage = root.getAsJsonObject("usage")
            TranscriptionResult(
                text = text,
                language = root.string("language"),
                durationSeconds = root.double("duration") ?: usage?.double("seconds"),
                costUsd = usage?.double("cost"),
                generationId = response.header("X-Generation-Id")
            )
        }
    }

    suspend fun synthesize(
        apiKey: String,
        model: String,
        input: String,
        voice: String? = null,
        responseFormat: String = "mp3",
        speed: Double? = null,
        baseUrl: String = DEFAULT_BASE_URL
    ): SpeechResult = withContext(Dispatchers.IO) {
        require(input.isNotBlank()) { "Нет текста для озвучивания" }
        val format = responseFormat.lowercase().let { if (it == "pcm") "pcm" else "mp3" }
        val payload = JsonObject().apply {
            addProperty("model", model)
            addProperty("input", input)
            voice?.trim()?.takeIf { it.isNotBlank() }?.let { addProperty("voice", it) }
            addProperty("response_format", format)
            speed?.takeIf { it > 0.0 }?.let { addProperty("speed", it) }
        }
        val request = Request.Builder()
            .url(endpoint(baseUrl, "audio/speech"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Umnik Android")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                error(apiError(response.code, body))
            }
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.isEmpty()) error("OpenRouter вернул пустой аудиофайл")
            SpeechResult(
                bytes = bytes,
                mimeType = response.header("Content-Type")?.substringBefore(';')?.trim()
                    ?: if (format == "mp3") "audio/mpeg" else "audio/pcm",
                format = format,
                generationId = response.header("X-Generation-Id")
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"

        fun normalizeAudioFormat(value: String): String {
            val lower = value.trim().lowercase().substringAfterLast('.')
            return when (lower) {
                "mpeg", "mpga" -> "mp3"
                "mp4" -> "m4a"
                "x-wav", "wave" -> "wav"
                else -> lower.takeIf { it in setOf("wav", "mp3", "flac", "m4a", "ogg", "webm", "aac") } ?: "mp3"
            }
        }
    }

    private fun endpoint(baseUrl: String, path: String): String =
        "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    private fun apiError(code: Int, body: String): String {
        val detail = runCatching {
            val root = gson.fromJson(body, JsonObject::class.java)
            root.getAsJsonObject("error")?.string("message") ?: root.string("message")
        }.getOrNull()
        return "OpenRouter Audio HTTP $code: ${detail ?: body.take(400)}"
    }

    private fun JsonObject.string(name: String): String? = runCatching {
        get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonObject.double(name: String): Double? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asDouble
    }.getOrNull()
}
