package com.ayuemin.ymnik.network

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.ayuemin.ymnik.RequestKeepAliveService
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
import kotlinx.coroutines.delay
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
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS)
        .build()
    @Volatile private var activeCall: Call? = null

    private data class RawResponse(val code: Int, val body: String) {
        val successful: Boolean get() = code in 200..299
    }

    fun cancelActiveRequest() {
        activeCall?.cancel()
        http.dispatcher.cancelAll()
        RequestKeepAliveService.stop(context)
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

            val discovered = data.mapNotNull { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = item.get("id")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (isNvidiaHosted(baseUrl) && !isLikelyChatModel(id)) return@mapNotNull null
                modelInfo(id)
            }.distinctBy { it.id }.sortedBy { it.id }

            if (isNvidiaHosted(baseUrl)) {
                DiagnosticLog.record(
                    context,
                    "MODEL CATALOG",
                    "NVIDIA /models raw=${data.size()}; chatCandidates=${discovered.size}; whitelist=false; quarantine=false"
                )
            }
            discovered
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
        val isNvidia = isNvidiaHosted(baseUrl)
        if (isNvidia) runCatching { RequestKeepAliveService.start(context, "NVIDIA · ${model.substringAfterLast('/')}") }

        try {
            val messages = JsonArray()
            if (systemPrompt.isNotBlank()) messages.add(message("system", systemPrompt))

            history.takeLast(30)
                .dropLastWhile { it.role == "user" }
                .filter { it.role == "user" || it.role == "assistant" }
                .forEach { item -> messages.add(message(item.role, item.text)) }
            messages.add(userMessage(prompt, attachments, isNvidia))

            val payload = JsonObject().apply {
                addProperty("model", model)
                add("messages", messages)
                if (!isNvidia) addProperty("max_tokens", 4096)
                addProperty("stream", false)
                // DeepSeek V4 on NVIDIA defaults to high reasoning. For normal Umnik
                // chat explicitly disable hidden thinking; otherwise even «привет» can
                // spend minutes reasoning before the first visible answer.
                if (isNvidia && model.lowercase().contains("deepseek-v4")) {
                    addProperty("reasoning_effort", "none")
                }
            }

            val providerLabel = if (isNvidia) "NVIDIA" else "Compatible API"
            DiagnosticLog.record(
                context,
                "TEXT REQUEST",
                "$providerLabel start; model=$model; history=${history.size}; sentMessages=${messages.size()}; promptChars=${prompt.length}; attachments=${attachments.size}; async202=$isNvidia"
            )

            var response = executeChat(apiKey, endpoint(baseUrl, "chat/completions"), payload)
            if (isNvidia && response.code == 202) {
                response = pollNvidia(apiKey, baseUrl, response.body)
            }

            if (!response.successful) {
                val detail = apiError(response.code, response.body)
                if (isNvidia && response.code == 404) {
                    error(
                        "NVIDIA не дала этому аккаунту доступ к модели «${model.substringAfterLast('/')}» через Chat API. " +
                            "Модель останется в списке. $detail"
                    )
                }
                if (isNvidia && response.code == 429) {
                    error("NVIDIA временно перегружена или исчерпан лимит запросов. Повторите позже. $detail")
                }
                error(detail)
            }

            val text = parseCompletionText(response.body)
            if (text.isBlank()) error("Совместимый API вернул пустой ответ")
            DiagnosticLog.record(
                context,
                "TEXT REQUEST",
                "$providerLabel success; model=$model; responseChars=${text.length}; responseBytes=${response.body.length}"
            )
            OpenRouterClient.Result(text, emptyList())
        } catch (t: Throwable) {
            DiagnosticLog.record(context, "TEXT REQUEST", "${if (isNvidia) "NVIDIA" else "Compatible API"} failed; model=$model", t)
            throw t
        } finally {
            activeCall = null
            if (isNvidia) RequestKeepAliveService.stop(context)
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

    private fun executeChat(apiKey: String, url: String, payload: JsonObject): RawResponse {
        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        val call = http.newCall(builder.build())
        activeCall = call
        return call.execute().use { RawResponse(it.code, it.body?.string().orEmpty()) }
    }

    private suspend fun pollNvidia(apiKey: String, baseUrl: String, firstBody: String): RawResponse {
        val requestId = extractRequestId(firstBody) ?: error("NVIDIA вернула HTTP 202 без requestId")
        val url = endpoint(baseUrl, "status/$requestId")
        DiagnosticLog.record(context, "TEXT REQUEST", "NVIDIA pending requestId=${requestId.take(8)}…")
        repeat(500) { attempt ->
            delay(1200L)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()
            val call = http.newCall(request)
            activeCall = call
            val response = call.execute().use { RawResponse(it.code, it.body?.string().orEmpty()) }
            when (response.code) {
                200 -> return response
                202 -> if ((attempt + 1) % 10 == 0) {
                    DiagnosticLog.record(context, "TEXT REQUEST", "NVIDIA still pending attempt=${attempt + 1}")
                }
                else -> return response
            }
        }
        error("NVIDIA слишком долго не завершает асинхронный запрос")
    }

    private fun extractRequestId(body: String): String? = runCatching {
        val root = gson.fromJson(body, JsonObject::class.java)
        listOf("requestId", "request_id", "id")
            .asSequence()
            .mapNotNull { root.get(it)?.takeIf { value -> value.isJsonPrimitive }?.asString }
            .firstOrNull { it.isNotBlank() }
    }.getOrNull()

    private fun parseCompletionText(body: String): String {
        val root = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull() ?: return ""
        val messageObject = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            ?.getAsJsonObject("message")
        return extractText(messageObject?.get("content")).ifBlank {
            extractText(messageObject?.get("reasoning_content"))
        }
    }

    private fun modelInfo(id: String): ModelInfo {
        val value = id.lowercase()
        val multimodal = listOf("vision", "vlm", "multimodal", "vila", "fuyu", "kosmos", "paligemma", "neva")
            .any { it in value }
        val deepSeek = value.contains("deepseek-v4")
        return ModelInfo(
            id = id,
            inputModalities = if (multimodal) setOf("text", "image") else setOf("text"),
            supportedParameters = if (deepSeek) setOf("reasoning", "reasoning_effort") else emptySet(),
            reasoningEfforts = if (deepSeek) setOf("high", "xhigh") else emptySet()
        )
    }

    private fun isLikelyChatModel(id: String): Boolean {
        val value = id.lowercase()
        val nonChatMarkers = listOf(
            "embed", "embedding", "rerank", "re-rank", "retriever", "retrieval", "reward",
            "nvclip", "/clip", "flux.", "stable-diffusion", "image-detection", "deepfake",
            "synthetic-video-detector", "object-detection", "ocr", "riva-translate", "whisper",
            "parakeet", "text-embedding"
        )
        return nonChatMarkers.none { it in value }
    }

    private fun userMessage(prompt: String, attachments: List<PendingAttachment>, nvidia: Boolean): JsonObject {
        val text = buildPromptWithTextAttachments(prompt, attachments)
        val images = attachments.filter { it.mimeType.lowercase().startsWith("image/") }
        if (!nvidia || images.isEmpty()) return message("user", text)

        val content = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", text)
            })
            images.forEach { attachment ->
                val bytes = readAttachment(attachment)
                val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                val mime = attachment.mimeType.ifBlank { "image/jpeg" }
                add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply {
                        addProperty("url", "data:$mime;base64,$encoded")
                    })
                })
            }
        }
        return JsonObject().apply {
            addProperty("role", "user")
            add("content", content)
        }
    }

    private fun buildPromptWithTextAttachments(prompt: String, attachments: List<PendingAttachment>): String = buildString {
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

    private fun saveGeneratedImageBytes(bytes: ByteArray, mimeType: String, index: Int): GeneratedFile {
        val extension = when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/svg+xml" -> "svg"
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

    private fun isNvidiaHosted(baseUrl: String): Boolean =
        baseUrl.contains("integrate.api.nvidia.com", ignoreCase = true)

    private fun apiError(code: Int, body: String): String {
        val message = runCatching {
            val root = gson.fromJson(body, JsonObject::class.java)
            root.getAsJsonObject("error")?.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                ?: root.get("detail")?.let { detail ->
                    when {
                        detail.isJsonPrimitive -> detail.asString
                        detail.isJsonNull -> null
                        else -> detail.toString()
                    }
                }
                ?: root.get("message")?.takeIf { it.isJsonPrimitive }?.asString
                ?: root.get("title")?.takeIf { it.isJsonPrimitive }?.asString
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
            ?: body.trim().replace(Regex("\\s+"), " ").take(300).takeIf { it.isNotBlank() }
        return if (message.isNullOrBlank()) "Ошибка API $code" else "Ошибка API $code: $message"
    }
}
