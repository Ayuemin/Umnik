package com.ayuemin.ymnik.agenttools

import com.ayuemin.ymnik.AsyncJobEvents
import com.ayuemin.ymnik.LocalShellActivity
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalShellStatusReadAdapterTest {
    private val guard = TrustedShellStatusGuard()

    private fun clearShellState() {
        AsyncJobEvents.localShellActivity.value?.let {
            AsyncJobEvents.markLocalShellFinished(it.chatId, it.taskId)
        }
    }

    @Test fun observedInactiveShellReturnsOriginalStatusShape() = runBlocking {
        clearShellState()
        val grant = guard.grantFromUiSubmission("chat-a", "direct-a", true, 100L)
        val result = guard.readObservedStatus(grant, "chat-a", "direct-a") { 200L }
        val value = (result as ToolDispatchResult.Completed).value.text
        assertEquals(JsonParser.parseString("""{"ok":true,"running":false}"""), JsonParser.parseString(value))
    }

    @Test fun observedActiveOwnShellReportsRealRuntimeProgress() = runBlocking {
        clearShellState()
        try {
            AsyncJobEvents.markLocalShellRunning(
                chatId = "chat-a", modelId = "status-model", attachmentCount = 2,
                maxTurns = 25, originUserMessageId = "direct-a", taskId = "shell-1"
            )
            AsyncJobEvents.updateLocalShellProgress("chat-a", "processing", 4, 7, "shell-1")
            val grant = guard.grantFromUiSubmission("chat-a", "direct-a", true, 100L)
            val value = (guard.readObservedStatus(grant, "chat-a", "direct-a") { 200L }
                as ToolDispatchResult.Completed).value.text
            val data = JsonParser.parseString(value).asJsonObject
            assertTrue(data.get("running").asBoolean)
            assertTrue(data.get("in_this_chat").asBoolean)
            assertEquals("status-model", data.get("model").asString)
            assertEquals("processing", data.get("status").asString)
            assertEquals(4, data.get("turn").asInt)
            assertEquals(7, data.get("tool_calls").asInt)
        } finally {
            clearShellState()
        }
    }

    @Test fun observedOtherChatCannotBeReadWithThisChatsGrant() = runBlocking {
        clearShellState()
        try {
            AsyncJobEvents.markLocalShellRunning("private-chat", "private-model", taskId = "private-task")
            val grant = guard.grantFromUiSubmission("chat-a", "direct-a", true, 100L)
            val result = guard.readObservedStatus(grant, "chat-a", "direct-a") { 200L }
            assertEquals(ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUT_OF_SCOPE), result)
        } finally {
            clearShellState()
        }
    }

    @Test fun deniedWithoutGrantOrForExpiredOrForgedUserMessage() = runBlocking {
        clearShellState()
        val grant = guard.grantFromUiSubmission("chat-a", "direct-a", true, 100L)
        assertEquals(
            ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_MISSING_GRANT),
            guard.readObservedStatus(null, "chat-a", "direct-a") { 200L }
        )
        assertEquals(
            ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_OUTSIDE_LIFETIME),
            guard.readObservedStatus(grant, "chat-a", "direct-a") { 900_100L }
        )
        assertEquals(
            ToolDispatchResult.Blocked(ToolPermissionDecision.DENIED_WRONG_TASK),
            guard.readObservedStatus(grant, "chat-a", "model-output") { 200L }
        )
    }

    @Test fun ownershipChangeDuringDispatchDoesNotLeakForeignStatus() = runBlocking {
        var reads = 0
        val activeSelf = LocalShellActivity("chat-a", modelId = "public")
        val activeForeign = LocalShellActivity("private-chat", modelId = "secret-model")
        val adapter = LocalShellStatusReadAdapter("chat-a", { 300L }) {
            if (reads++ == 0) activeSelf else activeForeign
        }
        val hub = AgentToolHub(ExistingLocalToolCatalog.createRegistry(adapter))
        val grant = guard.grantFromUiSubmission("chat-a", "direct-a", true, 100L)
        val call = ToolInvocation(
            "direct-a", "direct-a", "local_shell_status", ToolAction.READ,
            TaskResource(ToolResourceKind.LOCAL_SESSION, "chat:chat-a")
        )
        val result = hub.executeRead(grant, call) { 200L }
        val payload = (result as ToolDispatchResult.Completed).value.text
        assertFalse(payload.contains("secret-model"))
        assertFalse(payload.contains("private-chat"))
        assertFalse(JsonParser.parseString(payload).asJsonObject.get("ok").asBoolean)
        assertEquals(2, reads)
    }

    @Test fun catalogRejectsAdapterWithDifferentCapability() {
        val adapter = object : ExecutableToolAdapter {
            override val descriptor = ExistingLocalToolCatalog.shellStatusDescriptor()
                .copy(actions = setOf(ToolAction.EXECUTE))
            override suspend fun resolveActualResource() = null
            override suspend fun execute() = ToolTextResult("forbidden")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExistingLocalToolCatalog.createRegistry(adapter)
        }
    }

    @Test fun legacyShellStatusFormatStillHandlesOtherChatBusy() {
        val json = LocalShellStatusJson.render(
            "chat-a", LocalShellActivity("chat-b", modelId = "private-model"), 200L
        )
        val data = JsonParser.parseString(json).asJsonObject
        assertTrue(data.get("running").asBoolean)
        assertFalse(data.get("in_this_chat").asBoolean)
        assertFalse(json.contains("private-model"))
    }
}
