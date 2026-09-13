package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.ProviderRouteStrategy
import com.ayuemin.ymnik.model.ProviderRoutingSettings
import com.ayuemin.ymnik.model.ServerToolSettings
import com.ayuemin.ymnik.model.WebSearchMode
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** Shared OpenRouter-specific request extensions used by chat and Responses API clients. */
internal object OpenRouterFeaturePayload {
    fun applyRouting(payload: JsonObject, settings: ProviderRoutingSettings) {
        val fallbackModels = settings.fallbackModels.map(String::trim).filter(String::isNotBlank).distinct()
        if (fallbackModels.isNotEmpty()) {
            payload.add("models", JsonArray().apply { fallbackModels.forEach(::add) })
        }

        val provider = JsonObject()
        when (settings.strategy) {
            ProviderRouteStrategy.AUTO -> Unit
            ProviderRouteStrategy.CHEAPEST -> provider.addProperty("sort", "price")
            ProviderRouteStrategy.HIGHEST_THROUGHPUT -> provider.addProperty("sort", "throughput")
            ProviderRouteStrategy.LOWEST_LATENCY -> provider.addProperty("sort", "latency")
        }
        provider.addProperty("allow_fallbacks", settings.allowProviderFallbacks)
        if (settings.requireParameters) provider.addProperty("require_parameters", true)
        if (settings.zeroDataRetention) provider.addProperty("zdr", true)
        if (settings.denyDataCollection) provider.addProperty("data_collection", "deny")
        addStrings(provider, "order", settings.providerOrder)
        addStrings(provider, "only", settings.providerOnly)
        addStrings(provider, "ignore", settings.providerIgnore)
        addStrings(provider, "quantizations", settings.quantizations)

        val maxPrice = JsonObject()
        settings.maxPromptUsdPerMillion?.takeIf { it >= 0.0 }?.let { maxPrice.addProperty("prompt", it) }
        settings.maxCompletionUsdPerMillion?.takeIf { it >= 0.0 }?.let { maxPrice.addProperty("completion", it) }
        settings.maxImageUsd?.takeIf { it >= 0.0 }?.let { maxPrice.addProperty("image", it) }
        settings.maxRequestUsd?.takeIf { it >= 0.0 }?.let { maxPrice.addProperty("request", it) }
        if (maxPrice.size() > 0) provider.add("max_price", maxPrice)

        if (provider.size() > 0) payload.add("provider", provider)
    }

    /** Server tools supported directly by Chat Completions. Shell is Responses/Messages only. */
    fun chatServerTools(settings: ServerToolSettings): JsonArray = JsonArray().apply {
        if (settings.webSearch != WebSearchMode.OFF) {
            add(JsonObject().apply {
                addProperty("type", "openrouter:web_search")
                add("parameters", JsonObject().apply {
                    addProperty("engine", settings.webSearchEngine.apiValue)
                })
            })
        }
        if (settings.webFetch) add(serverTool("openrouter:web_fetch"))
        if (settings.datetime) add(serverTool("openrouter:datetime"))
        if (settings.imageGeneration) add(serverTool("openrouter:image_generation"))
        if (settings.fusion) add(serverTool("openrouter:fusion"))
        settings.advisorModel?.trim()?.takeIf { it.isNotBlank() }?.let { model ->
            add(modelTool("openrouter:advisor", model))
        }
        settings.subagentModel?.trim()?.takeIf { it.isNotBlank() }?.let { model ->
            add(modelTool("openrouter:subagent", model))
        }
    }

    fun responsesServerTools(settings: ServerToolSettings): JsonArray = chatServerTools(settings).apply {
        if (settings.shell) {
            add(JsonObject().apply {
                addProperty("type", "openrouter:shell")
                add("parameters", JsonObject().apply { addProperty("engine", "openrouter") })
            })
        }
    }

    fun requiresResponsesApi(settings: ServerToolSettings): Boolean = settings.shell

    private fun serverTool(type: String) = JsonObject().apply { addProperty("type", type) }

    private fun modelTool(type: String, model: String) = JsonObject().apply {
        addProperty("type", type)
        add("parameters", JsonObject().apply { addProperty("model", model) })
    }

    private fun addStrings(target: JsonObject, name: String, values: List<String>) {
        val cleaned = values.map(String::trim).filter(String::isNotBlank).distinct()
        if (cleaned.isNotEmpty()) target.add(name, JsonArray().apply { cleaned.forEach(::add) })
    }
}
