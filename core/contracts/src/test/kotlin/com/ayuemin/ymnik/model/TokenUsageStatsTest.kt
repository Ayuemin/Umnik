package com.ayuemin.ymnik.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenUsageStatsTest {
    @Test
    fun defaultsRemainUnknownNotZero() {
        val usage = TokenUsageStats()
        assertNull(usage.inputTokens)
        assertNull(usage.cachedInputTokens)
        assertNull(usage.cacheWriteTokens)
        assertNull(usage.outputTokens)
        assertNull(usage.reasoningTokens)
        assertEquals(0, usage.observedCalls)
    }

    @Test
    fun existingConstructorAndCopyPreserveFields() {
        val usage = TokenUsageStats(
            inputTokens = 200,
            cachedInputTokens = 80,
            cacheWriteTokens = 20,
            outputTokens = 50,
            reasoningTokens = 10,
            observedCalls = 2
        )
        assertEquals(usage, usage.copy())
        assertEquals(80L, usage.cachedInputTokens)
        assertEquals(20L, usage.cacheWriteTokens)
        assertEquals(10L, usage.reasoningTokens)
        assertEquals(2, usage.observedCalls)
    }
}
