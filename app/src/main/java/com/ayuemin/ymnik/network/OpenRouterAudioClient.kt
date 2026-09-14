package com.ayuemin.ymnik.network

import android.content.Context
import android.util.Base64
import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor
import com.ayuemin.ymnik.diagnostics.DiagnosticNetworkEventListener
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
        val sampleRateHz: Int? = null,
        val channels: Int? = null,
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
        responseFormat: String? = null,
        speed: Double? = null,
        baseUrl: String = DEFAULT_BASE_URL
    ): SpeechResult {
        require(input.isNotBlank()) { "Нет текста для озвучивания" }
        val requestedFormat = normalizeSpeechResponseFormat(responseFormat)
        val automaticFormat = requestedFormat == null
        val firstFormat = resolveSpeechResponseFormat(model, requestedFormat)

        return suspendCancellableCoroutine { continuation ->
            var activeCall: Call? = null
            continuation.invokeOnCancellation { activeCall?.cancel() }

            fun enqueue(format: String?, retryAllowed: Boolean) {
                val payload = JsonObject().apply {
                    addProperty("model", model)
                    addProperty("input", input)
                    voice?.trim()?.takeIf { it.isNotBlank() }?.let { addProperty("voice", it) }
                    format?.let { addProperty("response_format", it) }
                    speed?.takeIf { it > 0.0 }?.let { addProperty("speed", it) }
                }
                val request = Request.Builder()
                    .url(endpoint(baseUrl, "audio/speech"))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .header("X-Title", "Umnik Android")
                    .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                    .build()

                val call = http.newCall(request)
                activeCall = call
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { current ->
                            if (!current.isSuccessful) {
                                val body = current.body?.string().orEmpty()
                                val suggested = if (automaticFormat && retryAllowed) suggestedSpeechFormat(body) else null
                                if (suggested != null && suggested != format && continuation.isActive) {
                                    enqueue(suggested, retryAllowed = false)
                                    return
                                }
                                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(apiError(current.code, body)))
                                return
                            }
                            runCatching {
                                val bytes = current.body?.bytes() ?: ByteArray(0)
                                if (bytes.isEmpty()) error("OpenRouter вернул пустой аудиофайл")
                                val contentType = current.header("Content-Type").orEmpty()
                                val mime = contentType.substringBefore(';').trim().lowercase()
                                val actualFormat = when (mime) {
                                    "audio/mpeg", "audio/mp3" -> "mp3"
                                    "audio/pcm", "audio/l16" -> "pcm"
                                    "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
                                    else -> format ?: firstFormat ?: "pcm"
                                }
                                SpeechResult(
                                    bytes = bytes,
                                    mimeType = mime.ifBlank {
                                        when (actualFormat) {
                                            "mp3" -> "audio/mpeg"
                                            "wav" -> "audio/wav"
                                            else -> "audio/pcm"
                                        }
                                    },
                                    format = actualFormat,
                                    sampleRateHz = contentTypeParameter(contentType, "rate")?.toIntOrNull(),
                                    channels = contentTypeParameter(contentType, "channels")?.toIntOrNull(),
                                    generationId = current.header("X-Generation-Id")
                                )
                            }.onSuccess { result ->
                                if (continuation.isActive) continuation.resume(result)
                            }.onFailure { error ->
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        }
                    }
                })
            }

            enqueue(firstFormat, retryAllowed = true)
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

        fun normalizeSpeechResponseFormat(value: String?): String? = when (value?.trim()?.lowercase()) {
            "mp3" -> "mp3"
            "pcm" -> "pcm"
            else -> null
        }

        /** Auto is intentionally conservative: known single-format families get a safe value,
         * while unknown/current providers receive no response_format and may use their default. */
        fun resolveSpeechResponseFormat(model: String, requested: String?): String? {
            normalizeSpeechResponseFormat(requested)?.let { return it }
            val id = model.trim().lowercase()
            return when {
                "gemini" in id && ("tts" in id || "speech" in id) -> "pcm"
                "voxtral" in id && "tts" in id -> "mp3"
                else -> null
            }
        }

        fun pcmToWav(
            pcm: ByteArray,
            sampleRateHz: Int = 24_000,
            channels: Int = 1,
            bitsPerSample: Int = 16
        ): ByteArray {
            val safeRate = sampleRateHz.takeIf { it in 8_000..192_000 } ?: 24_000
            val safeChannels = channels.takeIf { it in 1..8 } ?: 1
            val safeBits = bitsPerSample.takeIf { it in setOf(8, 16, 24, 32) } ?: 16
            val blockAlign = safeChannels * safeBits / 8
            val byteRate = safeRate * blockAlign
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + pcm.size)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1.toShort())
            header.putShort(safeChannels.toShort())
            header.putInt(safeRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(safeBits.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(pcm.size)
            return header.array() + pcm
        }

        private fun suggestedSpeechFormat(body: String): String? {
            val lower = body.lowercase()
            if ("response_format" !in lower && "format" !in lower) return null
            return when {
                Regex("""only\s+(supports?|accepts?)\s+[^.]{0,40}pcm""").containsMatchIn(lower) ||
                    Regex("pcm[^.]{0,20}only").containsMatchIn(lower) -> "pcm"
                Regex("""only\s+(supports?|accepts?)\s+[^.]{0,40}mp3""").containsMatchIn(lower) ||
                    Regex("mp3[^.]{0,20}only").containsMatchIn(lower) -> "mp3"
                else -> null
            }
        }

        private fun contentTypeParameter(contentType: String, name: String): String? = contentType
            .split(';')
            .drop(1)
            .map { it.trim() }
            .firstOrNull { it.substringBefore('=').trim().equals(name, ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
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
