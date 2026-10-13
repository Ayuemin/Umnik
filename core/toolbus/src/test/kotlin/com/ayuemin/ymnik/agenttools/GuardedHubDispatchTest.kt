package com.ayuemin.ymnik.agenttools

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Core-level regression tests for the ORIGINAL permission dispatcher reached via the hub. */
class GuardedHubDispatchTest {
    private val session = TaskResource(ToolResourceKind.LOCAL_SESSION, "chat:one")
    private val foreign = TaskResource(ToolResourceKind.LOCAL_SESSION, "chat:other")
    private val registry = ToolRegistry().apply {
        register(object : ToolAdapter {
            override val descriptor = ToolDescriptor(
                id = "local_shell_status", label = "Status", version = 1,
                actions = setOf(ToolAction.READ, ToolAction.WRITE),
                resourceKinds = setOf(ToolResourceKind.LOCAL_SESSION)
            )
        })
    }
    private val hub = AgentToolHub(registry)
    private val read = ToolInvocation("task-1", "direct-1", "local_shell_status", ToolAction.READ, session)
    private val grant = TaskGrant.fromTrustedUserMessage(
        "task-1", "direct-1", 100L, 1_000L,
        setOf(
            TaskAllowance(read.toolId, ToolAction.READ, session),
            TaskAllowance(read.toolId, ToolAction.WRITE, session)
        )
    )

    // All test callbacks complete synchronously; no new coroutine dependency is needed.
    private fun <T> runNow(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value }
        })
        return requireNotNull(result) { "Unexpected suspension in synchronous unit test" }.getOrThrow()
    }

    @Test fun registeredReadInvokesOnlyAfterGrantAndActualResource() {
        var invoked = 0
        val result = runNow {
            hub.dispatch(grant, read, { session }, { 500L }) { invoked++; "status" }
        }
        assertEquals(ToolDispatchResult.Completed("status"), result)
        assertEquals(1, invoked)
    }

    @Test fun missingGrantCannotInvokeCallback() {
        var invoked = 0
        val result = runNow {
            hub.dispatch(null, read, { session }, { 500L }) { invoked++ }
        }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_MISSING_GRANT), result)
        assertEquals(0, invoked)
    }

    @Test fun realRuntimeResourceMismatchFailsClosed() {
        var invoked = 0
        val result = runNow {
            hub.dispatch(grant, read, { foreign }, { 500L }) { invoked++ }
        }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE), result)
        assertEquals(0, invoked)
    }

    @Test fun foreignUserTurnAndExpiredScopeCannotInvoke() {
        var invoked = 0
        val foreignCall = read.copy(originUserMessageId = "tool-output")
        val wrong = runNow {
            hub.dispatch(grant, foreignCall, { session }, { 500L }) { invoked++ }
        }
        val expired = runNow {
            hub.dispatch(grant, read, { session }, { 1_000L }) { invoked++ }
        }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_WRONG_ORIGIN), wrong)
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUTSIDE_LIFETIME), expired)
        assertEquals(0, invoked)
    }

    @Test fun undeclaredToolAndDangerousWriteAreBlocked() {
        var invoked = 0
        val unknown = runNow {
            hub.dispatch(grant, read.copy(toolId = "unregistered"), { session }, { 500L }) { invoked++ }
        }
        val write = runNow {
            hub.dispatch(grant, read.copy(action = ToolAction.WRITE), { session }, { 500L }) { invoked++ }
        }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_UNKNOWN_TOOL), unknown)
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.NEEDS_USER_CONFIRMATION), write)
        assertEquals(0, invoked)
    }

    @Test fun resolverFailureNeverExecutesButCancellationPropagates() {
        var invoked = 0
        val failed = runNow {
            hub.dispatch(grant, read, { error("resolver failed") }, { 500L }) { invoked++ }
        }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE), failed)
        assertEquals(0, invoked)
        assertThrows(CancellationException::class.java) {
            runNow {
                hub.dispatch(grant, read, { throw CancellationException("cancelled") }, { 500L }) {
                    invoked++
                }
            }
        }
        assertEquals(0, invoked)
    }
}
