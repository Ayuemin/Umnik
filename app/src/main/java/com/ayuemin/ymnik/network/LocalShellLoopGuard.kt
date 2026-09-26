package com.ayuemin.ymnik.network

internal data class LocalShellLoopDecision(
    val patternSize: Int,
    val strike: Int,
    val shouldStop: Boolean
)

/**
 * Detects repeated Local Shell action+observed-state patterns.
 *
 * A repeated tool name alone is not a loop: callers include normalized arguments and
 * a fingerprint of the actual tool result. The first detected loop asks the model to
 * re-plan. A second loop before sustained progress stops the run. After enough distinct
 * progress the old strike is forgotten so two unrelated dead ends do not kill a long job.
 */
internal class LocalShellLoopGuard(
    private val maxTrace: Int = 24,
    private val resetAfterProgress: Int = 20
) {
    private val trace = mutableListOf<String>()
    private var strikes = 0
    private var progressSinceLoop = 0

    fun observe(actionKey: String, stateKey: String): LocalShellLoopDecision? {
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
        return LocalShellLoopDecision(
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
        if (trace.size >= 4) {
            val tail = trace.takeLast(4)
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
