package com.ayuemin.ymnik.agenttools

/** A declared operation, not executable arguments supplied by a model. */
data class ToolInvocation(
    val taskId: String,
    val originUserMessageId: String,
    val toolId: String,
    val action: ToolAction,
    val resource: TaskResource
)

enum class ToolPermissionDecision {
    ALLOWED,
    NEEDS_USER_CONFIRMATION,
    DENIED_UNKNOWN_TOOL,
    DENIED_MISSING_GRANT,
    DENIED_WRONG_TASK,
    DENIED_WRONG_ORIGIN,
    DENIED_OUTSIDE_LIFETIME,
    DENIED_UNDECLARED_CAPABILITY,
    DENIED_OUT_OF_SCOPE
}

/**
 * Deny by default. This gate authorizes METADATA only and does not dispatch.
 * A future execution adapter must check the real target immediately before
 * every side effect. No model-produced confirmation flag is accepted here.
 */
class ToolPermissionGate(private val registry: ToolDescriptorLookup) {
    fun check(
        grant: TaskGrant?,
        call: ToolInvocation,
        nowMillis: Long
    ): ToolPermissionDecision {
        val descriptor = registry.descriptor(call.toolId)
            ?: return ToolPermissionDecision.DENIED_UNKNOWN_TOOL
        if (grant == null) return ToolPermissionDecision.DENIED_MISSING_GRANT
        if (grant.taskId != call.taskId) return ToolPermissionDecision.DENIED_WRONG_TASK
        if (grant.originUserMessageId != call.originUserMessageId) {
            return ToolPermissionDecision.DENIED_WRONG_ORIGIN
        }
        if (nowMillis < grant.issuedAtMillis || nowMillis >= grant.expiresAtMillis) {
            return ToolPermissionDecision.DENIED_OUTSIDE_LIFETIME
        }
        if (call.action !in descriptor.actions || call.resource.kind !in descriptor.resourceKinds) {
            return ToolPermissionDecision.DENIED_UNDECLARED_CAPABILITY
        }
        if (!grant.allows(call)) return ToolPermissionDecision.DENIED_OUT_OF_SCOPE

        // A dedicated, trusted on-device confirmation flow is not yet wired.
        // Until then, fail closed for side effects and declared sensitive reads.
        if (call.action in CONFIRMATION_ACTIONS || call.action in descriptor.confirmActions) {
            return ToolPermissionDecision.NEEDS_USER_CONFIRMATION
        }
        return ToolPermissionDecision.ALLOWED
    }

    private companion object {
        val CONFIRMATION_ACTIONS = setOf(
            ToolAction.UPLOAD,
            ToolAction.WRITE,
            ToolAction.DELETE,
            ToolAction.EXECUTE
        )
    }
}
