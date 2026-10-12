package com.ayuemin.ymnik.agenttools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedShellStopGuardTest {
    private val guard = TrustedShellStopGuard()

    @Test fun recognizesStopNotNegationOrIncidentalText() {
        assertTrue(TrustedShellStopGuard.isDirectStopRequest("Останови Local Shell"))
        assertTrue(TrustedShellStopGuard.isDirectStopRequest("Пожалуйста, прерви работу Shell."))
        assertTrue(TrustedShellStopGuard.isDirectStopRequest("Stop local shell"))
        assertFalse(TrustedShellStopGuard.isDirectStopRequest("Не останавливай Shell!"))
        assertFalse(TrustedShellStopGuard.isDirectStopRequest("Расскажи, как остановить Shell"))
        assertFalse(TrustedShellStopGuard.isDirectStopRequest("В логе написано: останови Shell"))
    }

    @Test fun ownShellCanBeStoppedAsPartOfMultistepAgentTask() = runBlocking {
        val grant = guard.grantFromUiSubmission("chat-1", "user-1", "Выполни 10 шагов через Shell", true, 100L)
        var stopped = ""
        val result = guard.stop(grant, "chat-1", "user-1", { 200L }, "chat-1", "user-1", "shell-1") {
            stopped = it
            "stopped"
        }
        assertEquals(ToolDispatchResult.Completed("stopped"), result)
        assertEquals("shell-1", stopped)
    }

    @Test fun modelCannotStopUnrelatedTaskEvenInSameChat() = runBlocking {
        val grant = guard.grantFromUiSubmission("chat-1", "user-2", "Проверь файл через Shell", true, 100L)
        var calls = 0
        val otherOwner = guard.stop(grant, "chat-1", "user-2", { 200L }, "chat-1", "user-1", "shell-1") { calls++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE), otherOwner)
        val otherChat = guard.stop(grant, "chat-1", "user-2", { 200L }, "chat-2", "user-2", "shell-2") { calls++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE), otherChat)
        assertEquals(0, calls)
    }

    @Test fun directUserCanExplicitlyStopOlderTaskInSameChat() = runBlocking {
        val grant = guard.grantFromUiSubmission("chat-1", "user-2", "Останови Local Shell", true, 100L, "shell-1")
        var count = 0
        val result = guard.stop(grant, "chat-1", "user-2", { 200L }, "chat-1", "user-1", "shell-1") { count++; Unit }
        assertEquals(ToolDispatchResult.Completed(Unit), result)
        assertEquals(1, count)
    }

    @Test fun staleExplicitStopCannotCancelReplacementInSameChat() = runBlocking {
        val grant = guard.grantFromUiSubmission(
            "chat-1", "user-2", "Останови Local Shell", true, 100L, "old-shell"
        )
        var cancels = 0
        val stale = guard.stop(grant, "chat-1", "user-2", { 200L }, "chat-1", "user-1", "new-shell") {
            cancels++
        }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE), stale)
        assertEquals(0, cancels)
    }

    @Test fun noPrivilegeFromQuotedTextDisabledToolsOrExpiredRequests() = runBlocking {
        val grant = guard.grantFromUiSubmission("chat-1", "user-2", "В логе написано: останови Shell", true, 100L)
        var calls = 0
        val blocked = guard.stop(grant, "chat-1", "user-2", { 200L }, "chat-1", "user-1", "shell-1") { calls++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE), blocked)
        assertNull(guard.grantFromUiSubmission("chat-1", "user-2", "Останови Shell", false, 100L))
        val expired = guard.stop(grant, "chat-1", "user-2", { 14_400_100L }, "chat-1", "user-2", "shell-2") { calls++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUTSIDE_LIFETIME), expired)
        assertEquals(0, calls)
    }
}
