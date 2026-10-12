package com.ayuemin.ymnik.agenttools

/**
 * Minimal read-only facade. Tool execution will be enabled only after
 * TaskGrant + ToolPermissionGate are implemented and tested.
 *
 * Do not add an unchecked adapter lookup or an execution shortcut here.
 */
internal class AgentToolHub(private val registry: ToolRegistry) {
    fun availableTools(): List<ToolDescriptor> = registry.descriptors()

    fun hasTool(id: String): Boolean = registry.contains(id)
}
