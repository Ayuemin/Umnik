package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.InternetMode
import com.ayuemin.ymnik.model.OpenRouterMediaSettings
import com.ayuemin.ymnik.model.ProviderRoutingSettings
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
            internetMode = runCatching { value.internetMode }.getOrNull() ?: InternetMode.AUTO,
            webSearchEngine = runCatching { value.webSearchEngine }.getOrNull() ?: WebSearchEngine.AUTO,
            webFetch = false,
            shell = false
        )
    }
    fun saveTools(value: ServerToolSettings) {
        val safe = value.copy(
            internetMode = runCatching { value.internetMode }.getOrNull() ?: InternetMode.AUTO,
            webFetch = false,
            shell = false
        )
        prefs.edit().putString("tools", gson.toJson(safe)).apply()
    }

    fun media(): OpenRouterMediaSettings = read("media", OpenRouterMediaSettings::class.java, OpenRouterMediaSettings())
    fun saveMedia(value: OpenRouterMediaSettings) { prefs.edit().putString("media", gson.toJson(value)).apply() }

    fun localShellMaxTurns(): Int = prefs.getInt("local_shell_max_turns", 24).coerceAtLeast(1)
    fun saveLocalShellMaxTurns(value: Int) {
        prefs.edit().putInt("local_shell_max_turns", value.coerceAtLeast(1)).apply()
    }

    fun localShellModelOverride(): String = prefs.getString("local_shell_model_override", "").orEmpty().trim()
    fun saveLocalShellModelOverride(value: String) {
        prefs.edit().putString("local_shell_model_override", value.trim()).apply()
    }

    private fun <T> read(key: String, type: Class<T>, fallback: T): T = runCatching {
        prefs.getString(key, null)?.let { gson.fromJson(it, type) } ?: fallback
    }.getOrDefault(fallback)
}
