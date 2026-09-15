from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def transform_between(text: str, start_marker: str, end_marker: str, transform, label: str) -> str:
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f"{label}: start marker not found")
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f"{label}: end marker not found")
    chunk = text[start:end]
    updated = transform(chunk)
    if updated == chunk:
        raise SystemExit(f"{label}: transform made no changes")
    return text[:start] + updated + text[end:]


# ---------- ChatViewModel ----------
path = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
text = path.read_text()

text = replace_once(
    text,
    '    private var requestGeneration: Long = 0L\n\n    private val initialProfiles = loadConnectionProfiles()',
    '    private var requestGeneration: Long = 0L\n\n    private fun chatSkillsKey(chatId: String): String = "chat_active_skills::$chatId"\n\n    private val initialProfiles = loadConnectionProfiles()',
    "add chat skill key"
)

text = replace_once(
    text,
    '    private val initialChat = initialChats.first { it.id == initialChatId }\n    private val initialProfileId = (initialChat.connectionProfileId',
    '''    private val initialChat = initialChats.first { it.id == initialChatId }
    private val initialProjects = projectsRepository.list()
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
    private val initialProfileId = (initialChat.connectionProfileId''',
    "initialize per-chat skills"
)

text = replace_once(text, '            projects = projectsRepository.list(),', '            projects = initialProjects,', "initial projects")
text = replace_once(
    text,
    '            activeSkillIds = prefs.getStringSet("active_skills", emptySet())?.toSet() ?: emptySet(),',
    '            activeSkillIds = initialSkillIds,',
    "initial active skills"
)

text = replace_once(
    text,
    '    val state: StateFlow<UiState> = _state.asStateFlow()\n\n    init {',
    '''    val state: StateFlow<UiState> = _state.asStateFlow()

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

    init {''',
    "add per-chat skill helpers"
)

text = replace_once(
    text,
    '''    fun toggleSkill(id: String) {
        val next = _state.value.activeSkillIds.toMutableSet().apply { if (!add(id)) remove(id) }
        prefs.edit().putStringSet("active_skills", next).apply()
        _state.value = _state.value.copy(activeSkillIds = next)
    }''',
    '''    fun toggleSkill(id: String) {
        val chat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId } ?: return
        val next = _state.value.activeSkillIds.toMutableSet().apply { if (!add(id)) remove(id) }.toSet()
        prefs.edit().putStringSet(chatSkillsKey(chat.id), next).apply()
        _state.value = _state.value.copy(activeSkillIds = next)
    }''',
    "per-chat toggleSkill"
)


def patch_create_chat(chunk: str) -> str:
    chunk = replace_once(
        chunk,
        '        val keepReasoning = reasoningStillValid(info, effort)\n        chatsRepository.save(next)',
        '''        val keepReasoning = reasoningStillValid(info, effort)
        val newSkillIds = projectId
            ?.let { id -> _state.value.projects.firstOrNull { it.id == id }?.skillIds }
            .orEmpty()
        chatsRepository.save(next)''',
        "createChat skill defaults"
    )
    chunk = replace_once(
        chunk,
        '            messages = emptyList(),\n            currentChatTextModel = null,',
        '            messages = emptyList(),\n            activeSkillIds = newSkillIds,\n            currentChatTextModel = null,',
        "createChat state skills"
    )
    return chunk


text = transform_between(text, '    fun createChat(projectId: String? = null): String {', '    fun openUsageGuide(): String {', patch_create_chat, "createChat")


def patch_usage_guide(chunk: str) -> str:
    return replace_once(
        chunk,
        '            messages = messages,\n            mode = ChatMode.TEXT,',
        '            messages = messages,\n            activeSkillIds = emptySet(),\n            mode = ChatMode.TEXT,',
        "usage guide skills"
    )


text = transform_between(text, '    fun openUsageGuide(): String {', '    fun branchFromMessage(messageId: String): String? {', patch_usage_guide, "openUsageGuide")


def patch_branch(chunk: str) -> str:
    chunk = replace_once(
        chunk,
        '        val keepReasoning = reasoningStillValid(info, effort)\n\n        chatsRepository.save(chats)',
        '        val keepReasoning = reasoningStillValid(info, effort)\n        val branchSkillIds = _state.value.activeSkillIds\n\n        chatsRepository.save(chats)',
        "branch skills snapshot"
    )
    chunk = replace_once(
        chunk,
        '            .putString("current_chat_id", branch.id)\n            .putString("chat_mode", ChatMode.TEXT.name)',
        '            .putString("current_chat_id", branch.id)\n            .putStringSet(chatSkillsKey(branch.id), branchSkillIds)\n            .putString("chat_mode", ChatMode.TEXT.name)',
        "branch persist skills"
    )
    chunk = replace_once(
        chunk,
        '            messages = branchedMessages,\n            mode = ChatMode.TEXT,',
        '            messages = branchedMessages,\n            activeSkillIds = branchSkillIds,\n            mode = ChatMode.TEXT,',
        "branch state skills"
    )
    return chunk


