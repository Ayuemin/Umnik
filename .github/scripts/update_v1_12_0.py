from pathlib import Path


def one(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 match, got {count}")
    return text.replace(old, new, 1)


def between(text: str, start: str, end: str, new: str, label: str) -> str:
    i = text.find(start)
    if i < 0:
        raise SystemExit(f"{label}: start not found")
    j = text.find(end, i + len(start))
    if j < 0:
        raise SystemExit(f"{label}: end not found")
    return text[:i] + new + text[j:]


# ChatViewModel.kt
p = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
s = p.read_text()
s = one(s,
'''import com.ayuemin.ymnik.data.ProjectRepository
import com.ayuemin.ymnik.data.OpenRouterFeaturePrefs
''',
'''import com.ayuemin.ymnik.data.ProjectRepository
import com.ayuemin.ymnik.data.ProjectAutomationRepository
import com.ayuemin.ymnik.data.OpenRouterFeaturePrefs
''', "automation repo import")
s = one(s,
'''import com.ayuemin.ymnik.model.ModelCategory
import com.ayuemin.ymnik.model.PendingAttachment
''',
'''import com.ayuemin.ymnik.model.ModelCategory
import com.ayuemin.ymnik.model.OrchestratorStep
import com.ayuemin.ymnik.model.OrchestratorStepType
import com.ayuemin.ymnik.model.PendingAttachment
''', "orchestrator model imports")
s = one(s,
'''import com.ayuemin.ymnik.model.ProjectStage
import com.ayuemin.ymnik.model.ProviderType
''',
'''import com.ayuemin.ymnik.model.ProjectStage
import com.ayuemin.ymnik.model.ProjectChatRuntimeProfile
import com.ayuemin.ymnik.model.ProviderType
''', "runtime profile import")
s = one(s,
'''    private val projectsRepository = ProjectRepository(context)
    private val openRouterFeaturePrefs = OpenRouterFeaturePrefs(context)
''',
'''    private val projectsRepository = ProjectRepository(context)
    private val projectAutomation = ProjectAutomationRepository(context)
    private val openRouterFeaturePrefs = OpenRouterFeaturePrefs(context)
''', "automation repo field")

s = one(s,
'''    private val initialProfiles = loadConnectionProfiles()
    private val initialDisabledConnectionIds = loadDisabledConnectionIds()
    private val initialChats = loadInitialChats()
    private val initialChatId = prefs.getString("current_chat_id", null)
        ?.takeIf { id -> initialChats.any { it.id == id } }
        ?: initialChats.first().id
    private val initialChat = initialChats.first { it.id == initialChatId }
    private val initialProjects = projectsRepository.list()
''',
'''    private val initialProfiles = loadConnectionProfiles()
    private val initialDisabledConnectionIds = loadDisabledConnectionIds()
    private val initialProjects = projectsRepository.list()
    private val initialChats = ensureProjectOrchestrators(initialProjects, loadInitialChats())
    private val initialChatId = prefs.getString("current_chat_id", null)
        ?.takeIf { id -> initialChats.any { it.id == id } }
        ?: initialChats.first().id
    private val initialChat = initialChats.first { it.id == initialChatId }
''', "initial orchestrators")

# Initial runtime state comes from a saved project-chat profile when one exists.
s = one(s,
'''            webSearchEnabled = prefs.getBoolean("web_search", false),
            reasoningEnabled = prefs.getBoolean("reasoning_enabled", false),
            reasoningEffort = runCatching {
                ReasoningEffort.valueOf(prefs.getString("reasoning_effort", ReasoningEffort.MEDIUM.name) ?: ReasoningEffort.MEDIUM.name)
            }.getOrDefault(ReasoningEffort.MEDIUM),
''',
'''            webSearchEnabled = projectAutomation.profile(initialChat.id)?.webSearchEnabled ?: prefs.getBoolean("web_search", false),
            reasoningEnabled = projectAutomation.profile(initialChat.id)?.reasoningEnabled ?: prefs.getBoolean("reasoning_enabled", false),
            reasoningEffort = projectAutomation.profile(initialChat.id)?.reasoningEffort ?: runCatching {
                ReasoningEffort.valueOf(prefs.getString("reasoning_effort", ReasoningEffort.MEDIUM.name) ?: ReasoningEffort.MEDIUM.name)
            }.getOrDefault(ReasoningEffort.MEDIUM),
''', "initial runtime profile")

s = one(s, '''    private fun defaultSkillIdsForChat(chat: ChatSession): Set<String> =
''', '''    private fun ensureProjectOrchestrators(projects: List<Project>, chats: List<ChatSession>): List<ChatSession> {
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
''', "ensure orchestrators function")

s = one(s, '''    private fun skillIdsForChat(chat: ChatSession): Set<String> {
        val key = chatSkillsKey(chat.id)
        return if (prefs.contains(key)) {
            prefs.getStringSet(key, emptySet())?.toSet().orEmpty()
        } else {
            defaultSkillIdsForChat(chat)
        }
    }

    init {
''', '''    private fun skillIdsForChat(chat: ChatSession): Set<String> {
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
''', "runtime helpers")

# Keep per-project-chat skill choice synchronized with its fixed runtime profile.
s = one(s, '''    fun toggleSkill(id: String) {
        val chat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId } ?: return
        val next = _state.value.activeSkillIds.toMutableSet().apply { if (!add(id)) remove(id) }.toSet()
        prefs.edit().putStringSet(chatSkillsKey(chat.id), next).apply()
        _state.value = _state.value.copy(activeSkillIds = next)
    }
''', '''    fun toggleSkill(id: String) {
        val chat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId } ?: return
        val next = _state.value.activeSkillIds.toMutableSet().apply { if (!add(id)) remove(id) }.toSet()
        prefs.edit().putStringSet(chatSkillsKey(chat.id), next).apply()
        if (chat.projectId != null) updateCurrentProjectRuntime { it.copy(skillIds = next) }
        _state.value = _state.value.copy(activeSkillIds = next)
    }
''', "toggle skill runtime")

# Web/reasoning toggles are global for ordinary chats, fixed per project chat.
s = one(s, '''        prefs.edit().putBoolean("web_search", enabled).apply()
        _state.value = _state.value.copy(webSearchEnabled = enabled)
    }

    fun setReasoningEnabled(enabled: Boolean) {
''', '''        val chat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId }
        if (chat?.projectId != null) updateCurrentProjectRuntime { it.copy(webSearchEnabled = enabled) }
        else prefs.edit().putBoolean("web_search", enabled).apply()
        _state.value = _state.value.copy(webSearchEnabled = enabled)
    }

    fun setReasoningEnabled(enabled: Boolean) {
''', "web toggle runtime")
s = one(s, '''        prefs.edit().putBoolean("reasoning_enabled", enabled).apply()
        _state.value = _state.value.copy(reasoningEnabled = enabled)
    }

    fun setReasoningEffort(effort: ReasoningEffort) {
''', '''        val chat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId }
        if (chat?.projectId != null) updateCurrentProjectRuntime { it.copy(reasoningEnabled = enabled, reasoningEffort = _state.value.reasoningEffort) }
        else prefs.edit().putBoolean("reasoning_enabled", enabled).apply()
        _state.value = _state.value.copy(reasoningEnabled = enabled)
    }

    fun setReasoningEffort(effort: ReasoningEffort) {
''', "reasoning toggle runtime")
s = one(s, '''        } else {
            _state.value = _state.value.copy(reasoningEffortsByModel = nextMap)
        }
    }

    fun saveUserProfile''', '''        } else {
            _state.value = _state.value.copy(reasoningEffortsByModel = nextMap)
        }
        if (clean == currentTextModelId()) {
            val chat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId }
            if (chat?.projectId != null) updateCurrentProjectRuntime { it.copy(reasoningEffort = effort) }
        }
    }

    fun saveUserProfile''', "reasoning effort runtime")

# New project chat gets a fixed snapshot immediately; the protected orchestrator is never reused as an empty chat.
s = one(s,
'''        if (current != null && current.projectId == projectId && isBareEmptyChat(current)) {
''',
'''        if (current != null && current.projectId == projectId && !isOrchestratorChat(current.id) && isBareEmptyChat(current)) {
''', "do not reuse orchestrator")
s = one(s, '''        chatsRepository.save(next)
        prefs.edit()
            .putString("current_chat_id", chat.id)
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", keepReasoning)
            .apply()
''', '''        chatsRepository.save(next)
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
''', "new project chat profile")

# Switch restores fixed project-chat settings instead of forcing reasoning off.
s = between(s, '''    fun switchChat(id: String) {
''', '''    fun refreshAsyncResults() {
''', '''    fun switchChat(id: String) {
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

''', "switch chat fixed profile")

# Project creation automatically creates and registers its protected orchestrator.
s = between(s, '''    fun createProject(
''', '''    fun updateProject(id: String, name: String, role: String, masterPrompt: String, favorite: Boolean) {
''', '''    fun createProject(
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
''', "create project orchestrator")

# Orchestrator cannot be deleted on its own.
s = one(s, '''        val deletingChat = _state.value.chats.firstOrNull { it.id == id } ?: return

        deletingChat.stages.orEmpty()
''', '''        val deletingChat = _state.value.chats.firstOrNull { it.id == id } ?: return
        if (isOrchestratorChat(id)) {
            _state.value = _state.value.copy(status = "Оркестратор удаляется только вместе с проектом")
            return
        }

        deletingChat.stages.orEmpty()
''', "protect delete orchestrator")
s = one(s, '''        prefs.edit().remove(chatSkillsKey(id)).apply()
        chatFilesRepository.deleteChat(id)
''', '''        prefs.edit().remove(chatSkillsKey(id)).apply()
        projectAutomation.deleteChat(id)
        chatFilesRepository.deleteChat(id)
''', "delete runtime with chat")

# Clear-all preserves project orchestrators.
s = one(s, '''        _state.value.chats.forEach { chat ->
            chat.stages.orEmpty()
                .flatMap { it.files.orEmpty() }
                .forEach { projectsRepository.deleteFile(it) }
            chatFilesRepository.deleteChat(chat.id)
            prefs.edit().remove(chatSkillsKey(chat.id)).apply()
        }

        val chat = ChatSession(
''', '''        val protectedOrchestrators = _state.value.chats.filter { isOrchestratorChat(it.id) }
        _state.value.chats.filterNot { isOrchestratorChat(it.id) }.forEach { chat ->
            chat.stages.orEmpty()
                .flatMap { it.files.orEmpty() }
                .forEach { projectsRepository.deleteFile(it) }
            chatFilesRepository.deleteChat(chat.id)
            projectAutomation.deleteChat(chat.id)
            prefs.edit().remove(chatSkillsKey(chat.id)).apply()
        }

        val chat = ChatSession(
''', "clear preserve orchestrators")
s = one(s, '''        chatsRepository.save(listOf(chat))
''', '''        val resetChats = listOf(chat) + protectedOrchestrators
        chatsRepository.save(resetChats)
''', "clear save orchestrators")
s = one(s, '''            chats = listOf(chat),
            currentChatId = chat.id,
''', '''            chats = resetChats,
            currentChatId = chat.id,
''', "clear state orchestrators")

# Project deletion removes its orchestrator but keeps ordinary project chats as ordinary chats.
s = between(s, '''    fun deleteProject(projectId: String) {
''', '''    fun refreshModels(mode: ChatMode) {
''', '''    fun deleteProject(projectId: String) {
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
''', "delete project orchestrator")

# Fixed reasoning remains visually enabled after model catalog refresh; actual request still checks model capability.
s = one(s, '''                val current = textInfos.firstOrNull { it.id == effectiveId }
                val effort = preferredReasoningEffort(effectiveId, current)
                val keepReasoning = reasoningStillValid(current, effort)
                prefs.edit()
                    .putString("reasoning_effort", effort.name)
                    .putBoolean("reasoning_enabled", keepReasoning)
                    .apply()
                next = next.copy(
                    textModel = selectedModel,
                    currentChatTextModel = next.currentChatTextModel?.takeIf { id -> textInfos.any { it.id == id } },
                    availableTextModels = textInfos,
                    reasoningEffort = effort,
                    reasoningEnabled = keepReasoning
                )
''', '''                val current = textInfos.firstOrNull { it.id == effectiveId }
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
''', "refresh fixed reasoning")

# Orchestrator CRUD and execution.
s = one(s, '''    fun runProjectStages(projectId: String, initialTask: String): String? {
''', r'''    fun upsertOrchestratorStep(
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
''', "orchestrator execution")

p.write_text(s)

# OpenRouter request enhancer chooses tools from the actual target chat during orchestration.
p = Path("app/src/main/java/com/ayuemin/ymnik/network/OpenRouterRequestEnhancer.kt")
s = p.read_text()
s = one(s,
'''import com.ayuemin.ymnik.data.BatchJobRepository
import com.ayuemin.ymnik.data.OpenRouterFeaturePrefs
''',
'''import com.ayuemin.ymnik.data.BatchJobRepository
import com.ayuemin.ymnik.data.ProjectAutomationRepository
import com.ayuemin.ymnik.data.OpenRouterFeaturePrefs
''', "enhancer automation import")
s = one(s, '''        val serverTools = prefs.tools()
''', '''        val execution = context.getSharedPreferences("request_execution", Context.MODE_PRIVATE)
        val requestChatId = execution.getString("target_chat_id", null) ?: execution.getString("chat_id", null)
        val serverTools = requestChatId?.let { ProjectAutomationRepository(context).profile(it)?.tools } ?: prefs.tools()
''', "enhancer per-chat tools")
p.write_text(s)

# Project UI routes project chats to the richer settings screen and protects/marks orchestrators.
p = Path("app/src/main/java/com/ayuemin/ymnik/ui/ProjectDialogs.kt")
s = p.read_text()
s = one(s,
'''    val projectChats = state.chats.filter { it.projectId == project.id }
        .sortedWith(compareByDescending<ChatSession> { it.isFavorite }.thenByDescending { it.updatedAt })
''',
'''    val projectChats = state.chats.filter { it.projectId == project.id }
        .sortedWith(compareByDescending<ChatSession> { vm.isOrchestratorChat(it.id) }.thenByDescending { it.isFavorite }.thenByDescending { it.updatedAt })
''', "orchestrator sort")
s = one(s, '''        ProjectChatSettingsDialog(
            chat = chat,
''', '''        ProjectChatAutomationDialog(
            chat = chat,
''', "advanced project chat settings")
# expose helpers used by the new file
s = s.replace("private fun SettingsExpander(", "fun SettingsExpander(", 1)
s = s.replace("private fun StageEditorDialog(", "fun StageEditorDialog(", 1)
s = s.replace("private fun StageCard(", "fun StageCard(", 1)
# visual marker and delete protection inside project row
row_start = '''@Composable
private fun ProjectChatRow(
'''
row_end = '''@Composable
private fun ProjectSettingsDialog(
'''
row_new = '''@Composable
private fun ProjectChatRow(
    chat: ChatSession,
    state: UiState,
    vm: ChatViewModel,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onSettings: () -> Unit,
    onDelete: () -> Unit
) {
    val orchestrator = vm.isOrchestratorChat(chat.id)
    var menuOpen by remember(chat.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth()) {
                Text(if (orchestrator) "◆ ${chat.title}" else chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (orchestrator) FontWeight.Bold else FontWeight.Medium)
                Text(
                    if (orchestrator) "Управление процессом · ${vm.orchestratorSteps(chat.id).size} шагов" else buildString {
                        append("${chat.messages.size} сообщ. · ${projectDate(chat.updatedAt)}")
                        if (chat.stages.orEmpty().isNotEmpty()) append(" · ${chat.stages.orEmpty().size} этапов")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Действия с чатом") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!orchestrator) DropdownMenuItem(
                    text = { Text(if (chat.isFavorite) "Убрать из избранного" else "В избранное") },
                    leadingIcon = { Icon(if (chat.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = null) },
                    onClick = { menuOpen = false; vm.setChatFavorite(chat.id, !chat.isFavorite) }
                )
                DropdownMenuItem(
                    text = { Text("Переименовать") },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = { menuOpen = false; onRename() }
                )
                DropdownMenuItem(
                    text = { Text("Настройки чата") },
                    leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    onClick = { menuOpen = false; onSettings() }
                )
                if (!orchestrator) DropdownMenuItem(
                    text = { Text("Удалить") },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                    enabled = !state.isLoading,
                    onClick = { menuOpen = false; onDelete() }
                )
            }
        }
    }
    HorizontalDivider()
}

'''
s = between(s, row_start, row_end, row_new, "project row")
p.write_text(s)

# Composer gets a quick orchestration button when the current chat is the project's orchestrator.
p = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
s = p.read_text()
s = one(s,
'''                if (projectToolsExpanded && currentProject != null) {
                    if (currentProject.stages.orEmpty().isNotEmpty()) {
''',
'''                if (projectToolsExpanded && currentProject != null) {
                    val orchestratorSteps = if (currentChat != null && vm.isOrchestratorChat(currentChat.id)) vm.orchestratorSteps(currentChat.id) else emptyList()
                    if (orchestratorSteps.isNotEmpty()) {
                        Button(
                            onClick = {
                                val chatId = vm.runOrchestrator(currentProject.id)
                                if (chatId != null) { text = ""; actionsOpen = false }
                            },
                            enabled = !state.isLoading && !state.requestActive,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Выполнить сценарий (${orchestratorSteps.size})")
                        }
                    }
                    if (currentProject.stages.orEmpty().isNotEmpty()) {
''', "composer orchestrator")
p.write_text(s)

# Fix orchestrator target list in the new settings file to exclude the protected chat.
p = Path("app/src/main/java/com/ayuemin/ymnik/ui/ProjectChatAutomationDialog.kt")
s = p.read_text()
s = one(s, '''        OrchestratorStepEditorDialog(
            project = project,
            state = state,
''', '''        OrchestratorStepEditorDialog(
            project = project,
            state = state,
            vm = vm,
''', "step editor vm arg")
s = one(s, '''private fun OrchestratorStepEditorDialog(
    project: Project,
    state: UiState,
    step: OrchestratorStep?,
''', '''private fun OrchestratorStepEditorDialog(
    project: Project,
    state: UiState,
    vm: ChatViewModel,
    step: OrchestratorStep?,
''', "step editor signature")
start = '''    val targetChats = state.chats.filter { it.projectId == project.id && !it.id.equals(targetChatId) || it.projectId == project.id }
        .filterNot { it.title.startsWith("◆") }
        .filterNot { false }
    val usableChats = targetChats.filter { it.projectId == project.id }
'''
replacement = '''    val usableChats = state.chats
        .filter { it.projectId == project.id && !vm.isOrchestratorChat(it.id) }
        .sortedByDescending { it.updatedAt }
'''
s = one(s, start, replacement, "step target chats")
p.write_text(s)

# Version and changelog
p = Path("app/build.gradle.kts")
s = p.read_text().replace("// Umnik v1.11.0", "// Umnik v1.12.0")
s = one(s, "versionCode = 110", "versionCode = 112", "version code")
s = one(s, 'versionName = "1.11.0"', 'versionName = "1.12.0"', "version name")
p.write_text(s)

p = Path("CHANGELOG.md")
s = p.read_text()
entry = '''# Changelog\n\n## v1.12.0\n\n- Закрепляемые параметры каждого чата проекта: модель, размышление, поиск, инструменты OpenRouter, вложения и навыки.\n- Проектные чаты восстанавливают собственные параметры при переключении; сохранённое размышление больше не сбрасывается из-за смены чата.\n- Каждый проект автоматически получает защищённый чат `◆ Оркестратор`, который удаляется только вместе с проектом.\n- Оркестратор поддерживает редактируемые шаги: выполнить чат, выполнить этапы выбранного чата или выполнить этапы проекта.\n- В шаг можно записать собственный промпт и передать результат предыдущего шага.\n- Целевой чат выполняется реально и сохраняет запрос и ответ в своей истории, используя собственные инструкции, модель, reasoning, поиск, инструменты, навыки и файлы.\n- В `+ → Проект` у оркестратора появилась быстрая кнопка выполнения сценария.\n\n'''
if s.startswith("# Changelog\n\n"):
    s = entry + s[len("# Changelog\n\n"):]
else:
    s = entry + s
p.write_text(s)

print("Umnik v1.12.0 updater completed")
