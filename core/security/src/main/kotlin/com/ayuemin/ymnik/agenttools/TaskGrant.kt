package com.ayuemin.ymnik.agenttools

/**
 * A resource is an exact scoped identifier, never a pattern or a wildcard.
 * Adapters must supply the actual, normalized resource for EACH operation
 * (including every redirect or target change) rather than a model-supplied label.
 */
data class TaskResource(
    val kind: ToolResourceKind,
    val key: String
) {
    init {
        require(key.isNotBlank() && key == key.trim() && key.length <= 2048)
        require('*' !in key && key.none { it.code < 32 || it.code == 127 })
    }
}

data class TaskAllowance(
    val toolId: String,
    val action: ToolAction,
    val resource: TaskResource
)

/**
 * Immutable, time-limited authorization scope for a single DIRECT user request.
 *
 * Only the trusted UI message-handling boundary may call [fromTrustedUserMessage].
 * Never create this from LLM output, OCR, files, pages, logs, tool responses or
 * remembered conversation text. This stage does not yet wire that boundary;
 * creating a grant is NOT a substitute for verifying the message provenance.
 */
class TaskGrant private constructor(
    val taskId: String,
    val originUserMessageId: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    allowedOperations: Set<TaskAllowance>
) {
    private val allowances: Set<TaskAllowance> = allowedOperations.toSet()

    internal fun allows(call: ToolInvocation): Boolean = allowances.contains(
        TaskAllowance(call.toolId, call.action, call.resource)
    )

    companion object {
        fun fromTrustedUserMessage(
            taskId: String,
            originUserMessageId: String,
            issuedAtMillis: Long,
            expiresAtMillis: Long,
            allowedOperations: Set<TaskAllowance>
        ): TaskGrant {
            require(taskId.isNotBlank() && taskId.length <= 128)
            require(originUserMessageId.isNotBlank() && originUserMessageId.length <= 128)
            require(issuedAtMillis >= 0L && expiresAtMillis > issuedAtMillis)
            require(expiresAtMillis - issuedAtMillis <= 86_400_000L) {
                "Task authority must expire within 24 hours"
            }
            require(allowedOperations.isNotEmpty() && allowedOperations.size <= 64)
            return TaskGrant(
                taskId,
                originUserMessageId,
                issuedAtMillis,
                expiresAtMillis,
                allowedOperations.toSet()
            )
        }
    }
}
