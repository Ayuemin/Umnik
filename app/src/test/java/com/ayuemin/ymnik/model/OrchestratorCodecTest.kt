package com.ayuemin.ymnik.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchestratorCodecTest {
    @Test
    fun parsesCallSpecialistDecisionWrappedInExtraText() {
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
                  "type": "call_specialist",
                  "specialistId": "specialist-1",
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

        val parsed = OrchestratorCodec.parse(raw)

        assertFalse(parsed.completed)
        assertEquals("Сначала аналитик", parsed.planSummary)
        assertEquals(1, parsed.actions.size)
        assertEquals(OrchestratorActionType.CALL_SPECIALIST, parsed.actions.first().type)
        assertEquals("specialist-1", parsed.actions.first().specialistId)
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
                {"id":"a","type":"CALL_SPECIALIST","specialistId":"specialist-a","parallelGroup":"research"},
                {"id":"b","type":"CALL_SPECIALIST","specialistId":"specialist-b","parallelGroup":"research"}
              ]
            }
        """.trimIndent()

        val parsed = OrchestratorCodec.parse(raw)

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

        val parsed = OrchestratorCodec.parse(raw)

        assertTrue(parsed.completed)
        assertEquals("Готовый результат", parsed.finalResult)
        assertEquals(OrchestratorActionType.COMPLETE_JOB, parsed.actions.first().type)
    }
    @Test
    fun rejectsEmptyActionDecisionThatIsNotComplete() {
        val parsed = OrchestratorCodec.parse(
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

        assertEquals("no_next_action", OrchestratorCodec.validationProblem(parsed))
    }

    @Test
    fun rejectsMissingOrWrongActionsShapeAsNoNextAction() {
        val missing = OrchestratorCodec.parse(
            """{"planSummary":"x","completed":false}"""
        )
        val wrongType = OrchestratorCodec.parse(
            """{"planSummary":"x","completed":false,"actions":"CALL_SPECIALIST"}"""
        )

        assertEquals("no_next_action", OrchestratorCodec.validationProblem(missing))
        assertEquals("no_next_action", OrchestratorCodec.validationProblem(wrongType))
    }

    @Test
    fun rejectsCallSpecialistWithoutSpecialistId() {
        val parsed = OrchestratorCodec.parse(
            """
            {
              "completed": false,
              "actions": [
                {"id":"a","type":"CALL_SPECIALIST","objective":"Проверить"}
              ]
            }
            """.trimIndent()
        )

        assertEquals("call_specialist_without_agent_id", OrchestratorCodec.validationProblem(parsed))
    }

    @Test
    fun rejectsCompletionWithoutFinalText() {
        val parsed = OrchestratorCodec.parse(
            """
            {
              "completed": true,
              "finalResult": null,
              "userReply": "",
              "actions": [{"id":"done","type":"COMPLETE_JOB"}]
            }
            """.trimIndent()
        )

        assertEquals("completion_without_result", OrchestratorCodec.validationProblem(parsed))
    }

    @Test
    fun acceptsAskUserWithMessage() {
        val parsed = OrchestratorCodec.parse(
            """
            {
              "completed": false,
              "userReply": "Уточните исполнителя",
              "actions": [{"id":"ask","type":"ASK_USER"}]
            }
            """.trimIndent()
        )

        assertEquals(null, OrchestratorCodec.validationProblem(parsed))
    }

    @Test
    fun acceptsParallelCallGroupAsExecutableDecision() {
        val parsed = OrchestratorCodec.parse(
            """
            {
              "completed": false,
              "actions": [
                {"id":"a","type":"CALL_SPECIALIST","specialistId":"specialist-a","parallelGroup":"pair"},
                {"id":"b","type":"CALL_SPECIALIST","specialistId":"specialist-b","parallelGroup":"pair"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(null, OrchestratorCodec.validationProblem(parsed))
    }

    @Test
    fun acceptsLegacyCallAgentAndAgentId() {
        val parsed = OrchestratorCodec.parse(
            """{"completed":false,"actions":[{"id":"legacy","type":"CALL_AGENT","agentId":"legacy-agent","objective":"Проверить"}]}"""
        )

        assertEquals(OrchestratorActionType.CALL_SPECIALIST, parsed.actions.single().type)
        assertEquals("legacy-agent", parsed.actions.single().specialistId)
    }
}
