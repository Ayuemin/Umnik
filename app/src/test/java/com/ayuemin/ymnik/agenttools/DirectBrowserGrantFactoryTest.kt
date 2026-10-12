package com.ayuemin.ymnik.agenttools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectBrowserGrantFactoryTest {
    @Test fun normalizesExactOriginAndStripsCredentialsAndQueryData() {
        assertEquals("https://example.org", DirectBrowserGrantFactory.origin("HTTPS://EXAMPLE.ORG:443/path?secret=123"))
        assertEquals("http://example.org:8080", DirectBrowserGrantFactory.origin("http://EXAMPLE.org:8080/a"))
        assertNull(DirectBrowserGrantFactory.origin("file:///secret.txt"))
        assertNull(DirectBrowserGrantFactory.origin("https://user:pass@example.org"))
        assertNull(DirectBrowserGrantFactory.origin("javascript:alert(1)"))
    }

    @Test fun uiGrantAllowsOnlyNavigationAndReadOfNamedOrigin() {
        val g = requireNotNull(DirectBrowserGrantFactory.fromUiSubmission(
            "task-1", "direct-1", "Открой https://example.org/guide и прочитай страницу",
            1_000L
        ))
        val gate = ToolPermissionGate(ExistingLocalToolCatalog.createRegistry())
        fun check(tool: String, action: ToolAction, host: String) = gate.check(
            g,
            ToolInvocation("task-1", "direct-1", tool, action, TaskResource(ToolResourceKind.WEB_ORIGIN, host)),
            1_001L
        )
        assertEquals(ToolPermissionDecision.ALLOWED, check("local_browser_open", ToolAction.NAVIGATE, "https://example.org"))
        assertEquals(ToolPermissionDecision.ALLOWED, check("local_browser_read", ToolAction.READ, "https://example.org"))
        assertEquals(ToolPermissionDecision.DENIED_OUT_OF_SCOPE, check("local_browser_read", ToolAction.READ, "https://evil.example"))
        assertEquals(ToolPermissionDecision.DENIED_OUT_OF_SCOPE, check("local_browser_click", ToolAction.INTERACT, "https://example.org"))
        assertEquals(ToolPermissionDecision.DENIED_OUT_OF_SCOPE, check("local_browser_download", ToolAction.DOWNLOAD, "https://example.org"))
    }

    @Test fun noDirectUrlMeansNoImplicitWildcardAccess() {
        assertNull(DirectBrowserGrantFactory.fromUiSubmission("t1", "m1", "Найди свежие новости", 1_000L))
    }

    @Test fun taskExpiresAndCannotSwitchMessages() {
        val g = requireNotNull(DirectBrowserGrantFactory.fromUiSubmission(
            "task-1", "direct-1", "https://example.org", 10_000L
        ))
        val gate = ToolPermissionGate(ExistingLocalToolCatalog.createRegistry())
        val resource = TaskResource(ToolResourceKind.WEB_ORIGIN, "https://example.org")
        val request = ToolInvocation("task-1", "direct-1", "local_browser_read", ToolAction.READ, resource)
        assertEquals(ToolPermissionDecision.DENIED_WRONG_ORIGIN, gate.check(g, request.copy(originUserMessageId = "tool-output"), 10_001L))
        assertEquals(ToolPermissionDecision.DENIED_OUTSIDE_LIFETIME, gate.check(g, request, 910_000L))
    }
}
