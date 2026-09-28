package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.ServerToolSettings
import com.ayuemin.ymnik.model.WebSearchMode
import java.util.concurrent.ConcurrentHashMap

internal fun requestWebToolSettings(
    runtime: ServerToolSettings?,
    saved: ServerToolSettings
): ServerToolSettings {
    val base = runtime ?: saved
    return base.copy(
        webSearchEngine = saved.webSearchEngine,
        webFetchEngine = saved.webFetchEngine
    )
}

internal fun shouldKeepExistingTool(
    type: String?,
    functionName: String?,
    serverWebEnabled: Boolean
): Boolean {
    if (type == "openrouter:web_search" || type == "openrouter:web_fetch") return false
    if (serverWebEnabled && functionName == "local_web_fetch") return false
    return true
}

internal object WebToolRequestSnapshotStore {
    private val byChatId = ConcurrentHashMap<String, ServerToolSettings>()

    fun record(chatId: String?, settings: ServerToolSettings) {
        val key = chatId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (settings.webSearch == WebSearchMode.OFF) return
        byChatId[key] = settings
    }

    fun consume(chatId: String): ServerToolSettings? = byChatId.remove(chatId)

    fun clear(chatId: String) {
        byChatId.remove(chatId)
    }
}
