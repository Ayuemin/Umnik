package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.ChatFile
import com.ayuemin.ymnik.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalAttachmentPolicyTest {
    private val image = ChatFile("img", "homework.jpg", "image/jpeg", "/tmp/homework.jpg", 100)
    private val pdf = ChatFile("pdf", "chapter.pdf", "application/pdf", "/tmp/chapter.pdf", 200)
    private val notes = ChatFile("txt", "notes.txt", "text/plain", "/tmp/notes.txt", 50)

    @Test
    fun exactFilenameSelectsThatSavedFile() {
        val selected = HistoricalAttachmentPolicy.select(
            "Открой ещё раз chapter.pdf",
            emptyList(),
            listOf(image, pdf)
        )
        assertEquals(listOf(pdf), selected)
    }

    @Test
    fun photoReferenceRehydratesLatestPriorImage() {
        val history = listOf(
            ChatMessage("u1", "user", "старый файл", attachmentNames = listOf("chapter.pdf")),
            ChatMessage("a1", "assistant", "готово"),
            ChatMessage("u2", "user", "что здесь?", attachmentNames = listOf("homework.jpg")),
            ChatMessage("a2", "assistant", "ответ")
        )
        val selected = HistoricalAttachmentPolicy.select(
            "Попробуй ещё раз внимательно прочитать фотографию",
            history,
            listOf(pdf, image)
        )
        assertEquals(listOf(image), selected)
    }

    @Test
    fun visualReferenceDoesNotPullNonImageFromMixedTurn() {
        val history = listOf(
            ChatMessage(
                "u1",
                "user",
                "проверь",
                attachmentNames = listOf("homework.jpg", "notes.txt")
            )
        )
        val selected = HistoricalAttachmentPolicy.select(
            "Посмотри фото ещё раз",
            history,
            listOf(image, notes)
        )
        assertEquals(listOf(image), selected)
    }

    @Test
    fun genericRetryRehydratesLatestAttachmentSet() {
        val history = listOf(
            ChatMessage("u1", "user", "материалы", attachmentNames = listOf("chapter.pdf", "notes.txt"))
        )
        val selected = HistoricalAttachmentPolicy.select(
            "Попробуй ещё раз",
            history,
            listOf(pdf, notes)
        )
        assertEquals(listOf(pdf, notes), selected)
    }

    @Test
    fun unrelatedPromptDoesNotResendSavedFiles() {
        val history = listOf(
            ChatMessage("u1", "user", "файл", attachmentNames = listOf("chapter.pdf"))
        )
        val selected = HistoricalAttachmentPolicy.select(
            "Какая сегодня тема разговора?",
            history,
            listOf(pdf)
        )
        assertTrue(selected.isEmpty())
    }
}
