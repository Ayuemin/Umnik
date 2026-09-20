package com.ayuemin.ymnik.model

data class ModelUniversalityScore(
    val total: Int,
    val inputBreadth: Int,
    val outputBreadth: Int,
    val generalCapabilities: Int,
    val capacity: Int,
    val specializedCapabilities: Int
)

object ModelUniversality {
    fun score(model: ModelInfo): ModelUniversalityScore {
        val input = (
            (if (model.accepts("text")) 2 else 0) +
            (if (model.accepts("image")) 6 else 0) +
            (if (model.accepts("audio")) 5 else 0) +
            (if (model.accepts("video")) 5 else 0) +
            (if (model.accepts("file") || model.accepts("pdf")) 2 else 0)
        ).coerceAtMost(20)

        val output = (
            (if (model.outputs("text")) 3 else 0) +
            (if (model.outputs("image")) 8 else 0) +
            (if (model.outputs("audio") || model.outputs("speech")) 5 else 0) +
            (if (model.outputs("video")) 7 else 0) +
            (if (model.outputs("transcription")) 2 else 0) +
            (if (model.outputs("embeddings") || model.outputs("embedding")) 2 else 0) +
            (if (model.outputs("rerank") || model.outputs("ranking")) 2 else 0)
        ).coerceAtMost(25)

        val parameters = model.supportedParameters.map(String::lowercase).toSet()
        val general = (
            (if (model.supportsReasoning) 6 else 0) +
            (if (model.supportsReasoningEffort) 2 else 0) +
            (if (model.supportsTools) 6 else 0) +
            (if ("response_format" in parameters || "structured_outputs" in parameters) 4 else 0) +
            (if ("web_search" in parameters || ModelVariant.ONLINE in model.variants) 3 else 0) +
            (if (model.supportsStreaming == true) 2 else 0) +
            (if ("seed" in parameters) 1 else 0) +
            (if ("logprobs" in parameters) 1 else 0)
        ).coerceAtMost(25)

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

        val specialized = (
            model.capabilityValues.keys.size.coerceAtMost(5) +
            model.capabilityFlags.values.count { it }.coerceAtMost(3) +
            model.allowedPassthroughParameters.size.coerceAtMost(3) +
            parameters.count {
                it !in setOf(
                    "reasoning", "reasoning_effort", "tools", "tool_choice",
                    "response_format", "structured_outputs", "web_search",
                    "temperature", "top_p", "top_k", "min_p", "max_tokens",
                    "seed", "logprobs"
                )
            }.coerceAtMost(4)
        ).coerceAtMost(15)

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
