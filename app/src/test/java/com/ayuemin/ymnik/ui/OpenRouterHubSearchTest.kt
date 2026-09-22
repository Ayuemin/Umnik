package com.ayuemin.ymnik.ui

import com.ayuemin.ymnik.model.ModelInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterHubSearchTest {
    private val model = ModelInfo(
        id = "openai/gpt-5.6-luna-pro",
        name = "GPT 5.6 Luna Pro",
        description = "Fast text model for everyday work"
    )

    @Test
    fun matchesNameAndIdAcrossSeparators() {
        assertTrue(modelMatchesSearch(model, "gpt 5.6"))
        assertTrue(modelMatchesSearch(model, "gpt-5-6"))
        assertTrue(modelMatchesSearch(model, "openai luna"))
        assertTrue(modelMatchesSearch(model, "LUNA PRO"))
    }

    @Test
    fun requiresAllSearchTokens() {
        assertTrue(modelMatchesSearch(model, "fast everyday"))
        assertFalse(modelMatchesSearch(model, "gpt claude"))
    }
}
