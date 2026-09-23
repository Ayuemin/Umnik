package com.ayuemin.ymnik.data

import com.ayuemin.ymnik.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeQueryBuilderTest {
    @Test
    fun expandsShortFollowUpWithPreviousUserQuestion() {
        val history = listOf(
            ChatMessage("u1", "user", "что такое психология?"),
            ChatMessage("a1", "assistant", "Психология изучает психику.")
        )

        val query = KnowledgeQueryBuilder.build(
            "что ещё есть в учебнике об этом?",
            history
        )

        assertTrue(query.contains("что такое психология?"))
        assertTrue(query.contains("что ещё есть в учебнике об этом?"))
    }


    @Test
    fun expandsNaturalShortFollowUp() {
        val history = listOf(
            ChatMessage("u1", "user", "Почему человеку перестаёт нравиться работа?"),
            ChatMessage("a1", "assistant", "Причин может быть несколько.")
        )

        val query = KnowledgeQueryBuilder.build("А если он просто устал?", history)

        assertTrue(query.contains("Почему человеку перестаёт нравиться работа?"))
        assertTrue(query.contains("А если он просто устал?"))
    }

    @Test
    fun leavesIndependentQuestionUntouched() {
        val history = listOf(
            ChatMessage("u1", "user", "что такое психология?")
        )
        val current = "Какие главы посвящены возрастной психологии?"

        assertEquals(current, KnowledgeQueryBuilder.build(current, history))
    }

    @Test
    fun doesNotTreatStandaloneBookQuestionAsFollowUp() {
        val history = listOf(
            ChatMessage("u1", "user", "что такое психология?")
        )
        val current = "Что в учебнике говорится о возрастных кризисах?"

        assertEquals(current, KnowledgeQueryBuilder.build(current, history))
    }

    @Test
    fun ignoresAssistantWhenLookingForPreviousTopic() {
        val history = listOf(
            ChatMessage("u1", "user", "определение психологии"),
            ChatMessage("a1", "assistant", "Большой ответ о другом")
        )

        val query = KnowledgeQueryBuilder.build("подробнее об этом", history)

        assertTrue(query.startsWith("определение психологии"))
        assertFalse(query.startsWith("Большой ответ"))
    }
}
