package com.ayuemin.ymnik.agenttools

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolDescriptorLookupTest {
    private val descriptor = ToolDescriptor(
        id = "browser_read",
        label = "Browser read",
        version = 1,
        actions = setOf(ToolAction.READ, ToolAction.WRITE),
        resourceKinds = setOf(ToolResourceKind.WEB_ORIGIN)
    )
    private val lookup = object : ToolDescriptorLookup {
        override fun descriptor(id: String): ToolDescriptor? =
            descriptor.takeIf { it.id == id }
    }
    private val resource = TaskResource(ToolResourceKind.WEB_ORIGIN, "https://example.org")
    private val gate = ToolPermissionGate(lookup)

    private fun call(action: ToolAction, resource: TaskResource = this.resource) =
        ToolInvocation("task", "direct-user", descriptor.id, action, resource)

    private fun grant(): TaskGrant = TaskGrant.fromTrustedUserMessage(
        "task", "direct-user", 100L, 1_000L,
        setOf(
            TaskAllowance(descriptor.id, ToolAction.READ, resource),
            TaskAllowance(descriptor.id, ToolAction.WRITE, resource)
        )
    )

    @Test fun lookupDoesNotAuthorizeAbsentGrantOrForeignResource() {
        assertEquals(ToolPermissionDecision.DENIED_MISSING_GRANT, gate.check(null, call(ToolAction.READ), 200L))
        assertEquals(
            ToolPermissionDecision.DENIED_OUT_OF_SCOPE,
            gate.check(grant(), call(ToolAction.READ, TaskResource(ToolResourceKind.WEB_ORIGIN, "https://other.org")), 200L)
        )
    }

    @Test fun dangerousActionCannotExecuteWithoutOnDeviceConfirmation() {
        assertEquals(ToolPermissionDecision.NEEDS_USER_CONFIRMATION, gate.check(grant(), call(ToolAction.WRITE), 200L))
        assertEquals(ToolPermissionDecision.DENIED_OUTSIDE_LIFETIME, gate.check(grant(), call(ToolAction.READ), 1_000L))
    }

    @Test fun descriptorLookupCannotExpandResourceAllowance() {
        assertEquals(ToolPermissionDecision.DENIED_UNKNOWN_TOOL,
            gate.check(grant(), call(ToolAction.READ).copy(toolId = "unauthorized"), 200L))
    }
}
