package com.ayuemin.ymnik.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCatalogFilterTest {
    private val models = listOf(
        ModelInfo(
            id = "vendor/chat",
            inputModalities = setOf("text", "image"),
            outputModalities = setOf("text"),
            supportedParameters = setOf("tools", "reasoning"),
            promptPriceUsdPerMillion = 0.2,
            completionPriceUsdPerMillion = 0.8
        ),
        ModelInfo(
            id = "vendor/chat:batch",
            inputModalities = setOf("text"),
            outputModalities = setOf("text"),
            promptPriceUsdPerMillion = 0.0,
            completionPriceUsdPerMillion = 0.0,
            variants = setOf(ModelVariant.BATCH)
        ),
        ModelInfo(
            id = "vendor/image-paid",
            inputModalities = setOf("text"),
            outputModalities = setOf("image"),
            promptPriceUsdPerMillion = 0.0,
            completionPriceUsdPerMillion = 0.0,
            imageOutputPriceUsd = 0.0000311377245508982
        ),
        ModelInfo(
            id = "vendor/image:free",
            inputModalities = setOf("text"),
            outputModalities = setOf("image"),
            promptPriceUsdPerMillion = 0.0,
            completionPriceUsdPerMillion = 0.0,
            imageOutputPriceUsd = 0.0,
            variants = setOf(ModelVariant.FREE)
        ),
        ModelInfo(
            id = "vendor/embed",
            inputModalities = setOf("text"),
            outputModalities = setOf("embeddings")
        )
    )

    @Test
    fun allMeansNoCategoryOrVariantFilter() {
        assertEquals(5, ModelCatalogFilter.apply(models).size)
    }

    @Test
    fun filtersByOutputCategory() {
        assertEquals(
            listOf("vendor/embed"),
            ModelCatalogFilter.apply(models, category = ModelCategory.EMBEDDINGS).map { it.id }
        )
    }

    @Test
    fun filtersBatchAsVariantNotCategory() {
        assertEquals(
            listOf("vendor/chat:batch"),
            ModelCatalogFilter.apply(models, variant = ModelVariant.BATCH).map { it.id }
        )
        assertEquals(
            listOf("vendor/chat", "vendor/chat:batch"),
            ModelCatalogFilter.apply(models, category = ModelCategory.TEXT).map { it.id }
        )
    }

    @Test
    fun filtersByMaximumTokenPrice() {
        assertEquals(
            listOf("vendor/chat:batch"),
            ModelCatalogFilter.apply(
                models,
                category = ModelCategory.TEXT,
                price = ModelPriceFilter.FREE
            ).map { it.id }
        )
        assertEquals(
            listOf("vendor/chat:batch", "vendor/chat"),
            ModelCatalogFilter.apply(
                models,
                category = ModelCategory.TEXT,
                price = ModelPriceFilter.UP_TO_1
            ).map { it.id }
        )
    }

    @Test
    fun imageFreeFilterUsesImageOutputPriceNotZeroTextPrice() {
        assertEquals(
            listOf("vendor/image:free"),
            ModelCatalogFilter.apply(
                models,
                category = ModelCategory.IMAGE,
                price = ModelPriceFilter.FREE
            ).map { it.id }
        )
        assertEquals(
            listOf("vendor/image:free"),
            ModelCatalogFilter.apply(
                models,
                category = ModelCategory.IMAGE,
                price = ModelPriceFilter.UP_TO_0_5
            ).map { it.id }
        )
        assertEquals(
            listOf("vendor/image:free", "vendor/image-paid"),
            ModelCatalogFilter.apply(
                models,
                category = ModelCategory.IMAGE,
                price = ModelPriceFilter.UP_TO_10
            ).map { it.id }
        )
    }

    @Test
    fun capabilityFiltersCanBeCombined() {
        assertEquals(
            listOf("vendor/chat"),
            ModelCatalogFilter.apply(
                models,
                capabilities = ModelCapabilityFilter(imageInput = true, reasoning = true, tools = true)
            ).map { it.id }
        )
    }
    @Test
    fun multimodalAndImageOutputFiltersAreExplicit() {
        val textAndImage = ModelInfo(
            id = "vendor/multimodal-output",
            inputModalities = setOf("text", "image"),
            outputModalities = setOf("text", "image")
        )
        val extended = models + textAndImage
        assertEquals(
            listOf("vendor/multimodal-output"),
            ModelCatalogFilter.apply(
                extended,
                capabilities = ModelCapabilityFilter(multimodal = true)
            ).map { it.id }
        )
        assertEquals(
            listOf("vendor/image-paid", "vendor/image:free", "vendor/multimodal-output"),
            ModelCatalogFilter.apply(
                extended,
                capabilities = ModelCapabilityFilter(imageOutput = true)
            ).map { it.id }
        )
    }

    @Test
    fun advancedCriteriaUseAndSemantics() {
        val advanced = ModelInfo(
            id = "vendor/advanced",
            name = "Advanced",
            description = "Vision model with tools",
            inputModalities = setOf("text", "image", "audio"),
            outputModalities = setOf("text", "image"),
            supportedParameters = setOf("tools", "reasoning", "response_format"),
            supportsStreaming = true,
            contextLength = 262144,
            maxCompletionTokens = 65536
        )
        val extended = models + advanced

        assertEquals(
            listOf("vendor/advanced"),
            ModelCatalogFilter.apply(
                extended,
                capabilities = ModelCapabilityFilter(
                    requiredInputModalities = setOf("image", "audio"),
                    requiredOutputModalities = setOf("image"),
                    requiredParameters = setOf("tools", "response_format"),
                    streaming = true,
                    minContextTokens = 256000,
                    minMaxCompletionTokens = 16384
                )
            ).map { it.id }
        )
    }

    @Test
    fun searchUsesNameDescriptionAndProvider() {
        val named = ModelInfo(
            id = "vendor/model-x",
            name = "Aurora Vision",
            description = "Specialized photo analysis model"
        )
        val extended = models + named

        assertEquals(listOf("vendor/model-x"), ModelCatalogFilter.apply(extended, query = "Aurora").map { it.id })
        assertEquals(listOf("vendor/model-x"), ModelCatalogFilter.apply(extended, query = "photo analysis").map { it.id })
        assertEquals(
            extended.filter { it.providerId == "vendor" }.map { it.id },
            ModelCatalogFilter.apply(extended, query = "vendor").map { it.id }
        )
    }

}
