package com.ayuemin.ymnik.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class KnowledgeToolBudgetTest {
    @Test
    fun zeroDisablesAutonomousSearch() = runBlocking {
        val budget = KnowledgeToolBudget(0)
        expectLimitFailure { budget.execute("мотивация") { "result" } }
        assertEquals(0, budget.usedCalls)
    }

    @Test
    fun uniqueQueriesConsumeConfiguredLimit() = runBlocking {
        val budget = KnowledgeToolBudget(2)
        assertEquals("a", budget.execute("тема 1") { "a" })
        assertEquals("b", budget.execute("тема 2") { "b" })
        assertEquals(2, budget.usedCalls)
        expectLimitFailure { budget.execute("тема 3") { "c" } }
    }

    @Test
    fun repeatedQueryUsesCacheWithoutSpendingLimit() = runBlocking {
        val budget = KnowledgeToolBudget(1)
        var loads = 0
        val first = budget.execute("Внимание") { loads += 1; "answer" }
        val second = budget.execute("  внимание  ") { loads += 1; "other" }
        assertEquals("answer", first)
        assertEquals("answer", second)
        assertEquals(1, loads)
        assertEquals(1, budget.usedCalls)
    }

    @Test
    fun configuredTenReallyAllowsTenUniqueSearches() = runBlocking {
        val budget = KnowledgeToolBudget(10)
        repeat(10) { index ->
            budget.execute("query-$index") { "result-$index" }
        }
        assertEquals(10, budget.usedCalls)
        expectLimitFailure { budget.execute("query-11") { "overflow" } }
    }

    @Test
    fun limitIsClampedToSafetyRange() {
        assertEquals(0, KnowledgeToolBudget(-5).limit)
        assertEquals(10, KnowledgeToolBudget(99).limit)
    }

    private suspend fun expectLimitFailure(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
