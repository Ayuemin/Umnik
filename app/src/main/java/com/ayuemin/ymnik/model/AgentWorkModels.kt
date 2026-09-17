package com.ayuemin.ymnik.model

/**
 * Runtime objects for the agent-first orchestrator architecture.
 *
 * These are deliberately separate from legacy ProjectStage/OrchestratorStep so the
 * new execution engine can evolve without inheriting the stage-based design.
 */
enum class AgentTaskStatus {
    CREATED,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class AgentTaskPackage(
    val id: String,
    val workspaceId: String,
    val agentId: String,
    val objective: String,
    val constraints: List<String> = emptyList(),
    val inputResultIds: List<String> = emptyList(),
    val inputFileIds: List<String> = emptyList(),
    val userMaterialIds: List<String> = emptyList(),
    val expectedOutput: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class AgentResult(
    val id: String,
    val workspaceId: String,
    val taskId: String,
    val agentId: String,
    val outputText: String,
    val fileIds: List<String> = emptyList(),
    val summary: String = "",
    val facts: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

data class AgentTransferLogEntry(
    val id: String,
    val workspaceId: String,
    val fromAgentId: String?,
    val toAgentId: String?,
    val taskId: String? = null,
    val resultIds: List<String> = emptyList(),
    val fileIds: List<String> = emptyList(),
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class AgentTaskState(
    val packageData: AgentTaskPackage,
    val status: AgentTaskStatus = AgentTaskStatus.CREATED,
    val resultId: String? = null,
    val error: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

data class JobWorkspace(
    val id: String,
    val projectId: String,
    val orchestratorAgentId: String,
    val userRequest: String,
    val plan: String = "",
    val tasks: List<AgentTaskState> = emptyList(),
    val results: List<AgentResult> = emptyList(),
    val transfers: List<AgentTransferLogEntry> = emptyList(),
    val finalResult: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
