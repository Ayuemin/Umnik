package com.ayuemin.ymnik.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOrchestratorCodecTest {
    @Test
    fun parsesCallAgentDecisionWrappedInExtraText() {
        val raw = """
            Решение:
            {
              "planSummary": "Сначала аналитик",
              "userReply": "",
              "completed": false,
              "finalResult": null,
              "actions": [
                {
                  "id": "a1",
                  "type": "call_agent",
                  "agentId": "agent-1",
                  "taskId": null,
                  "objective": "Разобрать материал",
                  "assignmentInstruction": "Не писать статью",
                  "inputResultIds": [],
                  "inputFileIds": ["USER"],
                  "expectedOutput": "Конспект",
                  "parallelGroup": null,
                  "note": ""
                }
              ]
            }
            конец
        """.trimIndent()

        val parsed = AgentOrchestratorCodec.parse(raw)

        assertFalse(parsed.completed)
        assertEquals("Сначала аналитик", parsed.planSummary)
        assertEquals(1, parsed.actions.size)
        assertEquals(AgentOrchestratorActionType.CALL_AGENT, parsed.actions.first().type)
        assertEquals("agent-1", parsed.actions.first().agentId)
        assertEquals(listOf("USER"), parsed.actions.first().inputFileIds)
    }

    @Test
    fun parsesCompletionWithFinalResult() {
        val raw = """
            {
              "planSummary": "Работа проверена",
              "userReply": "",
              "completed": true,
              "finalResult": "Готовый результат",
              "actions": [
                {"id":"done","type":"COMPLETE_JOB","note":"готово"}
              ]
            }
        """.trimIndent()

        val parsed = AgentOrchestratorCodec.parse(raw)

        assertTrue(parsed.completed)
        assertEquals("Готовый результат", parsed.finalResult)
        assertEquals(AgentOrchestratorActionType.COMPLETE_JOB, parsed.actions.first().type)
    }
}
