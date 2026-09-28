package com.ayuemin.ymnik.model

import com.google.gson.annotations.SerializedName

/**
 * Runtime objects for the specialist-first orchestrator architecture.
 *
 * These models define the current specialist-office protocol so the
 * new execution engine can evolve without inheriting stage-based behavior.
 */
enum class SpecialistTaskStatus {
    CREATED,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class SpecialistTaskPackage(
    val id: String,
    val workspaceId: String,
    @SerializedName(value = "specialistId", alternate = ["agentId"])
    val specialistId: String,
    val objective: String,
    val assignmentInstruction: String = "",
    val constraints: List<String> = emptyList(),
    val inputResultIds: List<String> = emptyList(),
    val inputFileIds: List<String> = emptyList(),
    val userMaterialIds: List<String> = emptyList(),
    val expectedOutput: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class SpecialistResult(
    val id: String,
    val workspaceId: String,
    val taskId: String,
    @SerializedName(value = "specialistId", alternate = ["agentId"])
    val specialistId: String,
    val outputText: String,
    val fileIds: List<String> = emptyList(),
    val summary: String = "",
    val facts: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

data class SpecialistTransferLogEntry(
    val id: String,
    val workspaceId: String,
    @SerializedName(value = "fromSpecialistId", alternate = ["fromAgentId"])
    val fromSpecialistId: String?,
    @SerializedName(value = "toSpecialistId", alternate = ["toAgentId"])
    val toSpecialistId: String?,
    val taskId: String? = null,
    val resultIds: List<String> = emptyList(),
    val fileIds: List<String> = emptyList(),
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class SpecialistTaskState(
    val packageData: SpecialistTaskPackage,
    val status: SpecialistTaskStatus = SpecialistTaskStatus.CREATED,
    val resultId: String? = null,
    val error: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

data class JobWorkspace(
    val id: String,
    @SerializedName(value = "teamId", alternate = ["projectId"])
    val teamId: String,
    @SerializedName(value = "orchestratorSpecialistId", alternate = ["orchestratorAgentId"])
    val orchestratorSpecialistId: String,
    val userRequest: String,
    val plan: String = "",
    val tasks: List<SpecialistTaskState> = emptyList(),
    val results: List<SpecialistResult> = emptyList(),
    val transfers: List<SpecialistTransferLogEntry> = emptyList(),
    val finalResult: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
