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
    fun comparablePriceKeepsFreeAndPaidTextModelsOrdered() {
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

        assertEquals(0.0, modelCatalogComparablePrice(free, SimpleModelKind.TEXT)!!, 0.0)
        assertEquals(0.02, modelCatalogComparablePrice(cheap, SimpleModelKind.TEXT)!!, 0.0)
        assertEquals(0.05, modelCatalogComparablePrice(mid, SimpleModelKind.TEXT)!!, 0.0)
    }

    @Test
    fun videoPriceUsesSpecializedOpenRouterTariffInsteadOfZeroTextTokens() {
        val video = ModelInfo(
            id = "vendor/video",
            promptPriceUsdPerMillion = 0.0,
            completionPriceUsdPerMillion = 0.0,
            outputModalities = setOf("video", "text"),
            pricingSkusUsd = mapOf(
                "duration_seconds_720p" to 0.10,
                "duration_seconds_1080p" to 0.17
            )
        )

        assertEquals(0.10, modelCatalogComparablePrice(video, SimpleModelKind.VIDEO)!!, 0.0)
    }

}
