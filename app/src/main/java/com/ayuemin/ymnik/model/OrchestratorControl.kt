package com.ayuemin.ymnik.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class OrchestratorControlPlan(
    val reply: String = "",
    val execute: Boolean = true,
    val actions: List<OrchestratorControlAction> = emptyList()
)

data class OrchestratorControlAction(
    val type: String,
    val title: String = "",
    val targetChatId: String? = null,
    val sourceChatId: String? = null,
    val prompt: String = "",
    val passPreviousResult: Boolean = true,
    val includeSourceResult: Boolean = false,
    val includeSourceFiles: Boolean = false,
    val persistTransferredFiles: Boolean = false,
    val useOrchestratorFiles: Boolean = false,
    val fileNameContains: String? = null,
    val temporaryModelId: String? = null,
    val temporaryWebSearchEnabled: Boolean? = null,
    val temporaryReasoningEnabled: Boolean? = null,
    val temporaryReasoningEffort: String? = null,
    val temporarySkillIds: List<String>? = null,
    val persistSettings: Boolean = false
)

object OrchestratorControlCodec {
    const val MAX_ACTIONS = 16

    private val allowedTypes = setOf(
        "EXECUTE_CHAT",
        "RUN_CHAT_STAGES",
        "RUN_PROJECT_STAGES",
        "SHOW_LAST_RESULT",
        "TRANSFER_FILES",
        "UPDATE_CHAT_SETTINGS"
    )

    fun parse(raw: String): OrchestratorControlPlan {
        val root = JsonParser.parseString(extractObject(raw)).asJsonObject
        val actions = mutableListOf<OrchestratorControlAction>()
        root.get("actions")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val type = obj.string("type")?.trim()?.uppercase()?.takeIf { it in allowedTypes } ?: return@forEach
            actions += OrchestratorControlAction(
                type = type,
                title = obj.string("title").orEmpty().trim(),
                targetChatId = obj.string("targetChatId")?.trim()?.takeIf { it.isNotBlank() },
                sourceChatId = obj.string("sourceChatId")?.trim()?.takeIf { it.isNotBlank() },
                prompt = obj.string("prompt").orEmpty().trim(),
                passPreviousResult = obj.bool("passPreviousResult") ?: true,
                includeSourceResult = obj.bool("includeSourceResult") ?: false,
                includeSourceFiles = obj.bool("includeSourceFiles") ?: false,
                persistTransferredFiles = obj.bool("persistTransferredFiles") ?: false,
                useOrchestratorFiles = obj.bool("useOrchestratorFiles") ?: false,
                fileNameContains = obj.string("fileNameContains")?.trim()?.takeIf { it.isNotBlank() },
                temporaryModelId = obj.string("temporaryModelId")?.trim()?.takeIf { it.isNotBlank() },
                temporaryWebSearchEnabled = obj.bool("temporaryWebSearchEnabled"),
                temporaryReasoningEnabled = obj.bool("temporaryReasoningEnabled"),
                temporaryReasoningEffort = obj.string("temporaryReasoningEffort")?.trim()?.lowercase()?.takeIf {
                    it in setOf("minimal", "low", "medium", "high", "xhigh")
                },
                temporarySkillIds = obj.stringList("temporarySkillIds")?.distinct(),
                persistSettings = obj.bool("persistSettings") ?: false
            )
        }
        return OrchestratorControlPlan(
            reply = root.string("reply").orEmpty().trim(),
            execute = root.bool("execute") ?: true,
            actions = actions.take(MAX_ACTIONS)
        )
    }

    private fun extractObject(raw: String): String {
        val clean = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        require(start >= 0 && end > start) { "Оркестратор не вернул понятный план" }
        return clean.substring(start, end + 1)
    }

    private fun JsonObject.string(name: String): String? = runCatching {
        get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
    }.getOrNull()

    private fun JsonObject.bool(name: String): Boolean? = runCatching {
        get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean
    }.getOrNull()

    private fun JsonObject.stringList(name: String): List<String>? = runCatching {
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { item -> item.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotBlank) }
    }.getOrNull()
}
