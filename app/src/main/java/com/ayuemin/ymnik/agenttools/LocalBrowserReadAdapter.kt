package com.ayuemin.ymnik.agenttools

import com.ayuemin.ymnik.browser.LocalBrowserRuntime
import com.ayuemin.ymnik.network.LocalWebFetchPolicy

/**
 * Guarded READ of the WebView's already observed document, never navigation.
 * The Browser mutex repeats its ownership and URL checks immediately before DOM inspection.
 */
internal class LocalBrowserReadAdapter(
    private val trustedChatId: String,
    private val directUserMessageId: String,
    private val grant: TaskGrant?,
    private val observeUrl: suspend () -> String? = { LocalBrowserRuntime.observedReadTarget(trustedChatId) },
    private val readPage: suspend () -> String = {
        LocalBrowserRuntime.read(trustedChatId, false, directUserMessageId, grant, observedOnly = true)
    }
) : ExecutableToolAdapter {
    init { require(trustedChatId.isNotBlank() && directUserMessageId.isNotBlank()) }

    override val descriptor: ToolDescriptor = ExistingLocalToolCatalog.browserReadDescriptor()

    override suspend fun resolveActualResource(): TaskResource? {
        val observed = observeUrl() ?: return null
        val valid = runCatching {
            val target = LocalWebFetchPolicy.parseUrl(observed)
            LocalWebFetchPolicy.validateLiteralHost(target.host)
            true
        }.getOrDefault(false)
        return if (valid) TaskResource(ToolResourceKind.PUBLIC_WEB_SESSION, "browser-public:$trustedChatId")
        else null
    }

    override suspend fun execute(): ToolTextResult = ToolTextResult(readPage())
}
