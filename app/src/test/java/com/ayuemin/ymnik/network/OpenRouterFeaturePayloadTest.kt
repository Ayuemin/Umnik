package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.ProviderRouteStrategy
import com.ayuemin.ymnik.model.ProviderRoutingSettings
import com.ayuemin.ymnik.model.ServerToolSettings
import com.ayuemin.ymnik.model.WebSearchEngine
import com.ayuemin.ymnik.model.WebSearchMode
import com.ayuemin.ymnik.model.WebSearchPreset
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
            webSearchPreset = WebSearchPreset.NORMAL,
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
    @Test
    fun normalSearchUsesModernServerToolAndFiveTurnBudget() {
        val settings = ServerToolSettings(
            webSearch = WebSearchMode.AUTO,
            webSearchPreset = WebSearchPreset.NORMAL,
            webSearchEngine = WebSearchEngine.EXA
        )
        val payload = JsonObject()
        OpenRouterFeaturePayload.applyServerToolBudget(payload, settings)
        val tools = OpenRouterFeaturePayload.chatServerTools(settings)
        val search = tools.first { it.asJsonObject.get("type").asString == "openrouter:web_search" }.asJsonObject
        val parameters = search.getAsJsonObject("parameters")

        assertEquals(5, payload.get("max_tool_calls").asInt)
        assertEquals("exa", parameters.get("engine").asString)
        assertEquals(5, parameters.get("max_results").asInt)
        assertEquals(25, parameters.get("max_total_results").asInt)
        assertEquals("medium", parameters.get("search_context_size").asString)
    }

    @Test
    fun searchPresetsMapToExpectedBudgets() {
        val fast = ServerToolSettings(
            webSearch = WebSearchMode.AUTO,
            webSearchPreset = WebSearchPreset.FAST
        )
        val fastPayload = JsonObject()
        OpenRouterFeaturePayload.applyServerToolBudget(fastPayload, fast)
        val fastParams = OpenRouterFeaturePayload.chatServerTools(fast)
            .first { it.asJsonObject.get("type").asString == "openrouter:web_search" }
            .asJsonObject.getAsJsonObject("parameters")
        assertEquals(1, fastPayload.get("max_tool_calls").asInt)
        assertEquals(3, fastParams.get("max_results").asInt)
        assertEquals(3, fastParams.get("max_total_results").asInt)
        assertEquals("low", fastParams.get("search_context_size").asString)

        val deep = ServerToolSettings(
            webSearch = WebSearchMode.AUTO,
            webSearchPreset = WebSearchPreset.DEEP
        )
        val deepPayload = JsonObject()
        OpenRouterFeaturePayload.applyServerToolBudget(deepPayload, deep)
        val deepParams = OpenRouterFeaturePayload.chatServerTools(deep)
            .first { it.asJsonObject.get("type").asString == "openrouter:web_search" }
            .asJsonObject.getAsJsonObject("parameters")
        assertEquals(25, deepPayload.get("max_tool_calls").asInt)
        assertEquals(10, deepParams.get("max_results").asInt)
        assertEquals(100, deepParams.get("max_total_results").asInt)
        assertEquals("high", deepParams.get("search_context_size").asString)

        val onDemandPayload = JsonObject()
        OpenRouterFeaturePayload.applyServerToolBudget(
            onDemandPayload,
            ServerToolSettings(webSearch = WebSearchMode.AUTO, webSearchPreset = WebSearchPreset.ON_DEMAND)
        )
        assertFalse(onDemandPayload.has("max_tool_calls"))
    }

}
