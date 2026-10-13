package com.ayuemin.ymnik.agenttools

import com.ayuemin.ymnik.AsyncJobEvents
import com.ayuemin.ymnik.LocalShellActivity

/**
 * Read-only bridge to an observed Local Shell runtime, bound to the chat
 * captured by trusted UI submission. No Shell start, stop or file access.
 */
internal class LocalShellStatusReadAdapter(
    private val trustedChatId: String,
    private val nowMillis: () -> Long,
    private val observe: () -> LocalShellActivity? = { AsyncJobEvents.localShellActivity.value }
) : ExecutableToolAdapter {
    init {
        require(trustedChatId.isNotBlank())
    }

    override val descriptor: ToolDescriptor = ExistingLocalToolCatalog.shellStatusDescriptor()

    override suspend fun resolveActualResource(): TaskResource {
        // If Shell is active, trust the observed owner, not the requested chat.
        // Reading an inactive Shell slot is scoped to the trusted chat request.
        val observedOwner = observe()?.chatId
        return TaskResource(ToolResourceKind.LOCAL_SESSION, "chat:${observedOwner ?: trustedChatId}")
    }

    override suspend fun execute(): ToolTextResult {
        val active = observe()
        if (active != null && active.chatId != trustedChatId) {
            // The observed Shell may change after the gate's resource check.
            // Never leak the new owner's status; fail closed with no details.
            return ToolTextResult("""{"ok":false,"error":"Local Shell session changed during status read"}""")
        }
        return ToolTextResult(LocalShellStatusJson.render(trustedChatId, active, nowMillis()))
    }
}
