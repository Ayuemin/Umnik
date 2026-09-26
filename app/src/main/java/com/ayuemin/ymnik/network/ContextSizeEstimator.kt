package com.ayuemin.ymnik.network

import java.nio.charset.StandardCharsets

/**
 * Deterministic size information for one request-context layer.
 *
 * chars and utf8Bytes are exact. estimatedTokens is deliberately approximate: OpenRouter
 * can route to models with different tokenizers, so the app must never present it as an
 * authoritative provider token count.
 */
internal data class ContextLayerSize(
    val chars: Int,
    val utf8Bytes: Int,
    val estimatedTokens: Int
)

internal object ContextSizeEstimator {
    /**
     * A tokenizer-independent display estimate. Four UTF-8 bytes per token is only a rough
     * cross-model heuristic, but it is stable enough to compare prompt layers before/after
     * local changes. Provider-reported input_tokens remains authoritative for total usage.
     */
    fun measure(text: String): ContextLayerSize {
        val bytes = text.toByteArray(StandardCharsets.UTF_8).size
        return ContextLayerSize(
            chars = text.length,
            utf8Bytes = bytes,
            estimatedTokens = if (bytes == 0) 0 else (bytes + 3) / 4
        )
    }
}
