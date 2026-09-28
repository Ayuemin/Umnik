package com.ayuemin.ymnik.network

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterStreamParserTest {
    @Test
    fun `collects text chunks and usage`() {
        val sse = """
            data: {"id":"gen-1","provider":"Test","model":"test/model","choices":[{"delta":{"content":"Привет"},"finish_reason":null}]}

            : OPENROUTER PROCESSING

            data: {"id":"gen-1","model":"test/model","choices":[{"delta":{"content":" мир"},"finish_reason":"stop","native_finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12,"cost":0.001}}

            data: [DONE]

        """.trimIndent()
        val updates = mutableListOf<String>()
        val result = OpenRouterStreamParser.parse(Buffer().writeUtf8(sse), onText = updates::add)

        assertEquals("Привет мир", result.message.get("content").asString)
        assertEquals("gen-1", result.id)
        assertEquals("test/model", result.model)
        assertEquals("stop", result.finishReason)
        assertEquals(10, result.promptTokens)
        assertEquals(2, result.completionTokens)
        assertEquals(12, result.totalTokens)
        assertEquals(0.001, result.costUsd!!, 0.000001)
        assertEquals("0.001", result.costUsdExact)
        assertEquals(listOf("Привет", " мир"), updates)
    }

    @Test
    fun `assembles streamed tool call arguments by index`() {
        val sse = """
            data: {"id":"gen-2","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"create_file","arguments":"{\"filename\":\"a"}}]},"finish_reason":null}]}

            data: {"id":"gen-2","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":".txt\",\"content\":\"ok\"}"}}]},"finish_reason":"tool_calls"}]}

            data: [DONE]

        """.trimIndent()
        val result = OpenRouterStreamParser.parse(Buffer().writeUtf8(sse))
        val calls = result.message.getAsJsonArray("tool_calls")

        assertEquals(1, calls.size())
        val function = calls[0].asJsonObject.getAsJsonObject("function")
        assertEquals("create_file", function.get("name").asString)
        assertEquals("{\"filename\":\"a.txt\",\"content\":\"ok\"}", function.get("arguments").asString)
        assertEquals("tool_calls", result.finishReason)
        assertTrue(result.message.get("content").asString.isEmpty())
    }
}
