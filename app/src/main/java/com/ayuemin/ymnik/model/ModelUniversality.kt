package com.ayuemin.ymnik.model

data class ModelUniversalityScore(
    val total: Int,
    val inputBreadth: Int,
    val outputBreadth: Int,
    val generalCapabilities: Int,
    val capacity: Int,
    val specializedCapabilities: Int
)

/**
 * Measures breadth of functionality exposed for a model by OpenRouter.
 *
 * This is deliberately NOT a quality, intelligence, benchmark, speed or value score.
 * 100/100 means "covers essentially the full capability surface Umnik can currently
 * describe", not "best model". The scale is absolute and does not depend on other models.
 */
object ModelUniversality {
    fun score(model: ModelInfo): ModelUniversalityScore {
        // 20 points: how many useful kinds of input the same model can consume.
        val input = (
            (if (model.accepts("text")) 2 else 0) +
            (if (model.accepts("image")) 5 else 0) +
            (if (model.accepts("audio")) 5 else 0) +
            (if (model.accepts("video")) 5 else 0) +
            (if (model.accepts("file") || model.accepts("pdf")) 3 else 0)
        ).coerceAtMost(20)

        // 25 points: how many useful kinds of output the same model can produce.
        val output = (
            (if (model.outputs("text")) 3 else 0) +
            (if (model.outputs("image")) 6 else 0) +
            (if (model.outputs("audio") || model.outputs("speech")) 5 else 0) +
            (if (model.outputs("video")) 6 else 0) +
            (if (model.outputs("transcription")) 2 else 0) +
            (if (model.outputs("embeddings") || model.outputs("embedding")) 1 else 0) +
            (if (model.outputs("rerank") || model.outputs("ranking")) 2 else 0)
        ).coerceAtMost(25)

        // 30 points: generally useful API features. These are capabilities, not quality signals.
        val parameters = model.supportedParameters.map(String::lowercase).toSet()
        val hasStructuredOutput = "response_format" in parameters || "structured_outputs" in parameters
        val hasWebSearch = "web_search" in parameters || ModelVariant.ONLINE in model.variants
        val hasSamplingControls = parameters.any { it in setOf("temperature", "top_p", "top_k", "min_p") }
        val hasToolChoice = "tool_choice" in parameters
        val hasStopControl = "stop" in parameters || "max_tokens" in parameters
        val hasPenaltyControls = parameters.any {
            it in setOf("frequency_penalty", "presence_penalty", "repetition_penalty")
        }
        val general = (
            (if (model.supportsReasoning) 6 else 0) +
            (if (model.supportsReasoningEffort) 2 else 0) +
            (if (model.supportsTools) 6 else 0) +
            (if (hasStructuredOutput) 4 else 0) +
            (if (hasWebSearch) 3 else 0) +
            (if (model.supportsStreaming == true) 2 else 0) +
            (if ("seed" in parameters) 1 else 0) +
            (if ("logprobs" in parameters) 1 else 0) +
            (if (hasSamplingControls) 2 else 0) +
            (if (hasToolChoice) 1 else 0) +
            (if (hasStopControl) 1 else 0) +
            (if (hasPenaltyControls) 1 else 0)
        ).coerceAtMost(30)

        // 15 points: practical working limits, not intelligence.
        val context = maxOf(model.contextLength ?: 0, model.topProviderContextLength ?: 0)
        val contextPoints = when {
            context >= 1_000_000 -> 10
            context >= 256_000 -> 8
            context >= 128_000 -> 6
            context >= 64_000 -> 4
            context >= 32_000 -> 2
            else -> 0
        }
        val completion = model.maxCompletionTokens ?: 0
        val completionPoints = when {
            completion >= 65_536 -> 5
            completion >= 16_384 -> 4
            completion >= 8_192 -> 3
            completion >= 4_096 -> 2
            completion > 0 -> 1
            else -> 0
        }
        val capacity = (contextPoints + completionPoints).coerceAtMost(15)

        // 10 points: media-generator controls and less common OpenRouter/provider capabilities.
        val commonParameters = setOf(
            "reasoning", "reasoning_effort", "tools", "tool_choice",
            "response_format", "structured_outputs", "web_search",
            "temperature", "top_p", "top_k", "min_p", "max_tokens", "stop",
            "seed", "logprobs", "frequency_penalty", "presence_penalty", "repetition_penalty"
        )
        val specialized = (
            model.capabilityValues.keys.size.coerceAtMost(4) +
            model.capabilityFlags.values.count { it }.coerceAtMost(2) +
            model.allowedPassthroughParameters.size.coerceAtMost(2) +
            parameters.count { it !in commonParameters }.coerceAtMost(2)
        ).coerceAtMost(10)

        return ModelUniversalityScore(
            total = (input + output + general + capacity + specialized).coerceIn(0, 100),
            inputBreadth = input,
            outputBreadth = output,
            generalCapabilities = general,
            capacity = capacity,
            specializedCapabilities = specialized
        )
    }
}
