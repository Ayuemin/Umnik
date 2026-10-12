package com.ayuemin.ymnik.model

/** Counted usage for completed OpenRouter calls. Null = unknown, never zero. */
data class TokenUsageStats(
    val inputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoningTokens: Long? = null,
    val observedCalls: Int = 0
)
