package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.ModelCategory
import com.ayuemin.ymnik.model.ModelVariant
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterModelCatalogTest {
    @Test
    fun parsesOutputModalitiesAndBatchVariant() {
        val json = JsonParser.parseString(
            """
            {
              "id": "deepseek/deepseek-v4-pro-0813:batch",
              "architecture": {
                "input_modalities": ["text", "image"],
                "output_modalities": ["text"]
              },
              "supported_parameters": ["tools", "reasoning"],
              "context_length": 131072,
              "pricing": {"prompt": "0.00000066", "completion": "0.00000198"},
              "top_provider": {"max_completion_tokens": 8192}
            }
            """.trimIndent()
        )

        val info = OpenRouterModelCatalog.parse(json)!!

        assertTrue(info.accepts("image"))
        assertTrue(info.outputs("text"))
        assertTrue(info.supportsTools)
        assertTrue(info.isBatch)
        assertEquals("deepseek/deepseek-v4-pro-0813", info.batchBaseModelId)
        assertTrue(ModelCategory.TEXT in info.categories)
        assertTrue(ModelVariant.BATCH in info.variants)
        assertEquals(0.66, info.promptPriceUsdPerMillion!!, 0.000001)
        assertEquals(1.98, info.completionPriceUsdPerMillion!!, 0.000001)
    }

    @Test
    fun parsesImageOutputPricingSeparatelyFromTextPricing() {
        val image = OpenRouterModelCatalog.parse(
            JsonParser.parseString(
                """
                {
                  "id": "sourceful/riverflow-v2.5-pro",
                  "architecture": {"input_modalities":["text","image"],"output_modalities":["image"]},
                  "pricing": {"prompt":"0","completion":"0","image_output":"0.0000311377245508982"}
                }
                """.trimIndent()
            )
        )!!
        assertEquals(0.0, image.promptPriceUsdPerMillion!!, 0.000001)
        assertEquals(0.0000311377245508982, image.imageOutputPriceUsd!!, 0.000000000001)
        assertTrue(image.estimatedImageOutputUsd1K!! > 0.12)
        assertTrue(!image.isFreeFor(ModelCategory.IMAGE))
    }

    @Test
    fun parsesSpecializedOutputCategories() {
        val embeddings = OpenRouterModelCatalog.parse(
            JsonParser.parseString(
                """
                {
                  "id": "openai/text-embedding-3-small",
                  "architecture": {
                    "input_modalities": ["text"],
                    "output_modalities": ["embeddings"]
                  }
                }
                """.trimIndent()
            )
        )!!
        val transcription = OpenRouterModelCatalog.parse(
            JsonParser.parseString(
                """
                {
                  "id": "openai/whisper-1",
                  "architecture": {
                    "input_modalities": ["audio"],
                    "output_modalities": ["transcription"]
                  }
                }
                """.trimIndent()
            )
        )!!

        assertEquals(setOf(ModelCategory.EMBEDDINGS), embeddings.categories)
        assertEquals(setOf(ModelCategory.TRANSCRIPTION), transcription.categories)
    }

    @Test
    fun detectsKnownVariantsWithoutTreatingStandardAsVariant() {
        assertEquals(
            setOf(ModelVariant.FREE, ModelVariant.ONLINE),
            OpenRouterModelCatalog.variants("vendor/model:free:online")
        )
        assertEquals(
            setOf(ModelVariant.STANDARD),
            OpenRouterModelCatalog.variants("vendor/model")
        )
    }

    @Test
    fun mergeKeepsRicherMetadata() {
        val first = OpenRouterModelCatalog.parse(
            JsonParser.parseString(
                """{"id":"vendor/model","architecture":{"input_modalities":["text"],"output_modalities":["text"]}}"""
            )
        )!!
        val second = OpenRouterModelCatalog.parse(
            JsonParser.parseString(
                """{"id":"vendor/model","architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},"supported_parameters":["tools"],"context_length":200000}"""
            )
        )!!

        val merged = OpenRouterModelCatalog.merge(first, second)
        assertTrue(merged.accepts("image"))
        assertTrue(merged.supportsTools)
        assertEquals(200000, merged.contextLength)
    }
}
