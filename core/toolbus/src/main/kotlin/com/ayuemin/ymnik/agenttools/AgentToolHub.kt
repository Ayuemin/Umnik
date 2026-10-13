package com.ayuemin.ymnik.agenttools

/**
 * Single catalog facade: capabilities are visible; execution is guarded.
 *
 * Trusted app code supplies [resolveActualResource] from observed runtime state,
 * NEVER from model arguments. This invokes the original permission dispatcher,
 * not a parallel authorization path. Browser/Shell side effects still need their
 * own per-action and per-redirect runtime validation.
 */
class AgentToolHub(private val registry: ToolRegistry) {
    private val dispatcher = GuardedToolDispatcher(ToolPermissionGate(registry))

    fun availableTools(): List<ToolDescriptor> = registry.descriptors()

    fun hasTool(id: String): Boolean = registry.contains(id)

    /**
     * First opt-in executor: bounded textual READ only. The adapter is installed
     * by trusted app code; the actual resource is resolved from live runtime state.
     * No result or metadata can authorize a call without a matching user TaskGrant.
     */
    suspend fun executeRead(
        grant: TaskGrant?,
        requested: ToolInvocation,
        nowMillis: () -> Long
    ): ToolDispatchResult<ToolTextResult> {
        if (requested.action != ToolAction.READ) {
            return ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_UNDECLARED_CAPABILITY)
        }
        val adapter = registry.executable(requested.toolId)
            ?: return ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_UNDECLARED_CAPABILITY)
        return dispatch(
            grant = grant,
            requested = requested,
            resolveActualResource = { adapter.resolveActualResource() },
            nowMillis = nowMillis,
            operation = { adapter.execute() }
        )
    }

    suspend fun <T> dispatch(
        grant: TaskGrant?,
        requested: ToolInvocation,
        resolveActualResource: suspend () -> TaskResource?,
        nowMillis: () -> Long,
        operation: suspend () -> T
    ): ToolDispatchResult<T> =
        dispatcher.invoke(grant, requested, resolveActualResource, nowMillis, operation)
}
