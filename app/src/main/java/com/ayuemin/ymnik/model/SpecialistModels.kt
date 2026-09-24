package com.ayuemin.ymnik.model

import com.google.gson.annotations.SerializedName

/**
 * Team-domain identity for the specialist-first architecture.
 *
 * Specialists are isolated from ordinary-chat defaults. Global settings may expose
 * technical connection profiles/API credentials, but never mutate specialist state.
 */
enum class SpecialistKind {
    ORCHESTRATOR,
    SPECIALIST
}

/** A concrete model reachable through one application-level connection profile. */
data class SpecialistModelRef(
    val connectionProfileId: String,
    val modelId: String
)

data class SpecialistProfile(
    val id: String,
    @SerializedName(value = "teamId", alternate = ["projectId"])
    val teamId: String,
    val kind: SpecialistKind = SpecialistKind.SPECIALIST,
    val name: String,
    val role: String = "",
    val instruction: String = "",

    /** Main reasoning/writing model for this specialist. */
    val primaryModel: SpecialistModelRef? = null,

    /** Additional chat models available for this specialist's conversations. */
    val quickModels: List<SpecialistModelRef> = emptyList(),

    val reasoningEnabled: Boolean = false,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val webSearchEnabled: Boolean = false,
    val tools: ServerToolSettings = ServerToolSettings(),

    /** IDs refer only to skills owned by this specialist. There is no global skill pool. */
    val skillIds: Set<String> = emptySet(),

    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class SpecialistSkill(
    val id: String,
    @SerializedName(value = "specialistId", alternate = ["agentId"])
    val specialistId: String,
    val name: String,
    val files: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * A conversation belongs to an specialist; it does not define the specialist.
 * Conversation storage can evolve independently.
 */
data class SpecialistConversationRef(
    val conversationId: String,
    @SerializedName(value = "specialistId", alternate = ["agentId"])
    val specialistId: String,
    val createdAt: Long = System.currentTimeMillis()
)
