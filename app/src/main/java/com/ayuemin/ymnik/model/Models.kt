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
    NVIDIA,
    OPENAI_COMPATIBLE
}

enum class ImageApiProtocol {
    AUTO,
    OPENAI_COMPATIBLE,
    NVIDIA_NIM
}

enum class ModelCategory {
    TEXT,
    IMAGE,
    VIDEO,
    SPEECH,
    TRANSCRIPTION,
    EMBEDDINGS,
    RERANK,
    AUDIO
}

enum class ModelVariant {
    STANDARD,
    BATCH,
    FREE,
    THINKING,
    EXTENDED,
    ONLINE,
    NITRO,
    FLOOR
}

enum class ModelPriceFilter(
    val textCeilingUsdPerMillion: Double?,
    val imageCeilingUsd1K: Double?,
    val freeOnly: Boolean = false
) {
    ALL(null, null),
    FREE(0.0, 0.0, true),
    UP_TO_0_5(0.5, 0.02),
    UP_TO_1(1.0, 0.05),
    UP_TO_5(5.0, 0.10),
    UP_TO_10(10.0, 0.20)
}

enum class BatchJobStatus {
    VALIDATING,
    QUEUED,
    IN_PROGRESS,
    FINALIZING,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED,
    UNKNOWN;

    val terminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED

    companion object {
        fun fromApi(value: String?): BatchJobStatus = when (value?.trim()?.lowercase()) {
            "validating" -> VALIDATING
            "queued", "pending" -> QUEUED
            "in_progress", "running", "processing" -> IN_PROGRESS
            "finalizing" -> FINALIZING
            "completed" -> COMPLETED
            "failed" -> FAILED
            "cancelled", "canceled" -> CANCELLED
            "expired" -> EXPIRED
            else -> UNKNOWN
        }
    }
}

data class BatchJobItem(
    val customId: String,
    val label: String = customId,
    val resultText: String? = null,
    val error: String? = null
)

