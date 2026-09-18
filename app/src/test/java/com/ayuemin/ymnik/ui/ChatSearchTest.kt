package com.ayuemin.ymnik.ui

import com.ayuemin.ymnik.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatSearchTest {
    private val messages = listOf(
        ChatMessage(id = "1", role = "user", text = "Первое сообщение про OpenRouter"),
        ChatMessage(id = "2", role = "assistant", text = "Второй ответ"),
        ChatMessage(id = "3", role = "user", text = "openrouter снова упомянут")
    )

    @Test
    fun matchesIgnoreCaseAndKeepMessageOrder() {
        assertEquals(listOf(0, 2), chatSearchMatchIndices(messages, "OPENROUTER"))
    }

    @Test
    fun trimsQueryAndReturnsNoMatchesForBlankInput() {
        assertEquals(listOf(1), chatSearchMatchIndices(messages, "  второй  "))
        assertEquals(emptyList<Int>(), chatSearchMatchIndices(messages, "   "))
    }
}
