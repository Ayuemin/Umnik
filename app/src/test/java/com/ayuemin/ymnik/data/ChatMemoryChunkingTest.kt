package com.ayuemin.ymnik.data

import com.ayuemin.ymnik.model.ChatMemoryGlobalSettings
import com.ayuemin.ymnik.network.ConversationContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMemoryChunkingTest {
    @Test
    fun `512 token embedding model caps a larger requested chunk`() {
        val plan = ChatMemoryChunking.plan(
            ChatMemoryGlobalSettings(
                chunkTokens = 1_200,
                chunkOverlapTokens = 80,
                embeddingContextTokens = 512
            )
        )
        assertEquals(384, plan.targetTokens)
        assertEquals(80, plan.overlapTokens)
    }

    @Test
    fun `unknown embedding window keeps requested chunk size`() {
        val plan = ChatMemoryChunking.plan(
            ChatMemoryGlobalSettings(chunkTokens = 1_200, embeddingContextTokens = null)
        )
        assertEquals(1_200, plan.targetTokens)
    }

    @Test
    fun `long russian text is split inside target budget`() {
        val text = (1..300).joinToString(" ") { index ->
            "Предложение $index описывает решение по памяти чата и сохраняет смысл предыдущего обсуждения."
        }
        val plan = ChatMemoryChunking.plan(
            ChatMemoryGlobalSettings(
                chunkTokens = 1_200,
                chunkOverlapTokens = 80,
                embeddingContextTokens = 512
            )
        )
        val chunks = ChatMemoryChunking.split(text, plan)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { ConversationContext.estimateTokens(it) <= plan.targetTokens })
    }

    @Test
    fun `retrieval query is clipped to embedding budget`() {
        val query = "Очень длинный запрос пользователя. ".repeat(500)
        val plan = ChatMemoryChunking.plan(
            ChatMemoryGlobalSettings(chunkTokens = 1_200, embeddingContextTokens = 512)
        )
        val clipped = ChatMemoryChunking.clipQuery(query, plan)
        assertTrue(clipped.isNotBlank())
        assertTrue(ConversationContext.estimateTokens(clipped) <= plan.targetTokens)
    }
}
