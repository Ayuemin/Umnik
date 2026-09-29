package com.ayuemin.ymnik.ui

import com.ayuemin.ymnik.model.ChatMessage

internal fun chatSearchMatchIndices(messages: List<ChatMessage>, query: String): List<Int> {
    val needle = query.trim()
    if (needle.isBlank()) return emptyList()
    return messages.mapIndexedNotNull { index, message ->
        index.takeIf { message.text.contains(needle, ignoreCase = true) }
    }
}
