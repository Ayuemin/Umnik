package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.PendingAttachment
import java.io.File

/** The complete conversation stays on disk. Only the oldest completed turns are omitted from a request. */
internal object ConversationContext {
    fun completedTextTurns(history: List<ChatMessage>): List<ChatMessage> {
        val turns = mutableListOf<ChatMessage>()
        var user: ChatMessage? = null
        for (item in history) {
            if (item.imageGeneration) continue
            when (item.role) {
                "user" -> user = item.takeUnless { it.deliveryState == "failed" || it.deliveryState == "pending" }
                "assistant" -> {
                    val question = user
                    if (question != null && item.text.isNotBlank() && item.text != "Пустой ответ модели.") {
                        turns += question
                        turns += if (item.deliveryState == "interrupted") {
                            item.copy(
                                text = item.text.trimEnd() +
                                    "\n\n[Системная пометка Umnik: этот ответ был прерван пользователем и может быть неполным.]"
                            )
                        } else {
                            item
                        }
                    }
                    user = null
                }
            }
        }
        return turns
    }

    /** A conservative estimate, not a tokenizer. Reserve headroom for provider-specific tokenization. */
    fun estimateTokens(value: String): Int {
        val nonAscii = value.count { it.code > 127 }
        return (value.length - nonAscii + 2) / 3 + (nonAscii + 1) / 2 + 16
    }

    fun attachmentTokens(attachments: List<PendingAttachment>): Int = attachments.sumOf { attachment ->
        val name = attachment.name.lowercase()
        val mime = attachment.mimeType.lowercase()
        val bytes = attachment.localPath?.let { File(it).takeIf(File::isFile)?.length() }
            ?: attachment.size.coerceAtLeast(0L)
        when {
            mime.startsWith("text/") || listOf(".md", ".json", ".csv", ".yaml", ".yml", ".xml")
                .any(name::endsWith) -> (bytes / 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() + 64
            mime.startsWith("image/") -> 4_096
            mime.startsWith("audio/") || mime.startsWith("video/") -> 16_384
            else -> (bytes / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() + 1_024
        }
    }

    /** No Umnik size cap: provider/model/device limits are authoritative. */
    fun checkTransferSize(attachments: List<PendingAttachment>) = Unit

    fun select(
        history: List<ChatMessage>,
        systemPrompt: String,
        prompt: String,
        attachmentTokens: Int,
        contextLength: Int?,
        outputTokens: Int
    ): List<ChatMessage> {
        val completed = completedTextTurns(history)
        // Unknown window: send the complete stored conversation and let the provider decide.
        // Known window: trim only the oldest completed turns. Fixed prompt/files are never
        // blocked by Umnik; provider/model limits remain authoritative.
        val inputBudget = contextLength?.takeIf { it > 0 }?.toLong() ?: return completed
        val fixed = estimateTokens(systemPrompt).toLong() + estimateTokens(prompt) + attachmentTokens + 256
        val selected = mutableListOf<ChatMessage>()
        var used = fixed
        for (index in completed.size - 2 downTo 0 step 2) {
            val question = completed[index]
            val answer = completed[index + 1]
            val turnCost = estimateTokens(question.text).toLong() + estimateTokens(answer.text) + 64
            if (used + turnCost > inputBudget) break
            selected.add(0, answer)
            selected.add(0, question)
            used += turnCost
        }
        return selected
    }
}
