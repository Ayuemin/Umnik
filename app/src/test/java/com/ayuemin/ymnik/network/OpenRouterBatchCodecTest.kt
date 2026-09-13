package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.BatchJobStatus
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterBatchCodecTest {
    @Test
    fun stripsBatchVariantAndBuildsOfficialPayloadShape() {
        val body = JsonObject().apply {
            addProperty("model", "wrong/model:batch")
            addProperty("stream", true)
        }
        val payload = OpenRouterBatchCodec.createPayload(
            "deepseek/deepseek-v4-pro-0813:batch",
            listOf(OpenRouterBatchClient.BatchRequest("one", body))
        )

        assertEquals("/v1/chat/completions", payload.get("endpoint").asString)
        assertEquals("deepseek/deepseek-v4-pro-0813", payload.get("model").asString)
        val requestBody = payload.getAsJsonArray("requests")[0].asJsonObject.getAsJsonObject("body")
        assertEquals("deepseek/deepseek-v4-pro-0813", requestBody.get("model").asString)
        assertFalse(requestBody.has("stream"))
    }

    @Test
    fun derivesBetaBatchUrlFromOpenRouterV1Base() {
        assertEquals(
            "https://openrouter.ai/api/beta/batches",
            OpenRouterBatchCodec.batchesUrl("https://openrouter.ai/api/v1/")
        )
    }

    @Test
    fun parsesCompletedChatCompletionResult() {
        val snapshot = OpenRouterBatchCodec.parseSnapshot(
            """
            {
              "id": "batch_123",
              "status": "completed",
              "results": [
                {
                  "custom_id": "request-1",
                  "response": {
                    "status_code": 200,
                    "body": {
                      "choices": [
                        {"message": {"role": "assistant", "content": "Готовый ответ"}}
                      ]
                    }
                  }
                }
              ]
            }
            """.trimIndent(),
            mapOf("request-1" to "Документ 1")
        )

        assertEquals("batch_123", snapshot.remoteId)
        assertEquals(BatchJobStatus.COMPLETED, snapshot.status)
        assertEquals(1, snapshot.items.size)
        assertEquals("Документ 1", snapshot.items.single().label)
        assertEquals("Готовый ответ", snapshot.items.single().resultText)
    }

    @Test
    fun keepsPerItemErrorsInsteadOfInventingText() {
        val snapshot = OpenRouterBatchCodec.parseSnapshot(
            """
            {
              "id": "batch_failed_item",
              "status": "completed",
              "results": [
                {
                  "custom_id": "request-2",
                  "error": {"message": "provider rejected request"}
                }
              ]
            }
            """.trimIndent()
        )

        assertTrue(snapshot.items.single().resultText == null)
        assertEquals("provider rejected request", snapshot.items.single().error)
    }
}
