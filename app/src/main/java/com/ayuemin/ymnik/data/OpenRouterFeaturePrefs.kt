package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.OpenRouterMediaSettings
import com.ayuemin.ymnik.model.ProviderRoutingSettings
import com.ayuemin.ymnik.model.RagSettings
import com.ayuemin.ymnik.model.ServerToolSettings
import com.ayuemin.ymnik.model.WebSearchEngine
import com.ayuemin.ymnik.model.WebSearchMode
import com.ayuemin.ymnik.model.WebSearchPreset
import com.google.gson.Gson

class OpenRouterFeaturePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("openrouter_features", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun routing(): ProviderRoutingSettings = read("routing", ProviderRoutingSettings::class.java, ProviderRoutingSettings())
    fun saveRouting(value: ProviderRoutingSettings) { prefs.edit().putString("routing", gson.toJson(value)).apply() }

    fun tools(): ServerToolSettings = read("tools", ServerToolSettings::class.java, ServerToolSettings()).let { value ->
        value.copy(
            webSearch = runCatching { value.webSearch }.getOrNull() ?: WebSearchMode.OFF,
            webSearchPreset = runCatching { value.webSearchPreset }.getOrNull() ?: WebSearchPreset.ON_DEMAND,
            webSearchEngine = (runCatching { value.webSearchEngine }.getOrNull() ?: WebSearchEngine.AUTO)
                .let { if (it == WebSearchEngine.FIRECRAWL) WebSearchEngine.AUTO else it }
        )
    }
    fun saveTools(value: ServerToolSettings) { prefs.edit().putString("tools", gson.toJson(value)).apply() }

    fun rag(): RagSettings = read("rag", RagSettings::class.java, RagSettings())
    fun saveRag(value: RagSettings) { prefs.edit().putString("rag", gson.toJson(value.copy(topK = value.topK.coerceIn(1, 30)))).apply() }

    fun media(): OpenRouterMediaSettings = read("media", OpenRouterMediaSettings::class.java, OpenRouterMediaSettings())
    fun saveMedia(value: OpenRouterMediaSettings) { prefs.edit().putString("media", gson.toJson(value)).apply() }

    private fun <T> read(key: String, type: Class<T>, fallback: T): T = runCatching {
        prefs.getString(key, null)?.let { gson.fromJson(it, type) } ?: fallback
    }.getOrDefault(fallback)
}
