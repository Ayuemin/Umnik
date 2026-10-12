package com.ayuemin.ymnik.agenttools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrustedShellStatusGuardTest {
    private val guard = TrustedShellStatusGuard()

    @Test fun validDirectSubmissionCanReadOnlyItsOwnShellStatus() = runBlocking {
        val grant = guard.grantFromUiSubmission("chat-1", "direct-1", true, 100L)
        var calls = 0
        val result = guard.readStatus(grant, "chat-1", "direct-1", { 500L }) {
            calls++
            "status"
        }
        assertEquals(ToolDispatchResult.Completed("status"), result)
        assertEquals(1, calls)
    }

    @Test fun offModeDoesNotMintGrants() = runBlocking {
        val grant = guard.grantFromUiSubmission("chat-1", "direct-1", false, 100L)
        assertNull(grant)
        var calls = 0
        val result = guard.readStatus(grant, "chat-1", "direct-1", { 500L }) { calls++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_MISSING_GRANT), result)
        assertEquals(0, calls)
    }

    @Test fun modelCannotReuseGrantForOtherChatOrMessage() = runBlocking {
        val grant = guard.grantFromUiSubmission("chat-1", "direct-1", true, 100L)
        var calls = 0
        val wrongChat = guard.readStatus(grant, "chat-2", "direct-1", { 500L }) { calls++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE), wrongChat)
        val wrongMessage = guard.readStatus(grant, "chat-1", "tool-output", { 500L }) { calls++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_WRONG_TASK), wrongMessage)
        assertEquals(0, calls)
    }

    @Test fun statusGrantExpiresAndNeverAllowsExecution() = runBlocking {
        val grant = requireNotNull(guard.grantFromUiSubmission("chat-1", "direct-1", true, 100L))
        var calls = 0
        val result = guard.readStatus(grant, "chat-1", "direct-1", { 900_100L }) { calls++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUTSIDE_LIFETIME), result)
        val registry = ExistingLocalToolCatalog.createRegistry()
        val gate = ToolPermissionGate(registry)
        val launch = ToolInvocation(
            "direct-1", "direct-1", "local_shell_start", ToolAction.EXECUTE,
            TaskResource(ToolResourceKind.LOCAL_FILE, "/sandbox")
        )
        assertEquals(ToolPermissionDecision.DENIED_OUT_OF_SCOPE, gate.check(grant, launch, 500L))
        assertEquals(0, calls)
    }
}
