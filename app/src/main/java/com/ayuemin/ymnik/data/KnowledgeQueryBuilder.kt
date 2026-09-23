package com.ayuemin.ymnik.data

import com.ayuemin.ymnik.model.ChatMessage

internal object KnowledgeQueryBuilder {
    fun build(current: String, history: List<ChatMessage>): String {
        val clean = current.trim()
        if (clean.isBlank()) return ""
        if (!looksContextualFollowUp(clean)) return clean.take(MAX_QUERY_CHARS)

        val previousUser = history.asReversed()
            .firstOrNull { it.role == "user" && it.text.isNotBlank() }
            ?.text
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return clean.take(MAX_QUERY_CHARS)

        return buildString {
            append(previousUser.take(MAX_PREVIOUS_CHARS))
            append("\nУточнение к предыдущему вопросу: ")
            append(clean)
        }.take(MAX_QUERY_CHARS)
    }

    private fun looksContextualFollowUp(text: String): Boolean {
        if (text.length > 260) return false
        val lower = text.lowercase()
        return FOLLOW_UP_MARKERS.any(lower::contains)
    }

    private val FOLLOW_UP_MARKERS = listOf(
        "об этом",
        "про это",
        "по этому",
        "что ещё",
        "что еще",
        "а ещё",
        "а еще",
        "подробнее",
        "продолжи",
        "продолжай",
        "там ещё",
        "там еще",
        "там есть",
        "этой теме",
        "предыдущ"
    )

    private const val MAX_PREVIOUS_CHARS = 6000
    private const val MAX_QUERY_CHARS = 12000
}
