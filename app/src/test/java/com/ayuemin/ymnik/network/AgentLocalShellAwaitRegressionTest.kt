package com.ayuemin.ymnik.network

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLocalShellAwaitRegressionTest {
    @Test fun localShellStartAwaitsTerminalInsteadOfPollingStatus() {
        val source = sequenceOf(
            File("src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"),
            File("app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt")
        ).first { it.isFile }.readText()
        val start = source.indexOf("\"local_shell_start\" ->")
        val end = source.indexOf("\"local_shell_status\" ->", start)
        require(start >= 0 && end > start)
        val branch = source.substring(start, end)
        assertTrue(branch.contains("LocalShellRuntime.awaitTerminal()"))
        assertTrue(branch.contains("LocalShellRuntime.markParentConsumesTerminal()"))
        assertFalse(branch.contains("localShellStatus"))
        assertTrue(branch.contains("if (it is CancellationException) throw it"))
        assertTrue(source.contains("localShellToolsEnabled -> 8"))
        assertTrue(source.contains("AgentToolLoopGuard()"))
    }
}
