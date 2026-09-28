package com.ayuemin.ymnik

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestChildCancellationRegistryTest {
    @Test
    fun cancelAll_cancelsRegisteredChildrenOnlyOnce() {
        val registry = RequestChildCancellationRegistry()
        var firstCancelled = 0
        var secondCancelled = 0

        assertTrue(registry.register("first") { firstCancelled += 1 })
        assertTrue(registry.register("second") { secondCancelled += 1 })

        registry.cancelAll()
        registry.cancelAll()

        assertTrue(registry.isCancelled())
        assertTrue(firstCancelled == 1)
        assertTrue(secondCancelled == 1)
    }

    @Test
    fun unregister_detachesCompletedChild() {
        val registry = RequestChildCancellationRegistry()
        var cancelled = false

        assertTrue(registry.register("done") { cancelled = true })
        registry.unregister("done")
        registry.cancelAll()

        assertFalse(cancelled)
    }

    @Test
    fun registerAfterCancellation_isRejectedAndCancelledImmediately() {
        val registry = RequestChildCancellationRegistry()
        registry.cancelAll()
        var cancelled = false

        assertFalse(registry.register("late") { cancelled = true })

        assertTrue(cancelled)
    }
}
