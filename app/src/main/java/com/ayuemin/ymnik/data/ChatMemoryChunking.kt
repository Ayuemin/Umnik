package com.ayuemin.ymnik.data

import com.ayuemin.ymnik.model.ChatMemoryGlobalSettings
import com.ayuemin.ymnik.network.ConversationContext
import kotlin.math.floor

/**
 * Plans retrieval chunks independently from a provider tokenizer.
 * ConversationContext.estimateTokens() is deliberately conservative and we keep
 * additional headroom below the embedding model's advertised context window.
 */
internal object ChatMemoryChunking {
    data class Plan(
        val targetTokens: Int,
        val overlapTokens: Int,
        val modelContextTokens: Int?
    )

    fun plan(settings: ChatMemoryGlobalSettings): Plan {
        val requested = settings.chunkTokens.coerceIn(128, 4_000)
        val modelLimit = settings.embeddingContextTokens?.takeIf { it >= 128 }
        val safeModelBudget = modelLimit
            ?.let { floor(it * 0.75).toInt().coerceAtLeast(96) }
        val target = minOf(requested, safeModelBudget ?: requested).coerceAtLeast(96)
        val overlap = settings.chunkOverlapTokens.coerceIn(0, (target / 3).coerceAtLeast(0))
        return Plan(target, overlap, modelLimit)
    }

    fun clipQuery(text: String, plan: Plan): String = clipToBudget(text.trim(), plan.targetTokens)

    fun split(text: String, plan: Plan): List<String> {
        val clean = text.trim()
        if (clean.isBlank()) return emptyList()
        if (ConversationContext.estimateTokens(clean) <= plan.targetTokens) return listOf(clean)

        val atomic = buildAtomicPieces(clean, plan.targetTokens)
        if (atomic.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        var current = mutableListOf<String>()

        fun currentText(): String = current.joinToString("\n\n").trim()
        fun flushAndSeedOverlap() {
            val value = currentText()
            if (value.isNotBlank()) result += value
            current = if (plan.overlapTokens > 0 && value.isNotBlank()) {
                val tail = tailToBudget(value, plan.overlapTokens)
                if (tail.isBlank()) mutableListOf() else mutableListOf(tail)
            } else {
                mutableListOf()
            }
        }

        atomic.forEach { piece ->
            if (current.isEmpty()) {
                current += piece
            } else {
                val candidate = (current + piece).joinToString("\n\n")
                if (ConversationContext.estimateTokens(candidate) <= plan.targetTokens) {
                    current += piece
                } else {
                    flushAndSeedOverlap()
                    val withOverlap = (current + piece).joinToString("\n\n")
                    if (current.isNotEmpty() && ConversationContext.estimateTokens(withOverlap) > plan.targetTokens) {
                        current.clear()
                    }
                    current += piece
                }
            }
        }
        val last = currentText()
        if (last.isNotBlank() && result.lastOrNull() != last) result += last
        return result.distinct().filter { ConversationContext.estimateTokens(it) <= plan.targetTokens }
    }

    private fun buildAtomicPieces(text: String, targetTokens: Int): List<String> {
        val rough = text
            .split(Regex("(?<=[.!?…])\\s+|\\n{2,}"))
            .map(String::trim)
            .filter(String::isNotBlank)
        return rough.flatMap { piece ->
            if (ConversationContext.estimateTokens(piece) <= targetTokens) listOf(piece)
            else hardSplit(piece, targetTokens)
        }
    }

    private fun hardSplit(text: String, targetTokens: Int): List<String> {
        val result = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.isNotBlank()) {
            if (ConversationContext.estimateTokens(remaining) <= targetTokens) {
                result += remaining
                break
            }
            var low = 1
            var high = remaining.length
            var best = 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (ConversationContext.estimateTokens(remaining.substring(0, mid)) <= targetTokens) {
                    best = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            val preferred = remaining.lastIndexOfAny(
                charArrayOf('\n', ' ', '.', ',', ';', ':', ')'),
                startIndex = (best - 1).coerceAtLeast(0)
            )
            val cut = if (preferred >= best / 2) preferred + 1 else best
            val part = remaining.substring(0, cut).trim()
            if (part.isNotBlank()) result += part
            remaining = remaining.substring(cut).trimStart()
        }
        return result
    }

    private fun clipToBudget(text: String, targetTokens: Int): String {
        if (text.isBlank() || ConversationContext.estimateTokens(text) <= targetTokens) return text
        var low = 1
        var high = text.length
        var best = 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (ConversationContext.estimateTokens(text.substring(0, mid)) <= targetTokens) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        val preferred = text.lastIndexOfAny(
            charArrayOf('\n', ' ', '.', ',', ';', ':'),
            startIndex = (best - 1).coerceAtLeast(0)
        )
        val cut = if (preferred >= best / 2) preferred + 1 else best
        return text.substring(0, cut).trim()
    }

    private fun tailToBudget(text: String, targetTokens: Int): String {
        if (targetTokens <= 0) return ""
        if (ConversationContext.estimateTokens(text) <= targetTokens) return text
        var low = 0
        var high = text.length - 1
        var bestStart = text.length - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val candidate = text.substring(mid)
            if (ConversationContext.estimateTokens(candidate) <= targetTokens) {
                bestStart = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        val nextBoundary = text.indexOfAny(charArrayOf(' ', '\n'), startIndex = bestStart)
        val start = if (nextBoundary in bestStart until text.length - 1) nextBoundary + 1 else bestStart
        return text.substring(start).trim()
    }
}
