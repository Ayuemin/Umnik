package com.ayuemin.ymnik.agenttools

/**
 * First real integration path: read-only status of the Local Shell belonging to
 * the submitting chat. New permission is created only by the trusted chat UI,
 * never by text returned from the model, browser, knowledge base or Shell.
 */
internal class TrustedShellStatusGuard(
    registry: ToolRegistry = ExistingLocalToolCatalog.createRegistry()
) {
    private val dispatcher = GuardedToolDispatcher(ToolPermissionGate(registry))

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
        return dispatcher.invoke(
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

    private fun resourceForChat(chatId: String): TaskResource =
        TaskResource(ToolResourceKind.LOCAL_SESSION, "chat:$chatId")
}
