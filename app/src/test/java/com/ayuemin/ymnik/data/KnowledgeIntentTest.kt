package com.ayuemin.ymnik.data

import org.junit.Assert.assertEquals
import org.junit.Test

class KnowledgeIntentTest {
    @Test
    fun detectsExplicitBaseOnlyRequests() {
        listOf(
            "Ответь только по книге",
            "Что написано в учебнике об этом?",
            "Найди в базе, почему человек теряет интерес к работе",
            "Процитируй в документе определение мотивации"
        ).forEach { prompt ->
            assertEquals(KnowledgeRequestMode.BASE_ONLY, KnowledgeIntent.mode(prompt))
        }
    }

    @Test
    fun leavesOrdinaryQuestionsInNormalMode() {
        listOf(
            "Почему человек теряет интерес к работе?",
            "Расскажи про мотивацию",
            "Сколько будет 18 умножить на 27?"
        ).forEach { prompt ->
            assertEquals(KnowledgeRequestMode.NORMAL, KnowledgeIntent.mode(prompt))
        }
    }
}
