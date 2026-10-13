package com.ayuemin.ymnik.agenttools

/**
 * Closed-by-default catalog. Registration exposes capabilities to the hub,
 * but does not authorize or execute them.
 */
class ToolRegistry : ToolDescriptorLookup {
    // One catalog owns both descriptor snapshots and trusted adapter objects.
    private data class Registration(val descriptor: ToolDescriptor, val adapter: ToolAdapter)
    private val registrations = linkedMapOf<String, Registration>()

    fun register(adapter: ToolAdapter) {
        val descriptor = adapter.descriptor
        require(!registrations.containsKey(descriptor.id)) {
            "Duplicate tool id: ${descriptor.id}"
        }
        registrations[descriptor.id] = Registration(snapshot(descriptor), adapter)
    }

    fun contains(id: String): Boolean = registrations.containsKey(id)

    override fun descriptor(id: String): ToolDescriptor? =
        registrations[id]?.descriptor?.let(::snapshot)

    fun descriptors(): List<ToolDescriptor> =
        registrations.values.map { it.descriptor }.sortedBy { it.id }.map(::snapshot)

    // Never exported to :app. The Hub alone may invoke registered adapters through the gate.
    internal fun executable(id: String): ExecutableToolAdapter? =
        registrations[id]?.adapter as? ExecutableToolAdapter

    private fun snapshot(descriptor: ToolDescriptor): ToolDescriptor =
        descriptor.copy(
            actions = descriptor.actions.toSet(),
            resourceKinds = descriptor.resourceKinds.toSet(),
            confirmActions = descriptor.confirmActions.toSet()
        )
}
