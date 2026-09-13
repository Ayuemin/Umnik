package com.ayuemin.ymnik.network

import com.google.gson.JsonParser
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
