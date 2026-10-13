package com.ayuemin.ymnik.agenttools

import com.ayuemin.ymnik.network.LocalWebFetchPolicy

/**
 * A narrow, time-limited PUBLIC-READ browsing capability derived only from
 * an actual direct UI submission with Browser enabled for that request.
 *
 * It is not an origin wildcard: the grant is bound to one chat and user turn
 * through PUBLIC_WEB_SESSION. Every adapter invocation MUST independently
 * validate the real target URL (including redirects inside WebView).
 *
 * This policy only authorizes read-only navigation and inspection. It does
 * NOT authorize browser clicks, form submission, downloads, Shell or uploads.
 *
 * Only observed DOM reads enter ToolBus. Navigation and mutation still need
 * separate network-layer hardening and per-action resource checks.
 */
internal class TrustedPublicBrowserReadGuard(
    registry: ToolRegistry = ExistingLocalToolCatalog.createRegistry()
) {
    private val gate = ToolPermissionGate(registry)

    fun grantFromUiSubmission(
        trustedChatId: String,
        directUserMessageId: String,
        browserEnabledForRequest: Boolean,
        issuedAtMillis: Long
    ): TaskGrant? {
        if (!browserEnabledForRequest) return null
        val resource = sessionResource(trustedChatId)
        val operations = actions.map { (toolId, action) ->
            TaskAllowance(toolId, action, resource)
        }.toSet()
        return TaskGrant.fromTrustedUserMessage(
            taskId = directUserMessageId,
            originUserMessageId = directUserMessageId,
            issuedAtMillis = issuedAtMillis,
            expiresAtMillis = issuedAtMillis + 30 * 60_000L,
            allowedOperations = operations
        )
    }

    /**
     * [actualRuntimeUrl] must come from WebView's observed target/current URL,
     * NEVER directly from model args or untrusted web text.
     *
     * This performs syntactic and literal-host validation. DNS/SSRF checks
     * and redirect enforcement are still mandatory in LocalBrowserRuntime.
     */
    fun checkActualPublicTarget(
        grant: TaskGrant?,
        trustedChatId: String,
        directUserMessageId: String,
        toolId: String,
        actualRuntimeUrl: String,
        nowMillis: Long
    ): ToolPermissionDecision {
        val action = actions[toolId] ?: return ToolPermissionDecision.DENIED_UNDECLARED_CAPABILITY
        val isValid = runCatching {
            val parsed = LocalWebFetchPolicy.parseUrl(actualRuntimeUrl)
            LocalWebFetchPolicy.validateLiteralHost(parsed.host)
            true
        }.getOrDefault(false)
        if (!isValid) return ToolPermissionDecision.DENIED_OUT_OF_SCOPE
        return gate.check(
            grant,
            ToolInvocation(
                taskId = directUserMessageId,
                originUserMessageId = directUserMessageId,
                toolId = toolId,
                action = action,
                resource = sessionResource(trustedChatId)
            ),
            nowMillis
        )
    }

    /** Execute only an already observed DOM read through the existing guarded Hub. */
    suspend fun readObservedPage(
        grant: TaskGrant?,
        trustedChatId: String,
        directUserMessageId: String,
        full: Boolean,
        nowMillis: () -> Long
    ): ToolDispatchResult<ToolTextResult> {
        val adapter = LocalBrowserReadAdapter(trustedChatId, directUserMessageId, grant, full)
        val hub = AgentToolHub(ExistingLocalToolCatalog.createRegistry(localBrowserReadAdapter = adapter))
        return hub.executeRead(
            grant = grant,
            requested = ToolInvocation(
                taskId = directUserMessageId,
                originUserMessageId = directUserMessageId,
                toolId = adapter.descriptor.id,
                action = ToolAction.READ,
                resource = sessionResource(trustedChatId)
            ),
            nowMillis = nowMillis
        )
    }

    private fun sessionResource(chatId: String) =
        TaskResource(ToolResourceKind.PUBLIC_WEB_SESSION, "browser-public:$chatId")

    private companion object {
        val actions = mapOf(
            "local_browser_open" to ToolAction.NAVIGATE,
            "local_browser_read" to ToolAction.READ,
            "local_browser_scroll" to ToolAction.READ,
            "local_browser_wait" to ToolAction.READ,
            "local_browser_back" to ToolAction.NAVIGATE
        )
    }
}