text = transform_between(text, '    fun branchFromMessage(messageId: String): String? {', '    fun switchChat(id: String) {', patch_branch, "branchFromMessage")


def patch_switch_chat(chunk: str) -> str:
    chunk = replace_once(
        chunk,
        '        val effort = preferredReasoningEffort(modelId, info)\n        prefs.edit()',
        '        val effort = preferredReasoningEffort(modelId, info)\n        val chatSkillIds = skillIdsForChat(chat)\n        prefs.edit()',
        "switchChat load skills"
    )
    chunk = replace_once(
        chunk,
        '            messages = chat.messages,\n            mode = nextMode,',
        '            messages = chat.messages,\n            activeSkillIds = chatSkillIds,\n            mode = nextMode,',
        "switchChat state skills"
    )
    return chunk


text = transform_between(text, '    fun switchChat(id: String) {', '    fun refreshAsyncResults() {', patch_switch_chat, "switchChat")


def patch_delete_chat(chunk: str) -> str:
    return replace_once(
        chunk,
        '        if (_state.value.chats.none { it.id == id }) return\n\n        chatFilesRepository.deleteChat(id)',
        '        if (_state.value.chats.none { it.id == id }) return\n\n        prefs.edit().remove(chatSkillsKey(id)).apply()\n        chatFilesRepository.deleteChat(id)',
        "delete chat skill prefs"
    )


text = transform_between(text, '    fun deleteChat(id: String) {', '    fun clearAllChats() {', patch_delete_chat, "deleteChat")


def patch_clear_all(chunk: str) -> str:
    chunk = replace_once(
        chunk,
        '''        _state.value.chats.forEach { chat ->
            chatFilesRepository.deleteChat(chat.id)
        }''',
        '''        _state.value.chats.forEach { chat ->
            chatFilesRepository.deleteChat(chat.id)
            prefs.edit().remove(chatSkillsKey(chat.id)).apply()
        }''',
        "clear all skill prefs"
    )
    chunk = replace_once(
        chunk,
        '            messages = emptyList(),\n            mode = ChatMode.TEXT,',
        '            messages = emptyList(),\n            activeSkillIds = emptySet(),\n            mode = ChatMode.TEXT,',
        "clear all state skills"
    )
    return chunk


text = transform_between(text, '    fun clearAllChats() {', '    fun updateChatProfile(', patch_clear_all, "clearAllChats")

start = text.index('    fun toggleProjectSkill(projectId: String, skillId: String) {')
end = text.index('    fun addProjectFile(projectId: String, uri: Uri) {', start)
text = text[:start] + '''    fun toggleProjectSkill(projectId: String, skillId: String) {
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

''' + text[end:]

text = replace_once(
    text,
    '                        val skillIds = _state.value.activeSkillIds + (currentProject?.skillIds ?: emptySet())',
    '                        val skillIds = _state.value.activeSkillIds',
    "ordinary request per-chat skills"
)

