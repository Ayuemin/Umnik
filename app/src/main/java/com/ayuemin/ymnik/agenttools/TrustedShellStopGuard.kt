package com.ayuemin.ymnik.agenttools

/**
 * Let the agent cancel the Shell task it started from this direct user message.
 * A fresh direct user stop command can also cancel an older task in the same
 * chat. Routine completion is automatic and needs no manual confirmation.
 */
internal class TrustedShellStopGuard(
    registry: ToolRegistry = ExistingLocalToolCatalog.createRegistry()
) {
    private val dispatcher = GuardedToolDispatcher(ToolPermissionGate(registry))

    fun grantFromUiSubmission(
        chatId: String,
        directUserMessageId: String,
        directUserText: String,
        shellEnabledForThisUserRequest: Boolean,
        issuedAtMillis: Long,
        activeTaskIdAtSubmission: String? = null
    ): TaskGrant? {
        if (!shellEnabledForThisUserRequest) return null
        val allowed = mutableSetOf(
            TaskAllowance(
                "local_shell_stop", ToolAction.CONTROL,
                taskResource(chatId, directUserMessageId)
            )
        )
        if (isDirectStopRequest(directUserText) && !activeTaskIdAtSubmission.isNullOrBlank()) {
            allowed += TaskAllowance(
                "local_shell_stop", ToolAction.CONTROL,
                runningTaskResource(chatId, activeTaskIdAtSubmission)
            )
        }
        return TaskGrant.fromTrustedUserMessage(
            taskId = directUserMessageId,
            originUserMessageId = directUserMessageId,
            issuedAtMillis = issuedAtMillis,
            expiresAtMillis = issuedAtMillis + 4 * 60 * 60_000L,
            allowedOperations = allowed
        )
    }

    suspend fun <T> stop(
        grant: TaskGrant?,
        trustedChatId: String,
        directUserMessageId: String,
        nowMillis: () -> Long,
        activeChatId: String?,
        activeOwnerUserMessageId: String?,
        activeTaskId: String?,
        operation: suspend (String) -> T
    ): ToolDispatchResult<T> {
        if (activeChatId != trustedChatId || activeTaskId.isNullOrBlank()) {
            return ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE)
        }
        val resource = if (activeOwnerUserMessageId == directUserMessageId) {
            taskResource(trustedChatId, directUserMessageId)
        } else {
            runningTaskResource(trustedChatId, activeTaskId)
        }
        return dispatcher.invoke(
            grant = grant,
            requested = ToolInvocation(
                taskId = directUserMessageId,
                originUserMessageId = directUserMessageId,
                toolId = "local_shell_stop",
                action = ToolAction.CONTROL,
                resource = resource
            ),
            resolveActualResource = { resource },
            nowMillis = nowMillis,
            operation = { operation(activeTaskId) }
        )
    }

    companion object {
        private fun runningTaskResource(chatId: String, taskId: String): TaskResource =
            TaskResource(ToolResourceKind.LOCAL_SESSION, "chat:$chatId:task:$taskId")
        private fun taskResource(chatId: String, originMessage: String): TaskResource =
            TaskResource(ToolResourceKind.LOCAL_SESSION, "chat:$chatId:owner:$originMessage")

        private val russian = Regex(
            """^(?:пожалуйста[\s,]+)?(?:останови|отмени|прерви|заверши)\s+(?:работу\s+)?(?:local\s+shell|shell|локальный\s+шелл|локальный\s+shell|шелл)(?=$|[\s,;.!?])""",
            RegexOption.IGNORE_CASE
        )
        private val english = Regex(
            """^(?:please[\s,]+)?(?:stop|cancel|terminate)\s+(?:the\s+)?(?:local\s+)?shell(?=$|[\s,;.!?])""",
            RegexOption.IGNORE_CASE
        )
        fun isDirectStopRequest(text: String): Boolean =
            russian.containsMatchIn(text.trim()) || english.containsMatchIn(text.trim())
    }
}
