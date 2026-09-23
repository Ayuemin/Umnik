package com.ayuemin.ymnik.model

enum class KnowledgeOwnerKind {
    CHAT,
    PROJECT,
    AGENT
}

data class KnowledgeBaseSettings(
    val embeddingModelId: String = DEFAULT_EMBEDDING_MODEL,
    val enabled: Boolean = true,
    val topK: Int = 5
) {
    companion object {
        const val DEFAULT_EMBEDDING_MODEL = "qwen/qwen3-embedding-8b"
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
