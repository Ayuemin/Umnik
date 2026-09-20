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

    @Test
    fun `parses OpenRouter usage metadata`() {
        val parsed = OpenRouterResponseParser.parse("""{
          "id":"gen_1",
          "model":"openai/test",
          "provider":"Provider X",
          "choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}],
          "usage":{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19,"cost":0.00123}
        }""")
        assertEquals("openai/test", parsed.model)
        assertEquals("Provider X", parsed.provider)
        assertEquals(12, parsed.promptTokens)
        assertEquals(7, parsed.completionTokens)
        assertEquals(19, parsed.totalTokens)
        assertEquals(0.00123, parsed.costUsd!!, 0.0000001)
    }
    @Test
    fun imageOnlyChatCompletionIsAccepted() {
        val response = OpenRouterResponseParser.parse(
            """{"choices":[{"message":{"role":"assistant","content":"","images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]},"finish_reason":"stop"}]}"""
        )
        assertEquals(1, response.message.getAsJsonArray("images").size())
    }

}
