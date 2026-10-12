package com.ayuemin.ymnik.agenttools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedShellStopGuardTest {
    private val guard = TrustedShellStopGuard()

    @Test fun recognizesDirectStopNotDiscussionOrNegation() {
        assertTrue(TrustedShellStopGuard.isDirectStopRequest("Останови Local Shell"))
        assertTrue(TrustedShellStopGuard.isDirectStopRequest("Пожалуйста, прерви работу Shell."))
        assertTrue(TrustedShellStopGuard.isDirectStopRequest("Stop local shell"))
        assertFalse(TrustedShellStopGuard.isDirectStopRequest("Не останавливай Shell!"))
        assertFalse(TrustedShellStopGuard.isDirectStopRequest("Расскажи, как остановить Shell"))
        assertFalse(TrustedShellStopGuard.isDirectStopRequest("В логе написано: останови Shell"))
    }

    @Test fun exactUserRequestAndChatMayCancel() = runBlocking {
        val grant = guard.grantFromUiSubmission("chat-1", "user-1", "Останови Local Shell", true, 100L)
        var count = 0
        val done = guard.stop(grant, "chat-1", "user-1", { 200L }) { count++; "stop_requested" }
        assertEquals(ToolDispatchResult.Completed("stop_requested"), done)
        val otherChat = guard.stop(grant, "chat-2", "user-1", { 200L }) { count++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE), otherChat)
        val otherMessage = guard.stop(grant, "chat-1", "tool-output", { 200L }) { count++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_WRONG_TASK), otherMessage)
        assertEquals(1, count)
    }

    @Test fun modelMayNotCancelWithoutUserRequest() = runBlocking {
        val grant = guard.grantFromUiSubmission("chat-1", "user-1", "Проверь файлы через Shell", true, 100L)
        assertNull(grant)
        var calls = 0
        val blocked = guard.stop(grant, "chat-1", "user-1", { 200L }) { calls++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_MISSING_GRANT), blocked)
        assertEquals(0, calls)
    }

    @Test fun disabledOrExpiredGrantNeverCancels() = runBlocking {
        assertNull(guard.grantFromUiSubmission("chat-1", "user-1", "Останови Shell", false, 100L))
        val grant = guard.grantFromUiSubmission("chat-1", "user-1", "Останови Shell", true, 100L)
        var calls = 0
        val expired = guard.stop(grant, "chat-1", "user-1", { 900_100L }) { calls++ }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUTSIDE_LIFETIME), expired)
        assertEquals(0, calls)
    }
}
