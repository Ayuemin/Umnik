package com.ayuemin.ymnik.model

/**
 * Agent-first orchestration protocol.
 *
 * This protocol intentionally does not expose commands that mutate another agent's
 * permanent personality/settings. The orchestrator manages work; it does not rewrite
 * employees.
 */
enum class AgentOrchestratorActionType {
    CALL_AGENT,
    REQUEST_REVISION,
    TRANSFER_WORK,
    CANCEL_TASK,
    ASK_USER,
    COMPLETE_JOB
}

/**
 * Public card visible to the orchestrator.
 *
 * No API secrets or private storage contents belong here. Capability summary is a
 * user-facing description of what this agent is for.
 */
data class AgentPublicDescriptor(
    val agentId: String,
    val name: String,
    val role: String,
    val capabilitySummary: String = ""
)

/**
 * One decision emitted by the orchestrator.
 *
 * Several CALL_AGENT actions may share parallelGroup to let TaskDispatcher run them
 * concurrently when safe.
 */
data class AgentOrchestratorAction(
    val id: String,
    val type: AgentOrchestratorActionType,
    val agentId: String? = null,
    val taskId: String? = null,
    val objective: String = "",
    val assignmentInstruction: String = "",
    val inputResultIds: List<String> = emptyList(),
    val inputFileIds: List<String> = emptyList(),
    val expectedOutput: String = "",
    val parallelGroup: String? = null,
    val note: String = ""
)

data class AgentOrchestratorDecision(
    val planSummary: String = "",
    val userReply: String = "",
    val actions: List<AgentOrchestratorAction> = emptyList(),
    val completed: Boolean = false,
    val finalResult: String? = null
)
