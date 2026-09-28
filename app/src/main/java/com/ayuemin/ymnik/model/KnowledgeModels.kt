package com.ayuemin.ymnik.model

import com.google.gson.annotations.SerializedName

enum class KnowledgeOwnerKind {
    CHAT,
    @SerializedName(value = "TEAM", alternate = ["PROJECT"])
    TEAM,
    @SerializedName(value = "SPECIALIST", alternate = ["AGENT"])
    SPECIALIST
}

data class KnowledgeBaseSettings(
    val enabled: Boolean = true,
    val topK: Int = DEFAULT_TOP_K,
    val modelInstruction: String = "",
    val modelSearchLimit: Int? = null
) {
    val effectiveModelSearchLimit: Int
        get() = (modelSearchLimit ?: DEFAULT_MODEL_SEARCH_LIMIT).coerceIn(0, MAX_MODEL_SEARCH_LIMIT)

    companion object {
        const val DEFAULT_EMBEDDING_MODEL = "qwen/qwen3-embedding-8b"
        const val DEFAULT_TOP_K = 4
        const val DEFAULT_MODEL_SEARCH_LIMIT = 4
        const val MAX_MODEL_SEARCH_LIMIT = 10
    }
}

data class KnowledgeDocument(
    val id: String,
    val ownerKind: KnowledgeOwnerKind,
    val ownerId: String,
    val name: String,
    val mimeType: String,
    val localPath: String,
    val size: Long,
    val embeddingModelId: String,
    val vectorDimension: Int,
    val chunkCount: Int,
    val charCount: Int,
    val indexedAt: Long = System.currentTimeMillis()
)

data class KnowledgeChunk(
    val ordinal: Int,
    val text: String,
    val page: Int? = null
)

data class KnowledgeHit(
    val documentId: String,
    val documentName: String,
    val text: String,
    val page: Int? = null,
    val score: Double,
    val ordinal: Int = -1,
    val semanticScore: Double? = null,
    val lexicalScore: Double? = null
)


enum class KnowledgeIndexTaskStatus {
    QUEUED,
    PREPARING,
    INDEXING,
    FAILED
}

data class KnowledgeIndexTask(
    val id: String,
    val ownerKind: KnowledgeOwnerKind,
    val ownerId: String,
    val name: String,
    val mimeType: String,
    val localPath: String,
    val size: Long,
    val embeddingModelId: String,
    val connectionProfileId: String,
    val baseUrl: String,
    val replaceDocumentId: String? = null,
    val totalChunks: Int = 0,
    val completedChunks: Int = 0,
    val vectorDimension: Int = 0,
    val status: KnowledgeIndexTaskStatus = KnowledgeIndexTaskStatus.QUEUED,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
