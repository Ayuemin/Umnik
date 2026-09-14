from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, got {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


# ---------------------------------------------------------------------------
# Repair Kotlin escape sequences from the primary migration. Python re.sub
# interpreted replacement backslashes, turning two intended \n sequences into
# literal line breaks inside Kotlin string literals.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHub.kt"
text = read(path)
text = text.replace(
    'placeholder = { Text("Задача 1\nЗадача 2\nЗадача 3") },',
    'placeholder = { Text("Задача 1\\nЗадача 2\\nЗадача 3") },'
)
text = text.replace(
    'joinToString("\n---\n")',
    'joinToString("\\n---\\n")'
)
write(path, text)

# ---------------------------------------------------------------------------
# Changelog and beginner guide: project stages are universal, not article-only.
# ---------------------------------------------------------------------------
replace_once(
    "CHANGELOG.md",
    "- Раздел Tools + RAG переведён на понятные русские названия. Ручной ввод ID Embeddings/Rerank и Advisor/Subagent убран из обычного интерфейса: модели назначаются из общего каталога OpenRouter.\n",
    "- Раздел Tools + RAG переведён на понятные русские названия. Ручной ввод ID Embeddings/Rerank и Advisor/Subagent убран из обычного интерфейса: модели назначаются из общего каталога OpenRouter.\n"
    "- Проекты получили универсальные «Этапы работы»: произвольные инструкции выполняются строго по порядку отдельными запросами, каждый следующий этап получает исходную задачу, материалы проекта и результаты предыдущих этапов. Для этапа можно выбрать свою быструю текстовую модель или наследовать модель чата.\n"
)

path = "app/src/main/java/com/ayuemin/ymnik/help/UmnikUsageGuide.kt"
text = read(path)
text = text.replace("# 7. Проекты и навыки", "# 7. Проекты, этапы работы и навыки", 1)
guide_anchor = """Чаты проекта показываются внутри проекта и не должны смешиваться с общей историей обычных чатов.

## Навык
"""
guide_insert = """Чаты проекта показываются внутри проекта и не должны смешиваться с общей историей обычных чатов.

## Этапы работы
Этапы нужны, когда одну задачу лучше решать **последовательно несколькими отдельными запросами**, а не просить модель сделать всё сразу.

В проекте раскройте **Этапы работы** → добавьте любое количество этапов → каждому дайте название и инструкцию. Этапы универсальны: анализ данных, подготовка плана, расчёт, проверка, преобразование материалов, программирование, исследование или любая другая последовательная работа.

При запуске Umnik выполняет этапы строго сверху вниз. Каждый следующий этап получает исходную задачу, постоянные файлы проекта и результаты уже выполненных этапов. Мастер-инструкция проекта действует на каждом шаге.

По умолчанию используется модель текущего чата. Для отдельного этапа можно выбрать другую из быстрых текстовых моделей.

**Важно:** это не Batch. Batch выполняет независимые задания, а этапы проекта специально передают результат предыдущей работы дальше по цепочке.

## Навык
"""
if guide_anchor not in text:
    raise SystemExit("UmnikUsageGuide.kt: projects guide anchor not found")
text = text.replace(guide_anchor, guide_insert, 1)
write(path, text)

# ---------------------------------------------------------------------------
# Data model. Nullable stages keeps old Gson project files backward compatible:
# old projects that have no field deserialize safely and are accessed via orEmpty().
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/ayuemin/ymnik/model/Models.kt"
text = read(path)
project_anchor = """data class Project(
    val id: String,
    val name: String,
"""
project_new = """data class ProjectStage(
    val id: String,
    val title: String,
    val instruction: String,
    val modelId: String? = null
)

data class Project(
    val id: String,
    val name: String,
"""
if project_anchor not in text:
    raise SystemExit("Models.kt: Project anchor not found")
text = text.replace(project_anchor, project_new, 1)
files_anchor = """    val skillIds: Set<String> = emptySet(),
    val files: List<ProjectFile> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
"""
files_new = """    val skillIds: Set<String> = emptySet(),
    val files: List<ProjectFile> = emptyList(),
    val stages: List<ProjectStage>? = null,
    val createdAt: Long = System.currentTimeMillis(),
"""
if files_anchor not in text:
    raise SystemExit("Models.kt: Project fields anchor not found")
text = text.replace(files_anchor, files_new, 1)
write(path, text)

# ---------------------------------------------------------------------------
# ChatViewModel: CRUD + sequential stage executor. Each stage is a real request.
# Project master instruction, skills and persistent project files are applied to
# every stage. Next stage receives source task + all previous stage results.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
text = read(path)
text = text.replace("import com.ayuemin.ymnik.model.ProjectFile\n", "import com.ayuemin.ymnik.model.ProjectFile\nimport com.ayuemin.ymnik.model.ProjectStage\n", 1)
text = text.replace("import kotlinx.coroutines.Job\n", "import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.Job\n", 1)
text = text.replace(
    "    private var activeRequestPending: List<PendingAttachment> = emptyList()\n    private var requestGeneration: Long = 0L",
    "    private var activeRequestPending: List<PendingAttachment> = emptyList()\n    private var projectStagesJob: Job? = null\n    private var requestGeneration: Long = 0L",
    1,
)

stage_anchor = """    fun setProjectFavorite(id: String, favorite: Boolean) {
"""
stage_methods = r'''    fun upsertProjectStage(
        projectId: String,
        stageId: String?,
        title: String,
        instruction: String,
        modelId: String?
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
        val stage = ProjectStage(
            id = id,
            title = title.trim().ifBlank { "Этап ${current.size + 1}" },
            instruction = cleanInstruction,
            modelId = modelId?.trim()?.takeIf { it.isNotBlank() }
        )
        val nextStages = if (current.any { it.id == id }) {
            current.map { if (it.id == id) stage else it }
        } else current + stage
        val projects = _state.value.projects.map {
            if (it.id == projectId) it.copy(stages = nextStages, updatedAt = System.currentTimeMillis()) else it
        }
        projectsRepository.save(projects)
        _state.value = _state.value.copy(projects = projects, status = "Этап сохранён")
        return id
    }

    fun deleteProjectStage(projectId: String, stageId: String) {
        if (_state.value.isLoading) return
        val projects = _state.value.projects.map { project ->
            if (project.id == projectId) project.copy(
                stages = project.stages.orEmpty().filterNot { it.id == stageId },
                updatedAt = System.currentTimeMillis()
            ) else project
        }
        projectsRepository.save(projects)
        _state.value = _state.value.copy(projects = projects)
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

    fun runProjectStages(projectId: String, initialTask: String): String? {
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

        val chatId = createChat(projectId)
        val startText = initialTask.trim().ifBlank { "Используй цель, инструкции и материалы проекта." }
        val now = System.currentTimeMillis()
        val user = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "user",
            text = "Запустить этапы проекта.\n\nИсходная задача:\n$startText",
            timestamp = now
        )
        val currentChat = _state.value.chats.firstOrNull { it.id == chatId } ?: return null
        val stageChat = currentChat.copy(
            title = "Этапы · ${project.name}".take(80),
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
        DiagnosticLog.action(context, "project_stages_start", "project=${projectId.take(8)}; chat=${chatId.take(8)}; stages=${stages.size}")

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
                    val attachments = project.files.mapNotNull { file ->
                        val mime = file.mimeType.lowercase()
                        val name = file.name.lowercase()
                        val textLike = mime.startsWith("text/") || name.endsWith(".md") || name.endsWith(".json") ||
                            name.endsWith(".csv") || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".xml")
                        val allowed = textLike || mime == "application/pdf" || name.endsWith(".pdf") ||
                            (mime.startsWith("image/") && modelInfo?.accepts("image") == true) ||
                            (mime.startsWith("audio/") && modelInfo?.accepts("audio") == true) ||
                            (mime.startsWith("video/") && modelInfo?.accepts("video") == true)
                        if (!allowed) null else PendingAttachment(
                            uri = "project://${file.id}",
                            name = file.name,
                            mimeType = file.mimeType,
                            size = file.size,
                            localPath = file.localPath
                        )
                    }
                    val skillsText = skills.promptFor(currentState.activeSkillIds + project.skillIds)
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
                    DiagnosticLog.record(context, "PROJECT_STAGE", "start project=${projectId.take(8)}; stage=${index + 1}/${stages.size}; model=$modelId")

                    val result = try {
                        api.chat(
                            apiKey,
                            modelId,
                            emptyList(),
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

''' + stage_anchor
if stage_anchor not in text:
    raise SystemExit("ChatViewModel.kt: setProjectFavorite anchor not found")
text = text.replace(stage_anchor, stage_methods, 1)

stop_anchor = """    fun stopGeneration() {
        if (!_state.value.requestActive) return
"""
stop_new = """    fun stopGeneration() {
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
"""
if stop_anchor not in text:
    raise SystemExit("ChatViewModel.kt: stopGeneration anchor not found")
text = text.replace(stop_anchor, stop_new, 1)
write(path, text)

# ---------------------------------------------------------------------------
# Project UI: global history excludes project chats; project stages live in an
# expandable block with add/edit/delete/reorder and run actions.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/ayuemin/ymnik/ui/ProjectDialogs.kt"
text = read(path)
text = text.replace("import androidx.compose.foundation.layout.Arrangement\n", "import androidx.compose.foundation.layout.Arrangement\nimport androidx.compose.foundation.layout.Box\n", 1)
text = text.replace("import androidx.compose.foundation.lazy.items\n", "import androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.lazy.itemsIndexed\n", 1)
text = text.replace("import androidx.compose.material.icons.outlined.Add\n", "import androidx.compose.material.icons.outlined.Add\nimport androidx.compose.material.icons.outlined.ArrowDownward\nimport androidx.compose.material.icons.outlined.ArrowUpward\n", 1)
text = text.replace("import androidx.compose.material.icons.outlined.FolderOpen\n", "import androidx.compose.material.icons.outlined.FolderOpen\nimport androidx.compose.material.icons.outlined.KeyboardArrowDown\nimport androidx.compose.material.icons.outlined.KeyboardArrowUp\nimport androidx.compose.material.icons.outlined.PlayArrow\n", 1)
text = text.replace("import androidx.compose.material3.AlertDialog\n", "import androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.Button\nimport androidx.compose.material3.DropdownMenu\nimport androidx.compose.material3.DropdownMenuItem\nimport androidx.compose.material3.ElevatedCard\n", 1)
text = text.replace("import com.ayuemin.ymnik.model.Project\n", "import com.ayuemin.ymnik.model.Project\nimport com.ayuemin.ymnik.model.ProjectStage\n", 1)

old_history = "    val chats = state.chats.sortedWith(compareByDescending<ChatSession> { it.isFavorite }.thenByDescending { it.updatedAt })"
new_history = "    val chats = state.chats.filter { it.projectId == null }\n        .sortedWith(compareByDescending<ChatSession> { it.isFavorite }.thenByDescending { it.updatedAt })"
if old_history not in text:
    raise SystemExit("ProjectDialogs.kt: global chat history anchor not found")
text = text.replace(old_history, new_history, 1)
text = text.replace(
    '                    "$count чатов · ${project.files.size} файлов · ${project.skillIds.size} навыков",',
    '                    "$count чатов · ${project.files.size} файлов · ${project.stages.orEmpty().size} этапов",',
    1,
)

vars_anchor = """    var editOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var deleteChatTarget by remember { mutableStateOf<ChatSession?>(null) }
"""
vars_new = """    var editOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var deleteChatTarget by remember { mutableStateOf<ChatSession?>(null) }
    var stagesExpanded by remember(project.id) { mutableStateOf(true) }
    var stageEditorOpen by remember { mutableStateOf(false) }
    var editingStage by remember { mutableStateOf<ProjectStage?>(null) }
    var runStagesOpen by remember { mutableStateOf(false) }
    var runInput by remember { mutableStateOf("") }
"""
if vars_anchor not in text:
    raise SystemExit("ProjectDialogs.kt: detail vars anchor not found")
text = text.replace(vars_anchor, vars_new, 1)

skills_anchor = """            item { SectionTitle("Навыки проекта") }
"""
stages_block = r'''            item {
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
            }
            if (stagesExpanded) {
                item {
                    Text(
                        "Последовательный сценарий для любых задач. Каждый этап — отдельный запрос. Следующий получает исходную задачу, материалы проекта и результаты предыдущих этапов. Мастер-инструкция действует на каждом шаге.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (project.stages.orEmpty().isEmpty()) {
                    item { Text("Этапов пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    itemsIndexed(project.stages.orEmpty(), key = { _, stage -> stage.id }) { index, stage ->
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("${index + 1}. ${stage.title}", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            stage.modelId?.substringAfterLast('/') ?: "Модель чата",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(onClick = { vm.moveProjectStage(project.id, stage.id, -1) }, enabled = index > 0 && !state.isLoading) {
                                        Icon(Icons.Outlined.ArrowUpward, contentDescription = "Поднять этап")
                                    }
                                    IconButton(onClick = { vm.moveProjectStage(project.id, stage.id, 1) }, enabled = index < project.stages.orEmpty().lastIndex && !state.isLoading) {
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
                                    TextButton(onClick = {
                                        editingStage = stage
                                        stageEditorOpen = true
                                    }, enabled = !state.isLoading) {
                                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(17.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Изменить")
                                    }
                                    TextButton(onClick = { vm.deleteProjectStage(project.id, stage.id) }, enabled = !state.isLoading) {
                                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(17.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Удалить")
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    FilledTonalButton(
                        onClick = {
                            editingStage = null
                            stageEditorOpen = true
                        },
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Добавить этап")
                    }
                }
                item {
                    Button(
                        onClick = { runStagesOpen = true },
                        enabled = project.stages.orEmpty().isNotEmpty() && !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Запустить этапы")
                    }
                }
            }

''' + skills_anchor
if skills_anchor not in text:
    raise SystemExit("ProjectDialogs.kt: skills anchor not found")
text = text.replace(skills_anchor, stages_block, 1)

dialog_anchor = """    deleteChatTarget?.let { chat ->
"""
stage_dialogs = r'''    if (stageEditorOpen) {
        ProjectStageEditorDialog(
            project = project,
            stage = editingStage,
            state = state,
            onDismiss = {
                stageEditorOpen = false
                editingStage = null
            },
            onSave = { title, instruction, modelId ->
                vm.upsertProjectStage(project.id, editingStage?.id, title, instruction, modelId)
                stageEditorOpen = false
                editingStage = null
            }
        )
    }

    if (runStagesOpen) {
        AlertDialog(
            onDismissRequest = { runStagesOpen = false },
            title = { Text("Запустить этапы работы") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Umnik создаст чат внутри проекта и выполнит ${project.stages.orEmpty().size} этапов строго по порядку. Постоянные файлы проекта будут доступны на каждом этапе.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = runInput,
                        onValueChange = { runInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Исходная задача или цель") },
                        placeholder = { Text("Можно оставить пустым, если всё необходимое уже есть в инструкциях и файлах проекта") },
                        minLines = 3,
                        maxLines = 8
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chatId = vm.runProjectStages(project.id, runInput)
                    if (chatId != null) {
                        runStagesOpen = false
                        onOpenChat(chatId)
                    }
                }) { Text("Запустить") }
            },
            dismissButton = { TextButton(onClick = { runStagesOpen = false }) { Text("Отмена") } }
        )
    }

''' + dialog_anchor
if dialog_anchor not in text:
    raise SystemExit("ProjectDialogs.kt: dialog anchor not found")
text = text.replace(dialog_anchor, stage_dialogs, 1)

section_anchor = """@Composable
private fun SectionTitle(text: String) {
"""
stage_editor = r'''@Composable
private fun ProjectStageEditorDialog(
    project: Project,
    stage: ProjectStage?,
    state: UiState,
    onDismiss: () -> Unit,
    onSave: (String, String, String?) -> Unit
) {
    var title by remember(stage?.id) { mutableStateOf(stage?.title.orEmpty()) }
    var instruction by remember(stage?.id) { mutableStateOf(stage?.instruction.orEmpty()) }
    var modelId by remember(stage?.id) { mutableStateOf(stage?.modelId) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    val quickIds = state.quickTextModels.map { ref -> ref.substringAfter('\u001F') }
    val choices = (listOfNotNull(state.currentChatTextModel, state.textModel) + quickIds)
        .filter { it.isNotBlank() && !it.endsWith(":batch", true) }
        .distinct()

    FullScreenPanel(title = if (stage == null) "Новый этап" else "Изменить этап", onBack = onDismiss) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Этапы универсальны и не привязаны к типу работы. Опишите только действие, которое модель должна выполнить на этом шаге.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(100) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Название этапа") },
                    placeholder = { Text("Например: Проверить исходные данные") },
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
                Text(
                    if (choices.isEmpty()) "Дополнительные модели появятся здесь после добавления их в быстрые." else "Для разных этапов можно использовать разные быстрые текстовые модели.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
        FilledTonalButton(
            onClick = { onSave(title, instruction, modelId) },
            enabled = instruction.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) { Text("Сохранить этап") }
    }
}

''' + section_anchor
if section_anchor not in text:
    raise SystemExit("ProjectDialogs.kt: SectionTitle anchor not found")
text = text.replace(section_anchor, stage_editor, 1)
write(path, text)

print("v1.10.0 follow-up migration applied: Kotlin escapes repaired, universal project stages added")
