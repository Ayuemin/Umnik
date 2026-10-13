package com.ayuemin.ymnik.agenttools

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExecutableToolAdapterTest {
    private val own = TaskResource(ToolResourceKind.LOCAL_SESSION, "chat:own")
    private val foreign = TaskResource(ToolResourceKind.LOCAL_SESSION, "chat:foreign")

    private class ReadAdapter(
        private var actual: TaskResource?,
        private val actions: Set<ToolAction> = setOf(ToolAction.READ)
    ) : ExecutableToolAdapter {
        var executions = 0
        var resolutions = 0
        override val descriptor = ToolDescriptor(
            id = "trusted_status",
            label = "Trusted status",
            version = 1,
            actions = actions,
            resourceKinds = setOf(ToolResourceKind.LOCAL_SESSION)
        )
        override suspend fun resolveActualResource(): TaskResource? {
            resolutions++
            return actual
        }
        override suspend fun execute(): ToolTextResult {
            executions++
            return ToolTextResult("ready")
        }
        fun changeTarget(resource: TaskResource?) { actual = resource }
    }

    private fun <T> now(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return requireNotNull(outcome) { "Unexpected asynchronous suspension" }.getOrThrow()
    }

    private fun scope(actions: Set<ToolAction> = setOf(ToolAction.READ)) =
        TaskGrant.fromTrustedUserMessage(
            "task-1", "direct-user-1", 100L, 1_000L,
            actions.map { TaskAllowance("trusted_status", it, own) }.toSet()
        )

    private fun call(action: ToolAction = ToolAction.READ) =
        ToolInvocation("task-1", "direct-user-1", "trusted_status", action, own)

    private fun hub(adapter: ToolAdapter): AgentToolHub =
        AgentToolHub(ToolRegistry().apply { register(adapter) })

    @Test fun trustedOptInAdapterRunsOnceAfterRealResourceValidation() {
        val adapter = ReadAdapter(own)
        val result = now { hub(adapter).executeRead(scope(), call()) { 500L } }
        assertEquals(ToolDispatchResult.Completed(ToolTextResult("ready")), result)
        assertEquals(1, adapter.executions)
        assertEquals(1, adapter.resolutions)
    }

    @Test fun metadataOnlyRegistrationIsNotAnExecutionPermission() {
        val adapter = object : ToolAdapter {
            override val descriptor = ReadAdapter(own).descriptor
        }
        val result = now { hub(adapter).executeRead(scope(), call()) { 500L } }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_UNDECLARED_CAPABILITY), result)
    }

    @Test fun absentGrantAndWrongActualResourceDoNotExecute() {
        val adapter = ReadAdapter(own)
        val hub = hub(adapter)
        assertEquals(
            ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_MISSING_GRANT),
            now { hub.executeRead(null, call()) { 500L } }
        )
        adapter.changeTarget(foreign)
        assertEquals(
            ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE),
            now { hub.executeRead(scope(), call()) { 500L } }
        )
        assertEquals(0, adapter.executions)
    }

    @Test fun forgedUserTurnExpiredGrantAndUnknownToolDoNotExecute() {
        val adapter = ReadAdapter(own)
        val hub = hub(adapter)
        assertEquals(
            ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_WRONG_ORIGIN),
            now { hub.executeRead(scope(), call().copy(originUserMessageId = "page-instruction")) { 500L } }
        )
        assertEquals(
            ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUTSIDE_LIFETIME),
            now { hub.executeRead(scope(), call()) { 1_000L } }
        )
        assertEquals(
            ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_UNDECLARED_CAPABILITY),
            now { hub.executeRead(scope(), call().copy(toolId = "missing_status")) { 500L } }
        )
        assertEquals(0, adapter.executions)
    }

    @Test fun writesFailClosedEvenWithExplicitGrantAndDeclaredCapability() {
        val adapter = ReadAdapter(own, setOf(ToolAction.READ, ToolAction.WRITE))
        val result = now {
            hub(adapter).executeRead(scope(setOf(ToolAction.READ, ToolAction.WRITE)), call(ToolAction.WRITE)) { 500L }
        }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_UNDECLARED_CAPABILITY), result)
        assertEquals(0, adapter.executions)
    }

    @Test fun missingReadCapabilityAndMissingActualTargetFailClosed() {
        val adapter = ReadAdapter(own, setOf(ToolAction.CONTROL))
        val hub = hub(adapter)
        assertEquals(
            ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_UNDECLARED_CAPABILITY),
            now { hub.executeRead(scope(), call()) { 500L } }
        )
        adapter.changeTarget(null)
        assertEquals(
            ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE),
            now { hub.executeRead(scope(), call()) { 500L } }
        )
        assertEquals(0, adapter.executions)
    }

    @Test fun textualOutputIsBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            ToolTextResult("x".repeat(65_537))
        }
    }
}
