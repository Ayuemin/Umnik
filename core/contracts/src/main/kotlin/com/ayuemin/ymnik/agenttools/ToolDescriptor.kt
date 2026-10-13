package com.ayuemin.ymnik.agenttools

/**
 * Metadata only. Descriptors never grant permissions by themselves.
 * The future ToolPermissionGate must validate the direct user's TaskGrant
 * immediately before dispatching any tool operation.
 */
enum class ToolAction {
    READ, NAVIGATE, INTERACT, CONTROL, DOWNLOAD, UPLOAD, WRITE, DELETE, EXECUTE
}

enum class ToolResourceKind {
    // Public-web discovery is a distinct trusted-UI grant, NOT a wildcard WEB_ORIGIN.
    // Runtime adapters must still check every actual URL and redirect as public.
    WEB_ORIGIN, PUBLIC_WEB_SESSION, LOCAL_FILE, LOCAL_SESSION, SSH_HOST, API_CONNECTION, CLOUD_ACCOUNT
}

data class ToolDescriptor(
    val id: String,
    val label: String,
    val version: Int,
    val actions: Set<ToolAction>,
    val resourceKinds: Set<ToolResourceKind>,
    val maxConcurrentCalls: Int = 1,
    val timeoutMillis: Long = 120_000L,
    val confirmActions: Set<ToolAction> = emptySet()
) {
    init {
        require(ID_PATTERN.matches(id)) { "Invalid tool id" }
        require(label.isNotBlank() && label.length <= 120) { "Invalid tool label" }
        require(version > 0) { "Tool version must be positive" }
        require(actions.isNotEmpty()) { "Tool must declare its operations" }
        require(resourceKinds.isNotEmpty()) { "Tool must declare its resource kinds" }
        require(maxConcurrentCalls in 1..8) { "Invalid tool concurrency limit" }
        require(timeoutMillis in 1_000L..600_000L) { "Invalid tool timeout" }
        require(confirmActions.all { it in actions }) { "Confirmation actions must be supported" }
    }

    companion object {
        private val ID_PATTERN = Regex("[a-z][a-z0-9_]{2,63}")
    }
}

/**
 * Registration-only contract for the first AgentToolHub increment.
 *
 * Execution is deliberately absent: a future guarded execution contract
 * must require a verified TaskGrant. Existing tools still use their original
 * runtime and cannot be dispatched through this registry.
 */
interface ToolAdapter {
    val descriptor: ToolDescriptor
}

/** Read-only descriptor lookup used by the permission gate without a ToolBus dependency. */
interface ToolDescriptorLookup {
    fun descriptor(id: String): ToolDescriptor?
}
