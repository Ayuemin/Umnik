package com.ayuemin.ymnik.network

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.PendingAttachment
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File

/** Builds one /v1/chat/completions body for OpenRouter Batch without sending it. */
class OpenRouterBatchBodyBuilder(private val context: Context) {
    fun build(
        model: String,
        history: List<ChatMessage>,
        prompt: String,
        attachments: List<PendingAttachment>,
        systemPrompt: String,
        reasoningEnabled: Boolean,
        reasoningEffort: String?,
        modelInfo: ModelInfo?
    ): JsonObject {
        val selectedHistory = ConversationContext.select(
            history,
            systemPrompt,
            prompt,
            ConversationContext.attachmentTokens(attachments),
            modelInfo?.contextLength,
            0
        )
        val messages = JsonArray().apply {
            add(message("system", systemPrompt))
            selectedHistory.forEach { add(message(it.role, it.text)) }
            add(userMessage(prompt, attachments))
        }
        return JsonObject().apply {
            addProperty("model", OpenRouterBatchCodec.baseModelId(model))
            add("messages", messages)
            if (reasoningEnabled) {
                add("reasoning", JsonObject().apply {
                    addProperty("enabled", true)
                    reasoningEffort?.takeIf { it.isNotBlank() }?.let { addProperty("effort", it) }
                    addProperty("exclude", true)
                })
            } else if (modelInfo?.reasoningMandatory == true) {
                add("reasoning", JsonObject().apply {
                    if ("low" in modelInfo.reasoningEfforts) addProperty("effort", "low")
                    addProperty("exclude", true)
                })
            } else if (modelInfo?.supportsReasoning == true || modelInfo?.reasoningDefaultEnabled == true) {
                add("reasoning", JsonObject().apply { addProperty("effort", "none") })
            }
        }
    }

    private fun message(role: String, text: String) = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", text)
    }

    private fun userMessage(text: String, attachments: List<PendingAttachment>): JsonObject {
        if (attachments.isEmpty()) return message("user", text)
        val parts = JsonArray()
        val fallbackText = if (attachments.all { it.mimeType.startsWith("audio/") }) {
            "Ответь на голосовое сообщение."
        } else {
            "Изучи вложения и помоги мне с ними."
        }
        parts.add(JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", text.ifBlank { fallbackText })
        })
        attachments.forEach { attachment ->
            val bytes = readAttachment(attachment)
            when {
                attachment.mimeType.startsWith("image/") -> {
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    parts.add(JsonObject().apply {
                        addProperty("type", "image_url")
                        add("image_url", JsonObject().apply {
                            addProperty("url", "data:${attachment.mimeType};base64,$b64")
                        })
                    })
                }
                attachment.mimeType.startsWith("audio/") -> {
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    parts.add(JsonObject().apply {
                        addProperty("type", "input_audio")
                        add("input_audio", JsonObject().apply {
                            addProperty("data", b64)
                            addProperty("format", audioFormat(attachment))
                        })
                    })
                }
                attachment.mimeType.startsWith("video/") -> {
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    parts.add(JsonObject().apply {
                        addProperty("type", "video_url")
                        add("video_url", JsonObject().apply {
                            addProperty("url", "data:${attachment.mimeType};base64,$b64")
                        })
                    })
                }
                isTextLike(attachment) -> {
                    val content = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
                    parts.add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", "\n--- Вложение: ${attachment.name} ---\n$content\n--- Конец вложения ---")
                    })
                }
                else -> {
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    parts.add(JsonObject().apply {
                        addProperty("type", "file")
                        add("file", JsonObject().apply {
                            addProperty("filename", attachment.name)
                            addProperty("file_data", "data:${attachment.mimeType};base64,$b64")
                        })
                    })
                }
            }
        }
        return JsonObject().apply {
            addProperty("role", "user")
            add("content", parts)
        }
    }

    private fun isTextLike(attachment: PendingAttachment): Boolean =
        attachment.mimeType.startsWith("text/") ||
            attachment.name.endsWith(".md", true) ||
            attachment.name.endsWith(".json", true) ||
            attachment.name.endsWith(".csv", true) ||
            attachment.name.endsWith(".yaml", true) ||
            attachment.name.endsWith(".yml", true) ||
            attachment.name.endsWith(".xml", true)

    private fun readAttachment(attachment: PendingAttachment): ByteArray {
        attachment.localPath?.takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (!file.exists()) error("Файл не найден: ${attachment.name}")
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
}
