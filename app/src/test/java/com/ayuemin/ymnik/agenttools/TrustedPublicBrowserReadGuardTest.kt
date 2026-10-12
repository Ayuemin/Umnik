package com.ayuemin.ymnik.agenttools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrustedPublicBrowserReadGuardTest {
    private val guard = TrustedPublicBrowserReadGuard()

    private fun check(
        grant: TaskGrant?,
        chat: String = "chat-1",
        message: String = "direct-1",
        tool: String = "local_browser_open",
        url: String = "https://example.com/article",
        time: Long = 1_500L
    ): ToolPermissionDecision =
        guard.checkActualPublicTarget(grant, chat, message, tool, url, time)

    @Test fun directBrowserEnabledTurnCanReadAndNavigateBetweenPublicSites() {
        val grant = guard.grantFromUiSubmission("chat-1", "direct-1", true, 1_000L)
        assertEquals(ToolPermissionDecision.ALLOWED, check(grant))
        assertEquals(ToolPermissionDecision.ALLOWED, check(grant, tool = "local_browser_read", url = "https://other.org/page"))
        assertEquals(ToolPermissionDecision.ALLOWED, check(grant, tool = "local_browser_wait", url = "http://example.net"))
        assertEquals(ToolPermissionDecision.ALLOWED, check(grant, tool = "local_browser_back", url = "https://other.org"))
    }

    @Test fun noBrowserPermissionNeverIssuesPublicWebScope() {
        val grant = guard.grantFromUiSubmission("chat-1", "direct-1", false, 1_000L)
        assertNull(grant)
        assertEquals(ToolPermissionDecision.DENIED_MISSING_GRANT, check(grant))
    }

    @Test fun doesNotGrantClicksFormsDownloadsOrShell() {
        val grant = guard.grantFromUiSubmission("chat-1", "direct-1", true, 1_000L)
        for (tool in listOf("local_browser_click", "local_browser_type", "local_browser_download", "local_shell_start")) {
            assertEquals(ToolPermissionDecision.DENIED_UNDECLARED_CAPABILITY, check(grant, tool = tool))
        }
        val gate = ToolPermissionGate(ExistingLocalToolCatalog.createRegistry())
        val resource = TaskResource(ToolResourceKind.WEB_ORIGIN, "https://example.com")
        assertEquals(
            ToolPermissionDecision.DENIED_OUT_OF_SCOPE,
            gate.check(
                grant,
                ToolInvocation("direct-1", "direct-1", "local_browser_open", ToolAction.NAVIGATE, resource),
                1_500L
            )
        )
    }

    @Test fun rejectsUnsafeOrCredentialBearingActualUrls() {
        val grant = guard.grantFromUiSubmission("chat-1", "direct-1", true, 1_000L)
        for (url in listOf(
            "file:///sdcard/private.txt",
            "javascript:alert(1)",
            "http://localhost/admin",
            "http://127.0.0.1/",
            "http://192.168.1.1/",
            "http://169.254.169.254/metadata",
            "https://user:secret@example.com/",
            "https://example.com:0/",
            ""
        )) {
            assertEquals("unexpectedly permitted: $url", ToolPermissionDecision.DENIED_OUT_OF_SCOPE, check(grant, url = url))
        }
    }

    @Test fun grantsCannotMoveAcrossChatTurnOrTime() {
        val grant = guard.grantFromUiSubmission("chat-1", "direct-1", true, 1_000L)
        assertEquals(ToolPermissionDecision.DENIED_OUT_OF_SCOPE, check(grant, chat = "chat-2"))
        assertEquals(ToolPermissionDecision.DENIED_WRONG_TASK, check(grant, message = "direct-2"))
        assertEquals(ToolPermissionDecision.DENIED_OUTSIDE_LIFETIME, check(grant, time = 1_801_000L))
    }
}
