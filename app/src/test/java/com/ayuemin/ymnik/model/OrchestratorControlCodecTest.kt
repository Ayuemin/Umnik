package com.ayuemin.ymnik.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchestratorControlCodecTest {
    @Test
    fun parsesPlanFromFencedJsonAndKeepsDefaults() {
        val plan = OrchestratorControlCodec.parse(
            """
            ```json
            {
              "reply": "Сначала исследование, потом автор",
              "actions": [
                {
                  "type": "execute_chat",
                  "title": "Исследование",
                  "targetChatId": "chat-a",
                  "prompt": "Найди варианты",
                  "temporaryWebSearchEnabled": true
                },
                {
                  "type": "EXECUTE_CHAT",
                  "targetChatId": "chat-b",
                  "prompt": "Напиши итог"
                }
              ]
            }
            ```
            """.trimIndent()
        )

        assertTrue(plan.execute)
        assertEquals(2, plan.actions.size)
        assertTrue(plan.actions.first().temporaryWebSearchEnabled == true)
        assertTrue(plan.actions.first().passPreviousResult)
        assertEquals("chat-b", plan.actions.last().targetChatId)
    }

    @Test
    fun supportsPreviewAndFileTransfer() {
        val plan = OrchestratorControlCodec.parse(
            """
            {
              "execute": false,
              "reply": "Показываю план без запуска",
              "actions": [{
                "type": "TRANSFER_FILES",
                "sourceChatId": "source",
                "targetChatId": "target",
                "fileNameContains": "research",
                "persistTransferredFiles": true
              }]
            }
            """.trimIndent()
        )

        assertFalse(plan.execute)
        assertEquals("TRANSFER_FILES", plan.actions.single().type)
        assertEquals("research", plan.actions.single().fileNameContains)
        assertTrue(plan.actions.single().persistTransferredFiles)
    }
}
