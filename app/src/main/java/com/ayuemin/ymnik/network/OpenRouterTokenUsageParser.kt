package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.TokenUsageStats
import com.google.gson.JsonObject

/** OpenAI-compatible usage: cached is part of input; reasoning part of output. */
internal object OpenRouterTokenUsageParser {
    fun parse(usage: JsonObject?): TokenUsageStats? {
        if (usage == null) return null
        fun JsonObject?.nonNegative(name: String): Long? = runCatching {
            this?.get(name)?.takeUnless { it.isJsonNull }?.asLong?.takeIf { it >= 0L }
        }.getOrNull()
        val i = usage.getAsJsonObject("prompt_tokens_details")
            ?: usage.getAsJsonObject("input_tokens_details")
        val o = usage.getAsJsonObject("completion_tokens_details")
            ?: usage.getAsJsonObject("output_tokens_details")
        val result = TokenUsageStats(
            inputTokens = usage.nonNegative("prompt_tokens") ?: usage.nonNegative("input_tokens"),
            cachedInputTokens = i.nonNegative("cached_tokens") ?: usage.nonNegative("cached_tokens"),
            cacheWriteTokens = i.nonNegative("cache_write_tokens")
                ?: i.nonNegative("cache_creation_input_tokens")
                ?: usage.nonNegative("cache_write_tokens"),
            outputTokens = usage.nonNegative("completion_tokens") ?: usage.nonNegative("output_tokens"),
            reasoningTokens = o.nonNegative("reasoning_tokens"),
            observedCalls = 1
        )
        return result.takeIf {
            it.inputTokens != null || it.cachedInputTokens != null || it.cacheWriteTokens != null ||
                it.outputTokens != null || it.reasoningTokens != null
        }
    }
}

/** Thread-safe, request-local accounting without inventing cache hits. */
internal class OpenRouterTokenUsageLedger {
    private val lock = Any()
    private var calls = 0
    private var input: Long? = null
    private var cached: Long? = null
    private var writes: Long? = null
    private var output: Long? = null
    private var reasoning: Long? = null

    fun record(usage: TokenUsageStats?) {
        if (usage == null) return
        synchronized(lock) {
            calls += 1
            fun sum(previous: Long?, current: Long?): Long? {
                if (current == null) return previous
                val base = previous ?: 0L
                return if (Long.MAX_VALUE - base < current) Long.MAX_VALUE else base + current
            }
            input = sum(input, usage.inputTokens)
            cached = sum(cached, usage.cachedInputTokens)
            writes = sum(writes, usage.cacheWriteTokens)
            output = sum(output, usage.outputTokens)
            reasoning = sum(reasoning, usage.reasoningTokens)
        }
    }

    fun snapshot(): TokenUsageStats? = synchronized(lock) {
        if (calls == 0) null else TokenUsageStats(input, cached, writes, output, reasoning, calls)
    }
}
