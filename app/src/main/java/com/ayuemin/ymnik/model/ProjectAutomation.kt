package com.ayuemin.ymnik.model

data class ProjectChatRuntimeProfile(
    val modelId: String? = null,
    val webSearchEnabled: Boolean = false,
    val reasoningEnabled: Boolean = false,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val tools: ServerToolSettings = ServerToolSettings(),
    val skillIds: Set<String> = emptySet()
)
