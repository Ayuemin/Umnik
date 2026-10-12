package com.ayuemin.ymnik.agenttools

/**
 * Authorizes Browser's session-local control callbacks; NOT URL navigation,
 * page reading or downloads. Task identity is captured at the trusted UI send.
 *
 * Browser runtime still owns the per-chat session and its WAITING_USER gate.
 * A task grant cannot authorize activity in another chat or re-enable tools.
 */
internal class TrustedBrowserSessionGuard(
    registry: ToolRegistry = ExistingLocalToolCatalog.createRegistry()
) {
    private val dispatcher = GuardedToolDispatcher(ToolPermissionGate(registry))

    fun grantFromUiSubmission(
        chatId: String,
        directUserMessageId: String,
        browserEnabledForRequest: Boolean,
        issuedAtMillis: Long
    ): TaskGrant? {
        if (!browserEnabledForRequest) return null
        val resource = resourceForChat(chatId)
        return TaskGrant.fromTrustedUserMessage(
            taskId = directUserMessageId,
            originUserMessageId = directUserMessageId,
            issuedAtMillis = issuedAtMillis,
            expiresAtMillis = issuedAtMillis + 60 * 60_000L,
            allowedOperations = setOf(
                TaskAllowance("local_browser_takeover", ToolAction.CONTROL, resource),
                TaskAllowance("local_browser_done", ToolAction.CONTROL, resource)
            )
        )
    }

    suspend fun <T> control(
        grant: TaskGrant?,
        trustedChatId: String,
        directUserMessageId: String,
        toolId: String,
        nowMillis: () -> Long,
        operation: suspend () -> T
    ): ToolDispatchResult<T> {
        require(toolId == "local_browser_takeover" || toolId == "local_browser_done") {
            "Only Browser session-control operations belong to this guard"
        }
        val resource = resourceForChat(trustedChatId)
        return dispatcher.invoke(
            grant = grant,
            requested = ToolInvocation(
                taskId = directUserMessageId,
                originUserMessageId = directUserMessageId,
                toolId = toolId,
                action = ToolAction.CONTROL,
                resource = resource
            ),
            resolveActualResource = { resourceForChat(trustedChatId) },
            nowMillis = nowMillis,
            operation = operation
        )
    }

    private fun resourceForChat(chatId: String) =
        TaskResource(ToolResourceKind.LOCAL_SESSION, "browser-chat:$chatId")
}
