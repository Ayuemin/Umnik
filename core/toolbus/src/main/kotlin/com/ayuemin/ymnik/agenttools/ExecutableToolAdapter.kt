package com.ayuemin.ymnik.agenttools

/**
 * Explicit opt-in to execution. Legacy ToolAdapter implementations remain
 * metadata-only, not callable merely because they are registered.
 *
 * Registered instances must be trusted application code bound to a concrete
 * runtime session. [resolveActualResource] obtains its target from observed
 * runtime state, never model arguments. Multi-step actions and redirects must
 * re-check their actual target before EACH side effect.
 */
interface ExecutableToolAdapter : ToolAdapter {
    suspend fun resolveActualResource(): TaskResource?
    suspend fun execute(): ToolTextResult
}

/** Small text-only response until the shared ArtifactStore is implemented. */
data class ToolTextResult(val text: String) {
    init {
        require(text.length <= 65_536) { "Tool text result exceeds size limit" }
    }
}
