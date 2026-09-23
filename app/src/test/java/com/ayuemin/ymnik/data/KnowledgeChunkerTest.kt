package com.ayuemin.ymnik.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeChunkerTest {
    @Test
    fun keepsPageMetadataAndCreatesOverlap() {
        val text = (1..1400).joinToString(" ") { "слово$it" }
        val chunks = KnowledgeChunker.chunk(
            listOf(KnowledgeSourceSection(text, page = 7)),
            targetChars = 900,
            overlapChars = 120
        )

        assertTrue(chunks.size > 2)
        assertTrue(chunks.all { it.page == 7 })
        assertEquals(chunks.indices.toList(), chunks.map { it.ordinal })
        assertTrue(chunks.zipWithNext().any { (a, b) ->
            val tail = a.text.takeLast(50).split(' ').filter { it.length > 4 }.toSet()
            b.text.split(' ').any { it in tail }
        })
    }

    @Test
    fun detectsLongBase64LikeChunk() {
        val encoded = "R0lGODlhAQABAIA" + "A".repeat(700)
        assertTrue(KnowledgeChunker.isLikelyEncodedBlob(encoded))
        assertTrue(!KnowledgeChunker.isLikelyEncodedBlob("Обычный читаемый текст с нормальными пробелами и предложениями."))
    }

    @Test
    fun normalizesWhitespaceAndSkipsEmptySections() {
        val chunks = KnowledgeChunker.chunk(
            listOf(
                KnowledgeSourceSection("   \n\n\n"),
                KnowledgeSourceSection("Один    абзац.\n\n\n\nВторой абзац.")
            ),
            targetChars = 500,
            overlapChars = 50
        )

        assertEquals(1, chunks.size)
        assertEquals("Один абзац.\n\nВторой абзац.", chunks.single().text)
    }
}
