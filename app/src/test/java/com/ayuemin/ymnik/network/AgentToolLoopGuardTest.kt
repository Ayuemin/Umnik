package com.ayuemin.ymnik.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolLoopGuardTest {
    @Test
    fun `changing state is progress even for same action`() {
        val guard = AgentToolLoopGuard()
        repeat(12) { index ->
            assertNull(guard.observe("local_browser_read:{}", "page-$index"))
        }
    }

    @Test
    fun `first exact no-progress loop warns and second stops`() {
        val guard = AgentToolLoopGuard()

        assertNull(guard.observe("local_shell_status:{}", "same"))
        assertNull(guard.observe("local_shell_status:{}", "same"))
        val first = guard.observe("local_shell_status:{}", "same")
        assertTrue(first != null)
        assertFalse(first!!.shouldStop)

        assertNull(guard.observe("local_shell_status:{}", "same"))
        assertNull(guard.observe("local_shell_status:{}", "same"))
        val second = guard.observe("local_shell_status:{}", "same")
        assertTrue(second != null)
        assertTrue(second!!.shouldStop)
    }

    @Test
    fun `repeating multi-action pattern is detected`() {
        val guard = AgentToolLoopGuard()
        val pattern = listOf(
            "local_list:{}" to "empty",
            "local_python:{scan}" to "not-found"
        )

        repeat(2) {
            pattern.forEach { (action, state) -> assertNull(guard.observe(action, state)) }
        }
        assertNull(guard.observe(pattern[0].first, pattern[0].second))
        val decision = guard.observe(pattern[1].first, pattern[1].second)
        assertTrue(decision != null)
        assertTrue(decision!!.patternSize == 2)
        assertFalse(decision.shouldStop)
    }

    @Test
    fun `sustained progress clears old strike`() {
        val guard = AgentToolLoopGuard(resetAfterProgress = 4)

        repeat(3) { guard.observe("same", "same") }
        repeat(4) { index -> assertNull(guard.observe("progress", "state-$index")) }

        repeat(2) { assertNull(guard.observe("same", "same")) }
        val decision = guard.observe("same", "same")
        assertTrue(decision != null)
        assertFalse(decision!!.shouldStop)
    }
}
