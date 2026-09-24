package com.ayuemin.ymnik.network

import com.google.gson.Gson
import com.google.gson.JsonParser
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenRouterResponsesCodecTest {
    @Test
    fun extractsOutputTextFromResponsesShape() {
        val root = JsonParser.parseString(
            """
            {
              "output": [
                {
                  "type": "message",
                  "content": [
                    {"type": "output_text", "text": "Первый блок"},
                    {"type": "output_text", "text": "Второй блок"}
                  ]
                }
              ]
            }
            """.trimIndent()
        ).asJsonObject

        assertEquals("Первый блок\nВторой блок", OpenRouterResponsesCodec.extractText(root))
    }

    @Test
    fun parsesCompletedResponsesStream() {
        val stream = Buffer().writeUtf8(
            """
            event: response.created
            data: {"type":"response.created","response":{"id":"resp_1","status":"in_progress"}}

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"готовлю"}

            event: response.completed
            data: {"type":"response.completed","response":{"id":"resp_1","status":"completed","model":"test/model","output":[{"type":"message","content":[{"type":"output_text","text":"Готово"}]}]}}

            data: [DONE]

            """.trimIndent()
        )

        val root = OpenRouterResponsesCodec.readCompletedResponseStream(stream, Gson())
        assertEquals("resp_1", root.get("id").asString)
        assertEquals("completed", root.get("status").asString)
        assertEquals("Готово", OpenRouterResponsesCodec.extractText(root))
    }

    @Test
    fun reportsFailedResponsesStream() {
        val stream = Buffer().writeUtf8(
            """
            data: {"type":"response.created","response":{"id":"resp_2","status":"in_progress"}}

            data: {"type":"response.failed","response":{"id":"resp_2","status":"failed","error":{"message":"Job was cancelled"}}}

            """.trimIndent()
        )

        try {
            OpenRouterResponsesCodec.readCompletedResponseStream(stream, Gson())
            throw AssertionError("Expected stream failure")
        } catch (error: IllegalStateException) {
            assertEquals("Job was cancelled", error.message)
        }
    }

    @Test
    fun findsContainerFilesInsideNestedShellResults() {
        val root = JsonParser.parseString(
            """
            {
              "output": [
                {
                  "type": "shell_call",
                  "container_id": "ctr_123",
                  "result": {
                    "files": [
                      {"id": "cfile_abc", "path": "/workspace/home/report.csv"}
                    ]
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val artifacts = OpenRouterResponsesCodec.collectShellArtifacts(root)
        assertEquals(1, artifacts.size)
        assertEquals("ctr_123", artifacts.single().containerId)
        assertEquals("cfile_abc", artifacts.single().fileId)
        assertEquals("report.csv", artifacts.single().name)
    }
}
