package com.ayuemin.ymnik.network

internal data class AgentToolLoopDecision(
    val patternSize: Int,
    val strike: Int,
    val shouldStop: Boolean
)

/**
 * Generic no-progress guard for the top-level model/tool loop.
 *
 * Browser and Local Shell have their own domain-specific guards. This one protects the
 * parent agent loop itself, where repeated tool batches could otherwise keep causing paid
 * model completions even when no useful state changes.
 *
 * actionKey should describe the normalized tool call/batch. stateKey should fingerprint the
 * normalized result/progress observed after that action. Repeating a tool while state changes
 * is therefore treated as progress, not a loop.
 */
internal class AgentToolLoopGuard(
    private val exactRepeatThreshold: Int = 3,
    private val maxTrace: Int = 36,
    private val resetAfterProgress: Int = 8
) {
    private val trace = mutableListOf<String>()
    private var strikes = 0
    private var progressSinceLoop = 0

    fun observe(actionKey: String, stateKey: String): AgentToolLoopDecision? {
        trace += "$actionKey|$stateKey"
        while (trace.size > maxTrace.coerceAtLeast(12)) trace.removeAt(0)

        val patternSize = repeatedPattern()
        if (patternSize == null) {
            if (strikes > 0) {
                progressSinceLoop += 1
                if (progressSinceLoop >= resetAfterProgress.coerceAtLeast(1)) {
                    strikes = 0
                    progressSinceLoop = 0
                }
            }
            return null
        }

        strikes += 1
        progressSinceLoop = 0
        trace.clear()
        return AgentToolLoopDecision(
            patternSize = patternSize,
            strike = strikes,
            shouldStop = strikes >= 2
        )
    }

    fun reset() {
        trace.clear()
        strikes = 0
        progressSinceLoop = 0
    }

    private fun repeatedPattern(): Int? {
        val exactThreshold = exactRepeatThreshold.coerceAtLeast(2)
        if (trace.size >= exactThreshold) {
            val tail = trace.takeLast(exactThreshold)
            if (tail.distinct().size == 1) return 1
        }

        for (patternSize in 2..4) {
            val repeats = 3
            val needed = patternSize * repeats
            if (trace.size < needed) continue
            val tail = trace.takeLast(needed)
            val pattern = tail.take(patternSize)
            if ((1 until repeats).all { repeatIndex ->
                    val from = repeatIndex * patternSize
                    tail.subList(from, from + patternSize) == pattern
                }
            ) return patternSize
        }
        return null
    }
}
