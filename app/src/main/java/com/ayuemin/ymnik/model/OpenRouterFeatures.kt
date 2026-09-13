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
    ALWAYS
}

enum class WebSearchEngine(val apiValue: String) {
    AUTO("auto"),
    NATIVE("native"),
    EXA("exa"),
    FIRECRAWL("firecrawl"),
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