data class BatchJob(
    val id: String,
    val remoteId: String,
    val connectionProfileId: String,
    val chatId: String? = null,
    val projectId: String? = null,
    val userMessageId: String? = null,
    val modelId: String,
    val baseModelId: String,
    val title: String,
    val status: BatchJobStatus = BatchJobStatus.VALIDATING,
    val items: List<BatchJobItem> = emptyList(),
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val totalItems: Int get() = items.size
    val completedItems: Int get() = items.count { it.resultText != null || it.error != null }
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
    val baseUrl: String,
    val imageEnabled: Boolean? = null,
    val imageBaseUrl: String? = null,
    val imageProtocol: ImageApiProtocol? = null,
    val useSameImageApiKey: Boolean? = null,
    val useProviderDefaults: Boolean? = null,
    val contextLimitTokens: Int? = null
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
    val outputModalities: Set<String> = setOf("text"),
    val supportedParameters: Set<String> = emptySet(),
    val reasoningEfforts: Set<String> = emptySet(),
    val parameterOptions: Map<String, List<String>> = emptyMap(),
    val contextLength: Int? = null,
    val maxCompletionTokens: Int? = null,
    val reasoningMandatory: Boolean = false,
    val reasoningDefaultEnabled: Boolean = false,
    val promptPriceUsdPerMillion: Double? = null,
    val completionPriceUsdPerMillion: Double? = null,
    val imagePriceUsd: Double? = null,
    val imageTokenPriceUsd: Double? = null,
    val imageOutputPriceUsd: Double? = null,
    val variants: Set<ModelVariant> = setOf(ModelVariant.STANDARD)
) {
    fun accepts(modality: String): Boolean = modality.lowercase() in inputModalities
    fun outputs(modality: String): Boolean = modality.lowercase() in outputModalities
    fun parameterValues(parameter: String): List<String> = parameterOptions[parameter.lowercase()].orEmpty()

    val supportsReasoning: Boolean
        get() = "reasoning" in supportedParameters || "reasoning_effort" in supportedParameters
    val supportsReasoningEffort: Boolean
        get() = "reasoning_effort" in supportedParameters
    val supportsTools: Boolean
        get() = "tools" in supportedParameters
    val isBatch: Boolean
        get() = ModelVariant.BATCH in variants || id.endsWith(":batch", ignoreCase = true)
    val batchBaseModelId: String
        get() = if (isBatch) id.removeSuffix(":batch") else id
    val maxTextPriceUsdPerMillion: Double?
        get() = listOfNotNull(promptPriceUsdPerMillion, completionPriceUsdPerMillion).maxOrNull()

    /**
     * Approximate price of a 1K generated image from OpenRouter's image-output token rate.
     * OpenRouter image models can bill by image, megapixel or image tokens; 4096 image tokens
     * is the useful 1K baseline exposed by the general catalog. The actual request usage cost
     * remains authoritative and can vary with resolution, quality and provider.
     */
    val estimatedImageOutputUsd1K: Double?
        get() = (imageOutputPriceUsd ?: imageTokenPriceUsd)
            ?.takeIf { it >= 0.0 }
            ?.times(4096.0)

    fun isFreeFor(category: ModelCategory?): Boolean {
        if (ModelVariant.FREE in variants) return true
        fun allKnownZero(values: List<Double?>): Boolean {
            val known = values.filterNotNull()
            return known.isNotEmpty() && known.all { it <= 0.0 }
        }
        return when (category) {
            ModelCategory.TEXT -> allKnownZero(listOf(promptPriceUsdPerMillion, completionPriceUsdPerMillion))
            ModelCategory.IMAGE -> allKnownZero(
                listOf(
                    promptPriceUsdPerMillion,
                    completionPriceUsdPerMillion,
                    imagePriceUsd,
                    imageTokenPriceUsd,
                    imageOutputPriceUsd
                )
            )
            null -> categories.isNotEmpty() && categories.all { output -> isFreeFor(output) }
            else -> false
        }
    }

    fun catalogPriceFor(category: ModelCategory?): Double? = when (category) {
        ModelCategory.IMAGE -> estimatedImageOutputUsd1K
        ModelCategory.TEXT -> maxTextPriceUsdPerMillion
        null -> when {
            categories == setOf(ModelCategory.IMAGE) -> estimatedImageOutputUsd1K
            categories == setOf(ModelCategory.TEXT) -> maxTextPriceUsdPerMillion
            else -> null
        }
        else -> null
    }

    val categories: Set<ModelCategory>
        get() {
            val categories = linkedSetOf<ModelCategory>()
            outputModalities.forEach { modality ->
                when (modality.lowercase()) {
                    "text" -> categories += ModelCategory.TEXT
                    "image" -> categories += ModelCategory.IMAGE
                    "video" -> categories += ModelCategory.VIDEO
                    "speech" -> categories += ModelCategory.SPEECH
                    "transcription" -> categories += ModelCategory.TRANSCRIPTION
                    "embeddings", "embedding" -> categories += ModelCategory.EMBEDDINGS
                    "rerank", "ranking" -> categories += ModelCategory.RERANK
                    "audio" -> categories += ModelCategory.AUDIO
                }
            }
            if (categories.isEmpty()) categories += ModelCategory.TEXT
            return categories
        }
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

data class ProjectStage(
    val id: String,
    val title: String,
    val instruction: String,
    val modelId: String? = null
)

data class Project(
    val id: String,
    val name: String,
    val role: String = "",
    val masterPrompt: String = "",
    val isFavorite: Boolean = false,
    val skillIds: Set<String> = emptySet(),
    val files: List<ProjectFile> = emptyList(),
    val stages: List<ProjectStage>? = null,
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
    val timestamp: Long = System.currentTimeMillis(),
    // Null means an existing/completed message; older stored chats need no migration.
    val deliveryState: String? = null,
    val modelId: String? = null,
    val providerName: String? = null,
    val costUsd: Double? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null
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
    val openRouterSpeechModel: String = "",
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
    val batchJobs: List<BatchJob> = emptyList(),
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
