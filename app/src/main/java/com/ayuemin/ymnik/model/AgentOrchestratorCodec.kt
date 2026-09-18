package com.ayuemin.ymnik.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

/**
 * JSON codec for decisions emitted by the agent Orchestrator.
 *
 * Parsing extracts the first balanced JSON object, so a model can accidentally add
 * a short sentence around the object without breaking the whole office run.
 */
object AgentOrchestratorCodec {
    fun parse(raw: String): AgentOrchestratorDecision {
        val root = JsonParser.parseString(extractObject(raw)).asJsonObject
        val actions = root.array("actions").mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val rawType = obj.string("type").trim()
            if (rawType.isBlank()) return@mapNotNull null
            val type = runCatching {
                AgentOrchestratorActionType.valueOf(
                    rawType.uppercase()
                        .replace('-', '_')
                        .replace(' ', '_')
                )
            }.getOrElse { error("Неизвестное действие Оркестратора: " + rawType) }
            AgentOrchestratorAction(
                id = obj.string("id").ifBlank { UUID.randomUUID().toString() },
                type = type,
                agentId = obj.string("agentId").ifBlank { null },
                taskId = obj.string("taskId").ifBlank { null },
                objective = obj.string("objective"),
                assignmentInstruction = obj.string("assignmentInstruction"),
                inputResultIds = obj.stringList("inputResultIds"),
                inputFileIds = obj.stringList("inputFileIds"),
                expectedOutput = obj.string("expectedOutput"),
                parallelGroup = obj.string("parallelGroup").ifBlank { null },
                note = obj.string("note")
            )
        }
        return AgentOrchestratorDecision(
            planSummary = root.string("planSummary"),
            userReply = root.string("userReply"),
            actions = actions,
            completed = root.bool("completed"),
            finalResult = root.string("finalResult").ifBlank { null }
        )
    }

    private fun extractObject(raw: String): String {
        val text = raw.trim().removePrefix("\uFEFF")
        val start = text.indexOf('{')
        require(start >= 0) { "Оркестратор не вернул JSON" }
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        error("JSON Оркестратора обрезан")
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }.orEmpty()

    private fun JsonObject.bool(name: String): Boolean =
        get(name)?.takeIf { !it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false

    private fun JsonObject.array(name: String): JsonArray =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()

    private fun JsonObject.stringList(name: String): List<String> =
        array(name).mapNotNull { element ->
            runCatching { element.asString.trim() }.getOrNull()?.takeIf { it.isNotBlank() }
        }
}
