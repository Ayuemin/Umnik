package com.ayuemin.ymnik.ui

import com.ayuemin.ymnik.model.ModelInfo
import org.junit.Assert.assertEquals
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

    @Test
    fun directNameAndIdMatchesRankAboveDescriptionOnlyMatches() {
        val descriptionOnly = ModelInfo(
            id = "aion-labs/aion-2.0",
            name = "AionLabs: Aion-2.0",
            description = "Model influenced by DeepSeek-style reasoning"
        )
        val direct = ModelInfo(
            id = "deepseek/deepseek-r1-0528",
            name = "DeepSeek: R1 0528"
        )

        assertTrue(modelMatchesSearch(descriptionOnly, "deepseek"))
        assertTrue(modelMatchesSearch(direct, "deepseek"))
        assertTrue(modelSearchRank(direct, "deepseek") < modelSearchRank(descriptionOnly, "deepseek"))
    }

    @Test
    fun paidPriceBandsDoNotIncludeFreeModelsOrOverlap() {
        val free = ModelInfo(
            id = "vendor/free",
            promptPriceUsdPerMillion = 0.0,
            completionPriceUsdPerMillion = 0.0
        )
        val cheap = ModelInfo(
            id = "vendor/cheap",
            promptPriceUsdPerMillion = 0.01,
            completionPriceUsdPerMillion = 0.02
        )
        val mid = ModelInfo(
            id = "vendor/mid",
            promptPriceUsdPerMillion = 0.03,
            completionPriceUsdPerMillion = 0.05
        )

        assertTrue(modelMatchesSimplePrice(free, SimpleModelKind.TEXT, SimplePriceFilter.FREE))
        assertFalse(modelMatchesSimplePrice(free, SimpleModelKind.TEXT, SimplePriceFilter.FROM_0_TO_0_02))
        assertTrue(modelMatchesSimplePrice(cheap, SimpleModelKind.TEXT, SimplePriceFilter.FROM_0_TO_0_02))
        assertFalse(modelMatchesSimplePrice(cheap, SimpleModelKind.TEXT, SimplePriceFilter.FROM_0_02_TO_0_05))
        assertTrue(modelMatchesSimplePrice(mid, SimpleModelKind.TEXT, SimplePriceFilter.FROM_0_02_TO_0_05))
    }

}
