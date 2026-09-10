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
import com.ayuemin.ymnik.data.SecretStore
import com.ayuemin.ymnik.data.SkillRepository
import com.ayuemin.ymnik.data.StorageRepository
import com.ayuemin.ymnik.model.AnswerSoundChoice
import com.ayuemin.ymnik.model.ChatFile
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.ChatMode
import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.GeneratedFile
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.PendingAttachment
import com.ayuemin.ymnik.model.Project
import com.ayuemin.ymnik.model.ProjectFile
import com.ayuemin.ymnik.model.ReasoningEffort
import com.ayuemin.ymnik.model.StoredFile
import com.ayuemin.ymnik.model.ThemeChoice
import com.ayuemin.ymnik.model.UiState
import com.ayuemin.ymnik.model.UserProfile
import com.ayuemin.ymnik.model.UserProfileScope
import com.ayuemin.ymnik.network.OpenRouterClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class ChatViewModel(private val context: Context) : ViewModel() {
    private val prefs = context.getSharedPreferences("ymnik", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)
    private val skills = SkillRepository(context)
    private val chatsRepository = ChatRepository(context)
    private val chatFilesRepository = ChatFileRepository(context)
    private val projectsRepository = ProjectRepository(context)
    private val storageRepository = StorageRepository(context)
    private val api = OpenRouterClient(context)
    private val gson = Gson()
    private var activeRequestJob: Job? = null
    private var activeRequestPending: List<PendingAttachment> = emptyList()
    private var requestGeneration: Long = 0L

    private val initialChats = loadInitialChats()
    private val initialChatId = prefs.getString("current_chat_id", null)
        ?.takeIf { id -> initialChats.any { it.id == id } }
        ?: initialChats.first().id
    private val initialChat = initialChats.first { it.id == initialChatId }

    private val _state = MutableStateFlow(
        UiState(
            messages = initialChat.messages,
            chats = initialChats,
            projects = projectsRepository.list(),
            currentChatId = initialChatId,
            skills = skills.list(),
            activeSkillIds = prefs.getStringSet("active_skills", emptySet())?.toSet() ?: emptySet(),
            mode = initialChat.mode ?: runCatching {
                ChatMode.valueOf(prefs.getString("chat_mode", ChatMode.TEXT.name) ?: ChatMode.TEXT.name)
            }.getOrDefault(ChatMode.TEXT),
            textModel = prefs.getString("text_model", prefs.getString("model", "openrouter/auto")) ?: "openrouter/auto",
            currentChatTextModel = initialChat.textModelOverride,
            quickTextModels = loadQuickTextModels(),
            imageModel = prefs.getString("image_model", "bytedance-seed/seedream-4.5") ?: "bytedance-seed/seedream-4.5",
            webSearchEnabled = prefs.getBoolean("web_search", false),
            reasoningEnabled = prefs.getBoolean("reasoning_enabled", false),
            reasoningEffort = runCatching {
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
            apiKeyConfigured = !secrets.getApiKey().isNullOrBlank(),
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
            storageStats = storageRepository.stats()
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        if (!secrets.getApiKey().isNullOrBlank()) refreshModelCapabilities()
    }

    fun saveApiKey(apiKey: String?) {
        if (!apiKey.isNullOrBlank()) secrets.saveApiKey(apiKey)
        _state.value = _state.value.copy(
            apiKeyConfigured = !secrets.getApiKey().isNullOrBlank(),
            status = "Настройки сохранены"
        )
        if (_state.value.apiKeyConfigured) refreshModelCapabilities()
    }

    fun setMode(mode: ChatMode) {
        prefs.edit().putString("chat_mode", mode.name).apply()
        val chats = _state.value.chats.map { chat ->
            if (chat.id == _state.value.currentChatId) chat.copy(mode = mode) else chat
        }
        chatsRepository.save(chats)
        _state.value = _state.value.copy(mode = mode, chats = chats)
    }

    fun selectModel(mode: ChatMode, model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
        when (mode) {
            ChatMode.TEXT -> {
                val effectiveId = _state.value.currentChatTextModel ?: clean
                val info = _state.value.availableTextModels.firstOrNull { it.id == effectiveId }
                val effort = preferredReasoningEffort(effectiveId, info)
                val keepReasoning = reasoningStillValid(info, effort)
                prefs.edit()
                    .putString("text_model", clean)
                    .putString("reasoning_effort", effort.name)
                    .putBoolean("reasoning_enabled", keepReasoning)
                    .apply()
                _state.value = _state.value.copy(
                    textModel = clean,
                    reasoningEffort = effort,
                    reasoningEnabled = keepReasoning
                )
            }
            ChatMode.IMAGE -> {
                prefs.edit().putString("image_model", clean).apply()
                _state.value = _state.value.copy(imageModel = clean)
            }
        }
    }

    fun toggleQuickTextModel(model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
        val current = _state.value.quickTextModels
        val next = if (clean in current) {
            current.filterNot { it == clean }
        } else {
            if (current.size >= 10) {
                _state.value = _state.value.copy(status = "Можно закрепить до 10 быстрых моделей")
                return
            }
            current + clean
        }
        prefs.edit().putString("quick_text_models_json", gson.toJson(next)).apply()
        _state.value = _state.value.copy(quickTextModels = next)
    }

    fun selectQuickTextModel(model: String) {
        if (_state.value.isLoading) return
        val clean = model.trim()
        if (clean.isBlank()) return
        val info = _state.value.availableTextModels.firstOrNull { it.id == clean }
        val effort = preferredReasoningEffort(clean, info)
        val keepReasoning = reasoningStillValid(info, effort)
        val chats = _state.value.chats.map { chat ->
            if (chat.id == _state.value.currentChatId) chat.copy(
                textModelOverride = clean,
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
            currentChatTextModel = clean,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning
        )
    }

    fun useDefaultTextModelForChat() {
        if (_state.value.isLoading) return
        val modelId = _state.value.textModel
        val info = _state.value.availableTextModels.firstOrNull { it.id == modelId }
        val effort = preferredReasoningEffort(modelId, info)
        val keepReasoning = reasoningStillValid(info, effort)
        val chats = _state.value.chats.map { chat ->
            if (chat.id == _state.value.currentChatId) chat.copy(
                textModelOverride = null,
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
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning
        )
    }

    fun setWebSearchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("web_search", enabled).apply()
        _state.value = _state.value.copy(webSearchEnabled = enabled)
    }

    fun setReasoningEnabled(enabled: Boolean) {
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
        prefs.edit().putBoolean("reasoning_enabled", enabled).apply()
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
                .replace(Regex("[^\p{L}\p{N}._ ()-]"), "_")
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

    fun createChat(projectId: String? = null): String {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return _state.value.currentChatId
        val chat = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "Новый чат",
            projectId = projectId,
            mode = _state.value.mode
        )
        val next = listOf(chat) + _state.value.chats
        val modelId = _state.value.textModel
        val info = _state.value.availableTextModels.firstOrNull { it.id == modelId }
        val effort = preferredReasoningEffort(modelId, info)
        val keepReasoning = reasoningStillValid(info, effort)
        chatsRepository.save(next)
        prefs.edit()
            .putString("current_chat_id", chat.id)
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", keepReasoning)
            .apply()
        _state.value = _state.value.copy(
            chats = next,
            currentChatId = chat.id,
            messages = emptyList(),
            currentChatTextModel = null,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning,
            pendingAttachments = emptyList(),
            storageStats = storageRepository.stats()
        )
        return chat.id
    }

    fun switchChat(id: String) {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return
        val chat = _state.value.chats.firstOrNull { it.id == id } ?: return
        val nextMode = chat.mode ?: _state.value.mode
        val modelId = chat.textModelOverride ?: _state.value.textModel
        val info = _state.value.availableTextModels.firstOrNull { it.id == modelId }
        val effort = preferredReasoningEffort(modelId, info)
        val keepReasoning = reasoningStillValid(info, effort)
        prefs.edit()
            .putString("current_chat_id", id)
            .putString("chat_mode", nextMode.name)
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", keepReasoning)
            .apply()
        _state.value = _state.value.copy(
            currentChatId = id,
            messages = chat.messages,
            mode = nextMode,
            currentChatTextModel = chat.textModelOverride,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning,
            pendingAttachments = emptyList()
        )
    }

    fun deleteChat(id: String) {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return
        if (_state.value.chats.none { it.id == id }) return

        chatFilesRepository.deleteChat(id)
        var remaining = _state.value.chats.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            remaining = listOf(ChatSession(UUID.randomUUID().toString(), "Новый чат", mode = _state.value.mode))
        }

        val selected = if (_state.value.currentChatId == id) remaining.first() else
            remaining.firstOrNull { it.id == _state.value.currentChatId } ?: remaining.first()

        chatsRepository.save(remaining)
        prefs.edit().putString("current_chat_id", selected.id).apply()
        _state.value = _state.value.copy(
            chats = remaining,
            currentChatId = selected.id,
            messages = selected.messages,
            mode = selected.mode ?: _state.value.mode,
            currentChatTextModel = selected.textModelOverride,
            pendingAttachments = emptyList(),
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Диалог удалён"
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
        val projects = listOf(project) + _state.value.projects
        projectsRepository.save(projects)
        _state.value = _state.value.copy(
            projects = projects,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats()
        )
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

    fun setProjectFavorite(id: String, favorite: Boolean) {
        val projects = _state.value.projects.map { project ->
            if (project.id == id) project.copy(isFavorite = favorite, updatedAt = System.currentTimeMillis()) else project
        }
        projectsRepository.save(projects)
        _state.value = _state.value.copy(projects = projects)
    }

    fun toggleProjectSkill(projectId: String, skillId: String) {
        val projects = _state.value.projects.map { project ->
            if (project.id != projectId) project else {
                val next = project.skillIds.toMutableSet().apply { if (!add(skillId)) remove(skillId) }
                project.copy(skillIds = next, updatedAt = System.currentTimeMillis())
            }
        }
        projectsRepository.save(projects)
        _state.value = _state.value.copy(projects = projects)
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

        // Диалоги не уничтожаем: после удаления проекта они становятся обычными чатами.
        val chats = _state.value.chats.map { chat ->
            if (chat.projectId == projectId) chat.copy(projectId = null) else chat
        }
        chatsRepository.save(chats)
        val current = chats.firstOrNull { it.id == _state.value.currentChatId }
        _state.value = _state.value.copy(
            projects = projects,
            chats = chats,
            messages = current?.messages ?: _state.value.messages,
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Проект удалён. Его чаты сохранены как обычные."
        )
    }

    fun refreshModels(mode: ChatMode) {
        val key = secrets.getApiKey()
        if (key.isNullOrBlank()) {
            _state.value = _state.value.copy(status = "Сначала сохраните API-ключ OpenRouter")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, busyLabel = "Загружаю модели…", status = null)
            runCatching {
                when (mode) {
                    ChatMode.TEXT -> api.models(key)
                    ChatMode.IMAGE -> api.imageModels(key)
                }
            }.onSuccess { infos ->
                _state.value = when (mode) {
                    ChatMode.TEXT -> {
                        val effectiveId = _state.value.currentChatTextModel ?: _state.value.textModel
                        val current = infos.firstOrNull { it.id == effectiveId }
                        val effort = preferredReasoningEffort(effectiveId, current)
                        val keepReasoning = reasoningStillValid(current, effort)
                        prefs.edit()
                            .putString("reasoning_effort", effort.name)
                            .putBoolean("reasoning_enabled", keepReasoning)
                            .apply()
                        _state.value.copy(
                            availableTextModels = infos,
                            reasoningEffort = effort,
                            reasoningEnabled = keepReasoning,
                            isLoading = false,
                            busyLabel = null
                        )
                    }
                    ChatMode.IMAGE -> _state.value.copy(
                        availableImageModels = infos,
                        isLoading = false,
                        busyLabel = null
                    )
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

    private fun refreshModelCapabilities() {
        val key = secrets.getApiKey() ?: return
        viewModelScope.launch {
            val textInfos = runCatching { api.models(key) }.getOrNull()
            val imageInfos = runCatching { api.imageModels(key) }.getOrNull()
            var next = _state.value
            if (textInfos != null) {
                val effectiveId = next.currentChatTextModel ?: next.textModel
                val current = textInfos.firstOrNull { it.id == effectiveId }
                val effort = preferredReasoningEffort(effectiveId, current)
                val keepReasoning = reasoningStillValid(current, effort)
                prefs.edit()
                    .putString("reasoning_effort", effort.name)
                    .putBoolean("reasoning_enabled", keepReasoning)
                    .apply()
                next = next.copy(
                    availableTextModels = textInfos,
                    reasoningEffort = effort,
                    reasoningEnabled = keepReasoning
                )
            }
            if (imageInfos != null) next = next.copy(availableImageModels = imageInfos)
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
        if (mime == "application/pdf" || name.endsWith(".pdf")) return true to null
        if (mime.startsWith("image/")) return if (info?.accepts("image") == true) true to null else false to "Выбранная модель не принимает изображения"
        if (mime.startsWith("audio/")) return if (info?.accepts("audio") == true) true to null else false to "Выбранная модель не принимает аудио"
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

    fun addAttachment(uri: Uri) {
        runCatching { api.attachmentFromUri(uri) }
            .onSuccess { attachment ->
                if (attachment.size > 25L * 1024 * 1024) {
                    _state.value = _state.value.copy(status = "Ограничение Umnik сейчас 25 МБ на один файл")
                } else {
                    val (allowed, reason) = attachmentAllowed(attachment)
                    if (!allowed) {
                        _state.value = _state.value.copy(status = reason)
                    } else if (shouldPersistInChat(attachment)) {
                        persistChatAttachment(attachment)
                    } else {
                        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)
                    }
                }
            }
            .onFailure { _state.value = _state.value.copy(status = it.message) }
    }

    fun addCameraAttachment(uri: Uri, localPath: String) {
        runCatching { api.attachmentFromUri(uri).copy(localPath = localPath) }
            .onSuccess { attachment ->
                if (attachment.size > 25L * 1024 * 1024) {
                    File(localPath).delete()
                    _state.value = _state.value.copy(status = "Фото превышает ограничение 25 МБ")
                } else {
                    val (allowed, reason) = attachmentAllowed(attachment)
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
        val next = _state.value.activeSkillIds.toMutableSet().apply { if (!add(id)) remove(id) }
        prefs.edit().putStringSet("active_skills", next).apply()
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
        if (!_state.value.requestActive) return
        requestGeneration += 1L
        api.cancelActiveRequest()
        activeRequestJob?.cancel()
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

    fun send(text: String) {
        val key = secrets.getApiKey()
        if (key.isNullOrBlank()) {
            _state.value = _state.value.copy(status = "Укажите API-ключ OpenRouter в настройках")
            return
        }

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
                    persistentChatFiles.isNotEmpty() -> "[Файлы чата]"
                    else -> "[Вложения]"
                }
            },
            attachmentNames = (pending.map { it.name } + persistentChatFiles.map { it.name }).distinct()
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
        val imageModel = _state.value.imageModel
        val webSearchEnabled = _state.value.webSearchEnabled
        val reasoningEnabled = _state.value.reasoningEnabled
        val reasoningEffort = _state.value.reasoningEffort
        val requestId = ++requestGeneration
        activeRequestPending = pending

        activeRequestJob = viewModelScope.launch {
            val operation = runCatching {
                when (mode) {
                    ChatMode.TEXT -> {
                        val skillIds = _state.value.activeSkillIds + (currentProject?.skillIds ?: emptySet())
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
                        val actualReasoning = reasoningEnabled && modelInfo?.supportsReasoning == true &&
                            (modelInfo.reasoningEfforts.isEmpty() || reasoningEffort.apiValue in modelInfo.reasoningEfforts)
                        val effort = if (actualReasoning && modelInfo.supportsReasoningEffort) reasoningEffort.apiValue else null
                        api.chat(
                            key,
                            textModel,
                            before,
                            clean,
                            (pending + persistentChatFiles.filter { attachmentAllowed(it).first } + projectFiles)
                                .distinctBy { it.localPath ?: it.uri },
                            buildSystemPrompt(skillText, currentProject, currentChat, modelInfo?.supportsTools == true),
                            webSearchEnabled,
                            actualReasoning,
                            effort,
                            modelInfo?.supportsTools == true
                        )
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
                        api.generateImage(key, imageModel, listOf(projectPrefix, clean).filter { it.isNotBlank() }.joinToString("\n\n"), pending + projectImages)
                    }
                }
            }

            if (requestId != requestGeneration) {
                return@launch
            }

            operation.onSuccess { result ->
                val assistant = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = "assistant",
                    text = result.text.ifBlank {
                        if (result.files.isNotEmpty()) "Готово." else "Пустой ответ модели."
                    },
                    generatedFiles = result.files
                )
                val messages = _state.value.messages + assistant
                val chats = replaceChatMessages(_state.value.chats, chatId, messages, null)
                chatsRepository.save(chats)
                _state.value = _state.value.copy(
                    messages = messages,
                    chats = chats,
                    isLoading = false,
                    requestActive = false,
                    busyLabel = null,
                    storedFiles = storageRepository.list(),
                    storageStats = storageRepository.stats()
                )
                playReadySound()
            }.onFailure {
                _state.value = _state.value.copy(
                    isLoading = false,
                    requestActive = false,
                    busyLabel = null,
                    status = it.message ?: "Ошибка запроса"
                )
            }
            cleanupTempAttachments(pending)
            if (requestId == requestGeneration) {
                activeRequestJob = null
                activeRequestPending = emptyList()
            }
        }
    }

    override fun onCleared() {
        api.cancelActiveRequest()
        activeRequestJob?.cancel()
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
        val cameraRoot = File(context.cacheDir, "camera")
        items.mapNotNull { it.localPath }.forEach { path ->
            runCatching {
                val file = File(path).canonicalFile
                val root = cameraRoot.canonicalFile
                if (file.path.startsWith(root.path + File.separator)) file.delete()
            }
        }
    }

    private fun playReadySound() {
        val state = _state.value
        if (!state.answerSoundEnabled) return

        if (state.answerSoundChoice == AnswerSoundChoice.CUSTOM) {
            val custom = state.answerSoundCustomPath?.let(::File)
            if (custom?.isFile == true) {
                runCatching {
                    val volume = state.answerSoundVolume.coerceIn(0, 100) / 100f
                    val player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
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
                AudioManager.STREAM_NOTIFICATION,
                state.answerSoundVolume.coerceIn(0, 100)
            )
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 90)
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { tone.release() } }, 180)
        }
    }

    private fun buildSystemPrompt(skillText: String, project: Project?, chat: ChatSession?, toolsEnabled: Boolean): String = buildString {
        appendLine("Ты работаешь внутри Android-приложения «Umnik». Отвечай на языке пользователя, если он не попросил иначе.")
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
                appendLine("Мастер-инструкция проекта:")
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
                messages = messages.takeLast(120),
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

    private fun loadQuickTextModels(): List<String> = runCatching {
        val type = object : TypeToken<List<String>>() {}.type
        gson.fromJson<List<String>>(prefs.getString("quick_text_models_json", "[]") ?: "[]", type)
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(10)
    }.getOrDefault(emptyList())

    private fun loadReasoningEffortsByModel(): Map<String, ReasoningEffort> = runCatching {
        val type = object : TypeToken<Map<String, ReasoningEffort>>() {}.type
        gson.fromJson<Map<String, ReasoningEffort>>(
            prefs.getString("reasoning_efforts_by_model_json", "{}") ?: "{}",
            type
        ).orEmpty()
    }.getOrDefault(emptyMap())

    private fun loadInitialChats(): List<ChatSession> {
        val existing = chatsRepository.list()
        if (existing.isNotEmpty()) return existing

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
