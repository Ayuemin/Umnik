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
    fun parsesParallelGroup() {
        val raw = """
            {
              "planSummary": "Два независимых исследования",
              "userReply": "",
              "completed": false,
              "actions": [
                {"id":"a","type":"CALL_AGENT","agentId":"agent-a","parallelGroup":"research"},
                {"id":"b","type":"CALL_AGENT","agentId":"agent-b","parallelGroup":"research"}
              ]
            }
        """.trimIndent()

        val parsed = AgentOrchestratorCodec.parse(raw)

        assertEquals(2, parsed.actions.size)
        assertEquals("research", parsed.actions[0].parallelGroup)
        assertEquals("research", parsed.actions[1].parallelGroup)
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
    @Test
    fun rejectsEmptyActionDecisionThatIsNotComplete() {
        val parsed = AgentOrchestratorCodec.parse(
            """
            {
              "planSummary": "Нужно продолжить",
              "userReply": "",
              "completed": false,
              "finalResult": null,
              "actions": []
            }
            """.trimIndent()
        )

        assertEquals("no_next_action", AgentOrchestratorCodec.validationProblem(parsed))
    }

    @Test
    fun rejectsMissingOrWrongActionsShapeAsNoNextAction() {
        val missing = AgentOrchestratorCodec.parse(
            """{"planSummary":"x","completed":false}"""
        )
        val wrongType = AgentOrchestratorCodec.parse(
            """{"planSummary":"x","completed":false,"actions":"CALL_AGENT"}"""
        )

        assertEquals("no_next_action", AgentOrchestratorCodec.validationProblem(missing))
        assertEquals("no_next_action", AgentOrchestratorCodec.validationProblem(wrongType))
    }

    @Test
    fun rejectsCallAgentWithoutAgentId() {
        val parsed = AgentOrchestratorCodec.parse(
            """
            {
              "completed": false,
              "actions": [
                {"id":"a","type":"CALL_AGENT","objective":"Проверить"}
              ]
            }
            """.trimIndent()
        )

        assertEquals("call_agent_without_agent_id", AgentOrchestratorCodec.validationProblem(parsed))
    }

    @Test
    fun rejectsCompletionWithoutFinalText() {
        val parsed = AgentOrchestratorCodec.parse(
            """
            {
              "completed": true,
              "finalResult": null,
              "userReply": "",
              "actions": [{"id":"done","type":"COMPLETE_JOB"}]
            }
            """.trimIndent()
        )

        assertEquals("completion_without_result", AgentOrchestratorCodec.validationProblem(parsed))
    }

    @Test
    fun acceptsAskUserWithMessage() {
        val parsed = AgentOrchestratorCodec.parse(
            """
            {
              "completed": false,
              "userReply": "Уточните исполнителя",
              "actions": [{"id":"ask","type":"ASK_USER"}]
            }
            """.trimIndent()
        )

        assertEquals(null, AgentOrchestratorCodec.validationProblem(parsed))
    }

    @Test
    fun acceptsParallelCallGroupAsExecutableDecision() {
        val parsed = AgentOrchestratorCodec.parse(
            """
            {
              "completed": false,
              "actions": [
                {"id":"a","type":"CALL_AGENT","agentId":"agent-a","parallelGroup":"pair"},
                {"id":"b","type":"CALL_AGENT","agentId":"agent-b","parallelGroup":"pair"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(null, AgentOrchestratorCodec.validationProblem(parsed))
    }

}