stage_start = text.index('    fun runProjectStages(projectId: String, initialTask: String): String? {')
stage_end = text.index('    private fun appendProjectStageMessage(', stage_start)
new_stage_runner = r'''    fun runProjectStages(projectId: String, initialTask: String): String? {
        if (_state.value.isLoading || _state.value.requestActive || projectStagesJob != null) return null
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return null
        val stages = project.stages.orEmpty().filter { it.instruction.isNotBlank() }
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
        val chatId = if (activeChat?.projectId == projectId) activeChat.id else createChat(projectId)
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
            text = "Запустить этапы проекта.\n\nИсходная задача:\n$startText",
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
        DiagnosticLog.action(context, "project_stages_start", "project=${projectId.take(8)}; chat=${chatId.take(8)}; stages=${stages.size}; continued=${activeChat?.id == chatId}")

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

                    val projectAttachments = project.files.map { file ->
                        PendingAttachment(
                            uri = "project://${file.id}",
                            name = file.name,
                            mimeType = file.mimeType,
                            size = file.size,
                            localPath = file.localPath
                        )
                    }
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
                    val attachments = (projectAttachments + chatAttachments)
                        .filter(::allowedForStage)
                        .distinctBy { it.localPath ?: it.uri }
                    val skillsText = skills.promptFor(currentState.activeSkillIds)
                    val systemPrompt = buildSystemPrompt(skillsText, project, stageChat, modelInfo?.supportsTools == true)
                    val prompt = buildString {
                        appendLine("Выполни только текущий этап универсального сценария проекта. Не переходи к следующим этапам сам.")
                        appendLine()
                        appendLine("===== ИСХОДНАЯ ЗАДАЧА =====")
                        appendLine(startText)
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
                    DiagnosticLog.record(context, "PROJECT_STAGE", "start project=${projectId.take(8)}; stage=${index + 1}/${stages.size}; model=$modelId; history=${baseHistory.size}; chatFiles=${chatAttachments.size}")

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
                        DiagnosticLog.record(context, "PROJECT_STAGE", "failed project=${projectId.take(8)}; stage=${index + 1}; model=$modelId", error)
                        _state.value = _state.value.copy(status = "Этап ${index + 1} остановлен: $friendly")
                        return@launch
                    }

                    val stageText = ProjectOutputPolicy.apply(result.text, project.masterPrompt).ifBlank { "Готово." }
                    results += stage to stageText
                    appendProjectStageMessage(chatId, index + 1, stage.title, stageText, result.modelId ?: modelId, result)
                    DiagnosticLog.record(context, "PROJECT_STAGE", "success project=${projectId.take(8)}; stage=${index + 1}/${stages.size}; model=${result.modelId ?: modelId}; chars=${stageText.length}")
                }
                if (generation == requestGeneration) {
                    _state.value = _state.value.copy(status = "Все этапы проекта выполнены: ${stages.size}")
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

'''
text = text[:stage_start] + new_stage_runner + text[stage_end:]
path.write_text(text)


# ---------- YmnikApp ----------
path = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
text = path.read_text()

text = replace_once(
    text,
    '    var actionsOpen by remember { mutableStateOf(false) }\n    var imagePromptMode by remember(state.currentChatId) { mutableStateOf(false) }',
    '''    var actionsOpen by remember { mutableStateOf(false) }
    var openRouterToolsExpanded by remember { mutableStateOf(false) }
    var skillsProjectsExpanded by remember { mutableStateOf(false) }
    var imagePromptMode by remember(state.currentChatId) { mutableStateOf(false) }''',
    "composer section state"
)

text = replace_once(
    text,
    '''    val currentChat = state.chats.firstOrNull { it.id == state.currentChatId }
    val currentChatFiles = currentChat?.chatFiles.orEmpty()
    val currentProjectSkillIds = currentChat?.projectId
        ?.let { projectId -> state.projects.firstOrNull { it.id == projectId }?.skillIds }
        .orEmpty()
    val activeSkillCount = (state.activeSkillIds + currentProjectSkillIds).size''',
    '''    val currentChat = state.chats.firstOrNull { it.id == state.currentChatId }
    val currentChatFiles = currentChat?.chatFiles.orEmpty()
    val currentProject = currentChat?.projectId
        ?.let { projectId -> state.projects.firstOrNull { it.id == projectId } }
    val activeSkillCount = state.activeSkillIds.size''',
    "chat project and skill count"
)

