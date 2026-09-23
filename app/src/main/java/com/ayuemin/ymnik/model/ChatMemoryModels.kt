package com.ayuemin.ymnik.model

enum class ChatContextMode {
    AUTO,
    FULL,
    ECONOMY
}

data class ChatMemoryGlobalSettings(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val embeddingModelId: String = DEFAULT_EMBEDDING_MODEL,
    val summaryModelId: String = DEFAULT_SUMMARY_MODEL,
    val defaultContextMode: ChatContextMode = ChatContextMode.AUTO,
    val autoThresholdTokens: Int = 30_000,
    val economyThresholdTokens: Int = 5_000,
    val autoContextBudgetTokens: Int = 16_000,
    val economyContextBudgetTokens: Int = 6_000,
    val autoRecentMessages: Int = 10,
    val economyRecentMessages: Int = 6,
    val autoTopK: Int = 3,
    val economyTopK: Int = 2,
    // Legacy shared value retained for migration from schema v2.
    val topK: Int = 3,
    val checkpointTokens: Int = 8_000,
    val chunkTokens: Int = 1_200,
    val chunkOverlapTokens: Int = 80,
    val neighborChunks: Int = 1,
    val embeddingContextTokens: Int? = null,
    val minimumScore: Double = 0.20,
    val stateCardMaxChars: Int = 3_000
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 3
        const val DEFAULT_EMBEDDING_MODEL = "qwen/qwen3-embedding-8b"
        const val DEFAULT_SUMMARY_MODEL = ""
    }
}

data class ChatMemoryCheckpoint(
    val id: String,
    val messageIds: List<String>,
    val summary: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class ChatMemoryChunk(
    val id: String,
    val checkpointId: String,
    val ordinal: Int,
    val text: String,
    val messageIds: List<String>,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val vectorDimension: Int = 0
)

data class ChatMemorySnapshot(
    val chatId: String,
    val embeddingModelId: String,
    val summaryModelId: String,
    val chunkTokens: Int = 0,
    val chunkOverlapTokens: Int = 0,
    val embeddingContextTokens: Int? = null,
    val stateCard: String = "",
    val checkpoints: List<ChatMemoryCheckpoint> = emptyList(),
    val chunks: List<ChatMemoryChunk> = emptyList(),
    val indexedFingerprints: Map<String, String> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ChatMemoryHit(
    val text: String,
    val messageIds: List<String>,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val score: Double
)

data class ChatMemoryStats(
    val checkpoints: Int = 0,
    val chunks: Int = 0,
    val bytes: Long = 0L,
    val stateCardChars: Int = 0,
    val updatedAt: Long? = null
)
