package com.ayuemin.ymnik.agenttools

/** Only an explicit user-submitted stop instruction may cancel a running Local Shell. */
internal class TrustedShellStopGuard(
    registry: ToolRegistry = ExistingLocalToolCatalog.createRegistry()
) {
    private val dispatcher = GuardedToolDispatcher(ToolPermissionGate(registry))

    fun grantFromUiSubmission(
        chatId: String,
        directUserMessageId: String,
        directUserText: String,
        shellEnabledForThisUserRequest: Boolean,
        issuedAtMillis: Long
    ): TaskGrant? {
        if (!shellEnabledForThisUserRequest || !isDirectStopRequest(directUserText)) return null
        return TaskGrant.fromTrustedUserMessage(
            taskId = directUserMessageId,
            originUserMessageId = directUserMessageId,
            issuedAtMillis = issuedAtMillis,
            expiresAtMillis = issuedAtMillis + 15 * 60_000L,
            allowedOperations = setOf(
                TaskAllowance(
                    "local_shell_stop", ToolAction.CONTROL,
                    TaskResource(ToolResourceKind.LOCAL_SESSION, "chat:$chatId")
                )
            )
        )
    }

    suspend fun <T> stop(
        grant: TaskGrant?,
        trustedChatId: String,
        directUserMessageId: String,
        nowMillis: () -> Long,
        operation: suspend () -> T
    ): ToolDispatchResult<T> {
        val resource = TaskResource(ToolResourceKind.LOCAL_SESSION, "chat:$trustedChatId")
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
            operation = operation
        )
    }

    companion object {
        // Anchored so negations and instructions quoted inside other tasks
        // cannot authorize cancellation.
        private val russian = Regex(
            """^(?:пожалуйста[\s,]+)?(?:останови|отмени|прерви|заверши)\s+(?:работу\s+)?(?:local\s+shell|shell|локальный\s+шелл|локальный\s+shell|шелл)(?=$|[\s,;.!?])""",
            RegexOption.IGNORE_CASE
        )
        private val english = Regex(
            """^(?:please[\s,]+)?(?:stop|cancel|terminate)\s+(?:the\s+)?(?:local\s+)?shell(?=$|[\s,;.!?])""",
            RegexOption.IGNORE_CASE
        )

        fun isDirectStopRequest(text: String): Boolean {
            val clean = text.trim()
            return russian.containsMatchIn(clean) || english.containsMatchIn(clean)
        }
    }
}
