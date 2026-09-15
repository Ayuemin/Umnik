from pathlib import Path
import re

ROOT = Path('.')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 occurrence, got {count}')
    return text.replace(old, new, 1)


def sub_once(text: str, pattern: str, repl: str, label: str) -> str:
    out, count = re.subn(pattern, repl, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, got {count}')
    return out


# -----------------------------------------------------------------------------
# Models.kt
# -----------------------------------------------------------------------------
path = ROOT / 'app/src/main/java/com/ayuemin/ymnik/model/Models.kt'
s = path.read_text()
s = replace_once(
    s,
    '''data class ProjectStage(\n    val id: String,\n    val title: String,\n    val instruction: String,\n    val modelId: String? = null\n)''',
    '''data class ProjectStage(\n    val id: String,\n    val title: String,\n    val instruction: String,\n    val modelId: String? = null,\n    // Nullable collections keep old Gson data fully backward-compatible.\n    val files: List<ProjectFile>? = null,\n    val sourceChatIds: Set<String>? = null\n)''',
    'ProjectStage model'
)
s = replace_once(
    s,
    '''    val assignedRole: String? = null,\n    val masterPrompt: String? = null,\n    val createdAt: Long = System.currentTimeMillis(),''',
    '''    val assignedRole: String? = null,\n    val masterPrompt: String? = null,\n    // Project chats may define their own stage sequence in addition to project stages.\n    val stages: List<ProjectStage>? = null,\n    val createdAt: Long = System.currentTimeMillis(),''',
    'ChatSession stages'
)
path.write_text(s)


# -----------------------------------------------------------------------------
# ChatViewModel.kt
# -----------------------------------------------------------------------------
path = ROOT / 'app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt'
s = path.read_text()

# Clean chat-stage files when a chat is removed.
s = replace_once(
    s,
    '''        if (_state.value.chats.none { it.id == id }) return\n\n        prefs.edit().remove(chatSkillsKey(id)).apply()\n        chatFilesRepository.deleteChat(id)''',
    '''        val deletingChat = _state.value.chats.firstOrNull { it.id == id } ?: return\n\n        deletingChat.stages.orEmpty()\n            .flatMap { it.files.orEmpty() }\n            .forEach { projectsRepository.deleteFile(it) }\n        prefs.edit().remove(chatSkillsKey(id)).apply()\n        chatFilesRepository.deleteChat(id)''',
    'deleteChat stage cleanup'
)
s = replace_once(
    s,
    '''        _state.value.chats.forEach { chat ->\n            chatFilesRepository.deleteChat(chat.id)\n            prefs.edit().remove(chatSkillsKey(chat.id)).apply()\n        }''',
    '''        _state.value.chats.forEach { chat ->\n            chat.stages.orEmpty()\n                .flatMap { it.files.orEmpty() }\n                .forEach { projectsRepository.deleteFile(it) }\n            chatFilesRepository.deleteChat(chat.id)\n            prefs.edit().remove(chatSkillsKey(chat.id)).apply()\n        }''',
    'clearAllChats stage cleanup'
)

stage_management = r'''    fun upsertProjectStage(
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

'''
s = sub_once(
    s,
    r'    fun upsertProjectStage\([\s\S]*?\n    fun runProjectStages\(',
    stage_management + '    fun runProjectStages(',
    'stage management block'
)

run_stages = r'''    fun runProjectStages(projectId: String, initialTask: String): String? {
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

'''
s = sub_once(
    s,
    r'    fun runProjectStages\([\s\S]*?\n    private fun appendProjectStageMessage\(',
    run_stages + '    private fun appendProjectStageMessage(',
    'run stage sequence'
)

# When a project is removed, its chat-stage files disappear with the project directory;
# clear the now-invalid stage definitions from chats that become ordinary chats.
s = replace_once(
    s,
    '''        val chats = _state.value.chats.map { chat ->\n            if (chat.projectId == projectId) chat.copy(projectId = null) else chat\n        }''',
    '''        val chats = _state.value.chats.map { chat ->\n            if (chat.projectId == projectId) chat.copy(projectId = null, stages = null) else chat\n        }''',
    'deleteProject clears chat stages'
)
path.write_text(s)


# -----------------------------------------------------------------------------
# YmnikApp.kt
# -----------------------------------------------------------------------------
path = ROOT / 'app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt'
s = path.read_text()
s = replace_once(s, 'import androidx.compose.foundation.horizontalScroll\n', 'import androidx.compose.foundation.horizontalScroll\nimport androidx.compose.foundation.verticalScroll\n', 'verticalScroll import')
s = replace_once(s, 'import com.ayuemin.ymnik.model.ReasoningEffort\n', 'import com.ayuemin.ymnik.model.ReasoningEffort\nimport com.ayuemin.ymnik.model.Skill\n', 'Skill import')
s = replace_once(
    s,
    '''    var skillsExpanded by remember { mutableStateOf(false) }\n    var projectToolsExpanded by remember { mutableStateOf(false) }''',
    '''    var skillsExpanded by remember { mutableStateOf(false) }\n    var projectToolsExpanded by remember { mutableStateOf(false) }\n    var projectSkillsExpanded by remember { mutableStateOf(false) }''',
    'composer project skills state'
)
s = replace_once(
    s,
    '''    val activeSkillCount = state.activeSkillIds.size\n''',
    '''    val activeSkillCount = state.activeSkillIds.size\n    val projectAvailableSkills = currentProject?.let { project ->\n        state.skills.filter { it.id in project.skillIds }\n    }.orEmpty()\n    val activeProjectSkillCount = projectAvailableSkills.count { it.id in state.activeSkillIds }\n''',
    'project available skills'
)
s = replace_once(
    s,
    '''      onNewChat = {\n          val projectId = state.chats.firstOrNull { it.id == state.currentChatId }?.projectId\n          vm.createChat(projectId)\n          sidebarOpen = false\n      },''',
    '''      onNewChat = {\n          vm.createChat()\n          sidebarOpen = false\n      },''',
    'sidebar ordinary new chat'
)

old_skills_block = r'''                if (skillsExpanded) {
                    Text(
                        "Навыки действуют только в текущем чате. Их можно включать и выключать независимо от проекта.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.skills.isEmpty()) {
                        Text("Навыков пока нет. Добавьте их в Настройки → Навыки.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }
'''
new_skills_block = r'''                if (skillsExpanded) {
                    Text(
                        "Выберите навыки для текущего чата. Включённые навыки добавляются к следующим запросам этого чата.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.skills.isEmpty()) {
                        Text("Навыков пока нет. Добавьте их в Настройки → Навыки.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        ComposerSkillList(
                            skills = state.skills,
                            selectedIds = state.activeSkillIds,
                            onToggle = vm::toggleSkill
                        )
                    }
                }
'''
s = replace_once(s, old_skills_block, new_skills_block, 'composer general skills')

old_project_block = r'''                if (projectToolsExpanded && currentProject != null) {
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
                        Text("В проекте пока нет этапов работы. Добавьте их в настройках проекта.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Text(
                        if (activeSkillCount > 0) "Навыки текущего чата · $activeSkillCount" else "Навыки текущего чата",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (state.skills.isEmpty()) {
                        Text("Навыков пока нет. Добавьте их в Настройки → Навыки.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }
'''
new_project_block = r'''                if (projectToolsExpanded && currentProject != null) {
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
                            Text("Запустить этапы проекта (${currentProject.stages.orEmpty().size})")
                        }
                    }

                    if (currentChat?.stages.orEmpty().isNotEmpty()) {
                        FilledTonalButton(
                            onClick = {
                                val chatId = vm.runCurrentChatStages(text)
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
                            Text("Запустить этапы чата (${currentChat?.stages.orEmpty().size})")
                        }
                    }

                    if (currentProject.stages.orEmpty().isEmpty() && currentChat?.stages.orEmpty().isEmpty()) {
                        Text(
                            "Этапы пока не настроены. Их можно добавить в настройках проекта или этого чата.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (projectAvailableSkills.isEmpty()) {
                        Text(
                            "В настройках проекта навыки не выбраны.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        ComposerSectionHeader(
                            icon = Icons.Outlined.Extension,
                            label = if (activeProjectSkillCount > 0) "Подключить навыки · $activeProjectSkillCount" else "Подключить навыки",
                            expanded = projectSkillsExpanded,
                            onClick = { projectSkillsExpanded = !projectSkillsExpanded }
                        )
                        if (projectSkillsExpanded) {
                            ComposerSkillList(
                                skills = projectAvailableSkills,
                                selectedIds = state.activeSkillIds,
                                onToggle = vm::toggleSkill
                            )
                        }
                    }
                }
'''
s = replace_once(s, old_project_block, new_project_block, 'composer project tools')

# Add reusable vertical skill list after CompactComposerTool.
needle = r'''@Composable
private fun RecordingStatusBar(seconds: Int, onCancel: () -> Unit) {'''
helper = r'''@Composable
private fun ComposerSkillList(
    skills: List<Skill>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        skills.forEach { skill ->
            val selected = skill.id in selectedIds
            FilterChip(
                selected = selected,
                onClick = { onToggle(skill.id) },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        skill.name,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                leadingIcon = {
                    Icon(
                        if (selected) Icons.Outlined.Check else Icons.Outlined.Extension,
                        contentDescription = if (selected) "Навык включён" else null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun RecordingStatusBar(seconds: Int, onCancel: () -> Unit) {'''
s = replace_once(s, needle, helper, 'ComposerSkillList helper')
path.write_text(s)


# -----------------------------------------------------------------------------
# NavigationSidebar.kt
# -----------------------------------------------------------------------------
path = ROOT / 'app/src/main/java/com/ayuemin/ymnik/ui/NavigationSidebar.kt'
s = path.read_text()
# Insert ordinary-new-chat action immediately before favorites/history.
anchor = '''                    if (favoriteChats.isNotEmpty()) {\n'''
insert = '''                    item {\n                        HorizontalDivider(Modifier.padding(vertical = 8.dp))\n                        FilledTonalButton(\n                            onClick = {\n                                focusManager.clearFocus(force = true)\n                                keyboardController?.hide()\n                                onNewChat()\n                            },\n                            enabled = !state.isLoading,\n                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)\n                        ) {\n                            Icon(Icons.Outlined.AddComment, contentDescription = null, modifier = Modifier.size(20.dp))\n                            Spacer(Modifier.width(7.dp))\n                            Text("Новый чат")\n                        }\n                    }\n\n                    if (favoriteChats.isNotEmpty()) {\n'''
s = replace_once(s, anchor, insert, 'new chat above history')
# Replace bottom two-button row with Settings only.
s = sub_once(
    s,
    r'''                HorizontalDivider\(\)\n                Row\([\s\S]*?\n                \}\n            \}\n        \}\n    \}\n\n    deleteTarget''',
    '''                HorizontalDivider()\n                TextButton(\n                    onClick = {\n                        focusManager.clearFocus(force = true)\n                        keyboardController?.hide()\n                        onOpenSettings()\n                    },\n                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)\n                ) {\n                    Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(21.dp))\n                    Spacer(Modifier.width(7.dp))\n                    Text("Настройки", maxLines = 1)\n                }\n            }\n        }\n    }\n\n    deleteTarget''',
    'sidebar bottom settings only'
)
path.write_text(s)


# -----------------------------------------------------------------------------
# ProjectDialogs.kt: replace project-related half with the v1.11 workflow UI.
# -----------------------------------------------------------------------------
path = ROOT / 'app/src/main/java/com/ayuemin/ymnik/ui/ProjectDialogs.kt'
s = path.read_text()
s = replace_once(s, 'import androidx.compose.material.icons.outlined.FolderOpen\n', 'import androidx.compose.material.icons.outlined.FolderOpen\nimport androidx.compose.material.icons.outlined.MoreVert\nimport androidx.compose.material.icons.outlined.Settings\n', 'project dialog icons')
s = replace_once(s, 'import androidx.compose.material3.Button\n', 'import androidx.compose.material3.Button\nimport androidx.compose.material3.Checkbox\n', 'project checkbox import')
s = replace_once(s, 'import com.ayuemin.ymnik.model.Project\n', 'import com.ayuemin.ymnik.model.Project\nimport com.ayuemin.ymnik.model.ProjectFile\n', 'ProjectFile import')

project_section = r'''@Composable
fun ProjectsDialog(
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    initialProjectId: String? = null,
    startCreate: Boolean = false
) {
    var openProjectId by remember(initialProjectId) { mutableStateOf(initialProjectId) }
    var createOpen by remember(startCreate) { mutableStateOf(startCreate) }
    val projects = state.projects.sortedWith(compareByDescending<Project> { it.isFavorite }.thenByDescending { it.updatedAt })
    val favorites = projects.filter { it.isFavorite }
    val others = projects.filterNot { it.isFavorite }

    FullScreenPanel(title = "Проекты", onBack = onDismiss) {
        FilledTonalButton(
            onClick = { createOpen = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Новый проект")
        }

        if (projects.isEmpty()) {
            Text(
                "Проект объединяет инструкции, постоянные материалы, рабочие чаты и последовательности этапов.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                if (favorites.isNotEmpty()) {
                    item { SectionTitle("Избранные") }
                    items(favorites, key = { it.id }) { project ->
                        ProjectRow(project, state, vm) { openProjectId = project.id }
                    }
                }
                if (others.isNotEmpty()) {
                    item { SectionTitle(if (favorites.isEmpty()) "Все проекты" else "Остальные") }
                    items(others, key = { it.id }) { project ->
                        ProjectRow(project, state, vm) { openProjectId = project.id }
                    }
                }
            }
        }
    }

    if (createOpen) {
        ProjectEditorDialog(project = null, onDismiss = { createOpen = false }) { name, role, prompt, favorite ->
            openProjectId = vm.createProject(name, role, prompt, favorite)
            createOpen = false
        }
    }

    state.projects.firstOrNull { it.id == openProjectId }?.let { project ->
        ProjectDetailDialog(
            project = project,
            state = state,
            vm = vm,
            onDismiss = { openProjectId = null },
            onOpenChat = { chatId ->
                vm.switchChat(chatId)
                openProjectId = null
                onDismiss()
            },
            onCreateChat = {
                vm.createChat(project.id)
                openProjectId = null
                onDismiss()
            }
        )
    }
}

@Composable
private fun ProjectRow(project: Project, state: UiState, vm: ChatViewModel, onOpen: () -> Unit) {
    val count = state.chats.count { it.projectId == project.id }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = onOpen,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 11.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(project.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$count чатов · ${project.files.size} постоянных файлов · ${project.stages.orEmpty().size} этапов",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = { vm.setProjectFavorite(project.id, !project.isFavorite) }) {
            Icon(
                if (project.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                contentDescription = if (project.isFavorite) "Убрать из избранного" else "В избранное"
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun ProjectDetailDialog(
    project: Project,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onOpenChat: (String) -> Unit,
    onCreateChat: () -> Unit
) {
    var settingsOpen by remember(project.id) { mutableStateOf(false) }
    var deleteChatTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameTarget by remember { mutableStateOf<ChatSession?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var chatSettingsId by remember { mutableStateOf<String?>(null) }
    val projectChats = state.chats.filter { it.projectId == project.id }
        .sortedWith(compareByDescending<ChatSession> { it.isFavorite }.thenByDescending { it.updatedAt })

    FullScreenPanel(title = project.name, onBack = onDismiss) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onCreateChat, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Новый чат")
                    }
                    FilledTonalButton(onClick = { settingsOpen = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Настройки")
                    }
                }
            }

            item { SectionTitle("Чаты проекта") }
            if (projectChats.isEmpty()) {
                item {
                    Text(
                        "Пока нет чатов. Создайте первый чат проекта кнопкой выше.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(projectChats, key = { it.id }) { chat ->
                    ProjectChatRow(
                        chat = chat,
                        state = state,
                        vm = vm,
                        onOpen = { onOpenChat(chat.id) },
                        onRename = {
                            renameTarget = chat
                            renameValue = chat.title
                        },
                        onSettings = { chatSettingsId = chat.id },
                        onDelete = { deleteChatTarget = chat }
                    )
                }
            }
        }
    }

    renameTarget?.let { chat ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Переименовать чат") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.updateChatProfile(chat.id, renameValue, chat.assignedRole.orEmpty(), chat.masterPrompt.orEmpty())
                        renameTarget = null
                    },
                    enabled = renameValue.isNotBlank()
                ) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Отмена") } }
        )
    }

    deleteChatTarget?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleteChatTarget = null },
            title = { Text("Удалить чат из проекта?") },
            text = { Text("«${chat.title}» будет удалён. Сгенерированные файлы останутся в хранилище Umnik.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteChat(chat.id)
                    deleteChatTarget = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteChatTarget = null }) { Text("Отмена") } }
        )
    }

    state.chats.firstOrNull { it.id == chatSettingsId }?.let { chat ->
        ProjectChatSettingsDialog(
            chat = chat,
            project = project,
            state = state,
            vm = vm,
            onDismiss = { chatSettingsId = null }
        )
    }

    if (settingsOpen) {
        ProjectSettingsDialog(
            project = project,
            state = state,
            vm = vm,
            onDismiss = { settingsOpen = false },
            onProjectDeleted = {
                settingsOpen = false
                onDismiss()
            }
        )
    }
}

@Composable
private fun ProjectChatRow(
    chat: ChatSession,
    state: UiState,
    vm: ChatViewModel,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onSettings: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember(chat.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth()) {
                Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    buildString {
                        append("${chat.messages.size} сообщ. · ${projectDate(chat.updatedAt)}")
                        if (chat.stages.orEmpty().isNotEmpty()) append(" · ${chat.stages.orEmpty().size} этапов")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Действия с чатом")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (chat.isFavorite) "Убрать из избранного" else "В избранное") },
                    leadingIcon = { Icon(if (chat.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        vm.setChatFavorite(chat.id, !chat.isFavorite)
                    }
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
                DropdownMenuItem(
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

@Composable
private fun ProjectSettingsDialog(
    project: Project,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
    onProjectDeleted: () -> Unit
) {
    var name by remember(project.id, project.name) { mutableStateOf(project.name) }
    var role by remember(project.id, project.role) { mutableStateOf(project.role) }
    var prompt by remember(project.id, project.masterPrompt) { mutableStateOf(project.masterPrompt) }
    var favorite by remember(project.id, project.isFavorite) { mutableStateOf(project.isFavorite) }
    var stagesExpanded by remember(project.id) { mutableStateOf(true) }
    var skillsExpanded by remember(project.id) { mutableStateOf(false) }
    var filesExpanded by remember(project.id) { mutableStateOf(false) }
    var stageEditorOpen by remember { mutableStateOf(false) }
    var editingStage by remember { mutableStateOf<ProjectStage?>(null) }
    var deleteConfirm by remember { mutableStateOf(false) }

    val addFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { vm.addProjectFile(project.id, it) }
    }

    FullScreenPanel(title = "Настройки проекта", onBack = onDismiss) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true)
            }
            item {
                OutlinedTextField(
                    role,
                    { role = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Роль") },
                    placeholder = { Text("Например: главный редактор IT-канала") }
                )
            }
            item {
                OutlinedTextField(
                    prompt,
                    { prompt = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Мастер-промпт") },
                    placeholder = { Text("Эта инструкция автоматически добавляется ко всем чатам проекта") },
                    minLines = 6,
                    maxLines = 16
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Избранное", Modifier.weight(1f))
                    Switch(favorite, { favorite = it })
                }
            }
            item {
                FilledTonalButton(
                    onClick = { vm.updateProject(project.id, name, role, prompt, favorite) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Сохранить основные настройки") }
            }

            item {
                SettingsExpander(
                    title = "Этапы работы",
                    subtitle = "${project.stages.orEmpty().size} этапов · выполняются строго по порядку",
                    expanded = stagesExpanded,
                    onToggle = { stagesExpanded = !stagesExpanded }
                )
            }
            if (stagesExpanded) {
                item {
                    Text(
                        "У каждого этапа может быть своя модель, свои файлы и выбранные чаты проекта как дополнительный источник контекста.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (project.stages.orEmpty().isEmpty()) {
                    item { Text("Этапов пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    itemsIndexed(project.stages.orEmpty(), key = { _, stage -> stage.id }) { index, stage ->
                        StageCard(
                            stage = stage,
                            index = index,
                            total = project.stages.orEmpty().size,
                            onMoveUp = { vm.moveProjectStage(project.id, stage.id, -1) },
                            onMoveDown = { vm.moveProjectStage(project.id, stage.id, 1) },
                            onEdit = { editingStage = stage; stageEditorOpen = true },
                            onDelete = { vm.deleteProjectStage(project.id, stage.id) },
                            enabled = !state.isLoading
                        )
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = { editingStage = null; stageEditorOpen = true },
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Добавить этап")
                    }
                }
            }

            item {
                SettingsExpander(
                    title = "Навыки проекта",
                    subtitle = if (project.skillIds.isEmpty()) "Не выбраны" else "Выбрано: ${project.skillIds.size}",
                    expanded = skillsExpanded,
                    onToggle = { skillsExpanded = !skillsExpanded }
                )
            }
            if (skillsExpanded) {
                item {
                    Text(
                        "Отмеченные навыки доступны как быстрый набор внутри чатов этого проекта.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.skills.isEmpty()) {
                    item { Text("Добавьте навыки в Настройки → Навыки", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(state.skills, key = { "project-skill-${it.id}" }) { skill ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(19.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(skill.name, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Switch(
                                checked = skill.id in project.skillIds,
                                onCheckedChange = { vm.toggleProjectSkill(project.id, skill.id) }
                            )
                        }
                    }
                }
            }

            item {
                SettingsExpander(
                    title = "Постоянные файлы",
                    subtitle = if (project.files.isEmpty()) "Нет файлов" else "${project.files.size} файлов",
                    expanded = filesExpanded,
                    onToggle = { filesExpanded = !filesExpanded }
                )
            }
            if (filesExpanded) {
                if (project.files.isEmpty()) {
                    item { Text("Нет файлов", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(project.files, key = { "project-file-${it.id}" }) { file ->
                        FileRow(file = file, onDelete = { vm.deleteProjectFile(project.id, file.id) })
                    }
                }
                item {
                    FilledTonalButton(onClick = { addFiles.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Добавить постоянные файлы")
                    }
                }
            }

            item {
                TextButton(onClick = { deleteConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Удалить проект")
                }
            }
        }
    }

    if (stageEditorOpen) {
        StageEditorDialog(
            project = project,
            stage = editingStage,
            state = state,
            vm = vm,
            ownerChatId = null,
            onDismiss = {
                stageEditorOpen = false
                editingStage = null
            },
            onSave = { title, instruction, modelId, files, sourceChatIds ->
                vm.upsertProjectStage(project.id, editingStage?.id, title, instruction, modelId, files, sourceChatIds)
                stageEditorOpen = false
                editingStage = null
            }
        )
    }

    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text("Удалить проект?") },
            text = { Text("Материалы проекта будут удалены. Его чаты сохранятся как обычные, а индивидуальные этапы этих чатов будут удалены.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(project.id)
                    deleteConfirm = false
                    onProjectDeleted()
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun ProjectChatSettingsDialog(
    chat: ChatSession,
    project: Project,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit
) {
    var title by remember(chat.id, chat.title) { mutableStateOf(chat.title) }
    var role by remember(chat.id, chat.assignedRole) { mutableStateOf(chat.assignedRole.orEmpty()) }
    var prompt by remember(chat.id, chat.masterPrompt) { mutableStateOf(chat.masterPrompt.orEmpty()) }
    var favorite by remember(chat.id, chat.isFavorite) { mutableStateOf(chat.isFavorite) }
    var stagesExpanded by remember(chat.id) { mutableStateOf(true) }
    var stageEditorOpen by remember { mutableStateOf(false) }
    var editingStage by remember { mutableStateOf<ProjectStage?>(null) }

    FullScreenPanel(title = "Настройки чата", onBack = onDismiss) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true) }
            item { OutlinedTextField(role, { role = it }, Modifier.fillMaxWidth(), label = { Text("Роль чата") }) }
            item {
                OutlinedTextField(
                    prompt,
                    { prompt = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Инструкция чата") },
                    minLines = 4,
                    maxLines = 12
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Избранное", Modifier.weight(1f))
                    Switch(favorite, { favorite = it })
                }
            }
            item {
                FilledTonalButton(
                    onClick = {
                        vm.updateChatProfile(chat.id, title, role, prompt)
                        vm.setChatFavorite(chat.id, favorite)
                    },
                    enabled = title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Сохранить настройки чата") }
            }

            item {
                SettingsExpander(
                    title = "Этапы этого чата",
                    subtitle = if (chat.stages.orEmpty().isEmpty()) "Не настроены" else "${chat.stages.orEmpty().size} этапов",
                    expanded = stagesExpanded,
                    onToggle = { stagesExpanded = !stagesExpanded }
                )
            }
            if (stagesExpanded) {
                item {
                    Text(
                        "Эти этапы принадлежат только этому чату. Запустить их можно из + → Проект.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (chat.stages.orEmpty().isEmpty()) {
                    item { Text("Этапов пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    itemsIndexed(chat.stages.orEmpty(), key = { _, stage -> "chat-stage-${stage.id}" }) { index, stage ->
                        StageCard(
                            stage = stage,
                            index = index,
                            total = chat.stages.orEmpty().size,
                            onMoveUp = { vm.moveChatStage(chat.id, stage.id, -1) },
                            onMoveDown = { vm.moveChatStage(chat.id, stage.id, 1) },
                            onEdit = { editingStage = stage; stageEditorOpen = true },
                            onDelete = { vm.deleteChatStage(chat.id, stage.id) },
                            enabled = !state.isLoading
                        )
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = { editingStage = null; stageEditorOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.isLoading
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Добавить этап чата")
                    }
                }
            }
        }
    }

    if (stageEditorOpen) {
        StageEditorDialog(
            project = project,
            stage = editingStage,
            state = state,
            vm = vm,
            ownerChatId = chat.id,
            onDismiss = {
                stageEditorOpen = false
                editingStage = null
            },
            onSave = { stageTitle, instruction, modelId, files, sourceChatIds ->
                vm.upsertChatStage(chat.id, editingStage?.id, stageTitle, instruction, modelId, files, sourceChatIds)
                stageEditorOpen = false
                editingStage = null
            }
        )
    }
}

@Composable
private fun ProjectEditorDialog(
    project: Project?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Boolean) -> Unit
) {
    var name by remember(project?.id) { mutableStateOf(project?.name.orEmpty()) }
    var role by remember(project?.id) { mutableStateOf(project?.role.orEmpty()) }
    var prompt by remember(project?.id) { mutableStateOf(project?.masterPrompt.orEmpty()) }
    var favorite by remember(project?.id) { mutableStateOf(project?.isFavorite ?: false) }

    FullScreenPanel(title = if (project == null) "Новый проект" else "Настройки проекта", onBack = onDismiss) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Название") }, singleLine = true) }
            item {
                OutlinedTextField(
                    role,
                    { role = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Роль") },
                    placeholder = { Text("Например: главный редактор IT-канала") }
                )
            }
            item {
                OutlinedTextField(
                    prompt,
                    { prompt = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Мастер-промпт") },
                    placeholder = { Text("Эта инструкция автоматически добавляется ко всем чатам проекта") },
                    minLines = 8,
                    maxLines = 18
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Избранное", Modifier.weight(1f))
                    Switch(favorite, { favorite = it })
                }
            }
        }
        FilledTonalButton(
            onClick = { onSave(name, role, prompt, favorite) },
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) { Text("Сохранить") }
    }
}

@Composable
private fun StageEditorDialog(
    project: Project,
    stage: ProjectStage?,
    state: UiState,
    vm: ChatViewModel,
    ownerChatId: String?,
    onDismiss: () -> Unit,
    onSave: (String, String, String?, List<ProjectFile>, Set<String>) -> Unit
) {
    var title by remember(stage?.id) { mutableStateOf(stage?.title.orEmpty()) }
    var instruction by remember(stage?.id) { mutableStateOf(stage?.instruction.orEmpty()) }
    var modelId by remember(stage?.id) { mutableStateOf(stage?.modelId) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var sourcesExpanded by remember(stage?.id) { mutableStateOf(false) }
    var files by remember(stage?.id) { mutableStateOf(stage?.files.orEmpty()) }
    var sourceChatIds by remember(stage?.id) { mutableStateOf(stage?.sourceChatIds.orEmpty()) }
    val originalFileIds = remember(stage?.id) { stage?.files.orEmpty().map { it.id }.toSet() }
    val quickIds = state.quickTextModels.map { ref -> ref.substringAfter('\u001F') }
    val choices = (listOfNotNull(state.currentChatTextModel, state.textModel) + quickIds)
        .filter { it.isNotBlank() && !it.endsWith(":batch", true) }
        .distinct()
    val projectChats = state.chats
        .filter { it.projectId == project.id && it.id != ownerChatId }
        .sortedByDescending { it.updatedAt }

    val addStageFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            vm.importStageDraftFile(project.id, uri)?.let { imported -> files = files + imported }
        }
    }

    fun cancelEditor() {
        vm.cleanupStageDraftFiles(originalFileIds, files)
        onDismiss()
    }

    FullScreenPanel(title = if (stage == null) "Новый этап" else "Изменить этап", onBack = ::cancelEditor) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название этапа") },
                    placeholder = { Text("Например: Собрать выводы") },
                    singleLine = true
                )
            }
            item {
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Инструкция этапа") },
                    placeholder = { Text("Что именно нужно сделать на этом шаге") },
                    minLines = 6,
                    maxLines = 18
                )
            }
            item {
                Text("Модель", fontWeight = FontWeight.SemiBold)
                Box {
                    FilledTonalButton(onClick = { modelMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(modelId?.substringAfterLast('/') ?: "Как в текущем чате", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Как в текущем чате") },
                            onClick = { modelId = null; modelMenuOpen = false }
                        )
                        choices.forEach { id ->
                            DropdownMenuItem(
                                text = { Text(id, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                onClick = { modelId = id; modelMenuOpen = false }
                            )
                        }
                    }
                }
            }

            item { SectionTitle("Файлы этого этапа") }
            if (files.isEmpty()) {
                item { Text("Нет отдельных файлов", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(files, key = { "stage-file-${it.id}" }) { file ->
                    FileRow(file = file, onDelete = {
                        if (file.id !in originalFileIds) vm.deleteStageDraftFile(file)
                        files = files.filterNot { it.id == file.id }
                    })
                }
            }
            item {
                FilledTonalButton(
                    onClick = { addStageFiles.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Прикрепить файл к этапу")
                }
            }

            item {
                SettingsExpander(
                    title = "Чаты проекта как источник",
                    subtitle = if (sourceChatIds.isEmpty()) "Не выбраны" else "Выбрано: ${sourceChatIds.size}",
                    expanded = sourcesExpanded,
                    onToggle = { sourcesExpanded = !sourcesExpanded }
                )
            }
            if (sourcesExpanded) {
                item {
                    Text(
                        "При запуске этап получит актуальную переписку и доступные файлы выбранных чатов. Чаты не запускаются повторно: они используются как источник результата и контекста.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (projectChats.isEmpty()) {
                    item { Text("Других чатов проекта пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(projectChats, key = { "source-chat-${it.id}" }) { source ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = source.id in sourceChatIds,
                                onCheckedChange = { checked ->
                                    sourceChatIds = if (checked) sourceChatIds + source.id else sourceChatIds - source.id
                                }
                            )
                            Column(Modifier.weight(1f)) {
                                Text(source.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${source.messages.size} сообщ. · ${projectDate(source.updatedAt)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        FilledTonalButton(
            onClick = { onSave(title, instruction, modelId, files, sourceChatIds) },
            enabled = instruction.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) { Text("Сохранить этап") }
    }
}

@Composable
private fun StageCard(
    stage: ProjectStage,
    index: Int,
    total: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${index + 1}. ${stage.title}", fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append(stage.modelId?.substringAfterLast('/') ?: "Модель чата")
                            if (stage.files.orEmpty().isNotEmpty()) append(" · ${stage.files.orEmpty().size} файлов")
                            if (stage.sourceChatIds.orEmpty().isNotEmpty()) append(" · ${stage.sourceChatIds.orEmpty().size} чатов")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onMoveUp, enabled = enabled && index > 0) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = "Поднять этап")
                }
                IconButton(onClick = onMoveDown, enabled = enabled && index < total - 1) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = "Опустить этап")
                }
            }
            Text(
                stage.instruction,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit, enabled = enabled) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Изменить")
                }
                TextButton(onClick = onDelete, enabled = enabled) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Удалить")
                }
            }
        }
    }
}

@Composable
private fun SettingsExpander(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    ElevatedCard(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "Свернуть" else "Развернуть"
            )
        }
    }
}

@Composable
private fun FileRow(file: ProjectFile, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(7.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(projectSize(file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить файл")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 9.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

private fun projectDate(timestamp: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun projectSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> String.format(Locale.getDefault(), "%.1f МБ", bytes / 1024.0 / 1024.0)
}
'''
s = sub_once(s, r'@Composable\nfun ProjectsDialog\([\s\S]*\Z', project_section, 'project dialogs section')
path.write_text(s)


# -----------------------------------------------------------------------------
# Version and changelog
# -----------------------------------------------------------------------------
path = ROOT / 'app/build.gradle.kts'
s = path.read_text()
s = replace_once(s, '// Umnik v1.10.8', '// Umnik v1.11.0', 'version header')
s = replace_once(s, 'versionCode = 108', 'versionCode = 110', 'versionCode')
s = replace_once(s, 'versionName = "1.10.8"', 'versionName = "1.11.0"', 'versionName')
path.write_text(s)

path = ROOT / 'CHANGELOG.md'
s = path.read_text()
entry = '''## v1.11.0 - 2026-09-15\n\n- В `+ → Проект` отображаются только навыки, выбранные в настройках текущего проекта. Если проектный набор пуст, чужие навыки там больше не показываются.\n- `+ → Навыки` показывает полный список библиотеки вертикально и управляет только навыками текущего чата; горизонтальная прокрутка убрана.\n- В окне проекта у каждого чата появилось меню `⋮` с избранным, переименованием, настройками и удалением.\n- В настройках проекта списки навыков и постоянных файлов стали вертикальными и сворачиваемыми. Загрузка мастер-промпта отдельным файлом убрана из интерфейса.\n- Каждый этап проекта теперь может иметь собственные прикреплённые файлы и использовать один или несколько существующих чатов проекта как дополнительный источник контекста и результатов.\n- У каждого чата проекта появились собственные индивидуальные этапы. Они настраиваются через `⋮ → Настройки чата` и запускаются из `+ → Проект` отдельно от общих этапов проекта.\n- При использовании чата как источника этап получает актуальную переписку, файлы контекста и сгенерированные файлы этого чата; результаты предыдущих этапов по-прежнему автоматически передаются дальше по цепочке.\n- Кнопка «Новый чат» перенесена в боковой панели над историей обычных чатов; нижняя панель оставлена для «Настроек».\n- Версия: 1.11.0 / versionCode 110.\n\n'''
s = replace_once(s, '## Unreleased\n\n', '## Unreleased\n\n' + entry, 'changelog')
path.write_text(s)

print('Umnik v1.11.0 source update prepared.')
