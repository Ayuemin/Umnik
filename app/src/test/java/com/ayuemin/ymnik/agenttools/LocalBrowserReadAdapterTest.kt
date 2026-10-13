package com.ayuemin.ymnik.agenttools

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalBrowserReadAdapterTest {
    private val chat = "chat-1"
    private val message = "direct-1"
    private val guard = TrustedPublicBrowserReadGuard()
    private val resource = TaskResource(ToolResourceKind.PUBLIC_WEB_SESSION, "browser-public:$chat")

    private fun <T> immediate(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return requireNotNull(outcome).getOrThrow()
    }

    private fun run(
        grant: TaskGrant?,
        url: String? = "https://example.org/page",
        turn: String = message,
        time: Long = 1_500L,
        full: Boolean = false,
        onRead: () -> String
    ): ToolDispatchResult<ToolTextResult> {
        val adapter = LocalBrowserReadAdapter(
            trustedChatId = chat, directUserMessageId = message, grant = grant,
            full = full, observeUrl = { url }, readPage = { onRead() }
        )
        val hub = AgentToolHub(ExistingLocalToolCatalog.createRegistry(localBrowserReadAdapter = adapter))
        return immediate {
            hub.executeRead(
                grant,
                ToolInvocation(turn, turn, "local_browser_read", ToolAction.READ, resource)
            ) { time }
        }
    }

    @Test fun exactDescriptorAndFullResultAreRetained() {
        val grant = guard.grantFromUiSubmission(chat, message, true, 1_000L)
        var count = 0
        val result = run(grant, full = true) { count++; """{"ok":true,"content":"page"}""" }
        assertEquals(ToolDispatchResult.Completed(ToolTextResult("""{"ok":true,"content":"page"}""")), result)
        assertEquals(1, count)
        assertEquals(ExistingLocalToolCatalog.browserReadDescriptor(),
            ExistingLocalToolCatalog.createRegistry().descriptor("local_browser_read"))
    }

    @Test fun missingExpiredAndForgedUserGrantNeverExecute() {
        val grant = guard.grantFromUiSubmission(chat, message, true, 1_000L)
        var count = 0
        val callback = { count++; "private" }
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_MISSING_GRANT),
            run(null, onRead = callback))
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_WRONG_TASK),
            run(grant, turn = "fake", onRead = callback))
        assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUTSIDE_LIFETIME),
            run(grant, time = 1_801_000L, onRead = callback))
        assertEquals(0, count)
    }

    @Test fun staleAndPrivateObservedUrlsNeverExecute() {
        val grant = guard.grantFromUiSubmission(chat, message, true, 1_000L)
        var count = 0
        val callback = { count++; "private" }
        for (url in listOf<String?>(null, "", "file:///private", "http://127.0.0.1",
            "http://169.254.169.254", "https://user:pass@example.org/")) {
            assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE),
                run(grant, url = url, onRead = callback))
        }
        assertEquals(0, count)
    }

    @Test fun unregisteredBrowserCallsRemainMetadataOnly() {
        val registry = ExistingLocalToolCatalog.createRegistry()
        assertEquals(null, registry.executable("local_browser_read"))
        assertEquals(null, registry.executable("local_browser_click"))
    }
}
