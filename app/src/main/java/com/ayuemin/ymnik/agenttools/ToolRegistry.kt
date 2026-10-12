package com.ayuemin.ymnik.agenttools

/**
 * Closed-by-default catalog. Registration exposes capabilities to the hub,
 * but does not authorize or execute them.
 */
internal class ToolRegistry {
    private val descriptions = linkedMapOf<String, ToolDescriptor>()

    fun register(adapter: ToolAdapter) {
        val descriptor = adapter.descriptor
        require(!descriptions.containsKey(descriptor.id)) {
            "Duplicate tool id: ${descriptor.id}"
        }
        descriptions[descriptor.id] = snapshot(descriptor)
    }

    fun contains(id: String): Boolean = descriptions.containsKey(id)

    fun descriptors(): List<ToolDescriptor> =
        descriptions.values.sortedBy { it.id }.map(::snapshot)

    private fun snapshot(descriptor: ToolDescriptor): ToolDescriptor =
        descriptor.copy(
            actions = descriptor.actions.toSet(),
            resourceKinds = descriptor.resourceKinds.toSet(),
            confirmActions = descriptor.confirmActions.toSet()
        )
}
