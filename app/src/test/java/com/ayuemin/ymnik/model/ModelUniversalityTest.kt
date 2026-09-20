package com.ayuemin.ymnik.model

import org.junit.Assert.assertTrue
import org.junit.Test

class ModelUniversalityTest {
    @Test
    fun broaderCapabilitiesProduceHigherUniversalityScore() {
        val textOnly = ModelInfo(
            id = "vendor/text",
            inputModalities = setOf("text"),
            outputModalities = setOf("text"),
            contextLength = 32768,
            maxCompletionTokens = 4096
        )
        val omni = ModelInfo(
            id = "vendor/omni",
            inputModalities = setOf("text", "image", "audio", "video"),
            outputModalities = setOf("text", "image", "audio", "video"),
            supportedParameters = setOf(
                "reasoning",
                "reasoning_effort",
                "tools",
                "response_format",
                "web_search",
                "seed",
                "logprobs"
            ),
            supportsStreaming = true,
            contextLength = 1_000_000,
            maxCompletionTokens = 65_536,
            capabilityValues = mapOf(
                "resolutions" to listOf("1K", "2K"),
                "aspect_ratios" to listOf("1:1", "16:9")
            ),
            capabilityFlags = mapOf("generate_audio" to true),
            allowedPassthroughParameters = setOf("foo", "bar")
        )

        val simpleScore = ModelUniversality.score(textOnly)
        val omniScore = ModelUniversality.score(omni)

        assertTrue(omniScore.total > simpleScore.total)
        assertTrue(omniScore.total in 0..100)
        assertTrue(simpleScore.total in 0..100)
    }

    @Test
    fun scoreIsBreadthNotQuality() {
        val specializedImage = ModelInfo(
            id = "vendor/image",
            inputModalities = setOf("text"),
            outputModalities = setOf("image"),
            supportedParameters = setOf("seed")
        )
        val score = ModelUniversality.score(specializedImage)

        assertTrue(score.total < 100)
        assertTrue(score.outputBreadth > 0)
    }
    @Test
    fun fullCapabilitySurfaceDefinesOneHundred() {
        val full = ModelInfo(
            id = "vendor/everything",
            inputModalities = setOf("text", "image", "audio", "video", "file"),
            outputModalities = setOf(
                "text", "image", "audio", "video",
                "transcription", "embeddings", "rerank"
            ),
            supportedParameters = setOf(
                "reasoning", "reasoning_effort",
                "tools", "tool_choice",
                "response_format", "web_search",
                "temperature", "top_p",
                "seed", "logprobs",
                "max_tokens", "frequency_penalty",
                "image_size", "image_quality"
            ),
            supportsStreaming = true,
            contextLength = 1_000_000,
            maxCompletionTokens = 65_536,
            capabilityValues = mapOf(
                "resolutions" to listOf("1K", "2K", "4K"),
                "aspect_ratios" to listOf("1:1", "16:9"),
                "durations" to listOf("5", "10"),
                "frame_images" to listOf("first", "last")
            ),
            capabilityFlags = mapOf(
                "generate_audio" to true,
                "seed" to true
            ),
            allowedPassthroughParameters = setOf("foo", "bar")
        )

        val score = ModelUniversality.score(full)

        assertTrue(score.total == 100)
        assertTrue(score.inputBreadth == 20)
        assertTrue(score.outputBreadth == 25)
        assertTrue(score.generalCapabilities == 30)
        assertTrue(score.capacity == 15)
        assertTrue(score.specializedCapabilities == 10)
    }

}
