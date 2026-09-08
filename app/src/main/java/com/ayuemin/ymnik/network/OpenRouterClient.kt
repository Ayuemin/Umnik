package com.ayuemin.ymnik.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
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

class OpenRouterClient(private val context: Context) {
    private val gson = Gson()
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(240, TimeUnit.SECONDS)
        .build()
    private val activeCallLock = Any()
    @Volatile private var activeCall: Call? = null

    data class Result(val text: String, val files: List<GeneratedFile>)

    fun cancelActiveRequest() {
        synchronized(activeCallLock) { activeCall?.cancel() }
        http.dispatcher.cancelAll()
    }

    private fun executeActive(request: Request): okhttp3.Response {
        val call = http.newCall(request)
        synchronized(activeCallLock) { activeCall = call }
        return call.execute()
    }

    private fun clearActiveCall() {
        synchronized(activeCallLock) { activeCall = null }
    }

    suspend fun models(apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        getModelInfos(apiKey, "https://openrouter.ai/api/v1/models")
    }

    suspend fun imageModels(apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        getModelInfos(apiKey, "https://openrouter.ai/api/v1/images/models")
    }

    private fun getModelInfos(apiKey: String, url: String): List<ModelInfo> {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Umnik Android")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiError(response.code, body))
            val root = gson.fromJson(body, JsonObject::class.java)
            return root.getAsJsonArray("data")
                ?.mapNotNull { element ->
                    val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val id = item.get("id")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val inputModalities = item.getAsJsonObject("architecture")
                        ?.getAsJsonArray("input_modalities")
                        ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString?.lowercase() }
                        ?.toSet()
                        .orEmpty()
                        .ifEmpty { setOf("text") }
                    val supportedParameters = when (val supported = item.get("supported_parameters")) {
                        null -> emptySet()
                        else -> when {
                            supported.isJsonArray -> supported.asJsonArray
                                .mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString?.lowercase() }
                                .toSet()
                            supported.isJsonObject -> supported.asJsonObject.keySet().map { it.lowercase() }.toSet()
                            else -> emptySet()
                        }
                    }
                    val reasoningEfforts = item.getAsJsonObject("reasoning")
                        ?.getAsJsonArray("supported_efforts")
                        ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString?.lowercase() }
                        ?.toSet()
                        .orEmpty()
                    ModelInfo(id, inputModalities, supportedParameters, reasoningEfforts)
                }
                ?.distinctBy { it.id }
                ?.sortedBy { it.id }
                ?: emptyList()
        }
    }

    suspend fun chat(
        apiKey: String,
        model: String,
        history: List<ChatMessage>,
        prompt: String,
        attachments: List<PendingAttachment>,
        systemPrompt: String,
        webSearchEnabled: Boolean = false,
        reasoningEnabled: Boolean = false,
        reasoningEffort: String? = "medium",
        toolsEnabled: Boolean = true
    ): Result = withContext(Dispatchers.IO) {
        val messages = JsonArray()
        messages.add(message("system", systemPrompt))
        history.takeLast(30).forEach { item ->
            messages.add(message(item.role, item.text))
        }
        messages.add(userMessage(prompt, attachments))

        val created = mutableListOf<GeneratedFile>()
        var loops = 0
        while (loops++ < 5) {
            val payload = JsonObject().apply {
                addProperty("model", model)
                add("messages", messages)
                addProperty("max_tokens", 6000)
                if (toolsEnabled) add("tools", tools())

                if (webSearchEnabled) {
                    add("plugins", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("id", "web")
                            addProperty("max_results", 5)
                        })
                    })
                }

                if (reasoningEnabled) {
                    add("reasoning", JsonObject().apply {
                        addProperty("enabled", true)
                        reasoningEffort?.takeIf { it.isNotBlank() }?.let { addProperty("effort", it) }
                        addProperty("exclude", true)
                    })
                }
            }
            val responseMessage = requestCompletion(apiKey, payload)
            val toolCalls = responseMessage.getAsJsonArray("tool_calls")
            if (toolCalls == null || toolCalls.size() == 0) {
                return@withContext Result(extractText(responseMessage.get("content")), created)
            }

            messages.add(responseMessage.deepCopy())
            toolCalls.forEach { callElement ->
                val call = callElement.asJsonObject
                val callId = call.get("id")?.asString ?: UUID.randomUUID().toString()
                val function = call.getAsJsonObject("function")
                val name = function?.get("name")?.asString.orEmpty()
                val argsRaw = function?.get("arguments")?.asString ?: "{}"
                val resultText = if (name == "create_file") {
                    runCatching {
                        val args = gson.fromJson(argsRaw, JsonObject::class.java)
                        val file = createGeneratedTextFile(
                            args.get("filename")?.asString ?: "result.txt",
                            args.get("content")?.asString.orEmpty(),
                            args.get("mime_type")?.asString ?: "text/plain"
                        )
                        created += file
                        gson.toJson(mapOf("ok" to true, "filename" to file.name, "size" to file.size))
                    }.getOrElse {
                        gson.toJson(mapOf("ok" to false, "error" to (it.message ?: "Ошибка создания файла")))
                    }
                } else {
                    gson.toJson(mapOf("ok" to false, "error" to "Неизвестный инструмент: $name"))
                }
                messages.add(JsonObject().apply {
                    addProperty("role", "tool")
                    addProperty("tool_call_id", callId)
                    addProperty("content", resultText)
                })
            }
        }
        Result("Модель слишком много раз вызывала инструменты. Операция остановлена.", created)
    }

    suspend fun generateImage(
        apiKey: String,
        model: String,
        prompt: String,
        attachments: List<PendingAttachment>
    ): Result = withContext(Dispatchers.IO) {
        val payload = JsonObject().apply {
            addProperty("model", model)
            addProperty("prompt", prompt.ifBlank { "Создай вариант приложенного изображения." })

            val references = JsonArray()
            attachments.filter { it.mimeType.startsWith("image/") }.forEach { attachment ->
                val bytes = readAttachment(attachment)
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                references.add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply {
                        addProperty("url", "data:${attachment.mimeType};base64,$b64")
                    })
                })
            }
            if (references.size() > 0) add("input_references", references)
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/images")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Umnik Android")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()

        try {
            executeActive(request).use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(apiError(response.code, body))
                val root = gson.fromJson(body, JsonObject::class.java)
                val data = root.getAsJsonArray("data") ?: error("OpenRouter не вернул изображение")
                val files = data.mapIndexedNotNull { index, element ->
                if (!element.isJsonObject) return@mapIndexedNotNull null
                val item = element.asJsonObject
                val encoded = item.get("b64_json")?.asString?.takeIf { it.isNotBlank() }
                    ?: return@mapIndexedNotNull null
                val mime = item.get("media_type")?.asString?.takeIf { it.startsWith("image/") } ?: "image/png"
                saveGeneratedImage(encoded, mime, index)
            }
                if (files.isEmpty()) error("OpenRouter вернул ответ без данных изображения")
                Result("Изображение создано.", files)
            }
        } finally {
            clearActiveCall()
        }
    }

    private fun requestCompletion(apiKey: String, payload: JsonObject): JsonObject {
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Umnik Android")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()
        try {
            executeActive(request).use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) error(apiError(response.code, body))
                val root = gson.fromJson(body, JsonObject::class.java)
                return root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("message") ?: error("OpenRouter вернул пустой ответ")
            }
        } finally {
            clearActiveCall()
        }
    }

    private fun message(role: String, text: String) = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", text)
    }

    private fun userMessage(text: String, attachments: List<PendingAttachment>): JsonObject {
        if (attachments.isEmpty()) return message("user", text)
        val parts = JsonArray()
        parts.add(JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", text.ifBlank { "Изучи вложения и помоги мне с ними." })
        })
        attachments.forEach { attachment ->
            val bytes = readAttachment(attachment)
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            when {
                attachment.mimeType.startsWith("image/") -> parts.add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply {
                        addProperty("url", "data:${attachment.mimeType};base64,$b64")
                    })
                })
                attachment.mimeType.startsWith("audio/") -> parts.add(JsonObject().apply {
                    addProperty("type", "input_audio")
                    add("input_audio", JsonObject().apply {
                        addProperty("data", b64)
                        addProperty("format", audioFormat(attachment))
                    })
                })
                attachment.mimeType.startsWith("video/") -> parts.add(JsonObject().apply {
                    addProperty("type", "video_url")
                    add("video_url", JsonObject().apply {
                        addProperty("url", "data:${attachment.mimeType};base64,$b64")
                    })
                })
                attachment.mimeType.startsWith("text/") ||
                    attachment.name.endsWith(".md", true) ||
                    attachment.name.endsWith(".json", true) ||
                    attachment.name.endsWith(".csv", true) ||
                    attachment.name.endsWith(".yaml", true) ||
                    attachment.name.endsWith(".yml", true) ||
                    attachment.name.endsWith(".xml", true) -> {
                    val content = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
                    parts.add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", "\n--- Вложение: ${attachment.name} ---\n$content\n--- Конец вложения ---")
                    })
                }
                else -> parts.add(JsonObject().apply {
                    addProperty("type", "file")
                    add("file", JsonObject().apply {
                        addProperty("filename", attachment.name)
                        addProperty("file_data", "data:${attachment.mimeType};base64,$b64")
                    })
                })
            }
        }
        return JsonObject().apply {
            addProperty("role", "user")
            add("content", parts)
        }
    }

    private fun tools() = JsonArray().apply {
        add(JsonObject().apply {
            addProperty("type", "function")
            add("function", JsonObject().apply {
                addProperty("name", "create_file")
                addProperty(
                    "description",
                    "Создать текстовый файл на устройстве пользователя. Используй для длинных материалов и когда пользователь просит результат файлом."
                )
                add("parameters", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject().apply {
                        add("filename", JsonObject().apply { addProperty("type", "string") })
                        add("content", JsonObject().apply { addProperty("type", "string") })
                        add("mime_type", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("description", "Например text/markdown, text/plain, text/csv, text/html или application/json")
                        })
                    })
                    add("required", JsonArray().apply { add("filename"); add("content") })
                })
            })
        })
    }

    private fun createGeneratedTextFile(nameRaw: String, content: String, mimeType: String): GeneratedFile {
        val name = safeName(nameRaw).ifBlank { "result.txt" }
        val dir = File(context.filesDir, "generated").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}_$name")
        file.writeText(content)
        return GeneratedFile(UUID.randomUUID().toString(), name, mimeType, file.absolutePath, file.length())
    }

    private fun saveGeneratedImage(encoded: String, mimeType: String, index: Int): GeneratedFile {
        val bytes = Base64.decode(encoded.substringAfter("base64,", encoded), Base64.DEFAULT)
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

    private fun readAttachment(attachment: PendingAttachment): ByteArray {
        attachment.localPath?.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (!file.exists()) error("Файл проекта не найден: ${attachment.name}")
            return file.readBytes()
        }
        val uri = Uri.parse(attachment.uri)
        return context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Не удалось прочитать ${attachment.name}")
    }

    private fun audioFormat(attachment: PendingAttachment): String {
        val ext = attachment.name.substringAfterLast('.', "").lowercase()
        if (ext in setOf("wav", "mp3", "flac", "m4a", "ogg", "webm", "aac")) return ext
        return when (attachment.mimeType.lowercase()) {
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/ogg" -> "ogg"
            "audio/webm" -> "webm"
            "audio/aac" -> "aac"
            else -> error("Формат аудио ${attachment.name} не поддерживается")
        }
    }

    private fun safeName(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_")
        .take(120)

    private fun extractText(content: JsonElement?): String {
        if (content == null || content.isJsonNull) return ""
        if (content.isJsonPrimitive) return content.asString
        if (content.isJsonArray) return content.asJsonArray.mapNotNull { part ->
            part.takeIf { it.isJsonObject }?.asJsonObject?.get("text")?.takeIf { it.isJsonPrimitive }?.asString
        }.joinToString("\n")
        return content.toString()
    }

    private fun apiError(code: Int, body: String): String {
        val message = runCatching {
            val root = gson.fromJson(body, JsonObject::class.java)
            root.getAsJsonObject("error")?.get("message")?.asString
        }.getOrNull()
        return "OpenRouter $code: ${message ?: body.take(500)}"
    }

    fun attachmentFromUri(uri: Uri): PendingAttachment {
        var name = "file"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = cursor.getLong(it) }
            }
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        return PendingAttachment(uri.toString(), name, mime, size)
    }
}
