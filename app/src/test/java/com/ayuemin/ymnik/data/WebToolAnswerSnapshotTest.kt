package com.ayuemin.ymnik.data

import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.InternetMode
import com.ayuemin.ymnik.model.ServerToolSettings
import com.ayuemin.ymnik.model.WebFetchEngine
import com.ayuemin.ymnik.model.WebSearchEngine
import com.ayuemin.ymnik.model.WebSearchMode
import com.ayuemin.ymnik.model.WebSearchPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebToolAnswerSnapshotTest {
    @Test
    fun snapshotPersistsRequestEnginesAndPreset() {
        val original = ChatMessage(
            id = "answer-1",
            role = "assistant",
            text = "ok",
            webSearchEnabled = true,
            internetMode = InternetMode.AUTO.name
        )
        val settings = ServerToolSettings(
            webSearch = WebSearchMode.AUTO,
            webSearchPreset = WebSearchPreset.NORMAL,
            webSearchEngine = WebSearchEngine.EXA,
            webFetchEngine = WebFetchEngine.OPENROUTER
        )

        val stored = snapshotWebToolAnswerMetadata(original, settings)

        assertEquals(WebSearchPreset.NORMAL.name, stored.webSearchPreset)
        assertEquals(WebSearchEngine.EXA.name, stored.webSearchEngine)
        assertEquals(WebFetchEngine.OPENROUTER.name, stored.webFetchEngine)
    }

    @Test
    fun browserAnswersAreNotStampedAsServerSearchFetch() {
        val browser = ChatMessage(
            id = "answer-2",
            role = "assistant",
            text = "ok",
            webSearchEnabled = true,
            internetMode = InternetMode.BROWSER.name
        )

        val stored = snapshotWebToolAnswerMetadata(browser, ServerToolSettings())

        assertNull(stored.webSearchPreset)
        assertNull(stored.webSearchEngine)
        assertNull(stored.webFetchEngine)
    }
}
