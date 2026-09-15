package com.ayuemin.ymnik.model

enum class OrchestratorStepType {
    EXECUTE_CHAT,
    RUN_CHAT_STAGES,
    RUN_PROJECT_STAGES
}

data class ProjectChatRuntimeProfile(
    val modelId: String? = null,
    val webSearchEnabled: Boolean = false,
    val reasoningEnabled: Boolean = false,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val tools: ServerToolSettings = ServerToolSettings(),
    val skillIds: Set<String> = emptySet()
)

data class OrchestratorStep(
    val id: String,
    val title: String,
    val type: OrchestratorStepType = OrchestratorStepType.EXECUTE_CHAT,
    val targetChatId: String? = null,
    val prompt: String = "",
    val passPreviousResult: Boolean = true
)
