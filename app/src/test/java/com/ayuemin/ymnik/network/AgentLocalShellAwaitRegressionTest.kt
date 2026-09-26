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


    @Test fun activeShellGuidanceUsesSameWorkerAndKeepsStopAvailable() {
        val vmSource = sequenceOf(
            File("src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"),
            File("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
        ).first { it.isFile }.readText()
        val uiSource = sequenceOf(
            File("src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"),
            File("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
        ).first { it.isFile }.readText()
        val repoSource = sequenceOf(
            File("src/main/java/com/ayuemin/ymnik/data/ChatRepository.kt"),
            File("app/src/main/java/com/ayuemin/ymnik/data/ChatRepository.kt")
        ).first { it.isFile }.readText()

        val route = vmSource.indexOf("if (trySendActiveLocalShellGuidance(text)) return")
        val activeRequestGuard = vmSource.indexOf("if (RequestExecutionManager.hasActiveChat(chatId))", route)
        assertTrue(route >= 0)
        assertTrue(activeRequestGuard > route)
        assertTrue(vmSource.contains("LocalShellRuntime.addGuidance(clean)"))
        assertTrue(vmSource.contains("deliveryState = \"guidance\""))
        assertTrue(uiSource.contains("localShellGuidanceHere && text.isNotBlank()"))
        assertTrue(uiSource.contains("contentDescription = \"Передать уточнение Local Shell\""))
        assertTrue(uiSource.contains("vm.stopGeneration()"))
        assertTrue(repoSource.contains("deliveryState == \"guidance\""))
        assertTrue(repoSource.contains("lastGuidanceIndex?.plus(1)"))
    }
}
