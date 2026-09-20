package com.ayuemin.ymnik.model

enum class ProviderRouteStrategy {
    AUTO,
    CHEAPEST,
    HIGHEST_THROUGHPUT,
    LOWEST_LATENCY
}

enum class WebSearchMode {
    OFF,
    AUTO,
    /**
     * Legacy value kept for stored settings compatibility.
     * Modern OpenRouter search is agentic, so ALWAYS is treated as AUTO.
     */
    ALWAYS
}

enum class WebSearchPreset {
    ON_DEMAND,
    FAST,
    NORMAL,
    DEEP
}

enum class WebSearchEngine(val apiValue: String) {
    AUTO("auto"),
    NATIVE("native"),
    EXA("exa"),
    PARALLEL("parallel"),
    PERPLEXITY("perplexity")
}

data class ProviderRoutingSettings(
    val strategy: ProviderRouteStrategy = ProviderRouteStrategy.AUTO,
    val allowProviderFallbacks: Boolean = true,
    val requireParameters: Boolean = false,
    val zeroDataRetention: Boolean = false,
    val denyDataCollection: Boolean = false,
    val providerOrder: List<String> = emptyList(),
    val providerOnly: List<String> = emptyList(),
    val providerIgnore: List<String> = emptyList(),
    val quantizations: List<String> = emptyList(),
    val maxPromptUsdPerMillion: Double? = null,
    val maxCompletionUsdPerMillion: Double? = null,
    val maxImageUsd: Double? = null,
    val maxRequestUsd: Double? = null,
    val fallbackModels: List<String> = emptyList()
)

data class ServerToolSettings(
    val webSearch: WebSearchMode = WebSearchMode.OFF,
    val webSearchPreset: WebSearchPreset = WebSearchPreset.ON_DEMAND,
    val webSearchEngine: WebSearchEngine = WebSearchEngine.AUTO,
    val webFetch: Boolean = false,
    val datetime: Boolean = false,
    val imageGeneration: Boolean = false,
    val fusion: Boolean = false,
    val advisorModel: String? = null,
    val subagentModel: String? = null,
    val shell: Boolean = false
) {
    val enabled: Boolean
        get() = webSearch != WebSearchMode.OFF || webFetch || datetime || imageGeneration ||
            fusion || !advisorModel.isNullOrBlank() || !subagentModel.isNullOrBlank() || shell
}

/** Safely upgrades Gson-loaded settings whose newer enum fields may be absent. */
fun ServerToolSettings.normalized(): ServerToolSettings = copy(
    webSearch = runCatching { webSearch }.getOrNull() ?: WebSearchMode.OFF,
    webSearchPreset = runCatching { webSearchPreset }.getOrNull() ?: WebSearchPreset.ON_DEMAND,
    webSearchEngine = runCatching { webSearchEngine }.getOrNull() ?: WebSearchEngine.AUTO
)
