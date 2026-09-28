package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.ServerToolSettings
import com.ayuemin.ymnik.model.WebFetchEngine
import com.ayuemin.ymnik.model.WebSearchEngine
import com.ayuemin.ymnik.model.WebSearchMode
import com.ayuemin.ymnik.model.WebSearchPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolRequestSettingsTest {
    @Test
    fun requestSettingsUseCurrentGlobalEnginesAndKeepChatSearchMode() {
        val runtime = ServerToolSettings(
            webSearch = WebSearchMode.AUTO,
            webSearchPreset = WebSearchPreset.DEEP,
            webSearchEngine = WebSearchEngine.NATIVE,
            webFetchEngine = WebFetchEngine.NATIVE
        )
        val saved = ServerToolSettings(
            webSearchEngine = WebSearchEngine.PARALLEL,
            webFetchEngine = WebFetchEngine.OPENROUTER
        )

        val request = requestWebToolSettings(runtime, saved)

        assertEquals(WebSearchMode.AUTO, request.webSearch)
        assertEquals(WebSearchPreset.DEEP, request.webSearchPreset)
        assertEquals(WebSearchEngine.PARALLEL, request.webSearchEngine)
        assertEquals(WebFetchEngine.OPENROUTER, request.webFetchEngine)
    }

    @Test
    fun existingServerWebDuplicatesAndLocalFetchAreRemoved() {
        assertFalse(shouldKeepExistingTool("openrouter:web_search", null, serverWebEnabled = true))
        assertFalse(shouldKeepExistingTool("openrouter:web_fetch", null, serverWebEnabled = true))
        assertFalse(shouldKeepExistingTool("function", "local_web_fetch", serverWebEnabled = true))
        assertTrue(shouldKeepExistingTool("function", "local_browser_open", serverWebEnabled = true))
        assertTrue(shouldKeepExistingTool("function", "local_web_fetch", serverWebEnabled = false))
    }
}
