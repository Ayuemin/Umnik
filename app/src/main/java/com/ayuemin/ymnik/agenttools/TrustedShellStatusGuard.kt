package com.ayuemin.ymnik.agenttools

/**
 * First real integration path: read-only status of the Local Shell belonging to
 * the submitting chat. New permission is created only by the trusted chat UI,
 * never by text returned from the model, browser, knowledge base or Shell.
 */
internal class TrustedShellStatusGuard(
    registry: ToolRegistry = ExistingLocalToolCatalog.createRegistry()
) {
    private val toolHub = AgentToolHub(registry)

    fun grantFromUiSubmission(
        chatId: String,
        directUserMessageId: String,
        shellEnabledForThisUserRequest: Boolean,
        issuedAtMillis: Long
    ): TaskGrant? {
        if (!shellEnabledForThisUserRequest) return null
        return TaskGrant.fromTrustedUserMessage(
            taskId = directUserMessageId,
            originUserMessageId = directUserMessageId,
            issuedAtMillis = issuedAtMillis,
            expiresAtMillis = issuedAtMillis + 15 * 60_000L,
            allowedOperations = setOf(
                TaskAllowance(
                    "local_shell_status",
                    ToolAction.READ,
                    resourceForChat(chatId)
                )
            )
        )
    }

    suspend fun <T> readStatus(
        grant: TaskGrant?,
        trustedChatId: String,
        directUserMessageId: String,
        nowMillis: () -> Long,
        operation: suspend () -> T
    ): ToolDispatchResult<T> {
        val resource = resourceForChat(trustedChatId)
        return toolHub.dispatch(
            grant = grant,
            requested = ToolInvocation(
                taskId = directUserMessageId,
                originUserMessageId = directUserMessageId,
                toolId = "local_shell_status",
                action = ToolAction.READ,
                resource = resource
            ),
            // Actual identity comes from the chat selected at UI submission,
            // not from a model-supplied tool argument.
            resolveActualResource = { resourceForChat(trustedChatId) },
            nowMillis = nowMillis,
            operation = operation
        )
    }


    /**
     * Production Shell status path using the same original ToolRegistry and
     * GuardedToolDispatcher as the other trusted callbacks. Runtime ownership
     * is observed inside the adapter, never taken from model arguments.
     */
    suspend fun readObservedStatus(
        grant: TaskGrant?,
        trustedChatId: String,
        directUserMessageId: String,
        nowMillis: () -> Long
    ): ToolDispatchResult<ToolTextResult> {
        val adapter = LocalShellStatusReadAdapter(trustedChatId, nowMillis)
        val hub = AgentToolHub(ExistingLocalToolCatalog.createRegistry(adapter))
        return hub.executeRead(
            grant = grant,
            requested = ToolInvocation(
                taskId = directUserMessageId,
                originUserMessageId = directUserMessageId,
                toolId = adapter.descriptor.id,
                action = ToolAction.READ,
                resource = resourceForChat(trustedChatId)
            ),
            nowMillis = nowMillis
        )
    }

    private fun resourceForChat(chatId: String): TaskResource =
        TaskResource(ToolResourceKind.LOCAL_SESSION, "chat:$chatId")
}
