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
}
