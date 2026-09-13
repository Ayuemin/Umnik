package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.ProviderRouteStrategy
import com.ayuemin.ymnik.model.ProviderRoutingSettings
import com.ayuemin.ymnik.model.ServerToolSettings
import com.ayuemin.ymnik.model.WebSearchEngine
import com.ayuemin.ymnik.model.WebSearchMode
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterFeaturePayloadTest {
    @Test
    fun routingBuildsProviderPreferencesAndFallbackModels() {
        val payload = JsonObject()
        OpenRouterFeaturePayload.applyRouting(
            payload,
            ProviderRoutingSettings(
                strategy = ProviderRouteStrategy.HIGHEST_THROUGHPUT,
                zeroDataRetention = true,
                denyDataCollection = true,
                fallbackModels = listOf("openai/gpt-5-mini", "google/gemini-3-flash"),
                maxPromptUsdPerMillion = 2.0,
                maxCompletionUsdPerMillion = 8.0
            )
        )

        assertEquals(2, payload.getAsJsonArray("models").size())
        val provider = payload.getAsJsonObject("provider")
        assertEquals("throughput", provider.get("sort").asString)
        assertTrue(provider.get("zdr").asBoolean)
        assertEquals("deny", provider.get("data_collection").asString)
        assertEquals(2.0, provider.getAsJsonObject("max_price").get("prompt").asDouble, 0.0)
    }

    @Test
    fun chatToolsExcludeShellButResponsesIncludeIt() {
        val settings = ServerToolSettings(
            webSearch = WebSearchMode.AUTO,
            webSearchEngine = WebSearchEngine.EXA,
            fusion = true,
            shell = true
        )

        val chat = OpenRouterFeaturePayload.chatServerTools(settings)
        val responses = OpenRouterFeaturePayload.responsesServerTools(settings)

        assertFalse(chat.any { it.asJsonObject.get("type").asString == "openrouter:shell" })
        assertTrue(responses.any { it.asJsonObject.get("type").asString == "openrouter:shell" })
        assertTrue(OpenRouterFeaturePayload.requiresResponsesApi(settings))
    }
}
