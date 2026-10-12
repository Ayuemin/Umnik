package com.ayuemin.ymnik.agenttools

import kotlinx.coroutines.CancellationException

/** A blocked operation never invokes the supplied tool callback. */
internal sealed interface ToolDispatchResult<out T> {
    data class Completed<T>(val value: T) : ToolDispatchResult<T>
    data class Blocked(val decision: ToolPermissionDecision) : ToolDispatchResult<Nothing>
}

/**
 * Guarded execution boundary for an adapter supplied by trusted app code.
 *
 * [resolveActualResource] must inspect the real, normalized runtime target
 * (WebView's current/target URL, or a sandbox-confined file path), NOT args
 * from the model. The resolver runs before EACH operation, not once per chat.
 *
 * This component does not yet replace legacy Browser or Shell callbacks.
 * Multi-step adapters must re-check their changing targets at each side effect
 * and obtain trusted on-device confirmation for NEEDS_USER_CONFIRMATION.
 */
internal class GuardedToolDispatcher(private val gate: ToolPermissionGate) {
    suspend fun <T> invoke(
        grant: TaskGrant?,
        requested: ToolInvocation,
        resolveActualResource: suspend () -> TaskResource?,
        nowMillis: () -> Long,
        operation: suspend () -> T
    ): ToolDispatchResult<T> {
        val actual = try {
            resolveActualResource()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE)
        if (requested.resource != actual) {
            return ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE)
        }
        val decision = gate.check(grant, requested.copy(resource = actual), nowMillis())
        if (decision != ToolPermissionDecision.ALLOWED) {
            return ToolDispatchResult.Blocked(decision)
        }
        return ToolDispatchResult.Completed(operation())
    }
}
