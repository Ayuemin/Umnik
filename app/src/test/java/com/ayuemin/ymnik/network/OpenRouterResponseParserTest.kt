package com.ayuemin.ymnik.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterResponseParserTest {
    @Test fun successfulTextIsAccepted() {
        val response = OpenRouterResponseParser.parse(
            """{"id":"gen-1","provider":"DeepSeek","choices":[{"message":{"role":"assistant","content":"Готово"},"finish_reason":"stop"}]}"""
        )
        assertEquals("Готово", response.message.get("content").asString)
        assertEquals("stop", response.finishReason)
    }

    @Test fun http200WithEmbeddedErrorIsNotSuccess() {
        val error = runCatching { OpenRouterResponseParser.parse(
            """{"id":"gen-2","error":{"code":502,"message":"Provider unavailable"}}"""
        ) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("Provider unavailable"))
    }

    @Test fun exhaustedReasoningBudgetIsNotSavedAsAnswer() {
        val error = runCatching { OpenRouterResponseParser.parse(
            """{"choices":[{"message":{"role":"assistant","content":""},"finish_reason":"length"}],"usage":{"completion_tokens_details":{"reasoning_tokens":6000}}}"""
        ) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("finish_reason=length"))
        assertTrue(error?.message.orEmpty().contains("reasoning_tokens=6000"))
    }

    @Test fun toolCallsMayHaveNoText() {
        val response = OpenRouterResponseParser.parse(
            """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"tool-1"}]},"finish_reason":"tool_calls"}]}"""
        )
        assertEquals(1, response.message.getAsJsonArray("tool_calls").size())
    }
}