actions_start = text.index('    if (actionsOpen) {')
actions_end = text.index('    if (projectsOpen) {', actions_start)
new_actions = r'''    if (actionsOpen) {
        ModalBottomSheet(onDismissRequest = { actionsOpen = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Добавить", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ComposerActionTile(
                        icon = Icons.Outlined.AttachFile,
                        label = "Вставить",
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            actionsOpen = false
                            val types = if (imagePromptMode) arrayOf("image/*") else arrayOf("*/*")
                            attach.launch(types)
                        }
                    )
                    ComposerActionTile(
                        icon = Icons.Outlined.CameraAlt,
                        label = "Быстрое фото",
                        enabled = !state.isLoading && cameraAvailable,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            actionsOpen = false
                            runCatching { createCameraTarget(context) }
                                .onSuccess { target ->
                                    cameraForImageGeneration = imagePromptMode
                                    cameraTarget = target
                                    camera.launch(target.uri)
                                }
                                .onFailure {
                                    Toast.makeText(context, it.message ?: "Не удалось открыть камеру", Toast.LENGTH_SHORT).show()
                                }
                        }
                    )
                    ComposerActionTile(
                        icon = Icons.Outlined.Image,
                        label = "Создать изображение",
                        enabled = !state.isLoading && imageConnectionAvailable && state.imageModel.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            actionsOpen = false
                            if (vm.prepareImageGeneration()) imagePromptMode = true
                        }
                    )
                }

                if (!imagePromptMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ComposerToggleTile(
                            icon = Icons.Outlined.Psychology,
                            label = "Размышление",
                            checked = state.reasoningEnabled,
                            enabled = reasoningAvailable,
                            modifier = Modifier.weight(1f),
                            onCheckedChange = vm::setReasoningEnabled
                        )
                        ComposerToggleTile(
                            icon = Icons.Outlined.Language,
                            label = "Веб-поиск",
                            checked = state.webSearchEnabled,
                            enabled = openRouterProfile,
                            modifier = Modifier.weight(1f),
                            onCheckedChange = vm::setWebSearchEnabled
                        )
                    }
                }

                ComposerSectionHeader(
                    icon = Icons.Outlined.Storage,
                    label = "Инструменты OpenRouter",
                    expanded = openRouterToolsExpanded,
                    onClick = { openRouterToolsExpanded = !openRouterToolsExpanded }
                )
                if (openRouterToolsExpanded) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CompactComposerTool(Icons.Outlined.Mic, "В текст", !state.isLoading, Modifier.weight(1f)) {
                            actionsOpen = false
                            com.ayuemin.ymnik.AsyncJobEvents.requestHub("stt")
                        }
                        CompactComposerTool(Icons.Outlined.VolumeUp, "Озвучить", !state.isLoading, Modifier.weight(1f)) {
                            actionsOpen = false
                            com.ayuemin.ymnik.AsyncJobEvents.requestHub("speech")
                        }
                        CompactComposerTool(Icons.Outlined.Image, "Видео", !state.isLoading, Modifier.weight(1f)) {
                            actionsOpen = false
                            com.ayuemin.ymnik.AsyncJobEvents.requestHub("video")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CompactComposerTool(Icons.Outlined.Description, "Пакет задач", !state.isLoading, Modifier.weight(1f)) {
                            actionsOpen = false
                            com.ayuemin.ymnik.AsyncJobEvents.requestHub("jobs")
                        }
                        CompactComposerTool(Icons.Outlined.Storage, "Shell", !state.isLoading, Modifier.weight(1f)) {
                            actionsOpen = false
                            com.ayuemin.ymnik.AsyncJobEvents.requestHub("shell")
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }

                ComposerSectionHeader(
                    icon = Icons.Outlined.FolderOpen,
                    label = "Навыки и проекты",
                    expanded = skillsProjectsExpanded,
                    onClick = { skillsProjectsExpanded = !skillsProjectsExpanded }
                )
                if (skillsProjectsExpanded) {
                    if (currentProject != null) {
                        Text("Проект: ${currentProject.name}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (currentProject.stages.orEmpty().isNotEmpty()) {
                            FilledTonalButton(
                                onClick = {
                                    val chatId = vm.runProjectStages(currentProject.id, text)
                                    if (chatId != null) {
                                        text = ""
                                        actionsOpen = false
                                    }
                                },
                                enabled = !state.isLoading && !state.requestActive,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Запустить этапы (${currentProject.stages.orEmpty().size})")
                            }
                        } else {
                            Text("В проекте пока нет этапов работы.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            onClick = {
                                selectedProjectId = currentProject.id
                                projectsOpen = true
                                actionsOpen = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Настройки проекта")
                        }
                    } else {
                        Text(
                            "Этот чат не входит в проект. Навыки можно использовать и без проекта.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                selectedProjectId = null
                                projectsOpen = true
                                actionsOpen = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Открыть проекты")
                        }
                    }

                    Text("Навыки текущего чата", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (state.skills.isEmpty()) {
                        Text("Навыков пока нет.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(state.skills, key = { it.id }) { skill ->
                                val selected = skill.id in state.activeSkillIds
                                FilterChip(
                                    selected = selected,
                                    onClick = { vm.toggleSkill(skill.id) },
                                    label = { Text(skill.name, maxLines = 1) },
                                    leadingIcon = {
                                        Icon(
                                            if (selected) Icons.Outlined.Check else Icons.Outlined.Extension,
                                            contentDescription = if (selected) "Навык включён" else null,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = {
                            actionsOpen = false
                            onOpenSkills()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Управление навыками")
                    }
                }
            }
        }
    }

'''
text = text[:actions_start] + new_actions + text[actions_end:]

insert_marker = '\n@Composable\nprivate fun RecordingStatusBar(seconds: Int, onCancel: () -> Unit) {'
helper_code = r'''

@Composable
private fun ComposerToggleTile(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
            Spacer(Modifier.width(7.dp))
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun ComposerSectionHeader(
    icon: ImageVector,
    label: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(9.dp))
        Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
        Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, contentDescription = if (expanded) "Свернуть" else "Развернуть", modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun CompactComposerTool(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
'''
text = replace_once(text, insert_marker, helper_code + insert_marker, "insert composer helpers")
path.write_text(text)


