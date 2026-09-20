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
    @Test
    fun parsesCurrentReasoningEffortsIncludingMax() {
        val info = OpenRouterModelCatalog.parse(
            JsonParser.parseString(
                """
                {
                  "id": "z-ai/glm-5.3",
                  "architecture": {"input_modalities":["text"],"output_modalities":["text"]},
                  "supported_parameters": ["reasoning", "reasoning_effort", "tools"],
                  "reasoning": {
                    "mandatory": false,
                    "default_enabled": true,
                    "supported_efforts": ["max", "high", "low"]
                  }
                }
                """.trimIndent()
            )
        )!!

        assertTrue(info.supportsReasoning)
        assertTrue(info.supportsReasoningEffort)
        assertEquals(setOf("max", "high", "low"), info.reasoningEfforts)
    }

    @Test
    fun parsesRichCatalogMetadataWithoutDroppingRawPayload() {
        val info = OpenRouterModelCatalog.parse(
            JsonParser.parseString(
                """
                {
                  "id": "vendor/omni",
                  "name": "Omni",
                  "description": "A rich multimodal model",
                  "canonical_slug": "vendor/omni-2026",
                  "hugging_face_id": "vendor/omni-hf",
                  "created": 1789900000,
                  "architecture": {
                    "modality": "text+image->text+image",
                    "input_modalities": ["text","image"],
                    "output_modalities": ["text","image"],
                    "tokenizer": "OmniTokenizer",
                    "instruct_type": "chatml"
                  },
                  "supported_parameters": {
                    "temperature": {"type":"number","min":0,"max":2},
                    "tools": {"type":"array"},
                    "response_format": {"type":"string","values":["json","text"]}
                  },
                  "supports_streaming": true,
                  "context_length": 262144,
                  "top_provider": {
                    "context_length": 200000,
                    "max_completion_tokens": 65536,
                    "is_moderated": false
                  },
                  "pricing": {
                    "prompt": "0.000001",
                    "completion": "0.000002",
                    "web_search": "0.01"
                  },
                  "supported_resolutions": ["1K","2K"],
                  "supported_aspect_ratios": ["1:1","16:9"],
                  "allowed_passthrough_parameters": ["foo","bar"]
                }
                """.trimIndent()
            )
        )!!

        assertEquals("Omni", info.name)
        assertEquals("vendor", info.providerId)
        assertTrue(info.isMultimodalChat)
        assertTrue(info.supportsStreaming == true)
        assertEquals(262144, info.contextLength)
        assertEquals(65536, info.maxCompletionTokens)
        assertEquals(2.0, info.parameterCapabilities["temperature"]?.max ?: 0.0, 0.000001)
        assertEquals(listOf("json", "text"), info.parameterCapabilities["response_format"]?.values)
        assertEquals(listOf("1K", "2K"), info.capabilityValues["resolutions"])
        assertEquals(setOf("foo", "bar"), info.allowedPassthroughParameters)
        assertTrue(info.pricingUsd.containsKey("web_search"))
        assertTrue(info.rawOpenRouterMetadata.single().contains("\"canonical_slug\""))
    }

}
