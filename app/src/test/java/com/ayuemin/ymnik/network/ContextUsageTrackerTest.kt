package com.ayuemin.ymnik.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextUsageTrackerTest {
    @Test
    fun measurePayloadSeparatesContextLayersAndAttachments() {
        val skillBlock = "===== НАЧАЛО ПОДКЛЮЧЁННЫХ НАВЫКОВ =====\nskill rule\n===== КОНЕЦ ПОДКЛЮЧЁННЫХ НАВЫКОВ ====="
        val memoryBlock = "===== ДОЛГОВРЕМЕННАЯ ПАМЯТЬ ЭТОГО ЧАТА =====\nold fact\n===== КОНЕЦ ДОЛГОВРЕМЕННОЙ ПАМЯТИ ====="
        val payload = JsonObject().apply {
  add("messages", JsonArray().apply {
      add(message("system", "base rule\n$skillBlock\n$memoryBlock"))
      add(message("user", "older question"))
      add(message("assistant", "older answer"))
      add(JsonObject().apply {
          addProperty("role", "user")
          add("content", JsonArray().apply {
              add(JsonObject().apply {
                  addProperty("type", "text")
                  addProperty("text", "current question")
              })
              add(JsonObject().apply {
                  addProperty("type", "image_url")
                  add("image_url", JsonObject().apply {
                      addProperty("url", "data:image/png;base64,AQIDBA==")
                  })
              })
          })
      })
  })
  add("tools", JsonArray().apply {
      add(JsonObject().apply { addProperty("type", "function") })
  })
        }
        val usage = ContextUsageTracker.measurePayload(payload)
        assertTrue(usage.systemPrompt.chars > 0)
        assertTrue(usage.skills.chars > 0)
        assertTrue(usage.memoryRag.chars > 0)
        assertTrue(usage.history.chars > 0)
        assertTrue(usage.tools.bytes > 0)
        assertEquals("current question".length, usage.currentUserPrompt.chars)
        assertEquals(1, usage.attachmentCount)
        assertEquals(4L, usage.attachmentBytes)
    }

    @Test
    fun messageAliasRecoversUsageWhenPersistedRequestIdIsGenerationCounter() {
        val payload = JsonObject().apply {
            add("messages", JsonArray().apply {
                add(message("system", "base rule"))
                add(message("user", "hello"))
            })
        }

        val captured = ContextUsageTracker.capture("network-uuid", payload)
        assertTrue(captured != null)
        ContextUsageTracker.linkToMessage("network-uuid", "chat-1", "user-message-1")

        assertEquals(null, ContextUsageTracker.consume("1"))
        val recovered = ContextUsageTracker.consumeForMessage("chat-1", "user-message-1")
        assertEquals(captured, recovered)
        assertEquals(null, ContextUsageTracker.consume("network-uuid"))
    }

    private fun message(role: String, text: String) = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", text)
    }
}
