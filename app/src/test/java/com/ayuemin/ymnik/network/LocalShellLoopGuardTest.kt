package com.ayuemin.ymnik.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalShellLoopGuardTest {
    @Test
    fun fourIdenticalActionStatesTriggerRecoveryWarning() {
        val guard = LocalShellLoopGuard()
        repeat(3) { assertNull(guard.observe("read:a", "same")) }
        val decision = guard.observe("read:a", "same")
        requireNotNull(decision)
        assertEquals(1, decision.patternSize)
        assertEquals(1, decision.strike)
        assertFalse(decision.shouldStop)
    }

    @Test
    fun sameActionWithChangingStateIsProgress() {
        val guard = LocalShellLoopGuard()
        repeat(12) { index ->
            assertNull(guard.observe("read:a", "state-$index"))
        }
    }

    @Test
    fun alternatingPatternRepeatedThreeTimesIsDetected() {
        val guard = LocalShellLoopGuard()
        val sequence = listOf("A", "B", "A", "B", "A", "B")
        var decision: LocalShellLoopDecision? = null
        sequence.forEach { action -> decision = guard.observe(action, "unchanged-$action") ?: decision }
        requireNotNull(decision)
        assertEquals(2, decision!!.patternSize)
        assertFalse(decision!!.shouldStop)
    }

    @Test
    fun secondLoopBeforeProgressStopsRun() {
        val guard = LocalShellLoopGuard()
        repeat(4) { guard.observe("read:a", "same") }
        var second: LocalShellLoopDecision? = null
        repeat(4) { second = guard.observe("read:a", "same") ?: second }
        requireNotNull(second)
        assertEquals(2, second!!.strike)
        assertTrue(second!!.shouldStop)
    }

    @Test
    fun sustainedProgressForgetsOldStrike() {
        val guard = LocalShellLoopGuard(resetAfterProgress = 3)
        repeat(4) { guard.observe("read:a", "same") }
        repeat(3) { index -> assertNull(guard.observe("progress-$index", "state-$index")) }
        var later: LocalShellLoopDecision? = null
        repeat(4) { later = guard.observe("read:b", "same-b") ?: later }
        requireNotNull(later)
        assertEquals(1, later!!.strike)
        assertFalse(later!!.shouldStop)
    }
}
