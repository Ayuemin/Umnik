package com.ayuemin.ymnik.model

enum class ChatMode {
    TEXT,
    IMAGE
}

enum class UserProfileScope {
    OFF,
    PROJECTS,
    EVERYWHERE
}

enum class ProviderType {
    OPENROUTER,
    OPENAI_COMPATIBLE
}

data class ProviderUsage(
    val providerName: String,
    val daily: Double,
    val weekly: Double,
    val monthly: Double,
    val total: Double,
    val updatedAt: Long = System.currentTimeMillis()
)

data class ConnectionProfile(
    val id: String,
    val name: String,
    val type: ProviderType,
    val baseUrl: String
)

data class UserProfile(
    val name: String = "",
    val gender: String = "",
    val age: String = "",
    val occupation: String = "",
    val note: String = ""
) {
    fun isEmpty(): Boolean = name.isBlank() && gender.isBlank() && age.isBlank() && occupation.isBlank() && note.isBlank()
}

data class ModelInfo(
    val id: String,
    val inputModalities: Set<String> = setOf("text"),
    val supportedParameters: Set<String> = emptySet(),
    val reasoningEfforts: Set<String> = emptySet(),
    val parameterOptions: Map<String, List<String>> = emptyMap()
) {
    fun accepts(modality: String): Boolean = modality.lowercase() in inputModalities
    fun parameterValues(parameter: String): List<String> = parameterOptions[parameter.lowercase()].orEmpty()
    val supportsReasoning: Boolean
        get() = "reasoning" in supportedParameters || "reasoning_effort" in supportedParameters
    val supportsReasoningEffort: Boolean
        get() = "reasoning_effort" in supportedParameters
    val supportsTools: Boolean
        get() = "tools" in supportedParameters
}

enum class ReasoningEffort(val apiValue: String) {
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh")
}

enum class AnswerSoundChoice {
    DEFAULT,
    CUSTOM,
    SOFT,
    BRIGHT,
    DOUBLE
}

enum class ThemeChoice {
    DYNAMIC,
    CUSTOM,
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
    val size: Long,
    val localPath: String? = null
)

data class ChatFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val localPath: String,
    val size: Long,
    val addedAt: Long = System.currentTimeMillis()
)

data class GeneratedFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val localPath: String,
    val size: Long
)

data class ProjectFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val localPath: String,
    val size: Long,
    val addedAt: Long = System.currentTimeMillis()
)

data class Project(
    val id: String,
    val name: String,
    val role: String = "",
    val masterPrompt: String = "",
    val isFavorite: Boolean = false,
    val skillIds: Set<String> = emptySet(),
    val files: List<ProjectFile> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val attachmentNames: List<String> = emptyList(),
    val generatedFiles: List<GeneratedFile> = emptyList(),
    val imageGeneration: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val projectId: String? = null,
    val mode: ChatMode? = null,
    val connectionProfileId: String? = null,
    val textModelOverride: String? = null,
    val chatFiles: List<ChatFile>? = null,
    val isFavorite: Boolean = false,
    val assignedRole: String? = null,
    val masterPrompt: String? = null,
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
    val projectBytes: Long = 0L,
    val chatBytes: Long = 0L,
    val soundBytes: Long = 0L
) {
    val totalBytes: Long
        get() = generatedBytes + exportBytes + skillBytes + projectBytes + chatBytes + soundBytes
}

data class UiState(
    val messages: List<ChatMessage> = emptyList(),
    val chats: List<ChatSession> = emptyList(),
    val projects: List<Project> = emptyList(),
    val currentChatId: String = "",
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val activeSkillIds: Set<String> = emptySet(),
    val mode: ChatMode = ChatMode.TEXT,
    val connectionProfiles: List<ConnectionProfile> = listOf(
        ConnectionProfile("openrouter", "OpenRouter", ProviderType.OPENROUTER, "https://openrouter.ai/api/v1")
    ),
    val activeConnectionProfileId: String = "openrouter",
    val disabledConnectionIds: Set<String> = emptySet(),
    val textModel: String = "openrouter/auto",
    val currentChatTextModel: String? = null,
    val quickTextModels: List<String> = emptyList(),
    val imageConnectionProfileId: String = "openrouter",
    val imageModel: String = "bytedance-seed/seedream-4.5",
    val imageAspectRatio: String? = null,
    val imageResolution: String? = null,
    val webSearchEnabled: Boolean = false,
    val reasoningEnabled: Boolean = false,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val reasoningEffortsByModel: Map<String, ReasoningEffort> = emptyMap(),
    val userProfile: UserProfile = UserProfile(),
    val userProfileScope: UserProfileScope = UserProfileScope.OFF,
    val apiKeyConfigured: Boolean = false,
    val isLoading: Boolean = false,
    val requestActive: Boolean = false,
    val busyLabel: String? = null,
    val status: String? = null,
    val availableTextModels: List<ModelInfo> = emptyList(),
    val availableImageModels: List<ModelInfo> = emptyList(),
    val modelCatalogConnectionId: String? = null,
    val modelCatalog: List<ModelInfo> = emptyList(),
    val providerUsage: ProviderUsage? = null,
    val answerSoundEnabled: Boolean = true,
    val answerSoundChoice: AnswerSoundChoice = AnswerSoundChoice.DEFAULT,
    val answerSoundVolume: Int = 28,
    val answerSoundCustomPath: String? = null,
    val answerSoundCustomName: String? = null,
    val themeChoice: ThemeChoice = ThemeChoice.DYNAMIC,
    val customThemeColor: Int = 0xFF6750A4.toInt(),
    val storedFiles: List<StoredFile> = emptyList(),
    val storageStats: StorageStats = StorageStats()
)
