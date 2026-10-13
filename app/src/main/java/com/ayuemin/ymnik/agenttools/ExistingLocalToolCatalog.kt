package com.ayuemin.ymnik.agenttools

/**
 * Descriptors for tools already exposed by OpenRouterClient.
 *
 * READ means inspecting state; NAVIGATE means public web navigation;
 * INTERACT means browser input/click checked by its own sensitive-action
 * confirmation gate; CONTROL means switching tool/session state. Mutating
 * tool operations remain WRITE/UPLOAD/DELETE/EXECUTE.
 *
 * Discovery is NOT execution or a permission grant. Session-only Browser
 * controls and Shell status have guarded callbacks; real WebView navigation,
 * page reads/downloads and Shell execution still use their previous path
 * pending origin/sandbox enforcement inside their serialized runtimes.
 */
internal object ExistingLocalToolCatalog {
    private fun descriptor(
        id: String,
        action: ToolAction,
        resource: ToolResourceKind,
        confirm: Boolean = false,
        publicWebRead: Boolean = false
    ) = ToolDescriptor(
        id = id,
        label = id,
        version = 1,
        actions = setOf(action),
        resourceKinds = if (publicWebRead) setOf(resource, ToolResourceKind.PUBLIC_WEB_SESSION) else setOf(resource),
        confirmActions = if (confirm) setOf(action) else emptySet()
    )

    private val browser = listOf(
        descriptor("local_browser_open", ToolAction.NAVIGATE, ToolResourceKind.WEB_ORIGIN, publicWebRead = true),
        descriptor("local_browser_read", ToolAction.READ, ToolResourceKind.WEB_ORIGIN, publicWebRead = true),
        descriptor("local_browser_click", ToolAction.INTERACT, ToolResourceKind.WEB_ORIGIN),
        descriptor("local_browser_download", ToolAction.DOWNLOAD, ToolResourceKind.WEB_ORIGIN),
        descriptor("local_browser_type", ToolAction.INTERACT, ToolResourceKind.WEB_ORIGIN),
        descriptor("local_browser_scroll", ToolAction.READ, ToolResourceKind.WEB_ORIGIN, publicWebRead = true),
        descriptor("local_browser_back", ToolAction.NAVIGATE, ToolResourceKind.WEB_ORIGIN, publicWebRead = true),
        descriptor("local_browser_wait", ToolAction.READ, ToolResourceKind.WEB_ORIGIN, publicWebRead = true),
        descriptor("local_browser_takeover", ToolAction.CONTROL, ToolResourceKind.LOCAL_SESSION),
        descriptor("local_browser_done", ToolAction.CONTROL, ToolResourceKind.LOCAL_SESSION)
    )

    private val shell = listOf(
        descriptor("local_shell_start", ToolAction.EXECUTE, ToolResourceKind.LOCAL_FILE),
        descriptor("local_shell_status", ToolAction.READ, ToolResourceKind.LOCAL_SESSION),
        descriptor("local_shell_note", ToolAction.WRITE, ToolResourceKind.LOCAL_SESSION),
        descriptor("local_shell_stop", ToolAction.CONTROL, ToolResourceKind.LOCAL_SESSION)
    )

    /** Original descriptor is the sole definition of the Shell status capability. */
    fun shellStatusDescriptor(): ToolDescriptor = shell.first { it.id == "local_shell_status" }

    /**
     * Optional trusted read adapter replaces ONLY its metadata-only registration.
     * All other Browser/Shell descriptors retain their original closed-by-default behavior.
     */
    fun createRegistry(localShellStatusAdapter: ExecutableToolAdapter? = null): ToolRegistry =
        ToolRegistry().apply {
            require(localShellStatusAdapter == null ||
                localShellStatusAdapter.descriptor == shellStatusDescriptor()) {
                "Local Shell status adapter must match the original descriptor exactly"
            }
            (browser + shell).forEach { tool ->
                val selected = if (tool.id == "local_shell_status" && localShellStatusAdapter != null) {
                    localShellStatusAdapter
                } else {
                    object : ToolAdapter { override val descriptor = tool }
                }
                register(selected)
            }
        }
}
