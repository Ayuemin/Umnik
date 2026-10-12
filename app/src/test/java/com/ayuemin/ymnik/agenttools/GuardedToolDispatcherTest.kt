package com.ayuemin.ymnik.agenttools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedToolDispatcherTest {
    private val url = TaskResource(ToolResourceKind.WEB_ORIGIN, "https://example.org")
    private val otherUrl = TaskResource(ToolResourceKind.WEB_ORIGIN, "https://evil.example")
    private val registry = ExistingLocalToolCatalog.createRegistry()
    private val dispatcher = GuardedToolDispatcher(ToolPermissionGate(registry))

    private fun grant(action: ToolAction, resource: TaskResource = url): TaskGrant =
        TaskGrant.fromTrustedUserMessage(
            taskId = "task-1",
            originUserMessageId = "direct-1",
            issuedAtMillis = 100L,
            expiresAtMillis = 2_000L,
            allowedOperations = setOf(TaskAllowance(
                toolId = "local_browser_read",
                action = action,
                resource = resource
            ))
        )

    private val read = ToolInvocation(
        taskId = "task-1",
        originUserMessageId = "direct-1",
        toolId = "local_browser_read",
        action = ToolAction.READ,
        resource = url
    )

    @Test fun exactTrustedReadRunsOnce() = runBlocking {
        var calls = 0
        val result = dispatcher.invoke(grant(ToolAction.READ), read, { url }, { 500L }) {
            calls++
            "page snapshot"
        }
        assertEquals(ToolDispatchResult.Completed("page snapshot"), result)
        assertEquals(1, calls)
    }

    @Test fun noGrantNeverInvokesTheTool() = runBlocking {
        var calls = 0
        val result = dispatcher.invoke(null, read, { url }, { 500L }) { calls++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_MISSING_GRANT), result)
        assertEquals(0, calls)
    }

    @Test fun runtimeTargetCannotBeReplacedWithRequestedSafeUrl() = runBlocking {
        var calls = 0
        // A malicious page redirects, but the model still asks for example.org.
        val result = dispatcher.invoke(grant(ToolAction.READ), read, { otherUrl }, { 500L }) { calls++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE), result)
        assertEquals(0, calls)
    }

    @Test fun missingRuntimeTargetFailsClosed() = runBlocking {
        var calls = 0
        val result = dispatcher.invoke(grant(ToolAction.READ), read, { null }, { 500L }) { calls++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE), result)
        assertEquals(0, calls)
    }

    @Test fun expiredTaskAndUnknownToolCannotExecute() = runBlocking {
        var calls = 0
        val expired = dispatcher.invoke(grant(ToolAction.READ), read, { url }, { 2_000L }) { calls++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUTSIDE_LIFETIME), expired)
        val unknown = dispatcher.invoke(
            grant(ToolAction.READ), read.copy(toolId = "remote_ssh_exec"),
            { url }, { 500L }
        ) { calls++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_UNKNOWN_TOOL), unknown)
        assertEquals(0, calls)
    }

    @Test fun browserNavigationRequiresMatchingExplicitNavigationGrant() = runBlocking {
        var calls = 0
        val action = read.copy(toolId = "local_browser_open", action = ToolAction.NAVIGATE)
        val permission = TaskGrant.fromTrustedUserMessage(
            "task-1", "direct-1", 100L, 2_000L,
            setOf(TaskAllowance("local_browser_open", ToolAction.NAVIGATE, url))
        )
        val result = dispatcher.invoke(permission, action, { url }, { 500L }) { calls++ }
        assertEquals(ToolDispatchResult.Completed(Unit), result)
        assertEquals(1, calls)
        val wrongActionGrant = grant(ToolAction.READ)
        val blocked = dispatcher.invoke(wrongActionGrant, action, { url }, { 500L }) { calls++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE), blocked)
        assertEquals(1, calls)
    }
}
