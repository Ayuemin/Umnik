package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationContextTest {
    private fun user(number: Int, text: String = "вопрос $number") =
        ChatMessage("user-$number", "user", text)
    private fun answer(number: Int, text: String = "ответ $number") =
        ChatMessage("answer-$number", "assistant", text)

    @Test fun aLargeContextIncludesMoreThanThirtyMessages() {
        val history = (1..90).flatMap { listOf(user(it), answer(it)) }
        val selected = ConversationContext.select(history, "инструкция", "ещё вопрос", 0, 1_048_576, 8_000)
        assertEquals(180, selected.size)
        assertEquals("user-1", selected.first().id)
    }

    @Test fun failedImageAndEmptyTurnsNeverEnterTextHistory() {
        val history = listOf(
            user(1), answer(1),
            user(2).copy(deliveryState = "failed"),
            user(3), answer(3, "Пустой ответ модели."),
            user(4).copy(imageGeneration = true), answer(4).copy(imageGeneration = true),
            user(5), answer(5),
            user(6).copy(deliveryState = "pending")
        )
        assertEquals(listOf("user-1", "answer-1", "user-5", "answer-5"),
            ConversationContext.completedTextTurns(history).map { it.id })
    }

    @Test fun oldestTurnsAreOmittedWithoutBreakingQuestionAnswerPairs() {
        val history = listOf(user(1, "старое".repeat(4_000)), answer(1), user(2), answer(2))
        val selected = ConversationContext.select(history, "инструкция", "новый запрос", 0, 8_192, 4_096)
        assertEquals(listOf("user-2", "answer-2"), selected.map { it.id })
    }

    @Test fun oversizedFixedContextIsSentToProviderWithoutClientRejection() {
        val selected = ConversationContext.select(emptyList(), "", "привет", 80_000, 32_000, 8_000)
        assertEquals(emptyList<ChatMessage>(), selected)
    }

    @Test fun unknownProviderWindowKeepsCompleteHistory() {
        val history = (1..90).flatMap { listOf(user(it), answer(it)) }
        val selected = ConversationContext.select(history, "инструкция", "вопрос", 0, null, 0)
        assertEquals(180, selected.size)
    }
}
