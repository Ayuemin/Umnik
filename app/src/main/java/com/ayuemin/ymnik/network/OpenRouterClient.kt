package com.ayuemin.ymnik.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.GeneratedFile
import com.ayuemin.ymnik.model.PendingAttachment
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    data class Result(val text: String, val files: List<GeneratedFile>)

    suspend fun models(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiError(response.code, body))
            val root = gson.fromJson(body, JsonObject::class.java)
            root.getAsJsonArray("data")?.mapNotNull { it.asJsonObject.get("id")?.asString }?.sorted() ?: emptyList()
        }
    }

    suspend fun chat(
        apiKey: String,
        model: String,
        history: List<ChatMessage>,
        prompt: String,
        attachments: List<PendingAttachment>,
        systemPrompt: String
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
                add("tools", tools())
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
                        val file = createGeneratedFile(
                            args.get("filename")?.asString ?: "result.txt",
                            args.get("content")?.asString.orEmpty(),
                            args.get("mime_type")?.asString ?: "text/plain"
                        )
                        created += file
                        gson.toJson(mapOf("ok" to true, "filename" to file.name, "size" to file.size))
                    }.getOrElse { gson.toJson(mapOf("ok" to false, "error" to (it.message ?: "Ошибка создания файла"))) }
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

    private fun requestCompletion(apiKey: String, payload: JsonObject): JsonObject {
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-Title", "Ymnik Android")
            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(apiError(response.code, body))
            val root = gson.fromJson(body, JsonObject::class.java)
            return root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("message") ?: error("OpenRouter вернул пустой ответ")
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
        attachments.forEach { a ->
            val uri = Uri.parse(a.uri)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Не удалось прочитать ${a.name}")
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            when {
                a.mimeType.startsWith("image/") -> parts.add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply { addProperty("url", "data:${a.mimeType};base64,$b64") })
                })
                a.mimeType.startsWith("text/") || a.name.endsWith(".md", true) || a.name.endsWith(".json", true) -> {
                    val content = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
                    parts.add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", "\n--- Вложение: ${a.name} ---\n$content\n--- Конец вложения ---")
                    })
                }
                else -> parts.add(JsonObject().apply {
                    addProperty("type", "file")
                    add("file", JsonObject().apply {
                        addProperty("filename", a.name)
                        addProperty("file_data", "data:${a.mimeType};base64,$b64")
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
                addProperty("description", "Создать текстовый файл, который пользователь сможет сохранить на Android. Используй, когда пользователь просит файл для скачивания или готовый артефакт.")
                add("parameters", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", JsonObject().apply {
                        add("filename", JsonObject().apply { addProperty("type", "string") })
                        add("content", JsonObject().apply { addProperty("type", "string") })
                        add("mime_type", JsonObject().apply {
                            addProperty("type", "string")
                            addProperty("description", "Например text/markdown, text/plain или application/json")
                        })
                    })
                    add("required", JsonArray().apply { add("filename"); add("content") })
                })
            })
        })
    }

    private fun createGeneratedFile(nameRaw: String, content: String, mimeType: String): GeneratedFile {
        val name = nameRaw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_")
            .take(120).ifBlank { "result.txt" }
        val dir = File(context.filesDir, "generated").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}_$name")
        file.writeText(content)
        return GeneratedFile(UUID.randomUUID().toString(), name, mimeType, file.absolutePath, file.length())
    }

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
        return "OpenRouter $code: ${message ?: body.take(300)}"
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
