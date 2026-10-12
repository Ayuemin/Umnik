package com.ayuemin.ymnik.agenttools

/**
 * Descriptors for tools already exposed by OpenRouterClient.
 *
 * READ means inspecting state; NAVIGATE means public web navigation;
 * INTERACT means browser input/click checked by its own sensitive-action
 * confirmation gate; CONTROL means switching tool/session state. Mutating
 * tool operations remain WRITE/UPLOAD/DELETE/EXECUTE.
 *
 * Discovery is NOT execution or a permission grant. Legacy callbacks stay
 * untouched until their real WebView origin / Shell sandbox is resolved by
 * a trusted adapter immediately before each effect.
 */
internal object ExistingLocalToolCatalog {
    private fun descriptor(
        id: String,
        action: ToolAction,
        resource: ToolResourceKind,
        confirm: Boolean = false
    ) = ToolDescriptor(
        id = id,
        label = id,
        version = 1,
        actions = setOf(action),
        resourceKinds = setOf(resource),
        confirmActions = if (confirm) setOf(action) else emptySet()
    )

    private val browser = listOf(
        descriptor("local_browser_open", ToolAction.NAVIGATE, ToolResourceKind.WEB_ORIGIN),
        descriptor("local_browser_read", ToolAction.READ, ToolResourceKind.WEB_ORIGIN),
        descriptor("local_browser_click", ToolAction.INTERACT, ToolResourceKind.WEB_ORIGIN),
        descriptor("local_browser_download", ToolAction.DOWNLOAD, ToolResourceKind.WEB_ORIGIN),
        descriptor("local_browser_type", ToolAction.INTERACT, ToolResourceKind.WEB_ORIGIN),
        descriptor("local_browser_scroll", ToolAction.READ, ToolResourceKind.WEB_ORIGIN),
        descriptor("local_browser_back", ToolAction.NAVIGATE, ToolResourceKind.WEB_ORIGIN),
        descriptor("local_browser_wait", ToolAction.READ, ToolResourceKind.WEB_ORIGIN),
        descriptor("local_browser_takeover", ToolAction.CONTROL, ToolResourceKind.WEB_ORIGIN),
        descriptor("local_browser_done", ToolAction.CONTROL, ToolResourceKind.WEB_ORIGIN)
    )

    private val shell = listOf(
        descriptor("local_shell_start", ToolAction.EXECUTE, ToolResourceKind.LOCAL_FILE),
        descriptor("local_shell_status", ToolAction.READ, ToolResourceKind.LOCAL_SESSION),
        descriptor("local_shell_note", ToolAction.WRITE, ToolResourceKind.LOCAL_SESSION),
        descriptor("local_shell_stop", ToolAction.CONTROL, ToolResourceKind.LOCAL_SESSION)
    )

    fun createRegistry(): ToolRegistry = ToolRegistry().apply {
        (browser + shell).forEach { tool ->
            register(object : ToolAdapter {
                override val descriptor = tool
            })
        }
    }
}
