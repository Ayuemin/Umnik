package com.ayuemin.ymnik.network

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenRouterTokenUsageParserTest {
    private fun parse(raw: String) = OpenRouterTokenUsageParser.parse(JsonParser.parseString(raw).asJsonObject)

    @Test fun providerSeparatesCachedAndReasoningWithoutDoubleCounting() {
        val x = parse("""{
            "prompt_tokens":1000,"completion_tokens":500,
            "prompt_tokens_details":{"cached_tokens":800,"cache_write_tokens":60},
            "completion_tokens_details":{"reasoning_tokens":300}
        }""")!!
        assertEquals(1000L, x.inputTokens)
        assertEquals(800L, x.cachedInputTokens)
        assertEquals(60L, x.cacheWriteTokens)
        assertEquals(500L, x.outputTokens)
        assertEquals(300L, x.reasoningTokens)
    }

    @Test fun absentCacheIsUnknownButExplicitZeroIsKnown() {
        assertNull(parse("""{"prompt_tokens":100,"completion_tokens":50}""")!!.cachedInputTokens)
        assertEquals(0L, parse("""{"prompt_tokens":100,"prompt_tokens_details":{"cached_tokens":0}}""")!!.cachedInputTokens)
    }

    @Test fun aggregatesAcrossToolCalls() {
        val x = OpenRouterTokenUsageLedger()
        x.record(parse("""{"prompt_tokens":100,"completion_tokens":70,"prompt_tokens_details":{"cached_tokens":20}}"""))
        x.record(parse("""{"prompt_tokens":150,"completion_tokens":30,"prompt_tokens_details":{"cached_tokens":125}}"""))
        val stats = x.snapshot()!!
        assertEquals(2, stats.observedCalls)
        assertEquals(250L, stats.inputTokens)
        assertEquals(145L, stats.cachedInputTokens)
        assertEquals(100L, stats.outputTokens)
        assertNull(stats.cacheWriteTokens)
        assertNull(stats.reasoningTokens)
    }
}
