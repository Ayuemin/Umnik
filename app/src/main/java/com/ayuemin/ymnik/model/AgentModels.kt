package com.ayuemin.ymnik.model

/**
 * Project-domain identity for the agent-first architecture. An agent is not a ChatSession.
 *
 * The model is intentionally independent from the obsolete stage-based project design.
 * Stages must not leak into the new agent architecture.
 */
enum class AgentKind {
    ORCHESTRATOR,
    SPECIALIST
}

data class AgentProfile(
    val id: String,
    val projectId: String,
    val kind: AgentKind = AgentKind.SPECIALIST,
    val name: String,
    val role: String = "",
    val instruction: String = "",
    val connectionProfileId: String? = null,
    val modelId: String? = null,
    val reasoningEnabled: Boolean = false,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val webSearchEnabled: Boolean = false,
    val tools: ServerToolSettings = ServerToolSettings(),
    /** IDs refer only to skills owned by this agent. There is no global skill pool. */
    val skillIds: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class AgentSkill(
    val id: String,
    val agentId: String,
    val name: String,
    val files: List<String>,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * A conversation belongs to an agent; it does not define the agent.
 * The reference is kept separate so conversation storage can evolve independently.
 */
data class AgentConversationRef(
    val conversationId: String,
    val agentId: String,
    val createdAt: Long = System.currentTimeMillis()
)
