package com.ayuemin.ymnik.network

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.diagnostics.DiagnosticNetworkEventListener
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.GeneratedFile
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.PendingAttachment
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
import java.util.UUID
import java.util.concurrent.TimeUnit

class CompatibleApiClient(private val context: Context) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .addInterceptor(DiagnosticHttpInterceptor(context, "Compatible text/image"))
        .eventListenerFactory { DiagnosticNetworkEventListener(context, "Compatible text/image") }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(240, TimeUnit.SECONDS)
        .build()
    @Volatile private var activeCall: Call? = null

    fun cancelActiveRequest() {
        activeCall?.cancel()
        http.dispatcher.cancelAll()
    }

    suspend fun models(apiKey: String, baseUrl: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(endpoint(baseUrl, "models")).get()
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiError(response.code, body))
            val root = gson.fromJson(body, JsonElement::class.java)
            val data = when {
                root.isJsonObject -> root.asJsonObject.getAsJsonArray("data")
                root.isJsonArray -> root.asJsonArray
                else -> null
            } ?: return@withContext emptyList()
            data.mapNotNull { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = item.get("id")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                // The generic transport currently implements text chat only.
                // Do not enable vision/reasoning/tools merely because a server reports them:
                // those wire formats differ between providers and get dedicated adapters later.
                ModelInfo(id)
            }.distinctBy { it.id }.sortedBy { it.id }
        }
    }

    suspend fun chat(
        apiKey: String,
        baseUrl: String,
        model: String,
        history: List<ChatMessage>,
        prompt: String,
        attachments: List<PendingAttachment>,
        systemPrompt: String
    ): OpenRouterClient.Result = withContext(Dispatchers.IO) {
        val messages = JsonArray()
        if (systemPrompt.isNotBlank()) messages.add(message("system", systemPrompt))
        // NVIDIA requires alternating user/assistant roles. After a timeout Umnik
        // keeps the unanswered user message in local history; do not send that stale
        // trailing user turn again when the user retries.
        history.takeLast(30)
            .dropLastWhile { it.role == "user" }
            .forEach { item -> messages.add(message(item.role, item.text)) }
        messages.add(message("user", userText(prompt, attachments)))

        val isNvidia = baseUrl.contains("nvidia.com", ignoreCase = true)
        val providerLabel = if (isNvidia) "NVIDIA" else "Compatible API"
        val payload = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            // Keep NVIDIA requests deliberately minimal while diagnosing hosted NIM
            // hangs: model + messages + explicit non-streaming mode only. max_tokens
            // is optional in NVIDIA's schema and the service can use its model default.
            if (!isNvidia) addProperty("max_tokens", 4096)
            addProperty("stream", false)
        }
        DiagnosticLog.record(
            context,
            "TEXT REQUEST",
            "$providerLabel start; model=$model; history=${history.size}; sentMessages=${messages.size()}; promptChars=${prompt.length}; attachments=${attachments.size}; minimalPayload=$isNvidia"
        )
        val builder = Request.Builder()
            .url(endpoint(baseUrl, "chat/completions"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        val call = http.newCall(builder.build())
        activeCall = call
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(apiError(response.code, body))
                val root = gson.fromJson(body, JsonObject::class.java)
                val messageObject = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("message")
                val content = messageObject?.get("content")
                val reasoningContent = messageObject?.get("reasoning_content")
                val text = extractText(content).ifBlank { extractText(reasoningContent) }
                if (text.isBlank()) error("Совместимый API вернул пустой ответ")
                DiagnosticLog.record(
                    context,
                    "TEXT REQUEST",
                    "$providerLabel success; model=$model; responseChars=${text.length}; responseBytes=${body.length}"
                )
                OpenRouterClient.Result(text, emptyList())
            }
        } catch (t: Throwable) {
            DiagnosticLog.record(context, "TEXT REQUEST", "$providerLabel failed; model=$model", t)
            throw t
        } finally {
            activeCall = null
        }
    }

    suspend fun generateImage(
        apiKey: String,
        baseUrl: String,
        model: String,
        prompt: String
    ): OpenRouterClient.Result = withContext(Dispatchers.IO) {
        val payload = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", prompt.ifBlank { "Создай изображение." })
        }
        val builder = Request.Builder()
            .url(imageGenerationEndpoint(baseUrl))
            .header("Content-Type", "application/json")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        val call = http.newCall(builder.build())
        activeCall = call
        try {
            call.execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(apiError(response.code, body))
                val root = gson.fromJson(body, JsonObject::class.java)
                val data = root.getAsJsonArray("data") ?: root.getAsJsonArray("images")
                    ?: error("Совместимый API не вернул изображение")
                val files = data.mapIndexedNotNull { index, element ->
                    val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null
                    val mime = item.get("media_type")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.takeIf { it.startsWith("image/") }
                    val encoded = item.get("b64_json")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.takeIf { it.isNotBlank() }
                    if (encoded != null) {
                        saveGeneratedImageBytes(Base64.decode(encoded.substringAfter("base64,", encoded), Base64.DEFAULT), mime ?: "image/png", index)
                    } else {
                        val url = item.get("url")?.takeIf { it.isJsonPrimitive }?.asString
                            ?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                        downloadGeneratedImage(url, mime, index)
                    }
                }
                if (files.isEmpty()) error("Совместимый API вернул ответ без данных изображения")
                OpenRouterClient.Result("Изображение создано.", files)
            }
        } finally {
            activeCall = null
        }
    }

    private fun saveGeneratedImageBytes(bytes: ByteArray, mimeType: String, index: Int): GeneratedFile {
        val extension = when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }
        val dir = File(context.filesDir, "generated").apply { mkdirs() }
        val name = "umnik_image_${System.currentTimeMillis()}_${index + 1}.$extension"
        val file = File(dir, "${UUID.randomUUID()}_$name")
        file.writeBytes(bytes)
        return GeneratedFile(UUID.randomUUID().toString(), name, mimeType, file.absolutePath, file.length())
    }

    private fun downloadGeneratedImage(url: String, hintedMime: String?, index: Int): GeneratedFile? {
        if (url.startsWith("data:image/")) {
            val mime = url.substringAfter("data:").substringBefore(';').takeIf { it.startsWith("image/") } ?: hintedMime ?: "image/png"
            val bytes = Base64.decode(url.substringAfter("base64,", ""), Base64.DEFAULT)
            return saveGeneratedImageBytes(bytes, mime, index)
        }
        return runCatching {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body?.bytes() ?: return@use null
                val mime = response.header("Content-Type")?.substringBefore(';')
                    ?.takeIf { it.startsWith("image/") } ?: hintedMime ?: "image/png"
                saveGeneratedImageBytes(bytes, mime, index)
            }
        }.getOrNull()
    }

    private fun userText(prompt: String, attachments: List<PendingAttachment>): String = buildString {
        append(prompt.ifBlank { "Изучи вложения и помоги мне с ними." })
        attachments.forEach { attachment ->
            val mime = attachment.mimeType.lowercase()
            val name = attachment.name.lowercase()
            val textLike = mime.startsWith("text/") || name.endsWith(".md") || name.endsWith(".json") ||
                name.endsWith(".csv") || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".xml")
            if (textLike) {
                val value = runCatching { String(readAttachment(attachment), Charsets.UTF_8) }.getOrDefault("")
                append("\n\n--- Вложение: ${attachment.name} ---\n")
                append(value)
                append("\n--- Конец вложения ---")
            }
        }
    }

    private fun readAttachment(attachment: PendingAttachment): ByteArray {
        attachment.localPath?.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (file.isFile) return file.readBytes()
        }
        val uri = Uri.parse(attachment.uri)
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Не удалось прочитать ${attachment.name}")
    }

    private fun message(role: String, text: String) = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", text)
    }

    private fun extractText(content: JsonElement?): String = when {
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

    private fun imageGenerationEndpoint(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return when {
            clean.endsWith("/images/generations") -> clean
            clean.endsWith("/images") -> clean
            else -> endpoint(clean, "images/generations")
        }
    }

    private fun endpoint(baseUrl: String, path: String): String = baseUrl.trim().trimEnd('/') + "/" + path

    private fun apiError(code: Int, body: String): String {
        val message = runCatching {
            gson.fromJson(body, JsonObject::class.java).getAsJsonObject("error")?.get("message")?.asString
        }.getOrNull().orEmpty()
        return if (message.isBlank()) "Ошибка API $code" else "Ошибка API $code: $message"
    }
}
