package com.ayuemin.ymnik.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemTaskPlannerTest {
    @Test
    fun parsesNormalPlanFromPlainJson() {
        val plan = parseSystemKnowledgePlan(
            """{"mode":"normal","search_query":"почему человек теряет мотивацию к работе"}"""
        )

        assertFalse(plan.baseOnly)
        assertEquals("почему человек теряет мотивацию к работе", plan.searchQuery)
    }

    @Test
    fun parsesBaseOnlyPlanFromMarkdownFence() {
        val plan = parseSystemKnowledgePlan(
            """
            ```json
            {"mode":"base_only","search_query":"что в книге говорится о страхе перемен"}
            ```
            """.trimIndent()
        )

        assertTrue(plan.baseOnly)
        assertEquals("что в книге говорится о страхе перемен", plan.searchQuery)
    }

    @Test
    fun parsesShellWatchdogWaitDecision() {
        val decision = parseShellWatchdogDecision(
            """{"action":"wait","reason":"Команда может законно выполняться дольше"}"""
        )

        assertEquals(ShellWatchdogAction.WAIT, decision.action)
        assertEquals("Команда может законно выполняться дольше", decision.reason)
    }

    @Test
    fun parsesShellWatchdogCheckDecisionFromFence() {
        val decision = parseShellWatchdogDecision(
            """
            ```json
            {"action":"check","reason":"Тот же Shell-этап молчит слишком долго"}
            ```
            """.trimIndent()
        )

        assertEquals(ShellWatchdogAction.CHECK, decision.action)
        assertEquals("Тот же Shell-этап молчит слишком долго", decision.reason)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankSearchQuery() {
        parseSystemKnowledgePlan("""{"mode":"normal","search_query":""}""")
    }
}
