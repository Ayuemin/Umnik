package com.ayuemin.ymnik.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCatalogFilterTest {
    private val models = listOf(
        ModelInfo(
            id = "vendor/chat",
            inputModalities = setOf("text", "image"),
            outputModalities = setOf("text"),
            supportedParameters = setOf("tools", "reasoning")
        ),
        ModelInfo(
            id = "vendor/chat:batch",
            inputModalities = setOf("text"),
            outputModalities = setOf("text"),
            variants = setOf(ModelVariant.BATCH)
        ),
        ModelInfo(
            id = "vendor/image",
            inputModalities = setOf("text"),
            outputModalities = setOf("image")
        ),
        ModelInfo(
            id = "vendor/embed",
            inputModalities = setOf("text"),
            outputModalities = setOf("embeddings")
        )
    )

    @Test
    fun allMeansNoCategoryOrVariantFilter() {
        assertEquals(4, ModelCatalogFilter.apply(models).size)
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
    fun capabilityFiltersCanBeCombined() {
        assertEquals(
            listOf("vendor/chat"),
            ModelCatalogFilter.apply(
                models,
                capabilities = ModelCapabilityFilter(imageInput = true, reasoning = true, tools = true)
            ).map { it.id }
        )
    }
}
