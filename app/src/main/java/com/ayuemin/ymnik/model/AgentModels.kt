package com.ayuemin.ymnik.model

/**
 * New project-domain identity. An agent is not a ChatSession.
 *
 * Keep this model independent from legacy project/chat stages. The latter are
 * migration-only concepts and must not leak into the agent-first architecture.
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
 * Message persistence is deliberately kept in the existing chat store during migration.
 */
data class AgentConversationRef(
    val conversationId: String,
    val agentId: String,
    val createdAt: Long = System.currentTimeMillis()
)
