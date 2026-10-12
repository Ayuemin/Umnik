package com.ayuemin.ymnik.agenttools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExistingLocalToolCatalogTest {
    private val registry = ExistingLocalToolCatalog.createRegistry()

    @Test fun allExistingBrowserAndShellCallsAreDeclaredPrecisely() {
        val expected = setOf(
            "local_browser_open", "local_browser_read", "local_browser_click",
            "local_browser_download", "local_browser_type", "local_browser_scroll",
            "local_browser_back", "local_browser_wait", "local_browser_takeover",
            "local_browser_done", "local_shell_start", "local_shell_status",
            "local_shell_note", "local_shell_stop"
        )
        assertEquals(expected, registry.descriptors().map { it.id }.toSet())
        assertFalse(registry.contains("remote_ssh_exec"))
    }

    @Test fun sideEffectingToolsDoNotMasqueradeAsReads() {
        assertEquals(setOf(ToolAction.EXECUTE), registry.descriptor("local_shell_start")?.actions)
        assertEquals(setOf(ToolAction.INTERACT), registry.descriptor("local_browser_type")?.actions)
        assertEquals(setOf(ToolResourceKind.LOCAL_SESSION), registry.descriptor("local_browser_takeover")?.resourceKinds)
        assertEquals(setOf(ToolResourceKind.LOCAL_SESSION), registry.descriptor("local_browser_done")?.resourceKinds)
        assertEquals(setOf(ToolAction.DOWNLOAD), registry.descriptor("local_browser_download")?.actions)
        assertEquals(setOf(ToolAction.READ), registry.descriptor("local_browser_read")?.actions)
        assertTrue(ToolAction.INTERACT in registry.descriptor("local_browser_click")!!.actions)
        assertEquals(setOf(ToolAction.NAVIGATE), registry.descriptor("local_browser_open")?.actions)
        assertEquals(setOf(ToolAction.CONTROL), registry.descriptor("local_shell_stop")?.actions)
        assertEquals(setOf(ToolResourceKind.LOCAL_SESSION), registry.descriptor("local_shell_status")?.resourceKinds)
    }
}