# ---------- ProjectDialogs ----------
path = Path("app/src/main/java/com/ayuemin/ymnik/ui/ProjectDialogs.kt")
text = path.read_text()
old_header = r'''            item {
                TextButton(
                    onClick = { stagesExpanded = !stagesExpanded },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Этапы работы", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "${project.stages.orEmpty().size} этапов · выполняются строго по порядку",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        if (stagesExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (stagesExpanded) "Свернуть" else "Развернуть"
                    )
                }
            }'''
new_header = r'''            item {
                ElevatedCard(
                    onClick = { stagesExpanded = !stagesExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Этапы работы", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text(
                                "${project.stages.orEmpty().size} этапов · выполняются строго по порядку",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            if (stagesExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = if (stagesExpanded) "Свернуть" else "Развернуть"
                        )
                    }
                }
            }'''
text = replace_once(text, old_header, new_header, "project stage header")
text = replace_once(
    text,
    '"Последовательный сценарий для любых задач. Каждый этап — отдельный запрос. Следующий получает исходную задачу, материалы проекта и результаты предыдущих этапов. Мастер-инструкция действует на каждом шаге."',
    '"Последовательный сценарий для любых задач. При запуске из чата каждый этап получает переписку и вложения этого чата, материалы проекта и результаты предыдущих этапов. Мастер-инструкция действует на каждом шаге."',
    "project stage description"
)
old_launch = r'''                item {
                    Button(
                        onClick = { runStagesOpen = true },
                        enabled = project.stages.orEmpty().isNotEmpty() && !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Запустить этапы")
                    }
                }'''
new_launch = r'''                item {
                    Text(
                        "Запуск этапов перенесён в нужный чат проекта: нажмите + → «Навыки и проекты». Так этапы видят именно переписку и вложения этого чата.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }'''
text = replace_once(text, old_launch, new_launch, "move stage launch to chat")
path.write_text(text)


# ---------- NavigationSidebar ----------
path = Path("app/src/main/java/com/ayuemin/ymnik/ui/NavigationSidebar.kt")
text = path.read_text()
start = text.index('                    Box(Modifier.weight(1f)) {\n                        TextButton(\n                            onClick = { menuOpen = true },')
end_marker = '                    }\n                }\n            }\n        }\n    }\n\n    deleteTarget?.let { chat ->'
end = text.index(end_marker, start)
replacement = r'''                    TextButton(
                        onClick = {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onOpenSettings()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Настройки", maxLines = 1)
                    }
'''
text = text[:start] + replacement + text[end:]
text = text.replace('    var menuOpen by remember { mutableStateOf(false) }\n', '', 1)
path.write_text(text)


# ---------- Version + changelog ----------
path = Path("app/build.gradle.kts")
text = path.read_text()
text = replace_once(text, '// Umnik v1.10.7', '// Umnik v1.10.8', "version comment")
text = replace_once(text, 'versionCode = 107', 'versionCode = 108', "version code")
text = replace_once(text, 'versionName = "1.10.7"', 'versionName = "1.10.8"', "version name")
path.write_text(text)

path = Path("CHANGELOG.md")
text = path.read_text()
entry = '''## v1.10.8 - 2026-09-15

- Этапы проекта теперь запускаются из текущего чата проекта и используют его историю, вложения, постоянные файлы проекта и результаты предыдущих этапов. Запуск больше не отрывает работу в отдельный пустой чат.
- Меню `+` переработано: сверху оставлены три быстрых действия, ниже на одной линии размещены переключатели «Размышление» и «Веб-поиск», инструменты OpenRouter и блок «Навыки и проекты» стали компактными раскрывающимися секциями.
- Навыки теперь выбираются отдельно для каждого чата. Навыки проекта служат стартовым набором, но в конкретном чате их можно включать и выключать независимо.
- Запуск этапов перенесён из настроек проекта в `+ → Навыки и проекты`, чтобы сценарий всегда работал с нужным контекстом чата.
- Блок «Этапы работы» в настройках проекта получил нормальные внутренние отступы и больше не обрезает подпись.
- Нижнее «Меню» в боковой панели заменено прямой кнопкой «Настройки»; управление навыками и проектами доступно из рабочего меню `+`.
- Версия: 1.10.8 / versionCode 108.

'''
text = replace_once(text, '## Unreleased\n\n', '## Unreleased\n\n' + entry, "changelog entry")
path.write_text(text)
