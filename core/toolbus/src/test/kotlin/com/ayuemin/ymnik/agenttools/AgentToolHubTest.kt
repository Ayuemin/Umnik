package com.ayuemin.ymnik.agenttools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolHubTest {
    private fun adapter(
        id: String,
        actions: Set<ToolAction> = setOf(ToolAction.READ),
        resources: Set<ToolResourceKind> = setOf(ToolResourceKind.WEB_ORIGIN)
    ): ToolAdapter = object : ToolAdapter {
        override val descriptor = ToolDescriptor(
            id = id,
            label = "Mock $id",
            version = 1,
            actions = actions,
            resourceKinds = resources
        )
    }

    @Test fun registryStartsClosedAndEmpty() {
        val hub = AgentToolHub(ToolRegistry())
        assertTrue(hub.availableTools().isEmpty())
        assertFalse(hub.hasTool("local_browser"))
    }

    @Test fun registeredToolsAreDiscoverableInStableOrder() {
        val registry = ToolRegistry()
        registry.register(adapter("local_shell", resources = setOf(ToolResourceKind.LOCAL_FILE)))
        registry.register(adapter("local_browser"))
        val hub = AgentToolHub(registry)

        assertEquals(listOf("local_browser", "local_shell"), hub.availableTools().map { it.id })
        assertTrue(hub.hasTool("local_shell"))
        assertFalse(hub.hasTool("remote_ssh"))
    }

    @Test fun duplicateRegistrationFailsWithoutOverwritingDescriptor() {
        val registry = ToolRegistry()
        registry.register(adapter("local_browser"))
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(adapter("local_browser", actions = setOf(ToolAction.DELETE)))
        }
        assertEquals(setOf(ToolAction.READ), registry.descriptors().single().actions)
    }

    @Test fun invalidToolMetadataIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            adapter("BAD_ID")
        }
        assertThrows(IllegalArgumentException::class.java) {
            adapter("empty_actions", actions = emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ToolDescriptor(
                id = "unsafe_tool",
                label = "Unsafe",
                version = 1,
                actions = setOf(ToolAction.READ),
                resourceKinds = setOf(ToolResourceKind.API_CONNECTION),
                confirmActions = setOf(ToolAction.DELETE)
            )
        }
    }

    @Test fun mutableInputCollectionsCannotExpandRegisteredCapabilities() {
        val actions = mutableSetOf(ToolAction.READ)
        val descriptor = ToolDescriptor(
            id = "local_files",
            label = "Files",
            version = 1,
            actions = actions,
            resourceKinds = setOf(ToolResourceKind.LOCAL_FILE)
        )
        val registry = ToolRegistry()
        registry.register(object : ToolAdapter { override val descriptor = descriptor })
        actions += ToolAction.DELETE

        assertEquals(setOf(ToolAction.READ), registry.descriptors().single().actions)
    }
}
