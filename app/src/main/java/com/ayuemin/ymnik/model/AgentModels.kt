package com.ayuemin.ymnik.model

/**
 * Project-domain identity for the agent-first architecture.
 *
 * Agents are isolated from ordinary-chat defaults. Global settings may expose
 * technical connection profiles/API credentials, but never mutate agent state.
 */
enum class AgentKind {
    ORCHESTRATOR,
    SPECIALIST
}

/** A concrete model reachable through one application-level connection profile. */
data class AgentModelRef(
    val connectionProfileId: String,
    val modelId: String
)

/**
 * Knowledge-base settings belong to one agent.
 *
 * The KB embedding model is intentionally separate from the agent memory embedding
 * model. Changing it requires rebuilding that agent's vector index.
 */
data class AgentKnowledgeBaseProfile(
    val enabled: Boolean = true,
    val embeddingModel: AgentModelRef? = null,
    val topK: Int = 5,
    val indexGeneration: Long = 0L
)

data class AgentProfile(
    val id: String,
    val projectId: String,
    val kind: AgentKind = AgentKind.SPECIALIST,
    val name: String,
    val role: String = "",
    val instruction: String = "",

    /** Main reasoning/writing model for this agent. */
    val primaryModel: AgentModelRef? = null,

    /** Cheap/fast helper models owned by this agent, not inherited from chat settings. */
    val quickModels: List<AgentModelRef> = emptyList(),

    /** Optional model used for context compression/summarisation inside this agent. */
    val contextModel: AgentModelRef? = null,

    /** Optional embedding model for the agent's own semantic memory. */
    val memoryEmbeddingModel: AgentModelRef? = null,

    /** Agent-owned knowledge base and its own embedding configuration. */
    val knowledgeBase: AgentKnowledgeBaseProfile = AgentKnowledgeBaseProfile(),

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
 * Conversation storage can evolve independently.
 */
data class AgentConversationRef(
    val conversationId: String,
    val agentId: String,
    val createdAt: Long = System.currentTimeMillis()
)
