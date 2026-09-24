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
        assertEquals(0.015, modelCatalogComparablePrice(cheap, SimpleModelKind.TEXT)!!, 0.0)
        assertEquals(0.04, modelCatalogComparablePrice(mid, SimpleModelKind.TEXT)!!, 0.0)
    }

    @Test
    fun videoPriceUsesSpecializedOpenRouterTariffInsteadOfZeroTextTokens() {
        val video = ModelInfo(
            id = "vendor/video",
            promptPriceUsdPerMillion = 0.0,
            completionPriceUsdPerMillion = 0.0,
            outputModalities = setOf("video", "text"),
            pricingSkusUsd = mapOf(
                "per-video-second" to 0.10,
                "per-video-second-1080p" to 0.17
            )
        )

        assertEquals(0.10, modelCatalogComparablePrice(video, SimpleModelKind.VIDEO)!!, 0.0)
    }

    @Test
    fun textFilterDoesNotMixInMediaGenerators() {
        val videoWithText = ModelInfo(
            id = "vendor/video",
            outputModalities = setOf("video", "text")
        )
        val imageWithText = ModelInfo(
            id = "vendor/image",
            outputModalities = setOf("image", "text")
        )
        val visionChat = ModelInfo(
            id = "vendor/vision-chat",
            inputModalities = setOf("text", "image"),
            outputModalities = setOf("text")
        )

        assertFalse(modelMatchesSimpleKind(videoWithText, SimpleModelKind.TEXT))
        assertFalse(modelMatchesSimpleKind(imageWithText, SimpleModelKind.TEXT))
        assertTrue(modelMatchesSimpleKind(visionChat, SimpleModelKind.TEXT))
    }

    @Test
    fun imageGenerationIgnoresInputImageCharge() {
        val image = ModelInfo(
            id = "qwen/qwen-image-3-pro",
            outputModalities = setOf("image"),
            imagePriceUsd = 0.003,
            imageOutputPriceUsd = 0.00000958083832335329
        )

        assertEquals(
            0.03924311377245507,
            modelCatalogComparablePrice(image, SimpleModelKind.IMAGE)!!,
            0.0000001
        )
    }

    @Test
    fun speechFilterDoesNotTreatGenericAudioOutputAsTts() {
        val tts = ModelInfo(id = "vendor/tts", outputModalities = setOf("speech"))
        val audio = ModelInfo(id = "vendor/audio", outputModalities = setOf("audio"))

        assertTrue(modelMatchesSimpleKind(tts, SimpleModelKind.SPEECH))
        assertFalse(modelMatchesSimpleKind(audio, SimpleModelKind.SPEECH))
    }

}
