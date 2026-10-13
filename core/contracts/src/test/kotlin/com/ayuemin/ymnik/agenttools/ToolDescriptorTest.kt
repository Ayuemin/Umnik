package com.ayuemin.ymnik.agenttools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolDescriptorTest {
    @Test fun metadataDefaultsDoNotGrantPermission() {
        val descriptor = ToolDescriptor(
            id = "local_browser",
            label = "Browser",
            version = 1,
            actions = setOf(ToolAction.READ),
            resourceKinds = setOf(ToolResourceKind.WEB_ORIGIN)
        )
        assertEquals(1, descriptor.maxConcurrentCalls)
        assertEquals(120_000L, descriptor.timeoutMillis)
        assertEquals(emptySet<ToolAction>(), descriptor.confirmActions)
    }

    @Test fun invalidConfirmationAndLimitsRemainRejected() {
        val descriptor = ToolDescriptor(
            id = "local_browser",
            label = "Browser",
            version = 1,
            actions = setOf(ToolAction.READ),
            resourceKinds = setOf(ToolResourceKind.WEB_ORIGIN)
        )
        assertThrows(IllegalArgumentException::class.java) {
            descriptor.copy(confirmActions = setOf(ToolAction.DELETE))
        }
        assertThrows(IllegalArgumentException::class.java) {
            descriptor.copy(maxConcurrentCalls = 9)
        }
        assertThrows(IllegalArgumentException::class.java) {
            descriptor.copy(timeoutMillis = 999L)
        }
    }
}
