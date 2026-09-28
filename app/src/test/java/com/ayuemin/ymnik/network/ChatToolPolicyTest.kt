package com.ayuemin.ymnik.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatToolPolicyTest {
    @Test
    fun ordinaryQuestionsDoNotForceToolCapableRouting() {
        assertFalse(ChatToolPolicy.needsCreateFile("Помоги решить домашнее задание"))
        assertFalse(ChatToolPolicy.needsCreateFile("Что написано в прикреплённом файле?"))
        assertFalse(ChatToolPolicy.needsCreateFile("Перечисли точки на карте"))
    }

    @Test
    fun explicitFileOutputEnablesCreateFile() {
        assertTrue(ChatToolPolicy.needsCreateFile("Дай это вордом"))
        assertTrue(ChatToolPolicy.needsCreateFile("Создай файл DOCX"))
        assertTrue(ChatToolPolicy.needsCreateFile("Экспортируй таблицу в CSV"))
        assertTrue(ChatToolPolicy.needsCreateFile("Save this as a PDF file"))
    }

    @Test
    fun specialistOrSkillInstructionCanRequireFileOutput() {
        assertTrue(
            ChatToolPolicy.needsCreateFile(
                prompt = "Продолжай",
                instructions = listOf("Финальный результат верни файлом DOCX.")
            )
        )
    }
}
