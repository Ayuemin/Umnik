package com.ayuemin.ymnik

import com.ayuemin.ymnik.model.GeneratedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalShellRuntimeTest {
    @After fun cleanup() {
        AsyncJobEvents.markLocalShellFinished("test-chat")
        LocalShellRuntime.clear()
    }

    @Test fun slowChildSuspendsParentWithoutPolling() = runBlocking {
        LocalShellRuntime.prepareForStart()
        val startedAt = System.nanoTime()
        val child = launch(Dispatchers.Default) {
            delay(250L)
            LocalShellRuntime.completeTerminal(LocalShellTerminalResult(
                state = LocalShellTerminalState.DONE,
                text = "AGENT TEST OK",
                files = listOf(GeneratedFile("f1", "agent_test.txt", "text/plain", "/tmp/agent_test.txt", 13L)),
                turns = 4,
                toolCalls = 5
            ))
        }
        val result = LocalShellRuntime.awaitTerminal()
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
        child.join()
        assertTrue("parent resumed too early: ${elapsedMs}ms", elapsedMs >= 180L)
        assertEquals(LocalShellTerminalState.DONE, result.state)
        assertEquals("AGENT TEST OK", result.text)
        assertEquals(4, result.turns)
        assertEquals(5, result.toolCalls)
        assertEquals("agent_test.txt", result.files.single().name)
    }

    @Test fun failedAndStoppedAreTerminalResults() = runBlocking {
        LocalShellRuntime.prepareForStart()
        LocalShellRuntime.completeTerminal(LocalShellTerminalResult(LocalShellTerminalState.FAILED, error = "boom"))
        assertEquals(LocalShellTerminalState.FAILED, LocalShellRuntime.awaitTerminal().state)
        LocalShellRuntime.prepareForStart()
        LocalShellRuntime.completeTerminal(LocalShellTerminalResult(LocalShellTerminalState.STOPPED, error = "stopped"))
        val stopped = LocalShellRuntime.awaitTerminal()
        assertFalse(stopped.ok)
        assertEquals(LocalShellTerminalState.STOPPED, stopped.state)
    }

    @Test fun guidanceUsesSameRuntimeAndParentFlagResets() {
        LocalShellRuntime.prepareForStart()
        AsyncJobEvents.markLocalShellRunning("test-chat", "model", 0, 500)
        assertTrue(LocalShellRuntime.addGuidance("same worker"))
        assertEquals(listOf("same worker"), LocalShellRuntime.drainGuidance())
        LocalShellRuntime.markParentConsumesTerminal()
        assertTrue(LocalShellRuntime.parentConsumesTerminal())
        LocalShellRuntime.prepareForStart()
        assertFalse(LocalShellRuntime.parentConsumesTerminal())
    }
}
