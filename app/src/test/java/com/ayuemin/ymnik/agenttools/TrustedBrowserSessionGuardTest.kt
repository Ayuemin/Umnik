package com.ayuemin.ymnik.agenttools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrustedBrowserSessionGuardTest {
    private val guard = TrustedBrowserSessionGuard()

    @Test fun directUserCanCloseOnlyItsOwnSession() = runBlocking {
        val grant = guard.grantFromUiSubmission("chat-1", "user-message-1", true, 1_000L)
        var calls = 0
        val done = guard.control(grant, "chat-1", "user-message-1", "local_browser_done", { 1_100L }) {
            calls++
            "closed"
        }
        assertEquals(ToolDispatchResult.Completed("closed"), done)
        assertEquals(1, calls)
    }

    @Test fun directUserCanHandOffOnlyItsOwnSession() = runBlocking {
        val grant = guard.grantFromUiSubmission("chat-1", "user-message-1", true, 1_000L)
        var calls = 0
        val takeover = guard.control(grant, "chat-1", "user-message-1", "local_browser_takeover", { 1_100L }) {
            calls++
            "waiting_user"
        }
        assertEquals(ToolDispatchResult.Completed("waiting_user"), takeover)
        assertEquals(1, calls)
    }

    @Test fun noAgentAuthorizationWhenBrowserToolNotEnabled() = runBlocking {
        val grant = guard.grantFromUiSubmission("chat-1", "user-message-1", false, 1_000L)
        assertNull(grant)
        var calls = 0
        val result = guard.control(grant, "chat-1", "user-message-1", "local_browser_done", { 1_100L }) {
            calls++
        }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_MISSING_GRANT), result)
        assertEquals(0, calls)
    }

    @Test fun cannotTakeoverOtherChatOrReuseFromOtherUserTurn() = runBlocking {
        val grant = guard.grantFromUiSubmission("chat-1", "user-message-1", true, 1_000L)
        var calls = 0
        val otherChat = guard.control(grant, "chat-2", "user-message-1", "local_browser_takeover", { 1_100L }) {
            calls++
        }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE), otherChat)
        val otherTurn = guard.control(grant, "chat-1", "user-message-2", "local_browser_done", { 1_100L }) {
            calls++
        }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_WRONG_TASK), otherTurn)
        assertEquals(0, calls)
    }

    @Test fun oldGrantsExpireAndNeverAuthorizeNavigationOrDownloads() = runBlocking {
        val grant = requireNotNull(
            guard.grantFromUiSubmission("chat-1", "user-message-1", true, 1_000L)
        )
        var calls = 0
        val expired = guard.control(grant, "chat-1", "user-message-1", "local_browser_done", { 3_601_000L }) {
            calls++
        }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUTSIDE_LIFETIME), expired)
        val gate = ToolPermissionGate(ExistingLocalToolCatalog.createRegistry())
        val navigation = ToolInvocation(
            "user-message-1", "user-message-1", "local_browser_open", ToolAction.NAVIGATE,
            TaskResource(ToolResourceKind.WEB_ORIGIN, "https://example.org")
        )
        assertEquals(ToolPermissionDecision.DENIED_OUT_OF_SCOPE, gate.check(grant, navigation, 1_100L))
        assertEquals(0, calls)
    }
}
