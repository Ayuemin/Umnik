package com.ayuemin.ymnik.model

enum class ChatMode {
    TEXT,
    IMAGE
}

enum class ThemeChoice {
    DYNAMIC,
    GRAPHITE,
    OCEAN,
    FOREST,
    AMBER
}

data class Skill(
    val id: String,
    val name: String,
    val files: List<String>,
    val createdAt: Long = System.currentTimeMillis()
)

data class PendingAttachment(
    val uri: String,
    val name: String,
    val mimeType: String,
    val size: Long
)

data class GeneratedFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val localPath: String,
    val size: Long
)

data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val attachmentNames: List<String> = emptyList(),
    val generatedFiles: List<GeneratedFile> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class StoredFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val localPath: String,
    val size: Long,
    val modifiedAt: Long,
    val category: String,
    val deletable: Boolean = true
)

data class StorageStats(
    val generatedBytes: Long = 0L,
    val exportBytes: Long = 0L,
    val skillBytes: Long = 0L,
    val chatBytes: Long = 0L
) {
    val totalBytes: Long
        get() = generatedBytes + exportBytes + skillBytes + chatBytes
}

data class UiState(
    val messages: List<ChatMessage> = emptyList(),
    val chats: List<ChatSession> = emptyList(),
    val currentChatId: String = "",
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val activeSkillIds: Set<String> = emptySet(),
    val mode: ChatMode = ChatMode.TEXT,
    val textModel: String = "openrouter/auto",
    val imageModel: String = "bytedance-seed/seedream-4.5",
    val apiKeyConfigured: Boolean = false,
    val isLoading: Boolean = false,
    val busyLabel: String? = null,
    val status: String? = null,
    val availableTextModels: List<String> = emptyList(),
    val availableImageModels: List<String> = emptyList(),
    val answerSoundEnabled: Boolean = true,
    val themeChoice: ThemeChoice = ThemeChoice.DYNAMIC,
    val storedFiles: List<StoredFile> = emptyList(),
    val storageStats: StorageStats = StorageStats()
)
