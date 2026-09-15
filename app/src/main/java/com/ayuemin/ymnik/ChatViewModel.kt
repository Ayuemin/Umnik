package com.ayuemin.ymnik

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ayuemin.ymnik.data.ChatFileRepository
import com.ayuemin.ymnik.data.ChatRepository
import com.ayuemin.ymnik.data.ProjectRepository
import com.ayuemin.ymnik.data.ProjectAutomationRepository
import com.ayuemin.ymnik.data.OpenRouterFeaturePrefs
import com.ayuemin.ymnik.data.SecretStore
import com.ayuemin.ymnik.data.SkillRepository
import com.ayuemin.ymnik.data.StorageRepository
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.help.UmnikUsageGuide
import com.ayuemin.ymnik.model.AnswerSoundChoice
import com.ayuemin.ymnik.model.ChatFile
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.ChatMode
import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.ConnectionProfile
import com.ayuemin.ymnik.model.GeneratedFile
import com.ayuemin.ymnik.model.ImageApiProtocol
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.ModelCategory
import com.ayuemin.ymnik.model.OrchestratorStep
import com.ayuemin.ymnik.model.OrchestratorStepType
import com.ayuemin.ymnik.model.PendingAttachment
import com.ayuemin.ymnik.model.Project
import com.ayuemin.ymnik.model.ProjectFile
import com.ayuemin.ymnik.model.ProjectStage
import com.ayuemin.ymnik.model.ProjectChatRuntimeProfile
import com.ayuemin.ymnik.model.ProviderType
import com.ayuemin.ymnik.model.ProviderUsage
import com.ayuemin.ymnik.model.ReasoningEffort
import com.ayuemin.ymnik.model.StoredFile
import com.ayuemin.ymnik.model.ThemeChoice
import com.ayuemin.ymnik.model.UiState
import com.ayuemin.ymnik.model.UserProfile
import com.ayuemin.ymnik.model.UserProfileScope
import com.ayuemin.ymnik.network.CompatibleApiClient
import com.ayuemin.ymnik.network.NvidiaImageClient
import com.ayuemin.ymnik.network.OpenRouterClient
import com.ayuemin.ymnik.network.ProviderRegistry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class ChatViewModel(private val context: Context) : ViewModel() {
    private companion object { const val QUICK_MODEL_SEPARATOR = "\u001F" }
    private val prefs = context.getSharedPreferences("ymnik", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    private val skills = SkillRepository(context)
    private val chatsRepository = ChatRepository(context)
    private val chatFilesRepository = ChatFileRepository(context)
    private val projectsRepository = ProjectRepository(context)
    private val projectAutomation = ProjectAutomationRepository(context)
    private val openRouterFeaturePrefs = OpenRouterFeaturePrefs(context)
    private val storageRepository = StorageRepository(context)
    private val api = OpenRouterClient(context)
    private val compatibleApi = CompatibleApiClient(context)
    private val nvidiaImageApi = NvidiaImageClient(context)
    private val providerRegistry = ProviderRegistry(context)
    private val gson = Gson()
    private val recoveredRequest = RequestExecutionManager.recoverInterrupted(context)
    private var activeRequestJob: Job? = null
    private var activeRequestPending: List<PendingAttachment> = emptyList()
    private var projectStagesJob: Job? = null
    private var requestGeneration: Long = 0L

    private fun chatSkillsKey(chatId: String): String = "chat_active_skills::$chatId"

    private val initialProfiles = loadConnectionProfiles()
    private val initialDisabledConnectionIds = loadDisabledConnectionIds()
    private val initialProjects = projectsRepository.list()
    private val initialChats = ensureProjectOrchestrators(initialProjects, loadInitialChats())
    private val initialChatId = prefs.getString("current_chat_id", null)
        ?.takeIf { id -> initialChats.any { it.id == id } }
        ?: initialChats.first().id
    private val initialChat = initialChats.first { it.id == initialChatId }
    private val initialSkillIds = run {
        val key = chatSkillsKey(initialChat.id)
        if (prefs.contains(key)) {
            prefs.getStringSet(key, emptySet())?.toSet().orEmpty()
        } else {
            val projectDefaults = initialChat.projectId
                ?.let { projectId -> initialProjects.firstOrNull { it.id == projectId }?.skillIds }
                .orEmpty()
            val legacy = prefs.getStringSet("active_skills", emptySet())?.toSet().orEmpty()
            if (legacy.isNotEmpty()) {
                (projectDefaults + legacy).also { selected ->
                    prefs.edit()
                        .putStringSet(key, selected)
                        .remove("active_skills")
                        .apply()
                }
            } else {
                projectDefaults
            }
        }
    }
    private val initialProfileId = (initialChat.connectionProfileId
        ?: prefs.getString("active_connection_profile", "openrouter"))
        ?.takeIf { id -> initialProfiles.any { it.id == id } && id !in initialDisabledConnectionIds }
        ?: initialProfiles.firstOrNull { it.id !in initialDisabledConnectionIds }?.id
        ?: "openrouter"
    private val initialProfile = initialProfiles.firstOrNull { it.id == initialProfileId }
        ?: defaultOpenRouterProfile()
    private val initialImageProfileId = prefs.getString("image_connection_profile", "openrouter")
        ?.takeIf { id -> initialProfiles.any { it.id == id && imageGenerationEnabled(it) } && id !in initialDisabledConnectionIds }
        ?: initialProfiles.firstOrNull { it.type == ProviderType.OPENROUTER && it.id !in initialDisabledConnectionIds && imageGenerationEnabled(it) }?.id
        ?: initialProfiles.firstOrNull { it.id !in initialDisabledConnectionIds && imageGenerationEnabled(it) }?.id
        ?: "openrouter"
    private val initialImageProfile = initialProfiles.firstOrNull { it.id == initialImageProfileId }
        ?: defaultOpenRouterProfile()
    private val initialImageModel = loadImageModelForProfile(initialImageProfile.id)
    private val initialImageAspectRatio = loadImageParameter("aspect_ratio", initialImageProfile.id, initialImageModel)
    private val initialImageResolution = loadImageParameter("resolution", initialImageProfile.id, initialImageModel)

    private val _state = MutableStateFlow(
        UiState(
            messages = initialChat.messages,
            chats = initialChats,
            projects = initialProjects,
            currentChatId = initialChatId,
            skills = skills.list(),
            activeSkillIds = initialSkillIds,
            mode = ChatMode.TEXT,
            connectionProfiles = initialProfiles,
            activeConnectionProfileId = initialProfileId,
            disabledConnectionIds = initialDisabledConnectionIds,
            textModel = loadTextModelForProfile(initialProfile),
            currentChatTextModel = initialChat.textModelOverride.takeIf { initialChat.connectionProfileId == null || initialChat.connectionProfileId == initialProfileId },
            quickTextModels = loadAllQuickTextModels(initialProfiles, initialDisabledConnectionIds),
            imageConnectionProfileId = initialImageProfileId,
            imageModel = initialImageModel,
            imageAspectRatio = initialImageAspectRatio,
            imageResolution = initialImageResolution,
            openRouterSpeechModel = prefs.getString("reply_speech_model", "").orEmpty().trim(),
            openRouterSpeechVoice = run {
                val replyModel = prefs.getString("reply_speech_model", "").orEmpty().trim()
                if (replyModel.isBlank()) "" else prefs.getString(replySpeechVoiceKey(replyModel), "").orEmpty().trim()
            },
            openRouterSpeechResponseFormat = run {
                val replyModel = prefs.getString("reply_speech_model", "").orEmpty().trim()
                if (replyModel.isBlank()) "" else prefs.getString(replySpeechFormatKey(replyModel), "").orEmpty().trim()
            },
            webSearchEnabled = projectAutomation.profile(initialChat.id)?.webSearchEnabled ?: prefs.getBoolean("web_search", false),
            reasoningEnabled = projectAutomation.profile(initialChat.id)?.reasoningEnabled ?: prefs.getBoolean("reasoning_enabled", false),
            reasoningEffort = projectAutomation.profile(initialChat.id)?.reasoningEffort ?: runCatching {
                ReasoningEffort.valueOf(prefs.getString("reasoning_effort", ReasoningEffort.MEDIUM.name) ?: ReasoningEffort.MEDIUM.name)
            }.getOrDefault(ReasoningEffort.MEDIUM),
            reasoningEffortsByModel = loadReasoningEffortsByModel(),
            userProfile = UserProfile(
                name = prefs.getString("profile_name", "").orEmpty(),
                gender = prefs.getString("profile_gender", "").orEmpty(),
                age = prefs.getString("profile_age", "").orEmpty(),
                occupation = prefs.getString("profile_occupation", "").orEmpty(),
                note = prefs.getString("profile_note", "").orEmpty()
            ),
            userProfileScope = runCatching {
                UserProfileScope.valueOf(prefs.getString("profile_scope", UserProfileScope.OFF.name) ?: UserProfileScope.OFF.name)
            }.getOrDefault(UserProfileScope.OFF),
            apiKeyConfigured = isProfileConfigured(initialProfile),
            answerSoundEnabled = prefs.getBoolean("answer_sound", true),
            answerSoundChoice = runCatching {
                AnswerSoundChoice.valueOf(
                    prefs.getString("answer_sound_choice", AnswerSoundChoice.DEFAULT.name)
                        ?: AnswerSoundChoice.DEFAULT.name
                )
            }.getOrDefault(AnswerSoundChoice.DEFAULT).let {
                if (it == AnswerSoundChoice.CUSTOM) it else AnswerSoundChoice.DEFAULT
            },
            answerSoundVolume = prefs.getInt("answer_sound_volume", 28).coerceIn(0, 100),
            answerSoundCustomPath = prefs.getString("answer_sound_custom_path", null),
            answerSoundCustomName = prefs.getString("answer_sound_custom_name", null),
            themeChoice = runCatching {
                ThemeChoice.valueOf(prefs.getString("theme_choice", ThemeChoice.DYNAMIC.name) ?: ThemeChoice.DYNAMIC.name)
            }.getOrDefault(ThemeChoice.DYNAMIC),
            customThemeColor = prefs.getInt("custom_theme_color", 0xFF6750A4.toInt()),
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = chatsRepository.loadError ?: projectsRepository.loadError ?: skills.loadError ?: recoveredRequest
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private fun ensureProjectOrchestrators(projects: List<Project>, chats: List<ChatSession>): List<ChatSession> {
        if (projects.isEmpty()) return chats
        var next = chats
        var changed = false
        val activeProfileId = prefs.getString("active_connection_profile", "openrouter") ?: "openrouter"
        projects.forEach { project ->
            val registered = projectAutomation.orchestratorChatId(project.id)
            val existing = registered?.let { id -> next.firstOrNull { it.id == id && it.projectId == project.id } }
            if (existing == null) {
                val orchestrator = ChatSession(
                    id = UUID.randomUUID().toString(),
                    title = "Оркестратор",
                    projectId = project.id,
                    mode = ChatMode.TEXT,
                    connectionProfileId = activeProfileId,
                    textModelOverride = prefs.getString(profilePrefKey("text_model", activeProfileId), null)
                )
                next = listOf(orchestrator) + next
                projectAutomation.registerOrchestrator(project.id, orchestrator.id)
                projectAutomation.saveProfile(
                    orchestrator.id,
                    ProjectChatRuntimeProfile(
                        modelId = orchestrator.textModelOverride,
                        reasoningEffort = ReasoningEffort.MEDIUM,
                        tools = openRouterFeaturePrefs.tools(),
                        skillIds = project.skillIds
                    )
                )
                prefs.edit().putStringSet(chatSkillsKey(orchestrator.id), project.skillIds).apply()
                changed = true
            }
        }
        if (changed) chatsRepository.save(next)
        return next
    }

    private fun defaultSkillIdsForChat(chat: ChatSession): Set<String> =
        chat.projectId
            ?.let { projectId -> _state.value.projects.firstOrNull { it.id == projectId }?.skillIds }
            .orEmpty()

    private fun skillIdsForChat(chat: ChatSession): Set<String> {
        val key = chatSkillsKey(chat.id)
        return if (prefs.contains(key)) {
            prefs.getStringSet(key, emptySet())?.toSet().orEmpty()
        } else {
            defaultSkillIdsForChat(chat)
        }
    }

    fun chatSkillIds(chatId: String): Set<String> =
        _state.value.chats.firstOrNull { it.id == chatId }?.let(::skillIdsForChat).orEmpty()

    fun globalOpenRouterTools() = openRouterFeaturePrefs.tools()
    fun isOrchestratorChat(chatId: String): Boolean = projectAutomation.isOrchestrator(chatId)
    fun orchestratorSteps(chatId: String): List<OrchestratorStep> = projectAutomation.steps(chatId)
    fun projectChatRuntimeProfile(chatId: String): ProjectChatRuntimeProfile? = projectAutomation.profile(chatId)

    private fun defaultRuntimeProfile(chat: ChatSession): ProjectChatRuntimeProfile = ProjectChatRuntimeProfile(
        modelId = chat.textModelOverride ?: _state.value.textModel,
        webSearchEnabled = prefs.getBoolean("web_search", false),
        reasoningEnabled = prefs.getBoolean("reasoning_enabled", false),
        reasoningEffort = runCatching {
            ReasoningEffort.valueOf(prefs.getString("reasoning_effort", ReasoningEffort.MEDIUM.name) ?: ReasoningEffort.MEDIUM.name)
        }.getOrDefault(ReasoningEffort.MEDIUM),
        tools = openRouterFeaturePrefs.tools(),
        skillIds = skillIdsForChat(chat)
    )

    private fun runtimeProfile(chat: ChatSession): ProjectChatRuntimeProfile =
        projectAutomation.profile(chat.id) ?: defaultRuntimeProfile(chat).also { projectAutomation.saveProfile(chat.id, it) }

    private fun updateCurrentProjectRuntime(transform: (ProjectChatRuntimeProfile) -> ProjectChatRuntimeProfile) {
        val chat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId && it.projectId != null } ?: return
        val next = transform(runtimeProfile(chat))
        projectAutomation.saveProfile(chat.id, next)
    }

    fun saveProjectChatRuntimeSettings(chatId: String, profile: ProjectChatRuntimeProfile) {
        if (_state.value.isLoading || _state.value.requestActive) return
        val chat = _state.value.chats.firstOrNull { it.id == chatId && it.projectId != null } ?: return
        val clean = profile.copy(modelId = profile.modelId?.trim()?.takeIf { it.isNotBlank() } ?: _state.value.textModel)
        projectAutomation.saveProfile(chatId, clean)
        prefs.edit().putStringSet(chatSkillsKey(chatId), clean.skillIds).apply()
        val chats = _state.value.chats.map {
            if (it.id == chatId) it.copy(textModelOverride = clean.modelId, updatedAt = System.currentTimeMillis()) else it
        }
        chatsRepository.save(chats)
        val current = chatId == _state.value.currentChatId
        _state.value = _state.value.copy(
            chats = chats,
            currentChatTextModel = if (current) clean.modelId else _state.value.currentChatTextModel,
            webSearchEnabled = if (current) clean.webSearchEnabled else _state.value.webSearchEnabled,
            reasoningEnabled = if (current) clean.reasoningEnabled else _state.value.reasoningEnabled,
            reasoningEffort = if (current) clean.reasoningEffort else _state.value.reasoningEffort,
            activeSkillIds = if (current) clean.skillIds else _state.value.activeSkillIds,
            status = "Параметры работы чата сохранены"
        )
        if (current) refreshModelCapabilities()
    }

    fun addChatContextFile(chatId: String, uri: Uri) {
        if (_state.value.isLoading || _state.value.requestActive) return
        val chat = _state.value.chats.firstOrNull { it.id == chatId } ?: return
        runCatching { api.attachmentFromUri(uri) }
            .onSuccess { attachment ->
                if (attachment.size > 25L * 1024L * 1024L) {
                    _state.value = _state.value.copy(status = "Ограничение Umnik сейчас 25 МБ на один файл")
                    return@onSuccess
                }
                if (chat.chatFiles.orEmpty().any { it.name.equals(attachment.name, true) && (attachment.size <= 0L || it.size == attachment.size) }) {
                    _state.value = _state.value.copy(status = "Файл «${attachment.name}» уже есть в этом чате")
                    return@onSuccess
                }
                runCatching { chatFilesRepository.importFile(chatId, attachment) }
                    .onSuccess { file ->
                        val chats = _state.value.chats.map { item ->
                            if (item.id == chatId) item.copy(chatFiles = item.chatFiles.orEmpty() + file, updatedAt = System.currentTimeMillis()) else item
                        }
                        chatsRepository.save(chats)
                        _state.value = _state.value.copy(chats = chats, storedFiles = storageRepository.list(), storageStats = storageRepository.stats())
                    }
                    .onFailure { _state.value = _state.value.copy(status = it.message ?: "Не удалось сохранить файл") }
            }
            .onFailure { _state.value = _state.value.copy(status = it.message ?: "Не удалось прочитать файл") }
    }

    fun removeChatContextFile(chatId: String, fileId: String) {
        if (_state.value.isLoading || _state.value.requestActive) return
        val chat = _state.value.chats.firstOrNull { it.id == chatId } ?: return
        val file = chat.chatFiles.orEmpty().firstOrNull { it.id == fileId } ?: return
        chatFilesRepository.delete(file)
        val chats = _state.value.chats.map { item ->
            if (item.id == chatId) item.copy(chatFiles = item.chatFiles.orEmpty().filterNot { it.id == fileId }, updatedAt = System.currentTimeMillis()) else item
        }
        chatsRepository.save(chats)
        _state.value = _state.value.copy(chats = chats, storedFiles = storageRepository.list(), storageStats = storageRepository.stats())
    }

    init {
        DiagnosticLog.record(
            context,
            "APP",
            "ChatViewModel initialized; activeProfile=${initialProfile.name}; activeModel=${loadTextModelForProfile(initialProfile)}"
        )
        if (initialProfile.id !in initialDisabledConnectionIds && isProfileConfigured(initialProfile)) refreshModelCapabilities()
        viewModelScope.launch {
            RequestExecutionManager.snapshots.collect { snapshot ->
                if (snapshot.sequence == 0L) return@collect
                val chats = chatsRepository.list()
                val active = snapshot.activeChatId != null
                _state.value = _state.value.copy(
                    chats = chats,
                    messages = chats.firstOrNull { it.id == _state.value.currentChatId }?.messages.orEmpty(),
                    isLoading = active,
                    requestActive = active,
                    busyLabel = if (active) "Модель работает…" else null,
                    status = if (active) null else snapshot.lastError,
                    storedFiles = storageRepository.list(),
                    storageStats = storageRepository.stats()
                )
            }
        }
        refreshProviderUsage()
        viewModelScope.launch {
            if (providerRegistry.refreshIfStale()) refreshModelCapabilities()
        }
    }

    private fun replySpeechVoiceKey(modelId: String): String = "reply_speech_voice::${modelId.trim()}"
    private fun replySpeechFormatKey(modelId: String): String = "reply_speech_format::${modelId.trim()}"

    private fun normalizeSpeechResponseFormat(value: String): String = when (value.trim().lowercase()) {
        "mp3" -> "mp3"
        "pcm" -> "pcm"
        else -> ""
    }

    fun setOpenRouterSpeechModel(modelId: String) {
        val clean = modelId.trim()
        if (clean.isBlank()) {
            prefs.edit().remove("reply_speech_model").apply()
        } else {
            prefs.edit().putString("reply_speech_model", clean).apply()
        }
        val savedVoice = if (clean.isBlank()) "" else prefs.getString(replySpeechVoiceKey(clean), "").orEmpty().trim()
        val savedFormat = if (clean.isBlank()) "" else prefs.getString(replySpeechFormatKey(clean), "").orEmpty().trim()
        _state.value = _state.value.copy(
            openRouterSpeechModel = clean,
            openRouterSpeechVoice = savedVoice,
            openRouterSpeechResponseFormat = normalizeSpeechResponseFormat(savedFormat),
            status = if (clean.isBlank()) "Модель озвучивания ответов не выбрана" else "Модель озвучивания ответов сохранена"
        )
    }

    fun setOpenRouterSpeechVoice(voice: String) {
        val model = _state.value.openRouterSpeechModel.trim()
        if (model.isBlank()) return
        val clean = voice.trim()
        val key = replySpeechVoiceKey(model)
        if (clean.isBlank()) prefs.edit().remove(key).apply() else prefs.edit().putString(key, clean).apply()
        _state.value = _state.value.copy(
            openRouterSpeechVoice = clean,
            status = if (clean.isBlank()) "Голос для ответов не задан" else "Голос озвучивания ответов сохранён"
        )
    }

    fun setOpenRouterSpeechResponseFormat(value: String) {
        val model = _state.value.openRouterSpeechModel.trim()
        if (model.isBlank()) return
        val clean = normalizeSpeechResponseFormat(value)
        val key = replySpeechFormatKey(model)
        if (clean.isBlank()) prefs.edit().remove(key).apply() else prefs.edit().putString(key, clean).apply()
        _state.value = _state.value.copy(
            openRouterSpeechResponseFormat = clean,
            status = if (clean.isBlank()) "Формат ответов: Авто" else "Формат ответов: ${clean.uppercase()}"
        )
    }

    fun isDiagnosticLoggingEnabled(): Boolean = DiagnosticLog.isEnabled(context)

    fun setDiagnosticLoggingEnabled(enabled: Boolean) {
        DiagnosticLog.setEnabled(context, enabled)
        _state.value = _state.value.copy(
            status = if (enabled)
                "Запись диагностических логов включена"
            else
                "Запись диагностических логов выключена"
        )
    }

    fun diagnosticLogSize(): Long = DiagnosticLog.size(context)

    fun diagnosticLogFile(): GeneratedFile? {
        val file = DiagnosticLog.file(context) ?: return null
        return GeneratedFile(
            id = "diagnostic-log",
            name = "umnik-diagnostic.log",
            mimeType = "text/plain",
            localPath = file.absolutePath,
            size = file.length()
        )
    }

    fun clearDiagnosticLog() {
        DiagnosticLog.clear(context)
        _state.value = _state.value.copy(status = "Диагностический лог очищен")
    }

    fun saveApiKey(apiKey: String?) {
        val profile = activeConnectionProfile()
        if (!apiKey.isNullOrBlank()) secrets.saveProfileApiKey(profile.id, apiKey)
        _state.value = _state.value.copy(
            apiKeyConfigured = isProfileConfigured(profile),
            status = "Настройки сохранены"
        )
        if (_state.value.apiKeyConfigured) refreshModelCapabilities()
    }

    fun selectConnectionProfile(profileId: String) {
        if (_state.value.isLoading) return
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Подключение «${profile.name}» выключено")
            return
        }
        prefs.edit().putString("active_connection_profile", profile.id).apply()
        val chats = _state.value.chats.map { chat ->
            if (chat.id == _state.value.currentChatId) chat.copy(
                connectionProfileId = profile.id,
                textModelOverride = null,
                mode = ChatMode.TEXT,
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        chatsRepository.save(chats)
        _state.value = _state.value.copy(
            activeConnectionProfileId = profile.id,
            chats = chats,
            currentChatTextModel = null,
            textModel = loadTextModelForProfile(profile),
            quickTextModels = loadAllQuickTextModels(_state.value.connectionProfiles, _state.value.disabledConnectionIds),
            imageModel = loadImageModelForProfile(_state.value.imageConnectionProfileId),
            availableTextModels = emptyList(),
            mode = ChatMode.TEXT,
            webSearchEnabled = if (profile.type == ProviderType.OPENROUTER) prefs.getBoolean("web_search", false) else false,
            reasoningEnabled = false,
            apiKeyConfigured = isProfileConfigured(profile),
            status = "Подключение «${profile.name}» выбрано для текущего чата"
        )
        if (isProfileConfigured(profile)) refreshModelCapabilities()
    }

    fun addCompatibleProfile(): String {
        val id = UUID.randomUUID().toString()
        val profile = ConnectionProfile(
            id = id,
            name = "Другой API",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "",
            imageEnabled = false,
            imageProtocol = ImageApiProtocol.AUTO,
            useSameImageApiKey = true,
            useProviderDefaults = false
        )
        val profiles = _state.value.connectionProfiles + profile
        saveConnectionProfiles(profiles)
        _state.value = _state.value.copy(
            connectionProfiles = profiles,
            quickTextModels = loadAllQuickTextModels(profiles, _state.value.disabledConnectionIds),
            status = "Подключение добавлено. Укажите адрес API."
        )
        return id
    }

    fun saveConnectionProfile(
        profileId: String,
        name: String,
        baseUrl: String,
        apiKey: String?,
        imageEnabled: Boolean,
        imageBaseUrl: String?,
        imageProtocol: ImageApiProtocol,
        useSameImageApiKey: Boolean,
        imageApiKey: String?,
        useProviderDefaults: Boolean,
        contextLimitTokens: Int? = null
    ) {
        val old = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        val cleanUrl = normalizeBaseUrl(baseUrl)
        val builtIn = old.type == ProviderType.OPENROUTER || old.type == ProviderType.NVIDIA
        if ((!builtIn || !useProviderDefaults) && cleanUrl.isBlank()) {
            _state.value = _state.value.copy(status = "Укажите адрес API")
            return
        }
        if (contextLimitTokens != null && contextLimitTokens !in 8_192..1_048_576) {
            _state.value = _state.value.copy(status = "Размер контекста должен быть от 8192 до 1048576 токенов")
            return
        }
        val cleanImageUrl = imageBaseUrl?.let(::normalizeBaseUrl)?.takeIf { it.isNotBlank() }
        val updated = old.copy(
            name = when (old.type) {
                ProviderType.OPENROUTER -> "OpenRouter"
                ProviderType.NVIDIA -> "NVIDIA"
                ProviderType.OPENAI_COMPATIBLE -> name.trim().ifBlank { "Другой API" }
            },
            baseUrl = cleanUrl,
            imageEnabled = imageEnabled,
            imageBaseUrl = cleanImageUrl,
            imageProtocol = imageProtocol,
            useSameImageApiKey = useSameImageApiKey,
            useProviderDefaults = if (builtIn) useProviderDefaults else false,
            contextLimitTokens = contextLimitTokens
        )
        val profiles = _state.value.connectionProfiles.map { if (it.id == profileId) updated else it }
        saveConnectionProfiles(profiles)
        if (!apiKey.isNullOrBlank()) secrets.saveProfileApiKey(profileId, apiKey)
        if (useSameImageApiKey) {
            secrets.deleteProfileImageApiKey(profileId)
        } else if (!imageApiKey.isNullOrBlank()) {
            secrets.saveProfileImageApiKey(profileId, imageApiKey)
        }

        var nextImageProfileId = _state.value.imageConnectionProfileId
        if (profileId == nextImageProfileId && !imageGenerationEnabled(updated)) {
            nextImageProfileId = profiles.firstOrNull {
                it.id !in _state.value.disabledConnectionIds && imageGenerationEnabled(it) && isImageProfileConfigured(it)
            }?.id ?: profiles.firstOrNull {
                it.id !in _state.value.disabledConnectionIds && imageGenerationEnabled(it)
            }?.id ?: nextImageProfileId
            prefs.edit().putString("image_connection_profile", nextImageProfileId).apply()
        }
        val nextImageProfile = profiles.firstOrNull { it.id == nextImageProfileId } ?: updated
        val nextImageModel = loadImageModelForProfile(nextImageProfile.id)
        val active = _state.value.activeConnectionProfileId == profileId
        _state.value = _state.value.copy(
            connectionProfiles = profiles,
            apiKeyConfigured = if (active) isProfileConfigured(updated) else _state.value.apiKeyConfigured,
            imageConnectionProfileId = nextImageProfileId,
            imageModel = nextImageModel,
            imageAspectRatio = loadImageParameter("aspect_ratio", nextImageProfileId, nextImageModel),
            imageResolution = loadImageParameter("resolution", nextImageProfileId, nextImageModel),
            availableTextModels = if (active) emptyList() else _state.value.availableTextModels,
            availableImageModels = if (profileId == _state.value.imageConnectionProfileId || nextImageProfileId != _state.value.imageConnectionProfileId) emptyList() else _state.value.availableImageModels,
            modelCatalogConnectionId = null,
            modelCatalog = emptyList(),
            status = "Подключение сохранено"
        )
        if ((active || profileId == nextImageProfileId) && profileId !in _state.value.disabledConnectionIds) refreshModelCapabilities()
        if (updated.type == ProviderType.OPENROUTER) refreshProviderUsage()
    }

    fun checkConnection(profileId: String) {
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Сначала включите подключение «${profile.name}»")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, busyLabel = "Проверяю подключение…", status = null)
            providerRegistry.refreshIfStale(force = true)
            val textCheck = runCatching { textModelsForProfile(profile) }
            val imageCheck = if (!imageGenerationEnabled(profile)) {
                null
            } else if (!isImageProfileConfigured(profile)) {
                Result.failure(IllegalStateException(imageConnectionSetupMessage(profile)))
            } else {
                runCatching { imageModelsForProfile(profile) }
            }

            val textStatus = textCheck.fold(
                onSuccess = { "Текст: ✓ ${it.size} моделей" },
                onFailure = { "Текст: ✕ ${it.message ?: "ошибка"}" }
            )
            val imageStatus = when {
                !imageGenerationEnabled(profile) -> "Изображения: выкл"
                imageCheck == null -> "Изображения: —"
                imageCheck.isSuccess -> {
                    val count = imageCheck.getOrNull().orEmpty().size
                    if (resolvedImageProtocol(profile) == ImageApiProtocol.NVIDIA_NIM)
                        "Изображения: ✓ $count моделей (без пробной генерации)"
                    else
                        "Изображения: ✓ $count моделей"
                }
                else -> "Изображения: ✕ ${imageCheck.exceptionOrNull()?.message ?: "ошибка"}"
            }
            _state.value = _state.value.copy(
                isLoading = false,
                busyLabel = null,
                status = "$textStatus · $imageStatus"
            )
        }
    }

    fun setConnectionEnabled(profileId: String, enabled: Boolean) {
        if (_state.value.isLoading) return
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        val disabled = _state.value.disabledConnectionIds.toMutableSet().apply {
            if (enabled) remove(profileId) else add(profileId)
        }.toSet()
        prefs.edit().putStringSet("disabled_connection_profiles", disabled).apply()
        _state.value = _state.value.copy(
            disabledConnectionIds = disabled,
            quickTextModels = loadAllQuickTextModels(_state.value.connectionProfiles, disabled),
            modelCatalogConnectionId = null,
            modelCatalog = emptyList(),
            status = if (enabled) "Подключение «${profile.name}» включено" else "Подключение «${profile.name}» выключено"
        )

        if (!enabled && _state.value.imageConnectionProfileId == profileId) {
            val fallbackImage = _state.value.connectionProfiles.firstOrNull {
                it.id !in disabled && it.id != profileId && imageGenerationEnabled(it) && isImageProfileConfigured(it)
            } ?: _state.value.connectionProfiles.firstOrNull {
                it.id !in disabled && it.id != profileId && imageGenerationEnabled(it)
            }
            if (fallbackImage != null) {
                prefs.edit().putString("image_connection_profile", fallbackImage.id).apply()
                val fallbackImageModel = loadImageModelForProfile(fallbackImage.id)
                _state.value = _state.value.copy(
                    imageConnectionProfileId = fallbackImage.id,
                    imageModel = fallbackImageModel,
                    imageAspectRatio = loadImageParameter("aspect_ratio", fallbackImage.id, fallbackImageModel),
                    imageResolution = loadImageParameter("resolution", fallbackImage.id, fallbackImageModel),
                    availableImageModels = emptyList()
                )
                if (isProfileConfigured(fallbackImage)) refreshModelCapabilities()
            } else {
                _state.value = _state.value.copy(availableImageModels = emptyList())
            }
        }

        if (!enabled && _state.value.activeConnectionProfileId == profileId) {
            val fallback = _state.value.connectionProfiles.firstOrNull {
                it.id !in disabled && isProfileConfigured(it)
            } ?: _state.value.connectionProfiles.firstOrNull { it.id !in disabled }
            if (fallback != null) {
                selectConnectionProfile(fallback.id)
            } else {
                _state.value = _state.value.copy(
                    mode = ChatMode.TEXT,
                    availableTextModels = emptyList(),
                    availableImageModels = emptyList(),
                    reasoningEnabled = false,
                    webSearchEnabled = false,
                    status = "Все подключения выключены"
                )
            }
        } else if (enabled && (profileId == _state.value.activeConnectionProfileId || profileId == _state.value.imageConnectionProfileId) && isProfileConfigured(profile)) {
            refreshModelCapabilities()
        }
    }

    fun deleteConnectionProfile(profileId: String) {
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.type == ProviderType.OPENROUTER || profile.type == ProviderType.NVIDIA) return
        secrets.deleteProfileApiKey(profileId)
        prefs.edit()
            .remove(profilePrefKey("text_model", profileId))
            .remove(profilePrefKey("image_model", profileId))
            .remove(profilePrefKey("quick_text_models_json", profileId))
            .apply()
        val profiles = _state.value.connectionProfiles.filterNot { it.id == profileId }
        val disabled = _state.value.disabledConnectionIds - profileId
        val fallback = profiles.firstOrNull { it.id !in disabled && isProfileConfigured(it) }
            ?: profiles.firstOrNull { it.id !in disabled }
            ?: profiles.first()
        val imageFallback = profiles.firstOrNull {
            it.id !in disabled && imageGenerationEnabled(it) && isImageProfileConfigured(it)
        } ?: profiles.firstOrNull { it.id !in disabled && imageGenerationEnabled(it) }
            ?: fallback
        val nextImageProfileId = if (_state.value.imageConnectionProfileId == profileId) imageFallback.id else _state.value.imageConnectionProfileId
        if (nextImageProfileId != _state.value.imageConnectionProfileId) {
            prefs.edit().putString("image_connection_profile", nextImageProfileId).apply()
        }
        val chats = _state.value.chats.map { chat ->
            if (chat.connectionProfileId == profileId) chat.copy(
                connectionProfileId = fallback.id,
                textModelOverride = null,
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        saveConnectionProfiles(profiles)
        prefs.edit().putStringSet("disabled_connection_profiles", disabled).apply()
        chatsRepository.save(chats)
        val nextImageModel = loadImageModelForProfile(nextImageProfileId)
        _state.value = _state.value.copy(
            connectionProfiles = profiles,
            disabledConnectionIds = disabled,
            chats = chats,
            quickTextModels = loadAllQuickTextModels(profiles, disabled),
            imageConnectionProfileId = nextImageProfileId,
            imageModel = nextImageModel,
            imageAspectRatio = loadImageParameter("aspect_ratio", nextImageProfileId, nextImageModel),
            imageResolution = loadImageParameter("resolution", nextImageProfileId, nextImageModel),
            availableImageModels = if (nextImageProfileId != _state.value.imageConnectionProfileId) emptyList() else _state.value.availableImageModels,
            modelCatalogConnectionId = null,
            modelCatalog = emptyList(),
            status = "Подключение «${profile.name}» удалено"
        )
        if (_state.value.activeConnectionProfileId == profileId) selectConnectionProfile(fallback.id)
        else if (isImageProfileConfigured(imageConnectionProfile())) refreshModelCapabilities()
    }

    fun loadConnectionModels(profileId: String) {
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Сначала включите подключение «${profile.name}»")
            return
        }
        if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = connectionSetupMessage(profile))
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, busyLabel = "Загружаю модели…", status = null)
            runCatching { textModelsForProfile(profile) }
                .onSuccess { infos ->
                    _state.value = _state.value.copy(
                        modelCatalogConnectionId = profile.id,
                        modelCatalog = infos,
                        isLoading = false,
                        busyLabel = null
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        busyLabel = null,
                        status = it.message ?: "Не удалось загрузить модели подключения"
                    )
                }
        }
    }

    fun loadImageConnectionModels(profileId: String) {
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Сначала включите подключение «${profile.name}»")
            return
        }
        if (!imageGenerationEnabled(profile)) {
            _state.value = _state.value.copy(status = "Генерация изображений выключена для «${profile.name}»")
            return
        }
        if (!isImageProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = imageConnectionSetupMessage(profile))
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, busyLabel = "Загружаю модели изображений…", status = null)
            runCatching { imageModelsForProfile(profile) }
                .onSuccess { infos ->
                    _state.value = _state.value.copy(
                        modelCatalogConnectionId = profile.id,
                        modelCatalog = infos,
                        isLoading = false,
                        busyLabel = null
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        busyLabel = null,
                        status = it.message ?: "Не удалось загрузить модели изображений"
                    )
                }
        }
    }

    fun selectImageModel(profileId: String, model: String) {
        val clean = model.trim()
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (clean.isBlank() || profile.id in _state.value.disabledConnectionIds) return
        if (!imageGenerationEnabled(profile)) {
            _state.value = _state.value.copy(status = "Генерация изображений выключена для «${profile.name}»")
            return
        }
        if (!isImageProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = imageConnectionSetupMessage(profile))
            return
        }
        val saved = prefs.edit()
            .putString("image_connection_profile", profile.id)
            .putString(profilePrefKey("image_model", profile.id), clean)
            .commit()
        if (!saved) {
            _state.value = _state.value.copy(status = "Не удалось сохранить выбор модели изображений")
            return
        }
        val available = if (_state.value.modelCatalogConnectionId == profile.id) _state.value.modelCatalog else emptyList()
        val info = available.firstOrNull { it.id == clean }
        _state.value = _state.value.copy(
            imageConnectionProfileId = profile.id,
            imageModel = clean,
            imageAspectRatio = validatedImageParameter("aspect_ratio", profile.id, clean, info),
            imageResolution = validatedImageParameter("resolution", profile.id, clean, info),
            availableImageModels = available,
            status = "${clean.substringAfterLast('/')} · ${profile.name}"
        )
    }

    fun clearImageModel() {
        val profileId = _state.value.imageConnectionProfileId
        prefs.edit().putString(profilePrefKey("image_model", profileId), "").apply()
        _state.value = _state.value.copy(
            imageModel = "",
            imageAspectRatio = null,
            imageResolution = null,
            status = "Модель изображений снята. Выберите новую в каталоге OpenRouter."
        )
    }

    fun setImageAspectRatio(value: String?) {
        setImageParameter("aspect_ratio", value)
    }

    fun setImageResolution(value: String?) {
        setImageParameter("resolution", value)
    }

    private fun setImageParameter(parameter: String, value: String?) {
        val profile = imageConnectionProfile()
        val model = _state.value.imageModel
        if (model.isBlank()) return
        val clean = value?.trim()?.takeIf { it.isNotBlank() }
        if (clean != null) {
            val allowed = currentImageModelInfo()?.parameterValues(parameter).orEmpty()
            if (allowed.isEmpty() || clean !in allowed) {
                _state.value = _state.value.copy(status = "Выбранная модель не поддерживает параметр $clean")
                return
            }
        }
        val key = imageParameterPrefKey(parameter, profile.id, model)
        prefs.edit().apply {
            if (clean == null) remove(key) else putString(key, clean)
        }.apply()
        _state.value = when (parameter) {
            "aspect_ratio" -> _state.value.copy(imageAspectRatio = clean)
            "resolution" -> _state.value.copy(imageResolution = clean)
            else -> _state.value
        }
    }

    fun toggleQuickTextModelForConnection(profileId: String, model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
        if (clean.endsWith(":batch", ignoreCase = true)) {
            _state.value = _state.value.copy(status = "Batch-модель можно использовать только для пакетных задач")
            return
        }
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        val stored = loadQuickTextModels(profileId).filterNot { it.endsWith(":batch", ignoreCase = true) }
        val alreadySelected = clean in stored
        val nextForProfile = if (alreadySelected) stored.filterNot { it == clean } else stored + clean
        prefs.edit().putString(
            profilePrefKey("quick_text_models_json", profileId),
            gson.toJson(nextForProfile.distinct())
        ).apply()
        _state.value = _state.value.copy(
            quickTextModels = loadAllQuickTextModels(_state.value.connectionProfiles, _state.value.disabledConnectionIds),
            status = if (!alreadySelected) "Модель добавлена в быстрые · ${profile.name}" else "Модель убрана из быстрых"
        )
    }

    fun setMode(mode: ChatMode) {
        if (mode == ChatMode.IMAGE) {
            val imageProfile = imageConnectionProfile()
            if (imageProfile.id in _state.value.disabledConnectionIds || !imageGenerationEnabled(imageProfile) || !isImageProfileConfigured(imageProfile)) {
                _state.value = _state.value.copy(status = "Для создания изображений включите и настройте Image API выбранного подключения")
                return
            }
        }
        prefs.edit().putString("chat_mode", mode.name).apply()
        val chats = _state.value.chats.map { chat ->
            if (chat.id == _state.value.currentChatId) chat.copy(mode = mode) else chat
        }
        chatsRepository.save(chats)
        _state.value = _state.value.copy(mode = mode, chats = chats)
        if (mode == ChatMode.IMAGE && _state.value.availableImageModels.isEmpty()) refreshModels(ChatMode.IMAGE)
    }

    fun defaultTextModelForConnection(profileId: String): String {
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return ""
        return loadTextModelForProfile(profile)
    }

    fun defaultImageModelForConnection(profileId: String): String = loadImageModelForProfile(profileId)

    fun connectionTextEndpoint(profileId: String): String = _state.value.connectionProfiles
        .firstOrNull { it.id == profileId }
        ?.let(::effectiveTextBaseUrl)
        .orEmpty()

    fun connectionImageEndpoint(profileId: String): String = _state.value.connectionProfiles
        .firstOrNull { it.id == profileId }
        ?.let(::effectiveImageBaseUrl)
        .orEmpty()

    fun connectionImageEnabled(profileId: String): Boolean = _state.value.connectionProfiles
        .firstOrNull { it.id == profileId }
        ?.let(::imageGenerationEnabled)
        ?: false

    fun connectionUsesSameImageKey(profileId: String): Boolean = _state.value.connectionProfiles
        .firstOrNull { it.id == profileId }
        ?.let(::usesSameImageApiKey)
        ?: true

    fun connectionUsesProviderDefaults(profileId: String): Boolean = _state.value.connectionProfiles
        .firstOrNull { it.id == profileId }
        ?.let(::usesProviderDefaults)
        ?: false

    fun selectDefaultTextModel(profileId: String, model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
        if (clean.endsWith(":batch", ignoreCase = true)) {
            _state.value = _state.value.copy(status = "Batch-модель нельзя назначить обычному чату")
            return
        }
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id in _state.value.disabledConnectionIds) return
        if (!isProfileConfigured(profile)) return

        val saved = prefs.edit()
            .putString(profilePrefKey("text_model", profile.id), clean)
            .putString("active_connection_profile", profile.id)
            .commit()
        if (!saved) {
            _state.value = _state.value.copy(status = "Не удалось сохранить выбор модели")
            return
        }

        val catalog = when {
            _state.value.modelCatalogConnectionId == profile.id -> _state.value.modelCatalog
            _state.value.activeConnectionProfileId == profile.id -> _state.value.availableTextModels
            else -> emptyList()
        }
        val info = catalog.firstOrNull { it.id == clean }
        val effort = preferredReasoningEffort(clean, info)
        val keepReasoning = reasoningStillValid(info, effort)
        val chats = _state.value.chats.map { chat ->
            if (chat.id == _state.value.currentChatId) chat.copy(
                connectionProfileId = profile.id,
                textModelOverride = null,
                mode = ChatMode.TEXT,
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        chatsRepository.save(chats)
        prefs.edit()
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", keepReasoning)
            .apply()

        _state.value = _state.value.copy(
            chats = chats,
            activeConnectionProfileId = profile.id,
            textModel = clean,
            currentChatTextModel = null,
            mode = ChatMode.TEXT,
            availableTextModels = catalog,
            quickTextModels = loadAllQuickTextModels(_state.value.connectionProfiles, _state.value.disabledConnectionIds),
            webSearchEnabled = if (profile.type == ProviderType.OPENROUTER) prefs.getBoolean("web_search", false) else false,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning,
            apiKeyConfigured = isProfileConfigured(profile),
            status = "Сохранено · ${profile.name}: ${clean.substringAfterLast('/')} · текущий и новые чаты"
        )
        refreshModelCapabilities()
        if (profile.type == ProviderType.OPENROUTER) refreshProviderUsage()
    }

    fun selectModel(mode: ChatMode, model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
        when (mode) {
            ChatMode.TEXT -> selectDefaultTextModel(_state.value.activeConnectionProfileId, clean)
            ChatMode.IMAGE -> selectImageModel(_state.value.imageConnectionProfileId, clean)
        }
    }

    fun toggleQuickTextModel(model: String) {
        toggleQuickTextModelForConnection(_state.value.activeConnectionProfileId, model)
    }

    fun selectQuickTextModel(modelRef: String) {
        if (_state.value.isLoading) return
        val (profileId, modelId) = decodeQuickModelRef(modelRef, _state.value.activeConnectionProfileId)
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Подключение «${profile.name}» выключено")
            return
        }
        if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = connectionSetupMessage(profile))
            return
        }
        val clean = modelId.trim()
        if (clean.isBlank()) return
        val sameProfile = profile.id == _state.value.activeConnectionProfileId
        val info = if (sameProfile) _state.value.availableTextModels.firstOrNull { it.id == clean } else null
        val effort = preferredReasoningEffort(clean, info)
        val keepReasoning = sameProfile && reasoningStillValid(info, effort)
        val chats = _state.value.chats.map { chat ->
            if (chat.id == _state.value.currentChatId) chat.copy(
                connectionProfileId = profile.id,
                textModelOverride = clean,
                mode = ChatMode.TEXT,
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        chatsRepository.save(chats)
        prefs.edit()
            .putString("active_connection_profile", profile.id)
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", keepReasoning)
            .apply()
        _state.value = _state.value.copy(
            chats = chats,
            activeConnectionProfileId = profile.id,
            textModel = loadTextModelForProfile(profile),
            currentChatTextModel = clean,
            quickTextModels = loadAllQuickTextModels(_state.value.connectionProfiles, _state.value.disabledConnectionIds),
            mode = ChatMode.TEXT,
            availableTextModels = if (sameProfile) _state.value.availableTextModels else emptyList(),
            webSearchEnabled = if (profile.type == ProviderType.OPENROUTER) prefs.getBoolean("web_search", false) else false,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning,
            apiKeyConfigured = isProfileConfigured(profile),
            status = null
        )
        refreshModelCapabilities()
    }

    fun useDefaultTextModelForChat() {
        if (_state.value.isLoading) return
        val profile = activeConnectionProfile()
        val modelId = _state.value.textModel
        val info = _state.value.availableTextModels.firstOrNull { it.id == modelId }
        val effort = preferredReasoningEffort(modelId, info)
        val keepReasoning = reasoningStillValid(info, effort)
        val chats = _state.value.chats.map { chat ->
            if (chat.id == _state.value.currentChatId) chat.copy(
                connectionProfileId = profile.id,
                textModelOverride = null,
                mode = ChatMode.TEXT,
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        chatsRepository.save(chats)
        prefs.edit()
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", keepReasoning)
            .apply()
        _state.value = _state.value.copy(
            chats = chats,
            currentChatTextModel = null,
            mode = ChatMode.TEXT,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning
        )
    }

    fun setWebSearchEnabled(enabled: Boolean) {
        DiagnosticLog.action(context, "web_search_toggle", "enabled=$enabled; model=${currentTextModelId()}")
        if (enabled && (activeConnectionProfile().type != ProviderType.OPENROUTER || "openrouter" in _state.value.disabledConnectionIds)) {
            _state.value = _state.value.copy(status = "Поиск в сети сейчас поддерживается подключением OpenRouter")
            return
        }
        val chat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId }
        if (chat?.projectId != null) updateCurrentProjectRuntime { it.copy(webSearchEnabled = enabled) }
        else prefs.edit().putBoolean("web_search", enabled).apply()
        _state.value = _state.value.copy(webSearchEnabled = enabled)
    }

    fun setReasoningEnabled(enabled: Boolean) {
        DiagnosticLog.action(context, "reasoning_toggle", "enabled=$enabled; model=${currentTextModelId()}; effort=${_state.value.reasoningEffort.name}")
        if (enabled) {
            val info = currentTextModelInfo()
            val effort = preferredReasoningEffort(currentTextModelId(), info)
            if (info?.supportsReasoning != true) {
                _state.value = _state.value.copy(status = "Выбранная модель не поддерживает размышление")
                return
            }
            if (info.supportsReasoningEffort && info.reasoningEfforts.isNotEmpty() && effort.apiValue !in info.reasoningEfforts) {
                _state.value = _state.value.copy(status = "Выбранная сила размышления не поддерживается этой моделью")
                return
            }
            prefs.edit().putString("reasoning_effort", effort.name).apply()
            _state.value = _state.value.copy(reasoningEffort = effort)
        }
        val chat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId }
        if (chat?.projectId != null) updateCurrentProjectRuntime { it.copy(reasoningEnabled = enabled, reasoningEffort = _state.value.reasoningEffort) }
        else prefs.edit().putBoolean("reasoning_enabled", enabled).apply()
        _state.value = _state.value.copy(reasoningEnabled = enabled)
    }

    fun setReasoningEffort(effort: ReasoningEffort) {
        setReasoningEffortForModel(currentTextModelId(), effort)
    }

    fun setReasoningEffortForModel(modelId: String, effort: ReasoningEffort) {
        val clean = modelId.trim()
        if (clean.isBlank()) return
        val info = _state.value.availableTextModels.firstOrNull { it.id == clean }
            ?: _state.value.availableImageModels.firstOrNull { it.id == clean }

        if (info?.supportsReasoningEffort == true && info.reasoningEfforts.isNotEmpty() && effort.apiValue !in info.reasoningEfforts) {
            _state.value = _state.value.copy(status = "${reasoningEffortName(effort)} не поддерживается моделью ${clean.substringAfter('/')}")
            return
        }

        val nextMap = _state.value.reasoningEffortsByModel + (clean to effort)
        prefs.edit().putString("reasoning_efforts_by_model_json", gson.toJson(nextMap)).apply()

        if (clean == currentTextModelId()) {
            val keepReasoning = reasoningStillValid(info, effort)
            prefs.edit()
                .putString("reasoning_effort", effort.name)
                .putBoolean("reasoning_enabled", keepReasoning)
                .apply()
            _state.value = _state.value.copy(
                reasoningEffortsByModel = nextMap,
                reasoningEffort = effort,
                reasoningEnabled = keepReasoning
            )
        } else {
            _state.value = _state.value.copy(reasoningEffortsByModel = nextMap)
        }
        if (clean == currentTextModelId()) {
            val chat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId }
            if (chat?.projectId != null) updateCurrentProjectRuntime { it.copy(reasoningEffort = effort) }
        }
    }

    fun saveUserProfile(name: String, gender: String, age: String, occupation: String, note: String) {
        val profile = UserProfile(
            name = name.trim(),
            gender = gender.trim(),
            age = age.trim(),
            occupation = occupation.trim(),
            note = note.trim().take(240)
        )
        prefs.edit()
            .putString("profile_name", profile.name)
            .putString("profile_gender", profile.gender)
            .putString("profile_age", profile.age)
            .putString("profile_occupation", profile.occupation)
            .putString("profile_note", profile.note)
            .apply()
        _state.value = _state.value.copy(userProfile = profile, status = "Профиль пользователя сохранён")
    }

    fun setUserProfileScope(scope: UserProfileScope) {
        prefs.edit().putString("profile_scope", scope.name).apply()
        _state.value = _state.value.copy(userProfileScope = scope)
    }

    fun setAnswerSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("answer_sound", enabled).apply()
        _state.value = _state.value.copy(answerSoundEnabled = enabled)
        if (enabled) playReadySound()
    }

    fun setAnswerSoundChoice(choice: AnswerSoundChoice) {
        val normalized = if (choice == AnswerSoundChoice.CUSTOM) choice else AnswerSoundChoice.DEFAULT
        prefs.edit().putString("answer_sound_choice", normalized.name).apply()
        _state.value = _state.value.copy(answerSoundChoice = normalized)
        playReadySound()
    }

    fun selectAnswerSound(file: StoredFile) {
        if (file.category != "Звуки" || !File(file.localPath).isFile) return
        prefs.edit()
            .putString("answer_sound_choice", AnswerSoundChoice.CUSTOM.name)
            .putString("answer_sound_custom_path", file.localPath)
            .putString("answer_sound_custom_name", file.name)
            .apply()
        _state.value = _state.value.copy(
            answerSoundChoice = AnswerSoundChoice.CUSTOM,
            answerSoundCustomPath = file.localPath,
            answerSoundCustomName = file.name
        )
        playReadySound()
    }

    fun importAnswerSound(uri: Uri) {
        runCatching {
            val resolver = context.contentResolver
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }.orEmpty().ifBlank { "sound_${System.currentTimeMillis()}" }
            val safeName = displayName
                .replace(Regex("[^\\p{L}\\p{N}._ ()-]"), "_")
                .take(120)
                .ifBlank { "sound_${System.currentTimeMillis()}" }
            val dir = File(context.filesDir, "sounds").apply { mkdirs() }
            val target = File(dir, "${UUID.randomUUID()}_$safeName")
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > 20L * 1024L * 1024L) throw IllegalArgumentException("Звуковой файл больше 20 МБ")
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IllegalArgumentException("Не удалось прочитать выбранный звук")
            if (target.length() == 0L) {
                target.delete()
                throw IllegalArgumentException("Выбран пустой звуковой файл")
            }
            target
        }.onSuccess { target ->
            val displayName = target.name.substringAfter('_', target.name)
            prefs.edit()
                .putString("answer_sound_choice", AnswerSoundChoice.CUSTOM.name)
                .putString("answer_sound_custom_path", target.absolutePath)
                .putString("answer_sound_custom_name", displayName)
                .apply()
            _state.value = _state.value.copy(
                answerSoundChoice = AnswerSoundChoice.CUSTOM,
                answerSoundCustomPath = target.absolutePath,
                answerSoundCustomName = displayName,
                storedFiles = storageRepository.list(),
                storageStats = storageRepository.stats(),
                status = "Звук «$displayName» сохранён в Umnik"
            )
            playReadySound()
        }.onFailure {
            _state.value = _state.value.copy(status = it.message ?: "Не удалось добавить звук")
        }
    }

    fun setAnswerSoundVolume(volume: Int) {
        val clean = volume.coerceIn(0, 100)
        prefs.edit().putInt("answer_sound_volume", clean).apply()
        _state.value = _state.value.copy(answerSoundVolume = clean)
    }

    fun setThemeChoice(choice: ThemeChoice) {
        prefs.edit().putString("theme_choice", choice.name).apply()
        _state.value = _state.value.copy(themeChoice = choice)
    }

    fun setCustomThemeColor(color: Int) {
        prefs.edit()
            .putInt("custom_theme_color", color)
            .putString("theme_choice", ThemeChoice.CUSTOM.name)
            .apply()
        _state.value = _state.value.copy(
            customThemeColor = color,
            themeChoice = ThemeChoice.CUSTOM
        )
    }

    private fun isBareEmptyChat(chat: ChatSession): Boolean =
        chat.messages.isEmpty() &&
            chat.chatFiles.orEmpty().isEmpty() &&
            !chat.isFavorite &&
            chat.title == "Новый чат" &&
            chat.assignedRole.isNullOrBlank() &&
            chat.masterPrompt.isNullOrBlank() &&
            chat.textModelOverride.isNullOrBlank()

    fun createChat(projectId: String? = null): String {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return _state.value.currentChatId

        val current = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId }
        if (current != null && current.projectId == projectId && !isOrchestratorChat(current.id) && isBareEmptyChat(current)) {
            _state.value = _state.value.copy(
                pendingAttachments = emptyList(),
                status = null
            )
            return current.id
        }

        val chat = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "Новый чат",
            projectId = projectId,
            mode = ChatMode.TEXT,
            connectionProfileId = _state.value.activeConnectionProfileId
        )

        val retained = _state.value.chats.filterNot { old ->
            old.id != _state.value.currentChatId && old.projectId == null && isBareEmptyChat(old)
        }
        val next = listOf(chat) + retained
        val modelId = _state.value.textModel
        val info = _state.value.availableTextModels.firstOrNull { it.id == modelId }
        val effort = preferredReasoningEffort(modelId, info)
        val keepReasoning = reasoningStillValid(info, effort)
        val newSkillIds = projectId
            ?.let { id -> _state.value.projects.firstOrNull { it.id == id }?.skillIds }
            .orEmpty()
        chatsRepository.save(next)
        if (projectId != null) {
            val fixed = ProjectChatRuntimeProfile(
                modelId = chat.textModelOverride ?: _state.value.textModel,
                webSearchEnabled = _state.value.webSearchEnabled,
                reasoningEnabled = _state.value.reasoningEnabled,
                reasoningEffort = _state.value.reasoningEffort,
                tools = openRouterFeaturePrefs.tools(),
                skillIds = newSkillIds
            )
            projectAutomation.saveProfile(chat.id, fixed)
            prefs.edit().putStringSet(chatSkillsKey(chat.id), newSkillIds).apply()
        }
        prefs.edit()
            .putString("current_chat_id", chat.id)
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", keepReasoning)
            .apply()
        _state.value = _state.value.copy(
            chats = next,
            currentChatId = chat.id,
            messages = emptyList(),
            activeSkillIds = newSkillIds,
            currentChatTextModel = null,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning,
            pendingAttachments = emptyList(),
            storageStats = storageRepository.stats(),
            status = null
        )
        DiagnosticLog.action(context, "new_chat", "chat=${chat.id.take(8)}; project=${projectId ?: "none"}")
        return chat.id
    }

    fun openUsageGuide(): String {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return _state.value.currentChatId
        val profile = _state.value.connectionProfiles.firstOrNull { it.type == ProviderType.OPENROUTER }
            ?: defaultOpenRouterProfile()
        val now = System.currentTimeMillis()
        val messages = UmnikUsageGuide.SECTIONS.map { section ->
            ChatMessage(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                text = section,
                providerName = "Umnik"
            )
        }
        val chat = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "Памятка по Umnik",
            messages = messages,
            mode = ChatMode.TEXT,
            connectionProfileId = profile.id,
            createdAt = now,
            updatedAt = now
        )
        val retained = _state.value.chats.filterNot { old -> old.projectId == null && isBareEmptyChat(old) }
        val next = listOf(chat) + retained
        chatsRepository.save(next)
        prefs.edit()
            .putString("current_chat_id", chat.id)
            .putString("active_connection_profile", profile.id)
            .putString("chat_mode", ChatMode.TEXT.name)
            .putBoolean("reasoning_enabled", false)
            .apply()
        _state.value = _state.value.copy(
            chats = next,
            currentChatId = chat.id,
            messages = messages,
            activeSkillIds = emptySet(),
            mode = ChatMode.TEXT,
            activeConnectionProfileId = profile.id,
            textModel = loadTextModelForProfile(profile),
            currentChatTextModel = null,
            reasoningEnabled = false,
            pendingAttachments = emptyList(),
            status = null
        )
        DiagnosticLog.action(context, "usage_guide_opened", "chat=${chat.id.take(8)}; local=true")
        if (isProfileConfigured(profile)) refreshModelCapabilities()
        return chat.id
    }

    fun branchFromMessage(messageId: String): String? {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return null

        val source = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId } ?: return null
        val messageIndex = source.messages.indexOfFirst { it.id == messageId }
        if (messageIndex < 0) return null

        val branchId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val branchedMessages = source.messages
            .take(messageIndex + 1)
            .map { message -> message.copy(id = UUID.randomUUID().toString()) }

        val copiedFiles = source.chatFiles.orEmpty().mapNotNull { file ->
            runCatching {
                chatFilesRepository.importFile(
                    branchId,
                    PendingAttachment(
                        uri = "branch://${file.id}",
                        name = file.name,
                        mimeType = file.mimeType,
                        size = file.size,
                        localPath = file.localPath
                    )
                )
            }.getOrNull()
        }

        val branchTitle = "Ветка: ${source.title}".take(80)
        val branch = ChatSession(
            id = branchId,
            title = branchTitle,
            messages = branchedMessages,
            projectId = source.projectId,
            mode = ChatMode.TEXT,
            connectionProfileId = source.connectionProfileId ?: _state.value.activeConnectionProfileId,
            textModelOverride = source.textModelOverride,
            chatFiles = copiedFiles,
            assignedRole = source.assignedRole,
            masterPrompt = source.masterPrompt,
            createdAt = now,
            updatedAt = now
        )
        val chats = listOf(branch) + _state.value.chats
        val modelId = branch.textModelOverride ?: _state.value.textModel
        val info = _state.value.availableTextModels.firstOrNull { it.id == modelId }
        val effort = preferredReasoningEffort(modelId, info)
        val keepReasoning = reasoningStillValid(info, effort)
        val branchSkillIds = _state.value.activeSkillIds

        chatsRepository.save(chats)
        prefs.edit()
            .putString("current_chat_id", branch.id)
            .putStringSet(chatSkillsKey(branch.id), branchSkillIds)
            .putString("chat_mode", ChatMode.TEXT.name)
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", keepReasoning)
            .apply()

        _state.value = _state.value.copy(
            chats = chats,
            currentChatId = branch.id,
            messages = branchedMessages,
            activeSkillIds = branchSkillIds,
            mode = ChatMode.TEXT,
            currentChatTextModel = branch.textModelOverride,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning,
            pendingAttachments = emptyList(),
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Ветка открыта в новом чате"
        )
        return branch.id
    }

    fun switchChat(id: String) {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return
        val refreshedChats = chatsRepository.list()
        val original = refreshedChats.firstOrNull { it.id == id } ?: _state.value.chats.firstOrNull { it.id == id } ?: return
        val requestedProfileId = original.connectionProfileId ?: prefs.getString("active_connection_profile", "openrouter") ?: "openrouter"
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == requestedProfileId && it.id !in _state.value.disabledConnectionIds }
            ?: _state.value.connectionProfiles.firstOrNull { it.id !in _state.value.disabledConnectionIds && isProfileConfigured(it) }
            ?: _state.value.connectionProfiles.firstOrNull { it.id !in _state.value.disabledConnectionIds }
            ?: openRouterProfile()
        val migrated = profile.id != requestedProfileId || original.connectionProfileId == null || original.mode == ChatMode.IMAGE
        val chat = if (migrated) original.copy(
            connectionProfileId = profile.id,
            textModelOverride = if (profile.id == requestedProfileId) original.textModelOverride else null,
            mode = ChatMode.TEXT,
            updatedAt = System.currentTimeMillis()
        ) else original
        val baseChats = if (refreshedChats.isNotEmpty()) refreshedChats else _state.value.chats
        val chats = if (migrated) baseChats.map { if (it.id == id) chat else it } else baseChats
        if (migrated) chatsRepository.save(chats)
        val defaultModel = loadTextModelForProfile(profile)
        val fixed = if (chat.projectId != null) runtimeProfile(chat) else null
        val modelId = fixed?.modelId ?: chat.textModelOverride ?: defaultModel
        val effort = fixed?.reasoningEffort ?: preferredReasoningEffort(modelId, null)
        val chatSkillIds = fixed?.skillIds ?: skillIdsForChat(chat)
        prefs.edit()
            .putString("current_chat_id", id)
            .putString("active_connection_profile", profile.id)
            .putString("chat_mode", ChatMode.TEXT.name)
            .putString("reasoning_effort", effort.name)
            .apply()
        _state.value = _state.value.copy(
            chats = chats,
            currentChatId = id,
            messages = chat.messages,
            activeSkillIds = chatSkillIds,
            mode = ChatMode.TEXT,
            activeConnectionProfileId = profile.id,
            textModel = defaultModel,
            currentChatTextModel = fixed?.modelId ?: chat.textModelOverride,
            quickTextModels = loadAllQuickTextModels(_state.value.connectionProfiles, _state.value.disabledConnectionIds),
            imageModel = loadImageModelForProfile(_state.value.imageConnectionProfileId),
            availableTextModels = emptyList(),
            reasoningEffort = effort,
            reasoningEnabled = fixed?.reasoningEnabled ?: prefs.getBoolean("reasoning_enabled", false),
            webSearchEnabled = if (profile.type == ProviderType.OPENROUTER) fixed?.webSearchEnabled ?: prefs.getBoolean("web_search", false) else false,
            apiKeyConfigured = isProfileConfigured(profile),
            pendingAttachments = emptyList()
        )
        DiagnosticLog.action(context, "switch_chat", "chat=${id.take(8)}; messages=${chat.messages.size}; model=$modelId")
        if (profile.id !in _state.value.disabledConnectionIds && isProfileConfigured(profile)) refreshModelCapabilities()
    }

    fun refreshAsyncResults() {
        val refreshed = chatsRepository.list()
        if (refreshed.isEmpty()) return
        val currentMessages = refreshed.firstOrNull { it.id == _state.value.currentChatId }?.messages
            ?: _state.value.messages
        _state.value = _state.value.copy(
            chats = refreshed,
            messages = currentMessages,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
        DiagnosticLog.record(context, "ASYNC", "results refreshed; chat=${_state.value.currentChatId.take(8)}; messages=${currentMessages.size}")
    }

    fun deleteChat(id: String) {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return
        val deletingChat = _state.value.chats.firstOrNull { it.id == id } ?: return
        if (isOrchestratorChat(id)) {
            _state.value = _state.value.copy(status = "Оркестратор удаляется только вместе с проектом")
            return
        }

        deletingChat.stages.orEmpty()
            .flatMap { it.files.orEmpty() }
            .forEach { projectsRepository.deleteFile(it) }
        prefs.edit().remove(chatSkillsKey(id)).apply()
        projectAutomation.deleteChat(id)
        chatFilesRepository.deleteChat(id)
        var remaining = _state.value.chats.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            remaining = listOf(
                ChatSession(
                    UUID.randomUUID().toString(),
                    "Новый чат",
                    mode = ChatMode.TEXT,
                    connectionProfileId = _state.value.activeConnectionProfileId
                )
            )
        }
        val selected = if (_state.value.currentChatId == id) remaining.first() else
            remaining.firstOrNull { it.id == _state.value.currentChatId } ?: remaining.first()
        chatsRepository.save(remaining)
        _state.value = _state.value.copy(chats = remaining, pendingAttachments = emptyList())
        switchChat(selected.id)
        _state.value = _state.value.copy(
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Диалог удалён"
        )
    }

    fun clearAllChats() {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return

        val protectedOrchestrators = _state.value.chats.filter { isOrchestratorChat(it.id) }
        _state.value.chats.filterNot { isOrchestratorChat(it.id) }.forEach { chat ->
            chat.stages.orEmpty()
                .flatMap { it.files.orEmpty() }
                .forEach { projectsRepository.deleteFile(it) }
            chatFilesRepository.deleteChat(chat.id)
            projectAutomation.deleteChat(chat.id)
            prefs.edit().remove(chatSkillsKey(chat.id)).apply()
        }

        val chat = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "Новый чат",
            mode = ChatMode.TEXT,
            connectionProfileId = _state.value.activeConnectionProfileId
        )
        val modelId = _state.value.textModel
        val info = _state.value.availableTextModels.firstOrNull { it.id == modelId }
        val effort = preferredReasoningEffort(modelId, info)
        val keepReasoning = reasoningStillValid(info, effort)

        val resetChats = listOf(chat) + protectedOrchestrators
        chatsRepository.save(resetChats)
        prefs.edit()
            .putString("current_chat_id", chat.id)
            .putString("chat_mode", ChatMode.TEXT.name)
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", keepReasoning)
            .apply()

        _state.value = _state.value.copy(
            chats = resetChats,
            currentChatId = chat.id,
            messages = emptyList(),
            activeSkillIds = emptySet(),
            mode = ChatMode.TEXT,
            currentChatTextModel = null,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning,
            pendingAttachments = emptyList(),
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "История чатов очищена"
        )
    }

    fun updateChatProfile(id: String, title: String, role: String, masterPrompt: String) {
        if (_state.value.isLoading) return
        val chats = _state.value.chats.map { chat ->
            if (chat.id == id) chat.copy(
                title = title.trim().ifBlank { "Новый чат" },
                assignedRole = role.trim().takeIf { it.isNotBlank() },
                masterPrompt = masterPrompt.trim().takeIf { it.isNotBlank() },
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        chatsRepository.save(chats)
        val current = chats.firstOrNull { it.id == _state.value.currentChatId }
        _state.value = _state.value.copy(chats = chats, messages = current?.messages ?: _state.value.messages)
    }

    fun setChatFavorite(id: String, favorite: Boolean) {
        val chats = _state.value.chats.map { chat ->
            if (chat.id == id) chat.copy(isFavorite = favorite, updatedAt = System.currentTimeMillis()) else chat
        }
        chatsRepository.save(chats)
        _state.value = _state.value.copy(chats = chats)
    }

    fun createProject(
        name: String,
        role: String = "",
        masterPrompt: String = "",
        favorite: Boolean = false
    ): String {
        val project = Project(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Новый проект" },
            role = role.trim(),
            masterPrompt = masterPrompt.trim(),
            isFavorite = favorite
        )
        val orchestrator = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "Оркестратор",
            projectId = project.id,
            mode = ChatMode.TEXT,
            connectionProfileId = _state.value.activeConnectionProfileId,
            textModelOverride = _state.value.currentChatTextModel ?: _state.value.textModel
        )
        val projects = listOf(project) + _state.value.projects
        val chats = listOf(orchestrator) + _state.value.chats
        projectsRepository.save(projects)
        chatsRepository.save(chats)
        projectAutomation.registerOrchestrator(project.id, orchestrator.id)
        val runtime = ProjectChatRuntimeProfile(
            modelId = orchestrator.textModelOverride,
            reasoningEffort = _state.value.reasoningEffort,
            tools = openRouterFeaturePrefs.tools(),
            skillIds = project.skillIds
        )
        projectAutomation.saveProfile(orchestrator.id, runtime)
        prefs.edit().putStringSet(chatSkillsKey(orchestrator.id), runtime.skillIds).apply()
        _state.value = _state.value.copy(projects = projects, chats = chats, storedFiles = storageRepository.list(), storageStats = storageRepository.stats())
        return project.id
    }

    fun updateProject(id: String, name: String, role: String, masterPrompt: String, favorite: Boolean) {
        val now = System.currentTimeMillis()
        val projects = _state.value.projects.map { project ->
            if (project.id == id) project.copy(
                name = name.trim().ifBlank { "Проект" },
                role = role.trim(),
                masterPrompt = masterPrompt.trim(),
                isFavorite = favorite,
                updatedAt = now
            ) else project
        }
        projectsRepository.save(projects)
        _state.value = _state.value.copy(projects = projects, storageStats = storageRepository.stats())
    }

    fun upsertProjectStage(
        projectId: String,
        stageId: String?,
        title: String,
        instruction: String,
        modelId: String?,
        files: List<ProjectFile> = emptyList(),
        sourceChatIds: Set<String> = emptySet()
    ): String? {
        if (_state.value.isLoading) return null
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return null
        val cleanInstruction = instruction.trim()
        if (cleanInstruction.isBlank()) {
            _state.value = _state.value.copy(status = "Введите инструкцию этапа")
            return null
        }
        val id = stageId ?: UUID.randomUUID().toString()
        val current = project.stages.orEmpty()
        current.firstOrNull { it.id == id }?.files.orEmpty()
            .filterNot { old -> files.any { it.id == old.id } }
            .forEach { projectsRepository.deleteFile(it) }
        val stage = ProjectStage(
            id = id,
            title = title.trim().ifBlank { "Этап ${current.size + 1}" },
            instruction = cleanInstruction,
            modelId = modelId?.trim()?.takeIf { it.isNotBlank() },
            files = files.ifEmpty { null },
            sourceChatIds = sourceChatIds.ifEmpty { null }
        )
        val nextStages = if (current.any { it.id == id }) {
            current.map { if (it.id == id) stage else it }
        } else current + stage
        val projects = _state.value.projects.map {
            if (it.id == projectId) it.copy(stages = nextStages, updatedAt = System.currentTimeMillis()) else it
        }
        projectsRepository.save(projects)
        _state.value = _state.value.copy(
            projects = projects,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Этап сохранён"
        )
        return id
    }

    fun deleteProjectStage(projectId: String, stageId: String) {
        if (_state.value.isLoading) return
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return
        project.stages.orEmpty().firstOrNull { it.id == stageId }?.files.orEmpty()
            .forEach { projectsRepository.deleteFile(it) }
        val projects = _state.value.projects.map { item ->
            if (item.id == projectId) item.copy(
                stages = item.stages.orEmpty().filterNot { it.id == stageId },
                updatedAt = System.currentTimeMillis()
            ) else item
        }
        projectsRepository.save(projects)
        _state.value = _state.value.copy(
            projects = projects,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
    }

    fun moveProjectStage(projectId: String, stageId: String, delta: Int) {
        if (_state.value.isLoading || delta == 0) return
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return
        val stages = project.stages.orEmpty().toMutableList()
        val from = stages.indexOfFirst { it.id == stageId }
        if (from < 0 || stages.isEmpty()) return
        val to = (from + delta).coerceIn(0, stages.lastIndex)
        if (to == from) return
        val item = stages.removeAt(from)
        stages.add(to, item)
        val projects = _state.value.projects.map {
            if (it.id == projectId) it.copy(stages = stages, updatedAt = System.currentTimeMillis()) else it
        }
        projectsRepository.save(projects)
        _state.value = _state.value.copy(projects = projects)
    }

    fun upsertChatStage(
        chatId: String,
        stageId: String?,
        title: String,
        instruction: String,
        modelId: String?,
        files: List<ProjectFile> = emptyList(),
        sourceChatIds: Set<String> = emptySet()
    ): String? {
        if (_state.value.isLoading) return null
        val chat = _state.value.chats.firstOrNull { it.id == chatId } ?: return null
        if (chat.projectId == null) {
            _state.value = _state.value.copy(status = "Индивидуальные этапы доступны для чатов проекта")
            return null
        }
        val cleanInstruction = instruction.trim()
        if (cleanInstruction.isBlank()) {
            _state.value = _state.value.copy(status = "Введите инструкцию этапа")
            return null
        }
        val id = stageId ?: UUID.randomUUID().toString()
        val current = chat.stages.orEmpty()
        current.firstOrNull { it.id == id }?.files.orEmpty()
            .filterNot { old -> files.any { it.id == old.id } }
            .forEach { projectsRepository.deleteFile(it) }
        val stage = ProjectStage(
            id = id,
            title = title.trim().ifBlank { "Этап ${current.size + 1}" },
            instruction = cleanInstruction,
            modelId = modelId?.trim()?.takeIf { it.isNotBlank() },
            files = files.ifEmpty { null },
            sourceChatIds = sourceChatIds.ifEmpty { null }
        )
        val nextStages = if (current.any { it.id == id }) {
            current.map { if (it.id == id) stage else it }
        } else current + stage
        val chats = _state.value.chats.map {
            if (it.id == chatId) it.copy(stages = nextStages, updatedAt = System.currentTimeMillis()) else it
        }
        chatsRepository.save(chats)
        _state.value = _state.value.copy(
            chats = chats,
            messages = chats.firstOrNull { it.id == _state.value.currentChatId }?.messages ?: _state.value.messages,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Этап чата сохранён"
        )
        return id
    }

    fun deleteChatStage(chatId: String, stageId: String) {
        if (_state.value.isLoading) return
        val chat = _state.value.chats.firstOrNull { it.id == chatId } ?: return
        chat.stages.orEmpty().firstOrNull { it.id == stageId }?.files.orEmpty()
            .forEach { projectsRepository.deleteFile(it) }
        val chats = _state.value.chats.map { item ->
            if (item.id == chatId) item.copy(
                stages = item.stages.orEmpty().filterNot { it.id == stageId },
                updatedAt = System.currentTimeMillis()
            ) else item
        }
        chatsRepository.save(chats)
        _state.value = _state.value.copy(
            chats = chats,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
    }

    fun moveChatStage(chatId: String, stageId: String, delta: Int) {
        if (_state.value.isLoading || delta == 0) return
        val chat = _state.value.chats.firstOrNull { it.id == chatId } ?: return
        val stages = chat.stages.orEmpty().toMutableList()
        val from = stages.indexOfFirst { it.id == stageId }
        if (from < 0 || stages.isEmpty()) return
        val to = (from + delta).coerceIn(0, stages.lastIndex)
        if (to == from) return
        val item = stages.removeAt(from)
        stages.add(to, item)
        val chats = _state.value.chats.map {
            if (it.id == chatId) it.copy(stages = stages, updatedAt = System.currentTimeMillis()) else it
        }
        chatsRepository.save(chats)
        _state.value = _state.value.copy(chats = chats)
    }

    fun importStageDraftFile(projectId: String, uri: Uri): ProjectFile? =
        runCatching { projectsRepository.importFile(projectId, uri) }
            .onSuccess {
                _state.value = _state.value.copy(
                    storedFiles = storageRepository.list(),
                    storageStats = storageRepository.stats()
                )
            }
            .onFailure {
                _state.value = _state.value.copy(status = it.message ?: "Не удалось добавить файл этапа")
            }
            .getOrNull()

    fun deleteStageDraftFile(file: ProjectFile) {
        projectsRepository.deleteFile(file)
        _state.value = _state.value.copy(
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
    }

    fun cleanupStageDraftFiles(originalFileIds: Set<String>, draftFiles: List<ProjectFile>) {
        draftFiles.filterNot { it.id in originalFileIds }.forEach { projectsRepository.deleteFile(it) }
        _state.value = _state.value.copy(
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
    }

    fun upsertOrchestratorStep(
        chatId: String,
        stepId: String?,
        title: String,
        type: OrchestratorStepType,
        targetChatId: String?,
        prompt: String,
        passPreviousResult: Boolean
    ): String? {
        if (_state.value.isLoading || _state.value.requestActive || !isOrchestratorChat(chatId)) return null
        val current = projectAutomation.steps(chatId)
        val id = stepId ?: UUID.randomUUID().toString()
        val step = OrchestratorStep(
            id = id,
            title = title.trim().ifBlank { "Шаг ${current.size + 1}" },
            type = type,
            targetChatId = targetChatId,
            prompt = prompt.trim(),
            passPreviousResult = passPreviousResult
        )
        val next = if (current.any { it.id == id }) current.map { if (it.id == id) step else it } else current + step
        projectAutomation.saveSteps(chatId, next)
        touchChat(chatId)
        return id
    }

    fun deleteOrchestratorStep(chatId: String, stepId: String) {
        if (_state.value.isLoading || _state.value.requestActive || !isOrchestratorChat(chatId)) return
        projectAutomation.saveSteps(chatId, projectAutomation.steps(chatId).filterNot { it.id == stepId })
        touchChat(chatId)
    }

    fun moveOrchestratorStep(chatId: String, stepId: String, delta: Int) {
        if (_state.value.isLoading || _state.value.requestActive || delta == 0 || !isOrchestratorChat(chatId)) return
        val steps = projectAutomation.steps(chatId).toMutableList()
        val from = steps.indexOfFirst { it.id == stepId }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, steps.lastIndex)
        if (to == from) return
        val item = steps.removeAt(from)
        steps.add(to, item)
        projectAutomation.saveSteps(chatId, steps)
        touchChat(chatId)
    }

    private fun touchChat(chatId: String) {
        val chats = _state.value.chats.map { if (it.id == chatId) it.copy(updatedAt = System.currentTimeMillis()) else it }
        chatsRepository.save(chats)
        _state.value = _state.value.copy(chats = chats)
    }

    fun runOrchestrator(projectId: String): String? {
        if (_state.value.isLoading || _state.value.requestActive || projectStagesJob != null) return null
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return null
        val orchestratorId = projectAutomation.orchestratorChatId(projectId) ?: return null
        val orchestrator = _state.value.chats.firstOrNull { it.id == orchestratorId } ?: return null
        val steps = projectAutomation.steps(orchestratorId)
        if (steps.isEmpty()) {
            _state.value = _state.value.copy(status = "У оркестратора пока нет шагов")
            return null
        }
        val launch = ChatMessage(UUID.randomUUID().toString(), "user", "Выполнить сценарий оркестратора (${steps.size} шагов).")
        val prepared = chatsRepository.list().map { chat ->
            if (chat.id == orchestratorId) chat.copy(messages = chat.messages + launch, updatedAt = System.currentTimeMillis()) else chat
        }
        chatsRepository.save(prepared)
        _state.value = _state.value.copy(
            chats = prepared,
            messages = prepared.firstOrNull { it.id == _state.value.currentChatId }?.messages ?: _state.value.messages,
            isLoading = true,
            requestActive = true,
            busyLabel = "Оркестратор · шаг 1 из ${steps.size}",
            status = null
        )
        activeRequestJob = launchRequest(orchestratorId, launch.id, "Оркестратор · ${project.name}") {
            var previous = ""
            var failed: Throwable? = null
            try {
                steps.forEachIndexed { index, step ->
                    _state.value = _state.value.copy(busyLabel = "Оркестратор · шаг ${index + 1} из ${steps.size}: ${step.title}")
                    val result = when (step.type) {
                        OrchestratorStepType.EXECUTE_CHAT -> {
                            val target = chatsRepository.list().firstOrNull {
                                it.id == step.targetChatId && it.projectId == project.id && !isOrchestratorChat(it.id)
                            } ?: error("Для шага «${step.title}» не выбран чат")
                            executeOrchestratorChatTask(project, target, step.prompt, previous, step.passPreviousResult)
                        }
                        OrchestratorStepType.RUN_CHAT_STAGES -> {
                            val target = chatsRepository.list().firstOrNull {
                                it.id == step.targetChatId && it.projectId == project.id && !isOrchestratorChat(it.id)
                            } ?: error("Для шага «${step.title}» не выбран чат")
                            if (target.stages.orEmpty().isEmpty()) error("У чата «${target.title}» нет этапов")
                            executeOrchestratorStageSequence(project, target, target.stages.orEmpty(), step.prompt, previous, step.passPreviousResult)
                        }
                        OrchestratorStepType.RUN_PROJECT_STAGES -> {
                            if (project.stages.orEmpty().isEmpty()) error("У проекта нет общих этапов")
                            executeOrchestratorStageSequence(project, orchestrator, project.stages.orEmpty(), step.prompt, previous, step.passPreviousResult)
                        }
                    }
                    previous = result
                    appendOrchestratorLog(orchestratorId, index + 1, step.title, result)
                }
            } catch (error: Throwable) {
                failed = error
                appendOrchestratorLog(orchestratorId, -1, "Сценарий остановлен", error.message ?: "Ошибка выполнения")
            } finally {
                context.getSharedPreferences("request_execution", Context.MODE_PRIVATE).edit().remove("target_chat_id").apply()
                appendOrchestratorLog(
                    orchestratorId,
                    0,
                    "Оркестратор",
                    if (failed == null) "Сценарий завершён. Выполнено шагов: ${steps.size}." else "Сценарий остановлен: ${failed?.message ?: "ошибка"}"
                )
                _state.value = _state.value.copy(
                    isLoading = false,
                    requestActive = false,
                    busyLabel = null,
                    status = if (failed == null) "Сценарий оркестратора выполнен" else "Оркестратор остановлен"
                )
            }
        }
        return orchestratorId
    }

    private fun orchestratorPrompt(prompt: String, previous: String, passPrevious: Boolean): String = buildString {
        append(prompt.trim().ifBlank { "Выполни свою заданную роль и инструкции, используя текущий контекст этого чата." })
        if (passPrevious && previous.isNotBlank()) {
            appendLine(); appendLine(); appendLine("===== РЕЗУЛЬТАТ ПРЕДЫДУЩЕГО ШАГА ОРКЕСТРАТОРА ====="); append(previous)
        }
    }

    private suspend fun executeOrchestratorChatTask(
        project: Project,
        requested: ChatSession,
        prompt: String,
        previous: String,
        passPrevious: Boolean
    ): String {
        val all = chatsRepository.list()
        val chat = all.firstOrNull { it.id == requested.id } ?: requested
        val runtime = projectAutomation.profile(chat.id) ?: defaultRuntimeProfile(chat)
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == chat.connectionProfileId } ?: openRouterProfile()
        if (!isProfileConfigured(profile)) error("Подключение для чата «${chat.title}» не настроено")
        val key = secrets.getProfileApiKey(profile.id).orEmpty()
        if (key.isBlank()) error("Нет API-ключа для чата «${chat.title}»")
        val modelId = runtime.modelId ?: chat.textModelOverride ?: loadTextModelForProfile(profile)
        val infos = runCatching { textModelsForProfile(profile) }.getOrDefault(emptyList())
        val modelInfo = infos.firstOrNull { it.id == modelId }
        val task = orchestratorPrompt(prompt, previous, passPrevious)
        val history = chat.messages
        val user = ChatMessage(UUID.randomUUID().toString(), "user", task)
        var updated = all.map { if (it.id == chat.id) it.copy(messages = it.messages + user, updatedAt = System.currentTimeMillis()) else it }
        chatsRepository.save(updated)
        publishChats(updated)
        val attachments = orchestratorAttachments(project, chat, modelInfo)
        val skillText = skills.promptFor(runtime.skillIds)
        val actualReasoning = runtime.reasoningEnabled && modelInfo?.supportsReasoning == true &&
            (modelInfo.reasoningEfforts.isEmpty() || runtime.reasoningEffort.apiValue in modelInfo.reasoningEfforts)
        val effort = if (actualReasoning && modelInfo?.supportsReasoningEffort == true) runtime.reasoningEffort.apiValue else null
        val requestInfo = (modelInfo ?: ModelInfo(modelId)).copy(
            contextLength = listOfNotNull(modelInfo?.contextLength, profile.contextLimitTokens).minOrNull()
        )
        val execPrefs = context.getSharedPreferences("request_execution", Context.MODE_PRIVATE)
        execPrefs.edit().putString("target_chat_id", chat.id).commit()
        val result = try {
            if (profile.type == ProviderType.OPENROUTER) {
                api.chat(
                    key, modelId, history, task, attachments,
                    buildSystemPrompt(skillText, project, chat, modelInfo?.supportsTools == true),
                    runtime.webSearchEnabled, actualReasoning, effort, modelInfo?.supportsTools == true,
                    effectiveTextBaseUrl(profile), requestInfo
                )
            } else {
                compatibleApi.chat(
                    key, effectiveTextBaseUrl(profile), modelId, history, task, attachments,
                    buildSystemPrompt(skillText, project, chat, false), requestInfo
                )
            }
        } finally {
            execPrefs.edit().remove("target_chat_id").commit()
        }
        val text = ProjectOutputPolicy.apply(result.text, project.masterPrompt).ifBlank { "Готово." }
        val assistant = ChatMessage(
            UUID.randomUUID().toString(), "assistant", text,
            generatedFiles = result.files,
            modelId = result.modelId ?: modelId,
            providerName = result.providerName,
            costUsd = result.costUsd,
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens
        )
        val latest = chatsRepository.list()
        updated = latest.map { if (it.id == chat.id) it.copy(messages = it.messages + assistant, updatedAt = System.currentTimeMillis()) else it }
        chatsRepository.save(updated)
        publishChats(updated)
        return text
    }

    private suspend fun executeOrchestratorStageSequence(
        project: Project,
        requested: ChatSession,
        stages: List<ProjectStage>,
        prompt: String,
        previous: String,
        passPrevious: Boolean
    ): String {
        val initial = orchestratorPrompt(prompt, previous, passPrevious)
        var all = chatsRepository.list()
        val first = all.firstOrNull { it.id == requested.id } ?: requested
        val launch = ChatMessage(UUID.randomUUID().toString(), "user", "Оркестратор запускает этапы.\n\n$initial")
        all = all.map { if (it.id == first.id) it.copy(messages = it.messages + launch, updatedAt = System.currentTimeMillis()) else it }
        chatsRepository.save(all)
        publishChats(all)
        val baseHistory = all.first { it.id == first.id }.messages
        val results = mutableListOf<Pair<ProjectStage, String>>()
        stages.forEachIndexed { index, stage ->
            val currentChats = chatsRepository.list()
            val chat = currentChats.firstOrNull { it.id == first.id } ?: first
            val runtime = projectAutomation.profile(chat.id) ?: defaultRuntimeProfile(chat)
            val profile = _state.value.connectionProfiles.firstOrNull { it.id == chat.connectionProfileId } ?: openRouterProfile()
            if (!isProfileConfigured(profile)) error("Подключение для чата «${chat.title}» не настроено")
            val key = secrets.getProfileApiKey(profile.id).orEmpty()
            val modelId = stage.modelId?.takeIf { it.isNotBlank() } ?: runtime.modelId ?: chat.textModelOverride ?: loadTextModelForProfile(profile)
            val infos = runCatching { textModelsForProfile(profile) }.getOrDefault(emptyList())
            val modelInfo = infos.firstOrNull { it.id == modelId }
            val sourceChats = stage.sourceChatIds.orEmpty().mapNotNull { id -> currentChats.firstOrNull { it.id == id && it.projectId == project.id } }
            val stagePrompt = buildString {
                appendLine("Выполни только текущий этап. Не переходи к следующим этапам сам.")
                appendLine(); appendLine("===== ИСХОДНАЯ ЗАДАЧА ====="); appendLine(initial)
                if (sourceChats.isNotEmpty()) {
                    appendLine(); appendLine("===== ВЫБРАННЫЕ ЧАТЫ ПРОЕКТА =====")
                    sourceChats.forEach { source ->
                        appendLine("### ${source.title}")
                        source.messages.forEach { message -> appendLine("${message.role}: ${message.text}") }
                    }
                }
                if (results.isNotEmpty()) {
                    appendLine(); appendLine("===== РЕЗУЛЬТАТЫ ПРЕДЫДУЩИХ ЭТАПОВ =====")
                    results.forEachIndexed { i, pair -> appendLine("--- Этап ${i + 1}: ${pair.first.title} ---\n${pair.second}") }
                }
                appendLine(); appendLine("===== ТЕКУЩИЙ ЭТАП ${index + 1}: ${stage.title} ====="); appendLine(stage.instruction)
            }
            val attachments = (
                orchestratorAttachments(project, chat, modelInfo) +
                    stage.files.orEmpty().map { PendingAttachment("stage://${it.id}", it.name, it.mimeType, it.size, it.localPath) } +
                    sourceChats.flatMap { it.chatFiles.orEmpty() }.map(::chatFileAsAttachment)
            ).distinctBy { it.localPath ?: it.uri }
            val skillText = skills.promptFor(runtime.skillIds)
            val actualReasoning = runtime.reasoningEnabled && modelInfo?.supportsReasoning == true &&
                (modelInfo.reasoningEfforts.isEmpty() || runtime.reasoningEffort.apiValue in modelInfo.reasoningEfforts)
            val effort = if (actualReasoning && modelInfo?.supportsReasoningEffort == true) runtime.reasoningEffort.apiValue else null
            val requestInfo = (modelInfo ?: ModelInfo(modelId)).copy(contextLength = listOfNotNull(modelInfo?.contextLength, profile.contextLimitTokens).minOrNull())
            val execPrefs = context.getSharedPreferences("request_execution", Context.MODE_PRIVATE)
            execPrefs.edit().putString("target_chat_id", chat.id).commit()
            val response = try {
                if (profile.type == ProviderType.OPENROUTER) api.chat(
                    key, modelId, baseHistory, stagePrompt, attachments,
                    buildSystemPrompt(skillText, project, chat, modelInfo?.supportsTools == true),
                    runtime.webSearchEnabled, actualReasoning, effort, modelInfo?.supportsTools == true,
                    effectiveTextBaseUrl(profile), requestInfo
                ) else compatibleApi.chat(
                    key, effectiveTextBaseUrl(profile), modelId, baseHistory, stagePrompt, attachments,
                    buildSystemPrompt(skillText, project, chat, false), requestInfo
                )
            } finally { execPrefs.edit().remove("target_chat_id").commit() }
            val text = ProjectOutputPolicy.apply(response.text, project.masterPrompt).ifBlank { "Готово." }
            results += stage to text
            appendProjectStageMessage(chat.id, index + 1, stage.title, text, response.modelId ?: modelId, response)
        }
        return results.lastOrNull()?.second ?: "Этапы выполнены."
    }

    private fun orchestratorAttachments(project: Project, chat: ChatSession, info: ModelInfo?): List<PendingAttachment> {
        fun allowed(a: PendingAttachment): Boolean {
            val mime = a.mimeType.lowercase(); val name = a.name.lowercase()
            val text = mime.startsWith("text/") || listOf(".md", ".json", ".csv", ".yaml", ".yml", ".xml").any(name::endsWith)
            return text || mime == "application/pdf" || name.endsWith(".pdf") ||
                (mime.startsWith("image/") && info?.accepts("image") == true) ||
                (mime.startsWith("audio/") && info?.accepts("audio") == true) ||
                (mime.startsWith("video/") && info?.accepts("video") == true)
        }
        val projectFiles = project.files.map { PendingAttachment("project://${it.id}", it.name, it.mimeType, it.size, it.localPath) }
        return (chat.chatFiles.orEmpty().map(::chatFileAsAttachment) + projectFiles).filter(::allowed).distinctBy { it.localPath ?: it.uri }
    }

    private fun appendOrchestratorLog(chatId: String, number: Int, title: String, text: String) {
        val latest = chatsRepository.list()
        val message = ChatMessage(
            UUID.randomUUID().toString(),
            "assistant",
            if (number > 0) "## Шаг $number: $title\n\n$text" else "## $title\n\n$text",
            providerName = "Оркестратор"
        )
        val updated = latest.map { if (it.id == chatId) it.copy(messages = it.messages + message, updatedAt = System.currentTimeMillis()) else it }
        chatsRepository.save(updated)
        publishChats(updated)
    }

    private fun publishChats(chats: List<ChatSession>) {
        _state.value = _state.value.copy(
            chats = chats,
            messages = chats.firstOrNull { it.id == _state.value.currentChatId }?.messages ?: _state.value.messages,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
    }

    fun runProjectStages(projectId: String, initialTask: String): String? {
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return null
        return runStageSequence(project, project.stages.orEmpty(), initialTask, "проекта")
    }

    fun runCurrentChatStages(initialTask: String): String? {
        val chat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId } ?: return null
        val projectId = chat.projectId ?: return null
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return null
        return runStageSequence(project, chat.stages.orEmpty(), initialTask, "чата")
    }

    private fun runStageSequence(
        project: Project,
        configuredStages: List<ProjectStage>,
        initialTask: String,
        sequenceName: String
    ): String? {
        if (_state.value.isLoading || _state.value.requestActive || projectStagesJob != null) return null
        val stages = configuredStages.filter { it.instruction.isNotBlank() }
        if (stages.isEmpty()) {
            _state.value = _state.value.copy(status = "Сначала добавьте хотя бы один этап работы")
            return null
        }
        val profile = openRouterProfile()
        if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = "Сначала сохраните API-ключ OpenRouter")
            return null
        }
        val apiKey = secrets.getProfileApiKey(profile.id).orEmpty()
        if (apiKey.isBlank()) return null

        val activeChat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId }
        val chatId = if (activeChat?.projectId == project.id) activeChat.id else createChat(project.id)
        val currentChat = _state.value.chats.firstOrNull { it.id == chatId } ?: return null
        val baseHistory = currentChat.messages
        val chatAttachments = currentChat.chatFiles.orEmpty().map(::chatFileAsAttachment)
        val startText = initialTask.trim().ifBlank {
            "Используй текущий диалог, его вложения, инструкции и материалы проекта."
        }
        val now = System.currentTimeMillis()
        val user = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            text = "Запустить этапы $sequenceName.\n\nИсходная задача:\n$startText",
            timestamp = now
        )
        val stageChat = currentChat.copy(
            title = if (currentChat.title == "Новый чат") "Этапы · ${project.name}".take(80) else currentChat.title,
            messages = currentChat.messages + user,
            updatedAt = now
        )
        val preparedChats = _state.value.chats.map { if (it.id == chatId) stageChat else it }
        chatsRepository.save(preparedChats)
        _state.value = _state.value.copy(
            chats = preparedChats,
            currentChatId = chatId,
            messages = stageChat.messages,
            pendingAttachments = emptyList(),
            isLoading = true,
            requestActive = true,
            busyLabel = "Этап 1 из ${stages.size}…",
            status = null
        )
        val generation = ++requestGeneration
        DiagnosticLog.action(
            context,
            "stage_sequence_start",
            "project=${project.id.take(8)}; chat=${chatId.take(8)}; sequence=$sequenceName; stages=${stages.size}"
        )

        projectStagesJob = viewModelScope.launch {
            val results = mutableListOf<Pair<ProjectStage, String>>()
            try {
                stages.forEachIndexed { index, stage ->
                    if (generation != requestGeneration) return@launch
                    val currentState = _state.value
                    val requestedModel = stage.modelId?.takeIf { it.isNotBlank() }
                        ?: currentState.currentChatTextModel
                        ?: currentState.textModel
                    val known = currentState.availableTextModels.firstOrNull { it.id == requestedModel }
                    val modelId = when {
                        requestedModel == "openrouter/auto" -> requestedModel
                        known != null && !known.isBatch && ModelCategory.TEXT in known.categories -> requestedModel
                        else -> currentState.textModel.takeUnless { it.endsWith(":batch", true) } ?: "openrouter/auto"
                    }
                    val modelInfo = currentState.availableTextModels.firstOrNull { it.id == modelId }
                    val chosenWindow = listOfNotNull(modelInfo?.contextLength, profile.contextLimitTokens).minOrNull()
                    val requestModelInfo = (modelInfo ?: ModelInfo(modelId)).copy(contextLength = chosenWindow)
                    val actualReasoning = currentState.reasoningEnabled && modelInfo?.supportsReasoning == true &&
                        (modelInfo.reasoningEfforts.isEmpty() || currentState.reasoningEffort.apiValue in modelInfo.reasoningEfforts)
                    val effort = if (actualReasoning && modelInfo?.supportsReasoningEffort == true) currentState.reasoningEffort.apiValue else null

                    fun projectFileAttachment(prefix: String, file: ProjectFile) = PendingAttachment(
                        uri = "$prefix://${file.id}",
                        name = file.name,
                        mimeType = file.mimeType,
                        size = file.size,
                        localPath = file.localPath
                    )

                    val latestChats = chatsRepository.list().ifEmpty { currentState.chats }
                    val sourceChats = stage.sourceChatIds.orEmpty()
                        .mapNotNull { sourceId -> latestChats.firstOrNull { it.id == sourceId } }
                        .filter { it.projectId == project.id && it.id != chatId }
                    val sourceChatText = buildString {
                        sourceChats.forEach { source ->
                            appendLine("--- Чат проекта: ${source.title} ---")
                            source.messages.forEach { message ->
                                val role = if (message.role == "assistant") "Модель" else "Пользователь"
                                if (message.text.isNotBlank()) appendLine("$role: ${message.text}")
                                if (message.generatedFiles.isNotEmpty()) {
                                    appendLine("Файлы результата: ${message.generatedFiles.joinToString { it.name }}")
                                }
                            }
                            appendLine()
                        }
                    }.trim()

                    val sourceAttachments = sourceChats.flatMap { source ->
                        source.chatFiles.orEmpty().map(::chatFileAsAttachment) +
                            source.messages.flatMap { message -> message.generatedFiles }.map { file ->
                                PendingAttachment(
                                    uri = "generated://${file.id}",
                                    name = file.name,
                                    mimeType = file.mimeType,
                                    size = file.size,
                                    localPath = file.localPath
                                )
                            }
                    }
                    val projectAttachments = project.files.map { projectFileAttachment("project", it) }
                    val stageAttachments = stage.files.orEmpty().map { projectFileAttachment("stage", it) }

                    fun allowedForStage(attachment: PendingAttachment): Boolean {
                        val mime = attachment.mimeType.lowercase()
                        val name = attachment.name.lowercase()
                        val textLike = mime.startsWith("text/") || name.endsWith(".md") || name.endsWith(".json") ||
                            name.endsWith(".csv") || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".xml")
                        return textLike || mime == "application/pdf" || name.endsWith(".pdf") ||
                            (mime.startsWith("image/") && modelInfo?.accepts("image") == true) ||
                            (mime.startsWith("audio/") && modelInfo?.accepts("audio") == true) ||
                            (mime.startsWith("video/") && modelInfo?.accepts("video") == true)
                    }
                    val attachments = (projectAttachments + chatAttachments + stageAttachments + sourceAttachments)
                        .filter(::allowedForStage)
                        .distinctBy { it.localPath ?: it.uri }
                    val skillsText = skills.promptFor(currentState.activeSkillIds)
                    val systemPrompt = buildSystemPrompt(skillsText, project, stageChat, modelInfo?.supportsTools == true)
                    val prompt = buildString {
                        appendLine("Выполни только текущий этап сценария. Не переходи к следующим этапам сам.")
                        appendLine()
                        appendLine("===== ИСХОДНАЯ ЗАДАЧА =====")
                        appendLine(startText)
                        if (sourceChatText.isNotBlank()) {
                            appendLine()
                            appendLine("===== ВЫБРАННЫЕ ЧАТЫ ПРОЕКТА =====")
                            appendLine(sourceChatText)
                        }
                        if (results.isNotEmpty()) {
                            appendLine()
                            appendLine("===== РЕЗУЛЬТАТЫ ПРЕДЫДУЩИХ ЭТАПОВ =====")
                            results.forEachIndexed { resultIndex, (previousStage, previousText) ->
                                appendLine("--- Этап ${resultIndex + 1}: ${previousStage.title} ---")
                                appendLine(previousText)
                            }
                        }
                        appendLine()
                        appendLine("===== ТЕКУЩИЙ ЭТАП ${index + 1}: ${stage.title} =====")
                        appendLine(stage.instruction)
                        appendLine()
                        appendLine("Верни законченный результат только этого этапа. Он будет передан следующему этапу автоматически.")
                    }
                    _state.value = _state.value.copy(busyLabel = "Этап ${index + 1} из ${stages.size}: ${stage.title}")
                    DiagnosticLog.record(
                        context,
                        "PROJECT_STAGE",
                        "start project=${project.id.take(8)}; stage=${index + 1}/${stages.size}; model=$modelId; history=${baseHistory.size}; sources=${sourceChats.size}; stageFiles=${stage.files.orEmpty().size}"
                    )

                    val result = try {
                        api.chat(
                            apiKey,
                            modelId,
                            baseHistory,
                            prompt,
                            attachments,
                            systemPrompt,
                            currentState.webSearchEnabled,
                            actualReasoning,
                            effort,
                            modelInfo?.supportsTools == true,
                            effectiveTextBaseUrl(profile),
                            requestModelInfo
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        val raw = error.message.orEmpty()
                        val friendly = when {
                            raw.contains("429") || raw.contains("rate limit", true) -> "Провайдер временно ограничил запросы. Попробуйте другую модель или повторите позже."
                            error is java.net.SocketException -> "Соединение оборвалось во время этого этапа."
                            error is java.net.SocketTimeoutException -> "Модель не ответила вовремя."
                            else -> raw.ifBlank { "Не удалось выполнить этап" }
                        }
                        appendProjectStageMessage(chatId, index + 1, stage.title, "Ошибка: $friendly", modelId, null)
                        DiagnosticLog.record(context, "PROJECT_STAGE", "failed project=${project.id.take(8)}; stage=${index + 1}; model=$modelId", error)
                        _state.value = _state.value.copy(status = "Этап ${index + 1} остановлен: $friendly")
                        return@launch
                    }

                    val stageText = ProjectOutputPolicy.apply(result.text, project.masterPrompt).ifBlank { "Готово." }
                    results += stage to stageText
                    appendProjectStageMessage(chatId, index + 1, stage.title, stageText, result.modelId ?: modelId, result)
                    DiagnosticLog.record(context, "PROJECT_STAGE", "success project=${project.id.take(8)}; stage=${index + 1}/${stages.size}; model=${result.modelId ?: modelId}; chars=${stageText.length}")
                }
                if (generation == requestGeneration) {
                    _state.value = _state.value.copy(status = "Все этапы $sequenceName выполнены: ${stages.size}")
                    playReadySound()
                }
            } finally {
                if (generation == requestGeneration) {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        requestActive = false,
                        busyLabel = null,
                        storedFiles = storageRepository.list(),
                        storageStats = storageRepository.stats()
                    )
                }
                projectStagesJob = null
            }
        }
        return chatId
    }

    private fun appendProjectStageMessage(
        chatId: String,
        number: Int,
        title: String,
        text: String,
        modelId: String?,
        result: OpenRouterClient.Result?
    ) {
        val stored = chatsRepository.list()
        val chat = stored.firstOrNull { it.id == chatId } ?: return
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "assistant",
            text = "## Этап $number: $title\n\n$text",
            generatedFiles = result?.files.orEmpty(),
            modelId = modelId,
            providerName = result?.providerName,
            costUsd = result?.costUsd,
            inputTokens = result?.inputTokens,
            outputTokens = result?.outputTokens
        )
        val updatedChat = chat.copy(messages = chat.messages + message, updatedAt = System.currentTimeMillis())
        val updated = stored.map { if (it.id == chatId) updatedChat else it }
        chatsRepository.save(updated)
        _state.value = _state.value.copy(
            chats = updated,
            messages = if (_state.value.currentChatId == chatId) updatedChat.messages else _state.value.messages,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
    }

    fun setProjectFavorite(id: String, favorite: Boolean) {
        val projects = _state.value.projects.map { project ->
            if (project.id == id) project.copy(isFavorite = favorite, updatedAt = System.currentTimeMillis()) else project
        }
        projectsRepository.save(projects)
        _state.value = _state.value.copy(projects = projects)
    }

    fun toggleProjectSkill(projectId: String, skillId: String) {
        var updatedSkillIds: Set<String>? = null
        val projects = _state.value.projects.map { project ->
            if (project.id != projectId) project else {
                val next = project.skillIds.toMutableSet().apply { if (!add(skillId)) remove(skillId) }.toSet()
                updatedSkillIds = next
                project.copy(skillIds = next, updatedAt = System.currentTimeMillis())
            }
        }
        projectsRepository.save(projects)
        val currentChat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId }
        val followsProjectDefaults = currentChat?.projectId == projectId && !prefs.contains(chatSkillsKey(currentChat.id))
        _state.value = _state.value.copy(
            projects = projects,
            activeSkillIds = if (followsProjectDefaults) updatedSkillIds.orEmpty() else _state.value.activeSkillIds
        )
    }

    fun addProjectFile(projectId: String, uri: Uri) {
        runCatching { projectsRepository.importFile(projectId, uri) }
            .onSuccess { file ->
                val projects = _state.value.projects.map { project ->
                    if (project.id == projectId) project.copy(
                        files = project.files + file,
                        updatedAt = System.currentTimeMillis()
                    ) else project
                }
                projectsRepository.save(projects)
                _state.value = _state.value.copy(
                    projects = projects,
                    storedFiles = storageRepository.list(),
                    storageStats = storageRepository.stats(),
                    status = "Файл «${file.name}» добавлен в проект"
                )
            }
            .onFailure { _state.value = _state.value.copy(status = it.message ?: "Не удалось добавить файл") }
    }

    fun deleteProjectFile(projectId: String, fileId: String) {
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return
        val file = project.files.firstOrNull { it.id == fileId } ?: return
        projectsRepository.deleteFile(file)
        val projects = _state.value.projects.map {
            if (it.id == projectId) it.copy(
                files = it.files.filterNot { f -> f.id == fileId },
                updatedAt = System.currentTimeMillis()
            ) else it
        }
        projectsRepository.save(projects)
        _state.value = _state.value.copy(
            projects = projects,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
    }

    fun importProjectPromptFile(projectId: String, uri: Uri) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().readText()
            } ?: error("Не удалось прочитать файл")
        }.onSuccess { text ->
            val current = _state.value.projects.firstOrNull { it.id == projectId } ?: return@onSuccess
            updateProject(current.id, current.name, current.role, text, current.isFavorite)
            _state.value = _state.value.copy(status = "Мастер-промпт загружен из файла")
        }.onFailure {
            _state.value = _state.value.copy(status = it.message ?: "Не удалось загрузить мастер-промпт")
        }
    }

    fun deleteProject(projectId: String) {
        if (_state.value.isLoading) return
        projectsRepository.deleteProjectFiles(projectId)
        val projects = _state.value.projects.filterNot { it.id == projectId }
        projectsRepository.save(projects)
        val orchestratorId = projectAutomation.orchestratorChatId(projectId)
        if (orchestratorId != null) {
            chatFilesRepository.deleteChat(orchestratorId)
            prefs.edit().remove(chatSkillsKey(orchestratorId)).apply()
        }
        projectAutomation.deleteProject(projectId)
        val chats = _state.value.chats.mapNotNull { chat ->
            when {
                chat.id == orchestratorId -> null
                chat.projectId == projectId -> {
                    projectAutomation.deleteChat(chat.id)
                    chat.copy(projectId = null, stages = null)
                }
                else -> chat
            }
        }
        chatsRepository.save(chats)
        val current = chats.firstOrNull { it.id == _state.value.currentChatId } ?: chats.firstOrNull()
        _state.value = _state.value.copy(
            projects = projects,
            chats = chats,
            currentChatId = current?.id ?: _state.value.currentChatId,
            messages = current?.messages ?: emptyList(),
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Проект удалён. Оркестратор удалён вместе с ним; остальные чаты сохранены как обычные."
        )
    }

    fun refreshModels(mode: ChatMode) {
    fun refreshModels(mode: ChatMode) {
        val profile = if (mode == ChatMode.IMAGE) imageConnectionProfile() else activeConnectionProfile()
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Подключение «${profile.name}» выключено")
            return
        }
        if (mode == ChatMode.IMAGE) {
            if (!imageGenerationEnabled(profile) || !isImageProfileConfigured(profile)) {
                _state.value = _state.value.copy(status = imageConnectionSetupMessage(profile))
                return
            }
        } else if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = connectionSetupMessage(profile))
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, busyLabel = "Загружаю модели…", status = null)
            runCatching {
                if (mode == ChatMode.TEXT) textModelsForProfile(profile) else imageModelsForProfile(profile)
            }.onSuccess { infos ->
                _state.value = when (mode) {
                    ChatMode.TEXT -> {
                        run {
                            val validIds = infos.map { it.id }.toSet()
                            pruneQuickTextModels(profile.id, validIds)
                            clearCurrentChatModelOverrideIfInvalid(profile.id, validIds)
                        }
                        var selectedModel = loadTextModelForProfile(profile)
                        if (profile.type != ProviderType.OPENROUTER && infos.none { it.id == selectedModel }) {
                            selectedModel = infos.firstOrNull()?.id.orEmpty()
                            if (selectedModel.isNotBlank()) {
                                prefs.edit().putString(profilePrefKey("text_model", profile.id), selectedModel).apply()
                            }
                        }
                        val effectiveId = _state.value.currentChatTextModel?.takeIf { id -> infos.any { it.id == id } } ?: selectedModel
                        val current = infos.firstOrNull { it.id == effectiveId }
                        val effort = preferredReasoningEffort(effectiveId, current)
                        val keepReasoning = reasoningStillValid(current, effort)
                        prefs.edit()
                            .putString("reasoning_effort", effort.name)
                            .putBoolean("reasoning_enabled", keepReasoning)
                            .apply()
                        _state.value.copy(
                            textModel = selectedModel,
                            currentChatTextModel = _state.value.currentChatTextModel?.takeIf { id -> infos.any { it.id == id } },
                            availableTextModels = infos,
                            reasoningEffort = effort,
                            reasoningEnabled = keepReasoning,
                            isLoading = false,
                            busyLabel = null
                        )
                    }
                    ChatMode.IMAGE -> {
                        val selectedModel = chooseImageModel(profile, infos)
                        val info = infos.firstOrNull { it.id == selectedModel }
                        _state.value.copy(
                            availableImageModels = infos,
                            imageConnectionProfileId = profile.id,
                            imageModel = selectedModel,
                            imageAspectRatio = validatedImageParameter("aspect_ratio", profile.id, selectedModel, info),
                            imageResolution = validatedImageParameter("resolution", profile.id, selectedModel, info),
                            isLoading = false,
                            busyLabel = null
                        )
                    }
                }
            }.onFailure {
                _state.value = _state.value.copy(
                    isLoading = false,
                    busyLabel = null,
                    status = it.message ?: "Не удалось загрузить модели"
                )
            }
        }
    }

    fun refreshProviderUsage(delayMs: Long = 0L) {
        val profile = _state.value.connectionProfiles.firstOrNull { it.type == ProviderType.OPENROUTER }
            ?: return
        if (profile.id in _state.value.disabledConnectionIds || !isProfileConfigured(profile)) {
            _state.value = _state.value.copy(providerUsage = null)
            return
        }
        val key = secrets.getProfileApiKey(profile.id).orEmpty()
        viewModelScope.launch {
            if (delayMs > 0L) delay(delayMs)
            runCatching { api.keyUsage(key, effectiveTextBaseUrl(profile)) }
                .onSuccess { usage ->
                    _state.value = _state.value.copy(
                        providerUsage = ProviderUsage(
                            providerName = "OpenRouter",
                            daily = usage.daily,
                            weekly = usage.weekly,
                            monthly = usage.monthly,
                            total = usage.total
                        )
                    )
                }
        }
    }

    private fun refreshModelCapabilities() {
        val profile = activeConnectionProfile()
        val imageProfile = imageConnectionProfile()
        viewModelScope.launch {
            val textInfos = if (profile.id !in _state.value.disabledConnectionIds && isProfileConfigured(profile)) {
                runCatching { textModelsForProfile(profile) }.getOrNull()
            } else null
            val imageInfos = if (
                imageProfile.id !in _state.value.disabledConnectionIds &&
                imageGenerationEnabled(imageProfile) &&
                isImageProfileConfigured(imageProfile)
            ) {
                runCatching { imageModelsForProfile(imageProfile) }.getOrNull()
            } else emptyList()

            var next = _state.value
            if (textInfos != null) {
                run {
                    val validIds = textInfos.map { it.id }.toSet()
                    pruneQuickTextModels(profile.id, validIds)
                    clearCurrentChatModelOverrideIfInvalid(profile.id, validIds)
                    next = _state.value
                }
                var selectedModel = loadTextModelForProfile(profile)
                if (profile.type != ProviderType.OPENROUTER && textInfos.none { it.id == selectedModel }) {
                    selectedModel = textInfos.firstOrNull()?.id.orEmpty()
                    if (selectedModel.isNotBlank()) prefs.edit().putString(profilePrefKey("text_model", profile.id), selectedModel).apply()
                }
                val effectiveId = next.currentChatTextModel?.takeIf { id -> textInfos.any { it.id == id } } ?: selectedModel
                val current = textInfos.firstOrNull { it.id == effectiveId }
                val activeChat = next.chats.firstOrNull { it.id == next.currentChatId }
                val fixed = activeChat?.takeIf { it.projectId != null }?.let { projectAutomation.profile(it.id) }
                val effort = fixed?.reasoningEffort ?: preferredReasoningEffort(effectiveId, current)
                val keepReasoning = fixed?.reasoningEnabled ?: reasoningStillValid(current, effort)
                if (fixed == null) {
                    prefs.edit().putString("reasoning_effort", effort.name).putBoolean("reasoning_enabled", keepReasoning).apply()
                }
                next = next.copy(
                    textModel = selectedModel,
                    currentChatTextModel = next.currentChatTextModel?.takeIf { id -> textInfos.any { it.id == id } },
                    availableTextModels = textInfos,
                    reasoningEffort = effort,
                    reasoningEnabled = keepReasoning
                )
            } else {
                next = next.copy(availableTextModels = emptyList(), reasoningEnabled = false)
            }

            val safeImageInfos = imageInfos ?: emptyList()
            val selectedImageModel = chooseImageModel(imageProfile, safeImageInfos)
            val selectedImageInfo = safeImageInfos.firstOrNull { it.id == selectedImageModel }
            next = next.copy(
                availableImageModels = safeImageInfos,
                imageConnectionProfileId = imageProfile.id,
                imageModel = selectedImageModel,
                imageAspectRatio = validatedImageParameter("aspect_ratio", imageProfile.id, selectedImageModel, selectedImageInfo),
                imageResolution = validatedImageParameter("resolution", imageProfile.id, selectedImageModel, selectedImageInfo),
                quickTextModels = loadAllQuickTextModels(next.connectionProfiles, next.disabledConnectionIds)
            )
            _state.value = next
        }
    }

    private fun currentTextModelId(): String = _state.value.currentChatTextModel ?: _state.value.textModel

    private fun currentTextModelInfo(): ModelInfo? =
        _state.value.availableTextModels.firstOrNull { it.id == currentTextModelId() }

    private fun reasoningStillValid(info: ModelInfo?, effort: ReasoningEffort = _state.value.reasoningEffort): Boolean =
        _state.value.reasoningEnabled && info?.supportsReasoning == true &&
            (!info.supportsReasoningEffort || info.reasoningEfforts.isEmpty() || effort.apiValue in info.reasoningEfforts)

    private fun preferredReasoningEffort(modelId: String, info: ModelInfo?): ReasoningEffort {
        val configured = _state.value.reasoningEffortsByModel[modelId] ?: _state.value.reasoningEffort
        if (info?.supportsReasoningEffort != true || info.reasoningEfforts.isEmpty()) return configured
        if (configured.apiValue in info.reasoningEfforts) return configured
        val fallbackOrder = listOf(
            ReasoningEffort.MEDIUM,
            ReasoningEffort.LOW,
            ReasoningEffort.HIGH,
            ReasoningEffort.MINIMAL,
            ReasoningEffort.XHIGH
        )
        return fallbackOrder.firstOrNull { it.apiValue in info.reasoningEfforts } ?: configured
    }

    private fun reasoningEffortName(effort: ReasoningEffort): String = when (effort) {
        ReasoningEffort.MINIMAL -> "Минимальная сила"
        ReasoningEffort.LOW -> "Низкая сила"
        ReasoningEffort.MEDIUM -> "Средняя сила"
        ReasoningEffort.HIGH -> "Высокая сила"
        ReasoningEffort.XHIGH -> "Максимальная сила"
    }

    private fun currentImageModelInfo(): ModelInfo? =
        _state.value.availableImageModels.firstOrNull { it.id == _state.value.imageModel }

    private fun imageAttachmentAllowed(attachment: PendingAttachment): Pair<Boolean, String?> {
        if (!attachment.mimeType.startsWith("image/")) {
            return false to "Для генерации изображения можно добавить только изображение-референс"
        }
        val profile = imageConnectionProfile()
        if (profile.type != ProviderType.OPENROUTER) {
            return false to when (resolvedImageProtocol(profile)) {
                ImageApiProtocol.NVIDIA_NIM -> "Облачный NVIDIA NIM сейчас принимает в Umnik текстовый промпт; произвольные референсы для этого API не отправляются"
                else -> "Формат image edit у этого API не стандартизирован; используйте текстовый промпт без референса"
            }
        }
        val info = currentImageModelInfo()
        return if (info == null || info.accepts("image")) true to null
        else false to "Выбранная модель изображений не принимает изображения-референсы"
    }

    private fun attachmentAllowed(attachment: PendingAttachment): Pair<Boolean, String?> {
        if (_state.value.mode == ChatMode.IMAGE) {
            if (!attachment.mimeType.startsWith("image/")) return false to "В режиме изображений можно добавлять только изображения-референсы"
            val info = currentImageModelInfo()
            return if (info?.accepts("image") == true) true to null
            else false to "Выбранная модель изображений не принимает изображения-референсы"
        }

        val info = currentTextModelInfo()
        val mime = attachment.mimeType.lowercase()
        val name = attachment.name.lowercase()
        val textLike = mime.startsWith("text/") || name.endsWith(".md") || name.endsWith(".json") ||
            name.endsWith(".csv") || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".xml")
        if (textLike) return true to null
        if (mime == "application/pdf" || name.endsWith(".pdf")) {
            return if (activeConnectionProfile().type == ProviderType.OPENROUTER) true to null
            else false to "PDF через произвольный совместимый API пока не включён: его формат передачи зависит от сервера"
        }
        if (mime.startsWith("image/")) return if (info?.accepts("image") == true) true to null else false to "Выбранная модель не принимает изображения"
        if (mime.startsWith("audio/")) return if (activeConnectionProfile().type == ProviderType.OPENROUTER && info?.accepts("audio") == true) true to null else false to "Выбранная модель не принимает аудио"
        if (mime.startsWith("video/")) return if (info?.accepts("video") == true) true to null else false to "Выбранная модель не принимает видео"
        return if (info?.accepts("file") == true) true to null else false to "Выбранная модель не принимает этот тип файла"
    }

    private fun shouldPersistInChat(attachment: PendingAttachment): Boolean {
        if (_state.value.mode != ChatMode.TEXT) return false
        val mime = attachment.mimeType.lowercase()
        val name = attachment.name.lowercase()
        val textLike = mime.startsWith("text/") || name.endsWith(".md") || name.endsWith(".json") ||
            name.endsWith(".csv") || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".xml")
        return textLike || mime == "application/pdf" || name.endsWith(".pdf")
    }

    private fun chatFileAsAttachment(file: ChatFile): PendingAttachment = PendingAttachment(
        uri = "chat://${file.id}",
        name = file.name,
        mimeType = file.mimeType,
        size = file.size,
        localPath = file.localPath
    )

    private fun persistChatAttachment(attachment: PendingAttachment) {
        val chatId = _state.value.currentChatId
        val current = _state.value.chats.firstOrNull { it.id == chatId } ?: return
        val duplicate = current.chatFiles.orEmpty().any {
            it.name.equals(attachment.name, ignoreCase = true) &&
                (attachment.size <= 0L || it.size == attachment.size)
        }
        if (duplicate) {
            _state.value = _state.value.copy(status = "Файл «${attachment.name}» уже есть в этом чате")
            return
        }
        runCatching { chatFilesRepository.importFile(chatId, attachment) }
            .onSuccess { file ->
                val chats = _state.value.chats.map { chat ->
                    if (chat.id == chatId) chat.copy(
                        chatFiles = chat.chatFiles.orEmpty() + file,
                        updatedAt = System.currentTimeMillis()
                    ) else chat
                }
                chatsRepository.save(chats)
                _state.value = _state.value.copy(
                    chats = chats,
                    storedFiles = storageRepository.list(),
                    storageStats = storageRepository.stats(),
                    status = "Файл «${file.name}» закреплён за этим чатом"
                )
            }
            .onFailure { _state.value = _state.value.copy(status = it.message ?: "Не удалось сохранить файл чата") }
    }

    fun removeChatFile(fileId: String) {
        if (_state.value.isLoading) return
        val chatId = _state.value.currentChatId
        val current = _state.value.chats.firstOrNull { it.id == chatId } ?: return
        val file = current.chatFiles.orEmpty().firstOrNull { it.id == fileId } ?: return
        chatFilesRepository.delete(file)
        val chats = _state.value.chats.map { chat ->
            if (chat.id == chatId) chat.copy(
                chatFiles = chat.chatFiles.orEmpty().filterNot { it.id == fileId },
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        chatsRepository.save(chats)
        _state.value = _state.value.copy(
            chats = chats,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Файл «${file.name}» убран из контекста чата"
        )
    }

    fun addAttachment(uri: Uri, forImageGeneration: Boolean = false) {
        DiagnosticLog.action(context, "attachment_pick", "imageGeneration=$forImageGeneration")
        runCatching { api.attachmentFromUri(uri) }
            .onSuccess { attachment ->
                DiagnosticLog.record(context, "ATTACHMENT", "loaded; mime=${attachment.mimeType}; bytes=${attachment.size}; imageGeneration=$forImageGeneration")
                if (attachment.size > 25L * 1024 * 1024) {
                    _state.value = _state.value.copy(status = "Ограничение Umnik сейчас 25 МБ на один файл")
                } else {
                    val (allowed, reason) = if (forImageGeneration) imageAttachmentAllowed(attachment) else attachmentAllowed(attachment)
                    if (!allowed) {
                        _state.value = _state.value.copy(status = reason)
                    } else if (!forImageGeneration && shouldPersistInChat(attachment)) {
                        persistChatAttachment(attachment)
                    } else {
                        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)
                    }
                }
            }
            .onFailure { _state.value = _state.value.copy(status = it.message) }
    }

    fun addCameraAttachment(uri: Uri, localPath: String, forImageGeneration: Boolean = false) {
        DiagnosticLog.action(context, "camera_result", "imageGeneration=$forImageGeneration")
        runCatching { api.attachmentFromUri(uri).copy(localPath = localPath) }
            .onSuccess { attachment ->
                DiagnosticLog.record(context, "ATTACHMENT", "camera loaded; mime=${attachment.mimeType}; bytes=${attachment.size}; imageGeneration=$forImageGeneration")
                if (attachment.size > 25L * 1024 * 1024) {
                    File(localPath).delete()
                    _state.value = _state.value.copy(status = "Фото превышает ограничение 25 МБ")
                } else {
                    val (allowed, reason) = if (forImageGeneration) imageAttachmentAllowed(attachment) else attachmentAllowed(attachment)
                    if (!allowed) {
                        File(localPath).delete()
                        _state.value = _state.value.copy(status = reason)
                    } else {
                        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)
                    }
                }
            }
            .onFailure {
                File(localPath).delete()
                _state.value = _state.value.copy(status = it.message ?: "Не удалось добавить фото")
            }
    }

    fun addVoiceRecording(localPath: String): Boolean {
        DiagnosticLog.action(context, "voice_recording_result")
        if (_state.value.isLoading || _state.value.requestActive) {
            File(localPath).delete()
            return false
        }
        val file = File(localPath)
        if (!file.isFile || file.length() <= 44L) {
            file.delete()
            _state.value = _state.value.copy(status = "Голосовое сообщение не записалось")
            return false
        }
        if (file.length() > 25L * 1024L * 1024L) {
            file.delete()
            _state.value = _state.value.copy(status = "Голосовое сообщение превышает ограничение 25 МБ")
            return false
        }
        val attachment = PendingAttachment(
            uri = "voice://${UUID.randomUUID()}",
            name = "Голосовое сообщение.wav",
            mimeType = "audio/wav",
            size = file.length(),
            localPath = file.absolutePath
        )
        val (allowed, reason) = attachmentAllowed(attachment)
        if (!allowed) {
            file.delete()
            _state.value = _state.value.copy(status = reason ?: "Выбранная модель не принимает голос")
            return false
        }
        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)
        DiagnosticLog.record(context, "ATTACHMENT", "voice accepted; mime=audio/wav; bytes=${file.length()}")
        return true
    }

    fun removeAttachment(uri: String) {
        val removed = _state.value.pendingAttachments.firstOrNull { it.uri == uri }
        cleanupTempAttachments(listOfNotNull(removed))
        _state.value = _state.value.copy(
            pendingAttachments = _state.value.pendingAttachments.filterNot { it.uri == uri }
        )
    }

    fun importSkillFile(uri: Uri) {
        runCatching { skills.importFile(uri) }
            .onSuccess { skill ->
                _state.value = _state.value.copy(
                    skills = skills.list(),
                    storedFiles = storageRepository.list(),
                    storageStats = storageRepository.stats(),
                    status = "Навык «${skill.name}» импортирован"
                )
            }
            .onFailure { _state.value = _state.value.copy(status = it.message) }
    }

    fun importSkillTree(uri: Uri) {
        runCatching { skills.importTree(uri) }
            .onSuccess { skill ->
                _state.value = _state.value.copy(
                    skills = skills.list(),
                    storedFiles = storageRepository.list(),
                    storageStats = storageRepository.stats(),
                    status = "Папка навыка «${skill.name}» импортирована"
                )
            }
            .onFailure { _state.value = _state.value.copy(status = it.message) }
    }

    fun toggleSkill(id: String) {
        val chat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId } ?: return
        val next = _state.value.activeSkillIds.toMutableSet().apply { if (!add(id)) remove(id) }.toSet()
        prefs.edit().putStringSet(chatSkillsKey(chat.id), next).apply()
        if (chat.projectId != null) updateCurrentProjectRuntime { it.copy(skillIds = next) }
        _state.value = _state.value.copy(activeSkillIds = next)
    }

    fun deleteSkill(id: String) {
        skills.delete(id)
        val next = _state.value.activeSkillIds - id
        prefs.edit().putStringSet("active_skills", next).apply()
        _state.value = _state.value.copy(
            skills = skills.list(),
            activeSkillIds = next,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
    }

    fun stopGeneration() {
        projectStagesJob?.let { running ->
            requestGeneration += 1L
            running.cancel()
            api.cancelActiveRequest()
            projectStagesJob = null
            _state.value = _state.value.copy(
                isLoading = false,
                requestActive = false,
                busyLabel = null,
                status = "Выполнение этапов остановлено"
            )
            return
        }
        if (!_state.value.requestActive) return
        requestGeneration += 1L
        RequestExecutionManager.fail("Работа остановлена. При необходимости повторите запрос вручную.")
        RequestExecutionManager.cancel()
        activeRequestJob = null
        val restore = activeRequestPending
        activeRequestPending = emptyList()
        _state.value = _state.value.copy(
            pendingAttachments = restore,
            isLoading = false,
            requestActive = false,
            busyLabel = null,
            status = "Работа остановлена. Уточните запрос и отправьте снова."
        )
    }

    fun retryFailedMessage(messageId: String) {
        if (_state.value.isLoading) return
        val previous = _state.value.messages.firstOrNull { it.id == messageId && it.deliveryState == "failed" }
            ?: return
        if (previous.attachmentNames.isNotEmpty()) {
            _state.value = _state.value.copy(status = "Для повтора прикрепите файлы заново и отправьте запрос вручную")
            return
        }
        val chatId = _state.value.currentChatId
        val cleanedMessages = _state.value.messages.filterNot { it.id == messageId }
        val cleanedChats = replaceChatMessages(_state.value.chats, chatId, cleanedMessages, null)
        chatsRepository.save(cleanedChats)
        _state.value = _state.value.copy(
            chats = cleanedChats,
            messages = cleanedMessages,
            mode = if (previous.imageGeneration) ChatMode.IMAGE else ChatMode.TEXT,
            status = null
        )
        DiagnosticLog.action(context, "retry_failed_message", "chat=${chatId.take(8)}; replaced=true")
        if (previous.imageGeneration) sendImagePrompt(previous.text) else send(previous.text)
    }

    private fun launchRequest(
        chatId: String,
        messageId: String,
        label: String,
        execute: suspend () -> Unit
    ): Job? = runCatching {
        RequestExecutionManager.start(
            context, chatId, messageId, label,
            cancelNetworkCall = {
                api.cancelActiveRequest()
                compatibleApi.cancelActiveRequest()
                nvidiaImageApi.cancelActiveRequest()
            },
            execute = execute
        )
    }.getOrElse { error ->
        val chats = chatsRepository.finishRequest(chatId, messageId, null)
        _state.value = _state.value.copy(
            chats = chats,
            messages = chats.firstOrNull { it.id == _state.value.currentChatId }?.messages.orEmpty(),
            requestActive = false,
            isLoading = false,
            busyLabel = null,
            status = "Не удалось запустить фоновую работу: ${error.message ?: "ошибка Android"}"
        )
        null
    }

    fun prepareImageGeneration(): Boolean {
        if (_state.value.isLoading) return false
        val profile = imageConnectionProfile()
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Для генерации изображений включите выбранное подключение")
            return false
        }
        if (!imageGenerationEnabled(profile) || !isImageProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = imageConnectionSetupMessage(profile))
            return false
        }
        if (_state.value.imageModel.isBlank()) {
            _state.value = _state.value.copy(status = "Сначала выберите модель генерации изображений в настройках")
            return false
        }
        if (_state.value.availableImageModels.isEmpty()) refreshModels(ChatMode.IMAGE)
        return true
    }

    fun send(text: String) {
        val profile = if (_state.value.mode == ChatMode.IMAGE) imageConnectionProfile() else activeConnectionProfile()
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Подключение «${profile.name}» выключено")
            return
        }
        if (_state.value.mode == ChatMode.IMAGE) {
            if (!imageGenerationEnabled(profile) || !isImageProfileConfigured(profile)) {
                _state.value = _state.value.copy(status = imageConnectionSetupMessage(profile))
                return
            }
        } else if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = connectionSetupMessage(profile))
            return
        }
        val key = if (_state.value.mode == ChatMode.IMAGE) imageApiKey(profile) else secrets.getProfileApiKey(profile.id).orEmpty()
        val clean = text.trim()
        val pending = _state.value.pendingAttachments
        if (_state.value.isLoading) return

        val mode = _state.value.mode
        val chatId = _state.value.currentChatId
        val currentChat = _state.value.chats.firstOrNull { it.id == chatId }
        val persistentChatFiles = if (mode == ChatMode.TEXT) {
            currentChat?.chatFiles.orEmpty().map(::chatFileAsAttachment)
        } else {
            emptyList()
        }
        if (clean.isBlank() && pending.isEmpty() && persistentChatFiles.isEmpty()) return

        val missingChatFile = persistentChatFiles.firstOrNull { attachment ->
            attachment.localPath?.takeIf { it.isNotBlank() }?.let { !File(it).isFile } == true
        }
        if (missingChatFile != null) {
            _state.value = _state.value.copy(
                status = "Файл чата «${missingChatFile.name}» не найден. Удалите его из контекста и прикрепите заново."
            )
            return
        }

        val invalidPending = pending.firstOrNull { !attachmentAllowed(it).first }
        if (invalidPending != null) {
            _state.value = _state.value.copy(status = attachmentAllowed(invalidPending).second ?: "Вложение не поддерживается выбранной моделью")
            return
        }

        val currentProject = currentChat?.projectId?.let { id -> _state.value.projects.firstOrNull { it.id == id } }
        val before = _state.value.messages
        val user = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            text = clean.ifBlank {
                when {
                    mode == ChatMode.IMAGE -> "Создай вариант приложенного изображения"
                    pending.isNotEmpty() && pending.all { it.mimeType.startsWith("audio/") } && persistentChatFiles.isEmpty() -> "Голосовое сообщение"
                    persistentChatFiles.isNotEmpty() -> "[Файлы чата]"
                    else -> "[Вложения]"
                }
            },
            attachmentNames = (pending.map { it.name } + persistentChatFiles.map { it.name }).distinct(),
            imageGeneration = mode == ChatMode.IMAGE,
            deliveryState = "pending"
        )
        val nextMessages = before + user
        val titleAttachments = pending.map { it.name } + currentChat?.chatFiles.orEmpty().map { it.name }
        val title = if (before.isEmpty()) makeChatTitle(clean, titleAttachments) else null
        val nextChats = replaceChatMessages(_state.value.chats, chatId, nextMessages, title)
        chatsRepository.save(nextChats)

        _state.value = _state.value.copy(
            messages = nextMessages,
            chats = nextChats,
            pendingAttachments = emptyList(),
            isLoading = true,
            requestActive = true,
            busyLabel = if (_state.value.mode == ChatMode.IMAGE) "Генерирую изображение…" else "Модель думает…",
            status = null,
            storageStats = storageRepository.stats()
        )

        val textModel = currentTextModelId()
        if (mode == ChatMode.TEXT && textModel != "openrouter/auto") {
            val knownInfo = _state.value.availableTextModels.firstOrNull { it.id == textModel }
                ?: _state.value.modelCatalog.firstOrNull { it.id == textModel }
            val absentFromLoadedTextCatalog = _state.value.availableTextModels.isNotEmpty() &&
                _state.value.availableTextModels.none { it.id == textModel }
            if (absentFromLoadedTextCatalog || knownInfo?.isBatch == true || (knownInfo != null && ModelCategory.TEXT !in knownInfo.categories)) {
                val failedChats = chatsRepository.finishRequest(chatId, user.id, null)
                _state.value = _state.value.copy(
                    chats = failedChats,
                    messages = failedChats.firstOrNull { it.id == chatId }?.messages.orEmpty(),
                    isLoading = false,
                    requestActive = false,
                    busyLabel = null,
                    status = "Эта модель предназначена не для обычного текстового чата. Выберите текстовую модель в каталоге OpenRouter."
                )
                refreshModels(ChatMode.TEXT)
                return
            }
        }
        val imageModel = _state.value.imageModel
        val imageInfo = currentImageModelInfo()
        val imageAspectRatio = _state.value.imageAspectRatio?.takeIf {
            profile.type != ProviderType.OPENROUTER || imageInfo?.supportedParameters?.contains("aspect_ratio") == true
        }
        val imageResolution = _state.value.imageResolution?.takeIf {
            profile.type != ProviderType.OPENROUTER || imageInfo?.supportedParameters?.contains("resolution") == true
        }
        val webSearchEnabled = _state.value.webSearchEnabled
        val reasoningEnabled = _state.value.reasoningEnabled
        val reasoningEffort = _state.value.reasoningEffort
        val requestId = ++requestGeneration
        activeRequestPending = pending
        DiagnosticLog.record(
            context,
            "REQUEST",
            "start id=$requestId; provider=${profile.name}; mode=$mode; model=${if (mode == ChatMode.TEXT) textModel else imageModel}; history=${before.size}; pending=${pending.size}; persistent=${persistentChatFiles.size}; promptChars=${clean.length}"
        )

        activeRequestJob = launchRequest(chatId, user.id, "${profile.name} · ${if (mode == ChatMode.TEXT) textModel else imageModel}") {
            val operation = runCatching {
                when (mode) {
                    ChatMode.TEXT -> {
                        val skillIds = _state.value.activeSkillIds
                        val skillText = skills.promptFor(skillIds)
                        val projectFiles = currentProject?.files.orEmpty().map { file ->
                            PendingAttachment(
                                uri = "project://${file.id}",
                                name = file.name,
                                mimeType = file.mimeType,
                                size = file.size,
                                localPath = file.localPath
                            )
                        }.filter { attachmentAllowed(it).first }
                        val modelInfo = _state.value.availableTextModels.firstOrNull { it.id == textModel }
                        val chosenWindow = listOfNotNull(modelInfo?.contextLength, profile.contextLimitTokens).minOrNull()
                        val requestModelInfo = (modelInfo ?: ModelInfo(textModel)).copy(contextLength = chosenWindow)
                        val actualReasoning = reasoningEnabled && modelInfo?.supportsReasoning == true &&
                            (modelInfo.reasoningEfforts.isEmpty() || reasoningEffort.apiValue in modelInfo.reasoningEfforts)
                        val effort = if (actualReasoning && modelInfo.supportsReasoningEffort) reasoningEffort.apiValue else null
                        val allAttachments = (pending + persistentChatFiles.filter { attachmentAllowed(it).first } + projectFiles)
                            .distinctBy { it.localPath ?: it.uri }
                        if (profile.type == ProviderType.OPENROUTER) {
                            api.chat(
                                key,
                                textModel,
                                before,
                                clean,
                                allAttachments,
                                buildSystemPrompt(skillText, currentProject, currentChat, modelInfo?.supportsTools == true),
                                webSearchEnabled,
                                actualReasoning,
                                effort,
                                modelInfo?.supportsTools == true,
                                effectiveTextBaseUrl(profile),
                                requestModelInfo
                            )
                        } else {
                            compatibleApi.chat(
                                key,
                                effectiveTextBaseUrl(profile),
                                textModel,
                                before,
                                clean,
                                allAttachments,
                                buildSystemPrompt(skillText, currentProject, currentChat, false),
                                requestModelInfo
                            )
                        }
                    }
                    ChatMode.IMAGE -> {
                        val projectImages = currentProject?.files.orEmpty()
                            .filter { it.mimeType.startsWith("image/") && currentImageModelInfo()?.accepts("image") == true }
                            .map { file -> PendingAttachment(
                                uri = "project://${file.id}",
                                name = file.name,
                                mimeType = file.mimeType,
                                size = file.size,
                                localPath = file.localPath
                            ) }
                        val projectPrefix = buildImageProjectPrompt(currentProject, currentChat)
                        val imagePrompt = listOf(projectPrefix, clean).filter { it.isNotBlank() }.joinToString("\n\n")
                        generateImageForProfile(
                            profile = profile,
                            apiKey = key,
                            model = imageModel,
                            prompt = imagePrompt,
                            attachments = pending + projectImages,
                            aspectRatio = imageAspectRatio,
                            resolution = imageResolution
                        )
                    }
                }
            }.mapCatching { result ->
                require(result.text.isNotBlank() || result.files.isNotEmpty()) { "Модель вернула пустой ответ" }
                result
            }

            if (requestId != requestGeneration) {
                return@launchRequest
            }

            operation.onSuccess { result ->
                val finalText = ProjectOutputPolicy.apply(result.text, currentProject?.masterPrompt)
                DiagnosticLog.record(
                    context,
                    "REQUEST",
                    "success id=$requestId; provider=${profile.name}; model=${if (mode == ChatMode.TEXT) textModel else imageModel}; responseChars=${finalText.length}; files=${result.files.size}"
                )
                val assistant = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = "assistant",
                    text = finalText.ifBlank { "Готово." },
                    generatedFiles = result.files,
                    modelId = result.modelId ?: if (mode == ChatMode.TEXT) textModel else imageModel,
                    providerName = result.providerName,
                    costUsd = result.costUsd,
                    inputTokens = result.inputTokens,
                    outputTokens = result.outputTokens
                )
                val chats = chatsRepository.finishRequest(chatId, user.id, assistant)
                _state.value = _state.value.copy(
                    messages = chats.firstOrNull { it.id == _state.value.currentChatId }?.messages.orEmpty(),
                    chats = chats,
                    isLoading = false,
                    requestActive = false,
                    busyLabel = null,
                    status = null,
                    storedFiles = storageRepository.list(),
                    storageStats = storageRepository.stats()
                )
                playReadySound()
                if (profile.type == ProviderType.OPENROUTER) {
                    refreshProviderUsage()
                    if (mode == ChatMode.IMAGE) refreshProviderUsage(2500L)
                }
            }.onFailure {
                DiagnosticLog.record(
                    context,
                    "REQUEST",
                    "failed id=$requestId; provider=${profile.name}; model=${if (mode == ChatMode.TEXT) textModel else imageModel}",
                    it
                )
                val rawError = it.message.orEmpty()
                val friendlyError = when {
                    it is java.net.SocketTimeoutException ->
                        "Сервис не ответил вовремя. Повторите запрос один раз или выберите другую модель."
                    it is java.net.SocketException ->
                        "Соединение оборвалось во время ответа. Нажмите повтор — Umnik заменит неудачный запуск без создания копии сообщения."
                    rawError.contains("429") || rawError.contains("rate limit", ignoreCase = true) ->
                        "Провайдер этой модели временно ограничил запросы. Повторите позже или выберите другую модель в OpenRouter."
                    rawError.contains("video generation model", ignoreCase = true) ||
                        rawError.contains("cannot be used with the chat/completions endpoint", ignoreCase = true) ->
                        "Выбранная модель предназначена для видео, а не для обычного чата. Назначьте её для видео или выберите текстовую модель."
                    else -> rawError.ifBlank { "Ошибка запроса" }
                }
                RequestExecutionManager.fail(friendlyError)
                val failedChats = chatsRepository.finishRequest(chatId, user.id, null)
                _state.value = _state.value.copy(
                    messages = failedChats.firstOrNull { it.id == _state.value.currentChatId }?.messages.orEmpty(),
                    chats = failedChats,
                    isLoading = false,
                    requestActive = false,
                    busyLabel = null,
                    status = friendlyError
                )
                if (
                    profile.type == ProviderType.NVIDIA &&
                    mode == ChatMode.TEXT &&
                    friendlyError.contains("временно скроет модель")
                ) {
                    refreshModelCapabilities()
                }
            }
            cleanupTempAttachments(pending)
            if (requestId == requestGeneration) {
                activeRequestJob = null
                activeRequestPending = emptyList()
            }
        }
    }

    fun sendImagePrompt(text: String): Boolean {
        if (_state.value.isLoading) return false
        val profile = imageConnectionProfile()
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Для генерации изображений включите выбранное подключение")
            return false
        }
        if (!imageGenerationEnabled(profile) || !isImageProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = imageConnectionSetupMessage(profile))
            return false
        }

        val clean = text.trim()
        val pending = _state.value.pendingAttachments
        if (clean.isBlank() && pending.isEmpty()) return false

        val invalidPending = pending.firstOrNull { !imageAttachmentAllowed(it).first }
        if (invalidPending != null) {
            _state.value = _state.value.copy(
                status = imageAttachmentAllowed(invalidPending).second ?: "Вложение не подходит для генерации изображения"
            )
            return false
        }

        val prompt = clean.ifBlank { "Создай вариант приложенного изображения" }
        val chatId = _state.value.currentChatId
        val before = _state.value.messages
        val user = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            text = prompt,
            attachmentNames = pending.map { it.name }.distinct(),
            imageGeneration = true,
            deliveryState = "pending"
        )
        val nextMessages = before + user
        val title = if (before.isEmpty()) makeChatTitle(prompt, pending.map { it.name }) else null
        val nextChats = replaceChatMessages(_state.value.chats, chatId, nextMessages, title)
        chatsRepository.save(nextChats)

        _state.value = _state.value.copy(
            messages = nextMessages,
            chats = nextChats,
            pendingAttachments = emptyList(),
            isLoading = true,
            requestActive = true,
            busyLabel = "Генерирую изображение…",
            status = null,
            storageStats = storageRepository.stats()
        )

        val key = imageApiKey(profile)
        val imageModel = _state.value.imageModel
        val imageInfo = currentImageModelInfo()
        val imageAspectRatio = _state.value.imageAspectRatio?.takeIf {
            profile.type != ProviderType.OPENROUTER || imageInfo?.supportedParameters?.contains("aspect_ratio") == true
        }
        val imageResolution = _state.value.imageResolution?.takeIf {
            profile.type != ProviderType.OPENROUTER || imageInfo?.supportedParameters?.contains("resolution") == true
        }
        val requestId = ++requestGeneration
        activeRequestPending = pending

        activeRequestJob = launchRequest(chatId, user.id, "${profile.name} · $imageModel") {
            val operation = runCatching {
                generateImageForProfile(
                    profile = profile,
                    apiKey = key,
                    model = imageModel,
                    prompt = prompt,
                    attachments = pending,
                    aspectRatio = imageAspectRatio,
                    resolution = imageResolution
                )
            }.mapCatching { result ->
                require(result.text.isNotBlank() || result.files.isNotEmpty()) { "Модель вернула пустой ответ" }
                result
            }

            if (requestId != requestGeneration) return@launchRequest

            operation.onSuccess { result ->
                val assistant = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = "assistant",
                    text = result.text.ifBlank { "Готово." },
                    generatedFiles = result.files,
                    imageGeneration = true
                )
                val chats = chatsRepository.finishRequest(chatId, user.id, assistant)
                _state.value = _state.value.copy(
                    messages = chats.firstOrNull { it.id == _state.value.currentChatId }?.messages.orEmpty(),
                    chats = chats,
                    isLoading = false,
                    requestActive = false,
                    busyLabel = null,
                    status = null,
                    storedFiles = storageRepository.list(),
                    storageStats = storageRepository.stats()
                )
                playReadySound()
                if (profile.type == ProviderType.OPENROUTER) {
                    refreshProviderUsage()
                    refreshProviderUsage(2500L)
                }
            }.onFailure {
                val friendlyError = it.message ?: "Ошибка генерации изображения"
                RequestExecutionManager.fail(friendlyError)
                val failedChats = chatsRepository.finishRequest(chatId, user.id, null)
                _state.value = _state.value.copy(
                    messages = failedChats.firstOrNull { it.id == _state.value.currentChatId }?.messages.orEmpty(),
                    chats = failedChats,
                    isLoading = false,
                    requestActive = false,
                    busyLabel = null,
                    status = friendlyError
                )
            }
            cleanupTempAttachments(pending)
            if (requestId == requestGeneration) {
                activeRequestJob = null
                activeRequestPending = emptyList()
            }
        }
        return true
    }

    private suspend fun generateImageForProfile(
        profile: ConnectionProfile,
        apiKey: String,
        model: String,
        prompt: String,
        attachments: List<PendingAttachment>,
        aspectRatio: String?,
        resolution: String?
    ): OpenRouterClient.Result {
        if (profile.type == ProviderType.OPENROUTER) {
            return generateOpenRouterImageWithResolutionFallback(
                profileId = profile.id,
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                attachments = attachments,
                baseUrl = effectiveImageBaseUrl(profile),
                aspectRatio = aspectRatio,
                resolution = resolution
            )
        }
        return when (resolvedImageProtocol(profile)) {
            ImageApiProtocol.NVIDIA_NIM -> nvidiaImageApi.generateImage(
                apiKey = apiKey,
                baseUrl = effectiveImageBaseUrl(profile),
                model = model,
                prompt = prompt,
                aspectRatio = aspectRatio
            )
            ImageApiProtocol.OPENAI_COMPATIBLE, ImageApiProtocol.AUTO -> compatibleApi.generateImage(
                apiKey = apiKey,
                baseUrl = effectiveImageBaseUrl(profile),
                model = model,
                prompt = prompt
            )
        }
    }

    private suspend fun generateOpenRouterImageWithResolutionFallback(
        profileId: String,
        apiKey: String,
        model: String,
        prompt: String,
        attachments: List<PendingAttachment>,
        baseUrl: String,
        aspectRatio: String?,
        resolution: String?
    ): OpenRouterClient.Result {
        try {
            return api.generateImage(
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                attachments = attachments,
                baseUrl = baseUrl,
                aspectRatio = aspectRatio,
                resolution = resolution
            )
        } catch (first: Throwable) {
            val message = first.message.orEmpty().lowercase()
            val canRetryWithoutResolution = !resolution.isNullOrBlank() &&
                "output pixels" in message &&
                ("omit resolution" in message || "larger resolution" in message)
            if (!canRetryWithoutResolution) throw first

            val result = api.generateImage(
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                attachments = attachments,
                baseUrl = baseUrl,
                aspectRatio = aspectRatio,
                resolution = null
            )
            prefs.edit()
                .remove(imageParameterPrefKey("resolution", profileId, model))
                .apply()
            _state.value = _state.value.copy(
                imageResolution = null,
                status = "Выбранное разрешение несовместимо с этим форматом. Umnik переключил разрешение на «Авто», сохранив ${aspectRatio ?: "соотношение сторон"}."
            )
            return result
        }
    }

    override fun onCleared() {
        super.onCleared()
    }

    fun exportMessage(message: ChatMessage): GeneratedFile {
        val dir = File(context.filesDir, "exports").apply { mkdirs() }
        val name = "umnik_${message.timestamp}.md"
        val file = File(dir, name)
        file.writeText(message.text)
        val generated = GeneratedFile(
            id = UUID.randomUUID().toString(),
            name = name,
            mimeType = "text/markdown",
            localPath = file.absolutePath,
            size = file.length()
        )
        _state.value = _state.value.copy(
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
        return generated
    }

    fun clearChat() {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return
        val chatId = _state.value.currentChatId
        chatFilesRepository.deleteChat(chatId)
        val now = System.currentTimeMillis()
        val chats = _state.value.chats.map { chat ->
            if (chat.id == chatId) chat.copy(
                title = "Новый чат",
                messages = emptyList(),
                chatFiles = emptyList(),
                updatedAt = now
            ) else chat
        }
        chatsRepository.save(chats)
        _state.value = _state.value.copy(
            messages = emptyList(),
            chats = chats,
            pendingAttachments = emptyList(),
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Чат очищен вместе с его временными файлами"
        )
    }

    fun refreshStorage() {
        _state.value = _state.value.copy(
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
    }

    fun deleteStoredFile(file: StoredFile) {
        if (!file.deletable) {
            _state.value = _state.value.copy(status = "Файлы навыков удаляются во вкладке «Навыки»")
            return
        }
        if (!storageRepository.delete(file.localPath)) {
            _state.value = _state.value.copy(status = "Не удалось удалить файл")
            return
        }
        removeFileReferences(setOf(file.localPath))
        val removedSelectedSound = file.localPath == _state.value.answerSoundCustomPath
        if (removedSelectedSound) {
            prefs.edit()
                .putString("answer_sound_choice", AnswerSoundChoice.DEFAULT.name)
                .remove("answer_sound_custom_path")
                .remove("answer_sound_custom_name")
                .apply()
        }
        _state.value = _state.value.copy(
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            answerSoundChoice = if (removedSelectedSound) AnswerSoundChoice.DEFAULT else _state.value.answerSoundChoice,
            answerSoundCustomPath = if (removedSelectedSound) null else _state.value.answerSoundCustomPath,
            answerSoundCustomName = if (removedSelectedSound) null else _state.value.answerSoundCustomName,
            status = if (removedSelectedSound) "Звук удалён. Выбран основной сигнал" else "Файл удалён"
        )
    }

    fun clearWorkingFiles() {
        if (_state.value.isLoading) return
        val generatedPaths = _state.value.chats
            .flatMap { it.messages }
            .flatMap { it.generatedFiles }
            .map { it.localPath }
            .toSet()
        storageRepository.clearWorkingFiles()
        removeFileReferences(generatedPaths)
        _state.value = _state.value.copy(
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Сгенерированные файлы и экспорт очищены"
        )
    }

    fun storedFileAsGenerated(file: StoredFile): GeneratedFile = GeneratedFile(
        id = file.id,
        name = file.name,
        mimeType = file.mimeType,
        localPath = file.localPath,
        size = file.size
    )

    fun saveGeneratedFile(file: GeneratedFile, destination: Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.openOutputStream(destination)?.use { output ->
                    File(file.localPath).inputStream().use { input -> input.copyTo(output) }
                } ?: error("Не удалось открыть место сохранения")
            }.onSuccess {
                _state.value = _state.value.copy(status = "${file.name} сохранён")
            }.onFailure {
                _state.value = _state.value.copy(status = it.message)
            }
        }
    }

    fun dismissStatus() {
        _state.value = _state.value.copy(status = null)
    }

    private fun cleanupTempAttachments(items: List<PendingAttachment>) {
        val tempRoots = listOf(File(context.cacheDir, "camera"), File(context.cacheDir, "voice"))
            .mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        items.mapNotNull { it.localPath }.forEach { path ->
            runCatching {
                val file = File(path).canonicalFile
                if (tempRoots.any { root -> file.path.startsWith(root.path + File.separator) }) file.delete()
            }
        }
    }

    fun previewAnswerSound() {
        playReadySound()
    }

    private fun playReadySound() {
        val state = _state.value
        if (!state.answerSoundEnabled) return

        if (state.answerSoundChoice == AnswerSoundChoice.CUSTOM) {
            val custom = state.answerSoundCustomPath?.let(::File)
            if (custom?.isFile == true) {
                runCatching {
                    val volume = state.answerSoundVolume.coerceIn(0, 100) / 100f
                    MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        setDataSource(custom.absolutePath)
                        setVolume(volume, volume)
                        setOnPreparedListener { it.start() }
                        setOnCompletionListener { it.release() }
                        setOnErrorListener { mp, _, _ -> mp.release(); true }
                        prepareAsync()
                    }
                    return
                }
            }
        }

        runCatching {
            val tone = ToneGenerator(
                AudioManager.STREAM_MUSIC,
                state.answerSoundVolume.coerceIn(0, 100)
            )
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 90)
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { tone.release() } }, 180)
        }
    }

    private fun buildSystemPrompt(skillText: String, project: Project?, chat: ChatSession?, toolsEnabled: Boolean): String = buildString {
        appendLine("Ты работаешь внутри Android-приложения «Umnik». Отвечай на языке пользователя, если он не попросил иначе.")
        appendLine("Не создавай скачиваемый файл автоматически из-за длины ответа. Используй create_file только если пользователь прямо просит файл/скачивание либо проект, навык или другая подключённая инструкция явно требует вернуть результат файлом.")
        val profile = _state.value.userProfile
        val useProfile = !profile.isEmpty() && when (_state.value.userProfileScope) {
            UserProfileScope.OFF -> false
            UserProfileScope.PROJECTS -> project != null
            UserProfileScope.EVERYWHERE -> true
        }
        if (useProfile) {
            appendLine("\n===== КРАТКО О ПОЛЬЗОВАТЕЛЕ =====")
            if (profile.name.isNotBlank()) appendLine("Имя: ${profile.name}")
            if (profile.gender.isNotBlank()) appendLine("Пол: ${profile.gender}")
            if (profile.age.isNotBlank()) appendLine("Возраст: ${profile.age}")
            if (profile.occupation.isNotBlank()) appendLine("Род занятий: ${profile.occupation}")
            if (profile.note.isNotBlank()) appendLine("Предпочтение в общении: ${profile.note}")
            appendLine("Используй эти сведения только когда они полезны. Не пересказывай профиль пользователю без необходимости. Явный запрос и инструкции проекта важнее этого краткого профиля.")
            appendLine("===== КОНЕЦ ПРОФИЛЯ =====")
        }
        if (project != null) {
            appendLine("\n===== ПРОЕКТ: ${project.name} =====")
            if (project.role.isNotBlank()) appendLine("Роль в проекте: ${project.role}")
            if (project.masterPrompt.isNotBlank()) {
                appendLine("Мастер-инструкция проекта (ВЫСШИЙ ПРИОРИТЕТ внутри проекта):")
                appendLine(project.masterPrompt)
            }
            if (project.files.isNotEmpty()) {
                appendLine("Постоянные файлы проекта приложены к текущему запросу. Используй их как рабочий контекст, когда они релевантны.")
            }
            appendLine("===== КОНЕЦ НАСТРОЕК ПРОЕКТА =====")
        }
        if (chat != null && (!chat.assignedRole.isNullOrBlank() || !chat.masterPrompt.isNullOrBlank())) {
            appendLine("\n===== НАСТРОЙКИ ЭТОГО ДИАЛОГА =====")
            chat.assignedRole?.takeIf { it.isNotBlank() }?.let { appendLine("Роль диалога: $it") }
            chat.masterPrompt?.takeIf { it.isNotBlank() }?.let {
                appendLine("Мастер-инструкция диалога:")
                appendLine(it)
            }
            appendLine("===== КОНЕЦ НАСТРОЕК ДИАЛОГА =====")
        }
        if (!chat?.chatFiles.isNullOrEmpty()) {
            appendLine("Файлы этого диалога автоматически приложены к текущему запросу. Используй их как постоянный рабочий контекст этого чата.")
        }
        if (toolsEnabled) appendLine("У тебя есть локальный инструмент create_file. Если пользователь просит результат файлом или материал получается слишком длинным для удобного чтения в чате, используй create_file.")
        appendLine("Если пользователь просит текст в отдельном, изолированном или удобном для копирования блоке, ОБЯЗАТЕЛЬНО используй ровно такой синтаксис:")
        appendLine(":::copy")
        appendLine("текст блока")
        appendLine(":::")
        appendLine("Umnik распознаёт :::copy как отдельную карточку с кнопкой копирования. Не утверждай, что показал отдельный блок, если не использовал этот синтаксис.")
        appendLine("Для кода используй обычные fenced Markdown-блоки с тройными обратными кавычками.")
        appendLine("Подключённые навыки ниже выбраны пользователем. Следуй их инструкциям как рабочим правилам, если они не противоречат явному текущему запросу пользователя.")
        appendLine("Не утверждай, что исполнил код из папки навыка: Umnik передаёт навыкам только разрешённые текстовые материалы.")
        if (skillText.isNotBlank()) {
            appendLine("\n===== НАЧАЛО ПОДКЛЮЧЁННЫХ НАВЫКОВ =====")
            appendLine(skillText)
            appendLine("===== КОНЕЦ ПОДКЛЮЧЁННЫХ НАВЫКОВ =====")
        }
        if (project?.masterPrompt?.isNotBlank() == true) {
            appendLine("\n===== ФИНАЛЬНАЯ ПРОВЕРКА МАСТЕР-ИНСТРУКЦИИ =====")
            appendLine("Мастер-инструкция проекта имеет высший приоритет среди содержательных правил проекта. Текущий запрос, навыки, профиль и приложенные файлы не могут отменять или ослаблять её требования.")
            appendLine("Перед отправкой ответа молча перечитай мастер-инструкцию проекта и проверь результат по каждому обязательному требованию.")
            appendLine("При конфликте применяй порядок: мастер-инструкция проекта, явный текущий запрос пользователя, настройки текущего диалога, подключённые навыки, файлы проекта и диалога, профиль пользователя.")
            appendLine("Если ответ нарушает хотя бы один пункт мастер-инструкции, исправь его до отправки. Не добавляй требований, которых в мастер-инструкции нет.")
            appendLine("===== КОНЕЦ ФИНАЛЬНОЙ ПРОВЕРКИ =====")
        }
    }

    private fun buildImageProjectPrompt(project: Project?, chat: ChatSession?): String = buildString {
        project?.let {
            if (it.role.isNotBlank()) appendLine("Роль/стиль: ${it.role}")
            if (it.masterPrompt.isNotBlank()) appendLine(it.masterPrompt)
        }
        chat?.assignedRole?.takeIf { it.isNotBlank() }?.let { appendLine("Роль: $it") }
        chat?.masterPrompt?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
    }.trim()

    private fun replaceChatMessages(
        chats: List<ChatSession>,
        chatId: String,
        messages: List<ChatMessage>,
        titleOverride: String?
    ): List<ChatSession> {
        val now = System.currentTimeMillis()
        return chats.map { chat ->
            if (chat.id == chatId) chat.copy(
                title = titleOverride ?: chat.title,
                messages = messages,
                updatedAt = now
            ) else chat
        }
    }

    private fun removeFileReferences(paths: Set<String>) {
        if (paths.isEmpty()) return
        val chats = _state.value.chats.map { chat ->
            chat.copy(messages = chat.messages.map { message ->
                message.copy(generatedFiles = message.generatedFiles.filterNot { it.localPath in paths })
            })
        }
        chatsRepository.save(chats)
        val current = chats.firstOrNull { it.id == _state.value.currentChatId }
        _state.value = _state.value.copy(
            chats = chats,
            messages = current?.messages ?: emptyList()
        )
    }

    private fun makeChatTitle(text: String, attachmentNames: List<String>): String {
        val source = text.trim().ifBlank { attachmentNames.firstOrNull().orEmpty() }.ifBlank { "Новый чат" }
        val oneLine = source.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length <= 38) oneLine else oneLine.take(38).trimEnd() + "…"
    }

    private fun loadQuickTextModels(profileId: String): List<String> = runCatching {
        val type = object : TypeToken<List<String>>() {}.type
        gson.fromJson<List<String>>(prefs.getString(profilePrefKey("quick_text_models_json", profileId), "[]") ?: "[]", type)
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }.getOrDefault(emptyList())

    private fun pruneQuickTextModels(profileId: String, availableIds: Set<String>) {
        if (availableIds.isEmpty()) return
        val stored = loadQuickTextModels(profileId)
        val valid = stored.filter { it in availableIds }
        if (valid != stored) {
            prefs.edit().putString(
                profilePrefKey("quick_text_models_json", profileId),
                gson.toJson(valid)
            ).apply()
        }
    }

    private fun clearCurrentChatModelOverrideIfInvalid(profileId: String, availableIds: Set<String>) {
        val currentOverride = _state.value.currentChatTextModel ?: return
        if (currentOverride in availableIds) return
        val currentChatId = _state.value.currentChatId
        var changed = false
        val chats = _state.value.chats.map { chat ->
            if (
                chat.id == currentChatId &&
                (chat.connectionProfileId == null || chat.connectionProfileId == profileId) &&
                !chat.textModelOverride.isNullOrBlank()
            ) {
                changed = true
                chat.copy(textModelOverride = null, updatedAt = System.currentTimeMillis())
            } else chat
        }
        if (changed) chatsRepository.save(chats)
        _state.value = _state.value.copy(
            chats = if (changed) chats else _state.value.chats,
            currentChatTextModel = null
        )
    }

    private fun quickModelRef(profileId: String, modelId: String): String =
        profileId + QUICK_MODEL_SEPARATOR + modelId

    private fun decodeQuickModelRef(ref: String, fallbackProfileId: String): Pair<String, String> {
        val index = ref.indexOf(QUICK_MODEL_SEPARATOR)
        return if (index > 0) {
            ref.substring(0, index) to ref.substring(index + QUICK_MODEL_SEPARATOR.length)
        } else {
            fallbackProfileId to ref
        }
    }

    private fun loadAllQuickTextModels(
        profiles: List<ConnectionProfile>,
        disabled: Set<String>
    ): List<String> = profiles
        .asSequence()
        .filter { it.id !in disabled }
        .flatMap { profile -> loadQuickTextModels(profile.id).asSequence().map { quickModelRef(profile.id, it) } }
        .filterNot { it.substringAfter(QUICK_MODEL_SEPARATOR).endsWith(":batch", ignoreCase = true) }
        .distinct()
        .toList()

    private fun totalStoredQuickModels(profiles: List<ConnectionProfile>): Int =
        profiles.sumOf { loadQuickTextModels(it.id).size }

    private fun loadDisabledConnectionIds(): Set<String> = emptySet()

    private fun defaultOpenRouterProfile() = ConnectionProfile(
        id = "openrouter",
        name = "OpenRouter",
        type = ProviderType.OPENROUTER,
        baseUrl = ProviderRegistry.DEFAULT_OPENROUTER_BASE_URL,
        imageEnabled = true,
        imageProtocol = ImageApiProtocol.AUTO,
        useSameImageApiKey = true,
        useProviderDefaults = true
    )

    private fun defaultNvidiaProfile() = ConnectionProfile(
        id = "nvidia",
        name = "NVIDIA",
        type = ProviderType.NVIDIA,
        baseUrl = ProviderRegistry.DEFAULT_NVIDIA_TEXT_BASE_URL,
        imageEnabled = true,
        imageProtocol = ImageApiProtocol.AUTO,
        useSameImageApiKey = true,
        useProviderDefaults = true
    )

    private fun loadConnectionProfiles(): List<ConnectionProfile> = runCatching {
        val type = object : TypeToken<List<ConnectionProfile>>() {}.type
        val stored = gson.fromJson<List<ConnectionProfile>>(
            prefs.getString("connection_profiles_json", "[]") ?: "[]",
            type
        ).orEmpty().filter { it.id.isNotBlank() }

        val storedOpenRouter = stored.firstOrNull { it.id == "openrouter" }
        val openRouter = storedOpenRouter?.copy(
            name = "OpenRouter",
            type = ProviderType.OPENROUTER,
            baseUrl = normalizeBaseUrl(storedOpenRouter.baseUrl).ifBlank { ProviderRegistry.DEFAULT_OPENROUTER_BASE_URL },
            imageEnabled = storedOpenRouter.imageEnabled ?: true,
            imageProtocol = storedOpenRouter.imageProtocol ?: ImageApiProtocol.AUTO,
            useSameImageApiKey = storedOpenRouter.useSameImageApiKey ?: true,
            useProviderDefaults = storedOpenRouter.useProviderDefaults ?: (
                normalizeBaseUrl(storedOpenRouter.baseUrl).ifBlank { ProviderRegistry.DEFAULT_OPENROUTER_BASE_URL } == ProviderRegistry.DEFAULT_OPENROUTER_BASE_URL
            )
        ) ?: defaultOpenRouterProfile()

        val nvidiaSource = stored.firstOrNull { profile ->
            profile.id != "openrouter" && (
                profile.type == ProviderType.NVIDIA ||
                    profile.id.equals("nvidia", true) ||
                    profile.name.equals("nvidia", true) ||
                    normalizeBaseUrl(profile.baseUrl).contains("integrate.api.nvidia.com", ignoreCase = true)
                )
        }
        val nvidia = nvidiaSource?.copy(
            name = "NVIDIA",
            type = ProviderType.NVIDIA,
            baseUrl = normalizeBaseUrl(nvidiaSource.baseUrl).ifBlank { ProviderRegistry.DEFAULT_NVIDIA_TEXT_BASE_URL },
            imageEnabled = nvidiaSource.imageEnabled ?: true,
            imageProtocol = nvidiaSource.imageProtocol ?: ImageApiProtocol.AUTO,
            useSameImageApiKey = nvidiaSource.useSameImageApiKey ?: true,
            useProviderDefaults = nvidiaSource.useProviderDefaults ?: (
                normalizeBaseUrl(nvidiaSource.baseUrl).ifBlank { ProviderRegistry.DEFAULT_NVIDIA_TEXT_BASE_URL } == ProviderRegistry.DEFAULT_NVIDIA_TEXT_BASE_URL
            )
        ) ?: defaultNvidiaProfile()

        val remaining = stored.filterNot { it.id == "openrouter" || it.id == nvidiaSource?.id }
            .map { profile ->
                profile.copy(
                    imageEnabled = profile.imageEnabled ?: !prefs.getString(profilePrefKey("image_model", profile.id), null).isNullOrBlank(),
                    imageProtocol = profile.imageProtocol ?: ImageApiProtocol.AUTO,
                    useSameImageApiKey = profile.useSameImageApiKey ?: true,
                    useProviderDefaults = false
                )
            }
        listOf(openRouter)
    }.getOrElse { listOf(defaultOpenRouterProfile()) }

    private fun saveConnectionProfiles(profiles: List<ConnectionProfile>) {
        prefs.edit().putString("connection_profiles_json", gson.toJson(profiles)).apply()
    }

    private fun openRouterProfile(): ConnectionProfile =
        _state.value.connectionProfiles.firstOrNull { it.type == ProviderType.OPENROUTER } ?: defaultOpenRouterProfile()

    private fun activeConnectionProfile(): ConnectionProfile =
        _state.value.connectionProfiles.firstOrNull { it.id == _state.value.activeConnectionProfileId }
            ?: openRouterProfile()

    private fun imageConnectionProfile(): ConnectionProfile =
        _state.value.connectionProfiles.firstOrNull { it.id == _state.value.imageConnectionProfileId }
            ?: openRouterProfile()

    private fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')

    private fun usesProviderDefaults(profile: ConnectionProfile): Boolean = profile.useProviderDefaults ?: when (profile.type) {
        ProviderType.OPENROUTER -> normalizeBaseUrl(profile.baseUrl).ifBlank { ProviderRegistry.DEFAULT_OPENROUTER_BASE_URL } == ProviderRegistry.DEFAULT_OPENROUTER_BASE_URL
        ProviderType.NVIDIA -> normalizeBaseUrl(profile.baseUrl).ifBlank { ProviderRegistry.DEFAULT_NVIDIA_TEXT_BASE_URL } == ProviderRegistry.DEFAULT_NVIDIA_TEXT_BASE_URL
        ProviderType.OPENAI_COMPATIBLE -> false
    }

    private fun effectiveTextBaseUrl(profile: ConnectionProfile): String {
        if (usesProviderDefaults(profile)) {
            providerRegistry.textBaseUrl(profile.type)?.let { return normalizeBaseUrl(it) }
        }
        return normalizeBaseUrl(profile.baseUrl)
    }

    private fun effectiveImageBaseUrl(profile: ConnectionProfile): String {
        if (usesProviderDefaults(profile)) {
            providerRegistry.imageBaseUrl(profile.type)?.let { return normalizeBaseUrl(it) }
        }
        profile.imageBaseUrl?.let(::normalizeBaseUrl)?.takeIf { it.isNotBlank() }?.let { return it }
        return when (profile.type) {
            ProviderType.NVIDIA -> ProviderRegistry.DEFAULT_NVIDIA_IMAGE_BASE_URL
            else -> effectiveTextBaseUrl(profile)
        }
    }

    private fun imageGenerationEnabled(profile: ConnectionProfile): Boolean = profile.imageEnabled ?: when (profile.type) {
        ProviderType.OPENROUTER, ProviderType.NVIDIA -> true
        ProviderType.OPENAI_COMPATIBLE -> false
    }

    private fun usesSameImageApiKey(profile: ConnectionProfile): Boolean = profile.useSameImageApiKey ?: true

    private fun imageApiKey(profile: ConnectionProfile): String = if (usesSameImageApiKey(profile)) {
        secrets.getProfileApiKey(profile.id).orEmpty()
    } else {
        secrets.getProfileImageApiKey(profile.id).orEmpty()
    }

    private fun resolvedImageProtocol(profile: ConnectionProfile): ImageApiProtocol {
        val explicit = profile.imageProtocol ?: ImageApiProtocol.AUTO
        if (explicit != ImageApiProtocol.AUTO) return explicit
        return if (profile.type == ProviderType.NVIDIA) ImageApiProtocol.NVIDIA_NIM else ImageApiProtocol.OPENAI_COMPATIBLE
    }

    private fun isProfileConfigured(profile: ConnectionProfile): Boolean = when (profile.type) {
        ProviderType.OPENROUTER, ProviderType.NVIDIA -> effectiveTextBaseUrl(profile).isNotBlank() && !secrets.getProfileApiKey(profile.id).isNullOrBlank()
        ProviderType.OPENAI_COMPATIBLE -> effectiveTextBaseUrl(profile).isNotBlank()
    }

    private fun isImageProfileConfigured(profile: ConnectionProfile): Boolean {
        if (!imageGenerationEnabled(profile) || effectiveImageBaseUrl(profile).isBlank()) return false
        return when (profile.type) {
            ProviderType.OPENROUTER, ProviderType.NVIDIA -> imageApiKey(profile).isNotBlank()
            ProviderType.OPENAI_COMPATIBLE -> true
        }
    }

    private fun connectionSetupMessage(profile: ConnectionProfile): String = when (profile.type) {
        ProviderType.OPENROUTER -> "Откройте «Подключения» и сохраните API-ключ OpenRouter"
        ProviderType.NVIDIA -> "Откройте «Подключения» и сохраните API-ключ NVIDIA"
        ProviderType.OPENAI_COMPATIBLE -> "Откройте «Подключения» и укажите адрес совместимого API"
    }

    private fun imageConnectionSetupMessage(profile: ConnectionProfile): String = when {
        !imageGenerationEnabled(profile) -> "В «Подключениях» включите генерацию изображений для «${profile.name}»"
        effectiveImageBaseUrl(profile).isBlank() -> "Укажите адрес API изображений для «${profile.name}»"
        !usesSameImageApiKey(profile) && imageApiKey(profile).isBlank() -> "Укажите отдельный API-ключ изображений для «${profile.name}»"
        profile.type == ProviderType.OPENROUTER && imageApiKey(profile).isBlank() -> "Сохраните API-ключ OpenRouter"
        profile.type == ProviderType.NVIDIA && imageApiKey(profile).isBlank() -> "Сохраните API-ключ NVIDIA"
        else -> "Проверьте настройки Image API для «${profile.name}»"
    }

    private suspend fun textModelsForProfile(profile: ConnectionProfile): List<ModelInfo> {
        val key = secrets.getProfileApiKey(profile.id).orEmpty()
        val models = if (profile.type == ProviderType.OPENROUTER) {
            api.models(key, effectiveTextBaseUrl(profile))
        } else {
            compatibleApi.models(key, effectiveTextBaseUrl(profile))
        }
        return models.filter { ModelCategory.TEXT in it.categories && !it.isBatch }
    }

    private suspend fun imageModelsForProfile(profile: ConnectionProfile): List<ModelInfo> {
        if (!imageGenerationEnabled(profile)) return emptyList()
        val key = imageApiKey(profile)
        if (profile.type == ProviderType.OPENROUTER) {
            return api.imageModels(key, effectiveImageBaseUrl(profile))
        }
        return when (resolvedImageProtocol(profile)) {
            ImageApiProtocol.NVIDIA_NIM -> {
                val registryModels = providerRegistry.imageModels(ProviderType.NVIDIA)
                val saved = loadImageModelForProfile(profile.id)
                if (profile.type == ProviderType.OPENAI_COMPATIBLE && saved.isNotBlank() && registryModels.none { it.id == saved }) {
                    listOf(ModelInfo(saved)) + registryModels
                } else registryModels
            }
            ImageApiProtocol.OPENAI_COMPATIBLE, ImageApiProtocol.AUTO -> {
                val saved = loadImageModelForProfile(profile.id)
                if (saved.isBlank()) emptyList() else listOf(ModelInfo(saved))
            }
        }
    }

    private fun chooseImageModel(profile: ConnectionProfile, infos: List<ModelInfo>): String {
        val key = profilePrefKey("image_model", profile.id)
        var selected = loadImageModelForProfile(profile.id)
        if (selected.isBlank() && prefs.contains(key)) return ""
        if (infos.isNotEmpty() && infos.none { it.id == selected }) {
            selected = infos.first().id
            prefs.edit().putString(key, selected).apply()
        }
        return selected
    }

    private fun profilePrefKey(base: String, profileId: String): String =
        if (profileId == "openrouter") base else "${base}_profile_$profileId"

    private fun loadTextModelForProfile(profile: ConnectionProfile): String {
        val fallback = if (profile.type == ProviderType.OPENROUTER) "openrouter/auto" else ""
        val stored = prefs.getString(profilePrefKey("text_model", profile.id), fallback)?.trim().orEmpty()
        val safe = if (stored.endsWith(":batch", ignoreCase = true)) stored.removeSuffix(":batch") else stored
        return safe.ifBlank { fallback }
    }

    private fun loadImageModelForProfile(profileId: String): String {
        val profile = initialProfiles.firstOrNull { it.id == profileId }
            ?: runCatching { _state.value.connectionProfiles.firstOrNull { it.id == profileId } }.getOrNull()
        val registryModels = if (profile?.type == ProviderType.NVIDIA) {
            providerRegistry.imageModels(ProviderType.NVIDIA)
        } else emptyList()
        val fallback = when (profile?.type) {
            ProviderType.OPENROUTER -> "bytedance-seed/seedream-4.5"
            ProviderType.NVIDIA -> registryModels.firstOrNull()?.id.orEmpty()
            else -> ""
        }
        val key = profilePrefKey("image_model", profileId)
        val stored = prefs.getString(key, fallback)?.trim().orEmpty()

        if (profile?.type == ProviderType.NVIDIA && registryModels.isNotEmpty() && registryModels.none { it.id == stored }) {
            prefs.edit().putString(key, fallback).apply()
            return fallback
        }
        return stored.ifBlank { fallback }
    }

    private fun imageParameterPrefKey(parameter: String, profileId: String, modelId: String): String =
        "image_parameter_${parameter}_${profileId}_${modelId}"

    private fun loadImageParameter(parameter: String, profileId: String, modelId: String): String? =
        prefs.getString(imageParameterPrefKey(parameter, profileId, modelId), null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun validatedImageParameter(
        parameter: String,
        profileId: String,
        modelId: String,
        info: ModelInfo?
    ): String? {
        val stored = loadImageParameter(parameter, profileId, modelId) ?: return null
        if (info == null) return stored
        return stored.takeIf { it in info.parameterValues(parameter) }
    }

    private fun loadReasoningEffortsByModel(): Map<String, ReasoningEffort> = runCatching {
        val type = object : TypeToken<Map<String, ReasoningEffort>>() {}.type
        gson.fromJson<Map<String, ReasoningEffort>>(
            prefs.getString("reasoning_efforts_by_model_json", "{}") ?: "{}",
            type
        ).orEmpty()
    }.getOrDefault(emptyMap())

    private fun loadInitialChats(): List<ChatSession> {
        val storedChats = chatsRepository.list()
        val existing = storedChats.map { chat ->
            val override = chat.textModelOverride
            if (override?.endsWith(":batch", ignoreCase = true) == true) {
                chat.copy(textModelOverride = override.removeSuffix(":batch"), mode = ChatMode.TEXT)
            } else chat
        }
        if (existing != storedChats && existing.isNotEmpty()) chatsRepository.save(existing)
        if (existing.isNotEmpty()) return existing
        if (chatsRepository.loadError != null) {
            return listOf(ChatSession(id = UUID.randomUUID().toString(), title = "Хранилище чатов недоступно"))
        }

        val legacy = loadLegacyMessages()
        val chat = ChatSession(
            id = UUID.randomUUID().toString(),
            title = if (legacy.isEmpty()) "Новый чат" else makeChatTitle(
                legacy.firstOrNull { it.role == "user" }?.text.orEmpty(),
                legacy.firstOrNull { it.role == "user" }?.attachmentNames ?: emptyList()
            ),
            messages = legacy
        )
        chatsRepository.save(listOf(chat))
        prefs.edit().remove("messages").apply()
        return listOf(chat)
    }

    private fun loadLegacyMessages(): List<ChatMessage> = runCatching {
        val raw = prefs.getString("messages", null) ?: return emptyList()
        val type = object : TypeToken<List<ChatMessage>>() {}.type
        gson.fromJson<List<ChatMessage>>(raw, type) ?: emptyList()
    }.getOrDefault(emptyList())

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ChatViewModel(context.applicationContext) as T
    }
}
