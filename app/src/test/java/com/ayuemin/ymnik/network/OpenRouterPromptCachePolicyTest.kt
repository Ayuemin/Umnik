package com.ayuemin.ymnik.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterPromptCachePolicyTest {
    private fun payload(model: String): JsonObject = JsonObject().apply {
        addProperty("model", model)
        add("messages", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", "Stable system instructions")
            })
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", "Build APK")
            })
        })
    }

    @Test fun sameChatGivesAnonymousStableSession() {
        val a = payload("deepseek/deepseek-v4.1-flash")
        val b = payload("deepseek/deepseek-v4.1-flash")
        OpenRouterPromptCachePolicy.apply(a, "secret-chat-id")
        OpenRouterPromptCachePolicy.apply(b, "secret-chat-id")
        assertEquals(a.get("session_id"), b.get("session_id"))
        assertFalse(a.toString().contains("secret-chat-id"))
        assertFalse(a.has("cache_control"))
        assertNotEquals(
            OpenRouterPromptCachePolicy.sessionId("secret-chat-id"),
            OpenRouterPromptCachePolicy.sessionId("another-chat")
        )
    }

    @Test fun anthropicReceivesAutomaticBreakpoints() {
        val p = payload("anthropic/claude-sonnet-4")
        OpenRouterPromptCachePolicy.apply(p, "chat")
        assertEquals("ephemeral", p.getAsJsonObject("cache_control").get("type").asString)
        assertFalse(p.getAsJsonObject("cache_control").has("ttl"))
    }

    @Test fun supportedQwenUsesStableExplicitBlock() {
        val p = payload("qwen/qwen3-coder-plus")
        OpenRouterPromptCachePolicy.apply(p, "chat")
        val block = p.getAsJsonArray("messages")[0].asJsonObject
            .getAsJsonArray("content")[0].asJsonObject
        assertEquals("Stable system instructions", block.get("text").asString)
        assertEquals("ephemeral", block.getAsJsonObject("cache_control").get("type").asString)
    }

    @Test fun unknownAndImplicitModelsKeepNativeBehavior() {
        val p = payload("google/gemini-2.5-flash")
        OpenRouterPromptCachePolicy.apply(p, null)
        assertFalse(p.has("session_id"))
        assertFalse(p.has("cache_control"))
        assertTrue(p.getAsJsonArray("messages")[0].asJsonObject.get("content").isJsonPrimitive)
    }
}
