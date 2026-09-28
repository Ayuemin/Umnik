package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.ChatFile
import com.ayuemin.ymnik.model.ChatMessage

/**
 * Selects only historical chat attachments the user is referring to now.
 *
 * Saved chat files are not resent on every turn. A prior attachment is rehydrated only when
 * the prompt names it or clearly refers back to an earlier file/image. This keeps multimodal
 * follow-ups working without silently inflating every request.
 */
internal object HistoricalAttachmentPolicy {
    private val visualMarkers = listOf(
        "фото", "фотограф", "изображ", "картин", "скрин",
        "photo", "photograph", "image", "picture", "screenshot"
    )
    private val fileMarkers = listOf(
        "этот файл", "тот файл", "файл выше", "документ", "вложен",
        "this file", "that file", "document", "attachment"
    )
    private val revisitMarkers = listOf(
        "ещё раз", "еще раз", "снова", "повторно", "пересмотри", "перечитай",
        "посмотри ещё", "посмотри еще", "прочитай ещё", "прочитай еще",
        "look again", "read again", "check again", "try again"
    )

    fun select(
        prompt: String,
        history: List<ChatMessage>,
        files: List<ChatFile>
    ): List<ChatFile> {
        if (files.isEmpty()) return emptyList()
        val normalized = prompt.lowercase()

        val explicitlyNamed = files.filter { file ->
            val name = file.name.trim().lowercase()
            name.isNotBlank() && normalized.contains(name)
        }
        if (explicitlyNamed.isNotEmpty()) return explicitlyNamed.distinctBy { it.id }

        val visualReference = visualMarkers.any(normalized::contains)
        val fileReference = fileMarkers.any(normalized::contains)
        val revisitReference = revisitMarkers.any(normalized::contains)
        if (!visualReference && !fileReference && !revisitReference) return emptyList()

        val lastAttachmentNames = history.asReversed()
            .firstOrNull { it.role == "user" && it.attachmentNames.isNotEmpty() }
            ?.attachmentNames
            .orEmpty()
        if (lastAttachmentNames.isEmpty()) return emptyList()

        val selected = files.filter { file ->
            lastAttachmentNames.any { previous -> previous.equals(file.name, ignoreCase = true) }
        }
        return if (visualReference) {
            selected.filter { it.mimeType.startsWith("image/", ignoreCase = true) }
        } else {
            selected
        }.distinctBy { it.id }
    }
}
