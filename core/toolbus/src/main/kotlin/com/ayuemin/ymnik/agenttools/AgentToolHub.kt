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

    suspend fun <T> dispatch(
        grant: TaskGrant?,
        requested: ToolInvocation,
        resolveActualResource: suspend () -> TaskResource?,
        nowMillis: () -> Long,
        operation: suspend () -> T
    ): ToolDispatchResult<T> =
        dispatcher.invoke(grant, requested, resolveActualResource, nowMillis, operation)
}
