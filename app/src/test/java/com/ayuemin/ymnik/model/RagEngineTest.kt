package com.ayuemin.ymnik.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagEngineTest {
    @Test
    fun chunkingPreservesSourceAndCreatesSeveralChunks() {
        val text = (1..12).joinToString("\n\n") { index ->
            "Абзац $index. " + "данные ".repeat(80)
        }
        val chunks = RagEngine.chunkText(
            sourceId = "file-1",
            sourceName = "report.txt",
            text = text,
            targetChars = 900,
            overlapChars = 100
        )

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.sourceId == "file-1" && it.sourceName == "report.txt" })
        assertEquals(chunks.indices.toList(), chunks.map { it.order })
    }

    @Test
    fun cosineRetrievalRanksNearestChunkFirst() {
        val chunks = listOf(
            RagChunk("a", "f", "f.txt", "first", listOf(1f, 0f), 0),
            RagChunk("b", "f", "f.txt", "second", listOf(0f, 1f), 1),
            RagChunk("c", "f", "f.txt", "diagonal", listOf(0.7f, 0.7f), 2)
        )

        val matches = RagEngine.retrieve(listOf(1f, 0f), chunks, topK = 2)

        assertEquals("a", matches.first().chunk.id)
        assertTrue(matches.first().score > matches[1].score)
    }
}
