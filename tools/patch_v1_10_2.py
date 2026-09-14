from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


def replace_between(text: str, start_marker: str, end_marker: str, replacement: str, label: str) -> str:
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f"{label}: start marker not found")
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f"{label}: end marker not found")
    return text[:start] + replacement + text[end:]


# 1) UiState exposes the selected OpenRouter speech model so the chat action can react immediately.
models_path = "app/src/main/java/com/ayuemin/ymnik/model/Models.kt"
models = read(models_path)
models = replace_once(
    models,
    '    val imageResolution: String? = null,\n    val webSearchEnabled: Boolean = false,',
    '    val imageResolution: String? = null,\n    val openRouterSpeechModel: String = "",\n    val webSearchEnabled: Boolean = false,',
    "UiState speech model"
)
write(models_path, models)


# 2) ChatViewModel mirrors the OpenRouter speech preference into UiState.
vm_path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
vm = read(vm_path)
vm = replace_once(
    vm,
    'import com.ayuemin.ymnik.data.ProjectRepository\n',
    'import com.ayuemin.ymnik.data.ProjectRepository\nimport com.ayuemin.ymnik.data.OpenRouterFeaturePrefs\n',
    "ChatViewModel OpenRouterFeaturePrefs import"
)
vm = replace_once(
    vm,
    '    private val projectsRepository = ProjectRepository(context)\n    private val storageRepository = StorageRepository(context)\n',
    '    private val projectsRepository = ProjectRepository(context)\n    private val openRouterFeaturePrefs = OpenRouterFeaturePrefs(context)\n    private val storageRepository = StorageRepository(context)\n',
    "ChatViewModel OpenRouterFeaturePrefs field"
)
vm = replace_once(
    vm,
    '            imageResolution = initialImageResolution,\n            webSearchEnabled = prefs.getBoolean("web_search", false),',
    '            imageResolution = initialImageResolution,\n            openRouterSpeechModel = openRouterFeaturePrefs.media().speechModel,\n            webSearchEnabled = prefs.getBoolean("web_search", false),',
    "ChatViewModel initial speech model"
)
insert_marker = '    fun isDiagnosticLoggingEnabled(): Boolean = DiagnosticLog.isEnabled(context)\n'
insert_code = '''    fun setOpenRouterSpeechModel(modelId: String) {\n        val clean = modelId.trim()\n        val media = openRouterFeaturePrefs.media().copy(speechModel = clean)\n        openRouterFeaturePrefs.saveMedia(media)\n        _state.value = _state.value.copy(\n            openRouterSpeechModel = clean,\n            status = if (clean.isBlank()) "Модель озвучивания OpenRouter не выбрана" else "Модель озвучивания OpenRouter сохранена"\n        )\n    }\n\n'''
if insert_marker not in vm:
    raise SystemExit("ChatViewModel speech setter marker not found")
vm = vm.replace(insert_marker, insert_code + insert_marker, 1)
write(vm_path, vm)


# 3) Async event for one-tap OpenRouter speech under assistant messages.
events_path = "app/src/main/java/com/ayuemin/ymnik/AsyncJobEvents.kt"
events = read(events_path)
events = replace_once(
    events,
    'internal object AsyncJobEvents {\n',
    'internal data class OpenRouterSpeechRequest(val chatId: String, val text: String)\n\ninternal object AsyncJobEvents {\n',
    "speech request data class"
)
events = replace_once(
    events,
    '    private val mutableHubRequest = MutableStateFlow<String?>(null)\n    val hubRequest: StateFlow<String?> = mutableHubRequest\n',
    '    private val mutableHubRequest = MutableStateFlow<String?>(null)\n    val hubRequest: StateFlow<String?> = mutableHubRequest\n\n    private val mutableSpeechRequest = MutableStateFlow<OpenRouterSpeechRequest?>(null)\n    val speechRequest: StateFlow<OpenRouterSpeechRequest?> = mutableSpeechRequest\n',
    "speech request flow"
)
events = replace_once(
    events,
    '    fun consumeHubRequest() {\n        mutableHubRequest.value = null\n    }\n',
    '''    fun consumeHubRequest() {\n        mutableHubRequest.value = null\n    }\n\n    fun requestSpeech(chatId: String, text: String) {\n        if (chatId.isBlank() || text.isBlank()) return\n        mutableSpeechRequest.value = OpenRouterSpeechRequest(chatId, text)\n    }\n\n    fun consumeSpeechRequest() {\n        mutableSpeechRequest.value = null\n    }\n''',
    "speech request methods"
)
write(events_path, events)


# 4) OpenRouter controller: per-task Batch files, clear finished Batch history, speech-model sync and answer speech.
controller_path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHubController.kt"
controller = read(controller_path)
controller = replace_once(
    controller,
    '''            ModelCategory.SPEECH, ModelCategory.AUDIO -> {\n                val media = mutableState.value.media.copy(speechModel = model.id)\n                featurePrefs.saveMedia(media)\n                mutableState.value = mutableState.value.copy(media = media, status = "${model.id} назначена для озвучивания")\n            }''',
    '''            ModelCategory.SPEECH, ModelCategory.AUDIO -> {\n                val media = mutableState.value.media.copy(speechModel = model.id)\n                featurePrefs.saveMedia(media)\n                viewModel.setOpenRouterSpeechModel(model.id)\n                mutableState.value = mutableState.value.copy(media = media, status = "${model.id} назначена для озвучивания")\n            }''',
    "sync assigned speech model"
)
controller = replace_once(
    controller,
    '''            ModelCategory.SPEECH, ModelCategory.AUDIO -> {\n                val media = mutableState.value.media.copy(speechModel = "")\n                featurePrefs.saveMedia(media); mutableState.value = mutableState.value.copy(media = media, status = "Модель озвучивания снята")\n            }''',
    '''            ModelCategory.SPEECH, ModelCategory.AUDIO -> {\n                val media = mutableState.value.media.copy(speechModel = "")\n                featurePrefs.saveMedia(media)\n                viewModel.setOpenRouterSpeechModel("")\n                mutableState.value = mutableState.value.copy(media = media, status = "Модель озвучивания снята")\n            }''',
    "sync cleared speech model"
)
clear_batch_marker = '''    fun clearBatchModel() {\n        val media = mutableState.value.media.copy(batchModel = "")\n        featurePrefs.saveMedia(media)\n        mutableState.value = mutableState.value.copy(media = media, status = "Batch-модель снята")\n    }\n'''
clear_batch_replacement = clear_batch_marker + '''\n    fun clearFinishedBatchHistory() {\n        runCatching {\n            val remaining = batchRepository.list().filterNot { it.status.terminal }\n            batchRepository.save(remaining)\n            remaining\n        }.onSuccess { remaining ->\n            mutableState.value = mutableState.value.copy(\n                batches = remaining,\n                status = if (remaining.isEmpty()) "История Batch очищена" else "Завершённая история Batch очищена; активные задачи сохранены"\n            )\n        }.onFailure { error ->\n            mutableState.value = mutableState.value.copy(status = error.message ?: "Не удалось очистить историю Batch")\n        }\n    }\n'''
controller = replace_once(controller, clear_batch_marker, clear_batch_replacement, "Batch history clear method")

new_submit_batch = '''    fun submitBatch(raw: String, taskFileUris: List<List<Uri>> = emptyList()) {\n        val totalFiles = taskFileUris.sumOf { it.size }\n        DiagnosticLog.action(context, "batch_submit", "inputChars=${raw.length}; taskFiles=$totalFiles")\n        val input = raw.trim()\n        if (input.isBlank()) {\n            mutableState.value = mutableState.value.copy(status = "Введите хотя бы одно задание")\n            return\n        }\n        val profile = openRouterProfile()\n        val model = mutableState.value.media.batchModel.trim()\n        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()\n        if (profile == null || key.isBlank()) {\n            mutableState.value = mutableState.value.copy(status = "OpenRouter не настроен")\n            return\n        }\n        if (!model.endsWith(":batch", true)) {\n            mutableState.value = mutableState.value.copy(status = "Сначала выберите модель Batch в каталоге")\n            return\n        }\n        val prompts = input.split(Regex("(?m)^\\\\s*---+\\\\s*$"))\n            .map(String::trim)\n            .filter(String::isNotBlank)\n        if (prompts.isEmpty()) return\n        val filesPerPrompt = prompts.indices.map { index -> taskFileUris.getOrNull(index).orEmpty().take(6) }\n\n        scope.launch {\n            mutableState.value = mutableState.value.copy(loading = true, operation = "Отправляю Batch…", status = null)\n            runCatching {\n                val appState = viewModel.state.value\n                val chat = appState.chats.firstOrNull { it.id == appState.currentChatId }\n                val project = chat?.projectId?.let { id -> appState.projects.firstOrNull { it.id == id } }\n                val modelInfo = mutableState.value.catalog.firstOrNull { it.id == model }\n                // Batch API не принимает обычные file/image parts. Текстовые файлы\n                // конкретной задачи безопасно встраиваются только в её prompt.\n                val fileContexts = withContext(Dispatchers.IO) { filesPerPrompt.map(::batchTextContext) }\n                val system = buildSystemPrompt(chat, project)\n                val requests = prompts.mapIndexed { index, prompt ->\n                    val inlineFiles = fileContexts[index]\n                    val promptWithFiles = if (inlineFiles.isBlank()) prompt else "$prompt\\n\\n===== ФАЙЛЫ ЭТОЙ ЗАДАЧИ =====\\n$inlineFiles"\n                    OpenRouterBatchClient.BatchRequest(\n                        customId = "hub-${index + 1}-${UUID.randomUUID()}",\n                        label = prompt.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "Задание ${index + 1}",\n                        body = batchBuilder.build(\n                            model = model,\n                            history = chat?.messages.orEmpty(),\n                            prompt = promptWithFiles,\n                            attachments = emptyList(),\n                            systemPrompt = system,\n                            reasoningEnabled = false,\n                            reasoningEffort = null,\n                            modelInfo = modelInfo\n                        )\n                    )\n                }\n                val snapshot = batchClient.create(key, model, requests, viewModel.connectionTextEndpoint(profile.id))\n                val job = BatchJob(\n                    id = UUID.randomUUID().toString(),\n                    remoteId = snapshot.remoteId,\n                    connectionProfileId = profile.id,\n                    chatId = chat?.id,\n                    projectId = project?.id,\n                    modelId = model,\n                    baseModelId = model.removeSuffix(":batch"),\n                    title = "Batch · ${requests.size} заданий",\n                    status = snapshot.status,\n                    items = if (snapshot.items.isNotEmpty()) snapshot.items else requests.map { BatchJobItem(it.customId, it.label) },\n                    error = snapshot.error\n                )\n                batchRepository.upsert(job)\n                appendHubUserMessage(chat?.id, "[Batch: ${requests.size}${if (totalFiles > 0) " · файлов: $totalFiles" else ""}]\\n$input")\n                OpenRouterBackgroundWorker.schedule(context, replace = false)\n                job\n            }.onSuccess { job ->\n                mutableState.value = mutableState.value.copy(\n                    batches = batchRepository.list(),\n                    loading = false,\n                    operation = null,\n                    status = "Batch принят · ${job.remoteId}"\n                )\n                AsyncJobEvents.notifyChanged()\n            }.onFailure { error ->\n                mutableState.value = mutableState.value.copy(loading = false, operation = null, status = error.message ?: "Не удалось создать Batch")\n            }\n        }\n    }\n\n'''
controller = replace_between(controller, '    fun submitBatch(', '    fun submitVideo(', new_submit_batch, "replace submitBatch")

speech_insert_marker = '    fun runShell(promptRaw: String, attachments: List<Uri> = emptyList()) {'
speech_answer = '''    fun synthesizeAnswer(chatId: String, textRaw: String) {\n        val text = textRaw.trim()\n        if (text.isBlank() || chatId.isBlank()) return\n        if (mutableState.value.loading) {\n            mutableState.value = mutableState.value.copy(status = "Дождитесь завершения текущей операции OpenRouter")\n            return\n        }\n        val profile = openRouterProfile()\n        val media = featurePrefs.media()\n        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()\n        if (profile == null || key.isBlank()) {\n            mutableState.value = mutableState.value.copy(status = "OpenRouter не настроен")\n            return\n        }\n        if (media.speechModel.isBlank()) {\n            viewModel.setOpenRouterSpeechModel("")\n            mutableState.value = mutableState.value.copy(status = "Сначала выберите модель озвучивания OpenRouter")\n            return\n        }\n        scope.launch {\n            mutableState.value = mutableState.value.copy(loading = true, operation = "Озвучиваю ответ через OpenRouter…", status = null)\n            runCatching {\n                val result = audioClient.synthesize(\n                    apiKey = key,\n                    model = media.speechModel,\n                    input = text,\n                    voice = media.voice.takeIf { it.isNotBlank() },\n                    baseUrl = viewModel.connectionTextEndpoint(profile.id)\n                )\n                saveGeneratedAudio(result.bytes, result.mimeType, result.format)\n            }.onSuccess { file ->\n                appendHubAssistantResult(\n                    chatId = chatId,\n                    assistantText = "Озвучка OpenRouter · ${media.speechModel.substringAfterLast('/')}",\n                    files = listOf(file)\n                )\n                mutableState.value = mutableState.value.copy(loading = false, operation = null, speechFile = file, status = "Озвучка OpenRouter добавлена в чат")\n                AsyncJobEvents.notifyChanged()\n            }.onFailure { error ->\n                mutableState.value = mutableState.value.copy(loading = false, operation = null, status = error.message ?: "Не удалось озвучить ответ через OpenRouter")\n            }\n        }\n    }\n\n'''
if speech_insert_marker not in controller:
    raise SystemExit("synthesizeAnswer insertion marker not found")
controller = controller.replace(speech_insert_marker, speech_answer + speech_insert_marker, 1)

assistant_helper_marker = '    private fun ensureTextFile(data: UriData) {'
assistant_helper = '''    private fun appendHubAssistantResult(chatId: String, assistantText: String, files: List<GeneratedFile>) {\n        val all = chats.list()\n        val marker = "hub:${UUID.randomUUID()}"\n        val next = all.map { chat ->\n            if (chat.id == chatId) chat.copy(\n                messages = chat.messages + ChatMessage(\n                    UUID.randomUUID().toString(),\n                    "assistant",\n                    assistantText,\n                    generatedFiles = files,\n                    deliveryState = marker\n                ),\n                updatedAt = System.currentTimeMillis()\n            ) else chat\n        }\n        chats.save(next)\n        DiagnosticLog.record(context, "CHAT_RESULT", "hub assistant result added; chat=${chatId.take(8)}; files=${files.size}")\n    }\n\n'''
if assistant_helper_marker not in controller:
    raise SystemExit("appendHubAssistantResult marker not found")
controller = controller.replace(assistant_helper_marker, assistant_helper + assistant_helper_marker, 1)
write(controller_path, controller)


# 5) Hub UI: one file picker per task and clear completed Batch history.
hub_path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHub.kt"
hub = read(hub_path)
hub = replace_once(
    hub,
    'import androidx.compose.material3.Button\n',
    'import androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.Button\n',
    "Hub AlertDialog import"
)
old_jobs_start = '@Composable\nprivate fun JobsPage(state: OpenRouterHubState, controller: OpenRouterHubController) {'
old_jobs_end = '@Composable\nprivate fun MediaPage(state: OpenRouterHubState, controller: OpenRouterHubController, section: MediaSection) {'
new_jobs = '''private data class BatchDraftTask(\n    val text: String = "",\n    val files: List<Uri> = emptyList()\n)\n\n@Composable\nprivate fun JobsPage(state: OpenRouterHubState, controller: OpenRouterHubController) {\n    val tasks = remember { mutableStateListOf(BatchDraftTask()) }\n    var bulkInput by remember { mutableStateOf("") }\n    var fileTargetIndex by remember { mutableStateOf<Int?>(null) }\n    var clearHistoryConfirm by remember { mutableStateOf(false) }\n    val taskFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->\n        val index = fileTargetIndex\n        if (index != null && index in tasks.indices) {\n            tasks[index] = tasks[index].copy(files = uris.take(6))\n        }\n        fileTargetIndex = null\n    }\n    val readyCount = tasks.count { it.text.isNotBlank() }\n\n    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {\n        item {\n            Text("Пакет из нескольких независимых заданий", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)\n            Text(\n                "Batch удобен, когда задания не зависят друг от друга. Результаты вернутся в тот чат, из которого вы запустили пакет.",\n                style = MaterialTheme.typography.bodySmall,\n                color = MaterialTheme.colorScheme.onSurfaceVariant\n            )\n            Spacer(Modifier.height(8.dp))\n            CategoryModelPicker(\n                title = "Модель для пакетных задач",\n                current = state.media.batchModel,\n                models = state.catalog.filter { it.isBatch && ModelCategory.TEXT in it.categories },\n                onSelect = { controller.assignModel(it, ModelCategory.TEXT) }\n            )\n        }\n\n        item {\n            Text("Задания", fontWeight = FontWeight.Bold)\n            Text("Каждое поле — отдельный запрос. Файлы можно добавить отдельно к нужной задаче.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)\n        }\n\n        item {\n            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {\n                tasks.forEachIndexed { index, task ->\n                    ElevatedCard(Modifier.fillMaxWidth()) {\n                        Column(Modifier.padding(10.dp)) {\n                            OutlinedTextField(\n                                value = task.text,\n                                onValueChange = { tasks[index] = task.copy(text = it) },\n                                modifier = Modifier.fillMaxWidth(),\n                                label = { Text("Задача ${index + 1}") },\n                                placeholder = { Text(if (index == 0) "Например: сделай краткое резюме текста" else "Введите независимое задание") },\n                                minLines = 2,\n                                maxLines = 7\n                            )\n                            Row(\n                                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),\n                                verticalAlignment = Alignment.CenterVertically\n                            ) {\n                                TextButton(\n                                    onClick = {\n                                        fileTargetIndex = index\n                                        taskFilePicker.launch(arrayOf("text/*", "application/json", "application/xml", "text/csv", "text/markdown", "application/yaml"))\n                                    }\n                                ) {\n                                    Text(if (task.files.isEmpty()) "+ Файлы к задаче" else "Файлы: ${task.files.size}")\n                                }\n                                Spacer(Modifier.weight(1f))\n                                if (task.files.isNotEmpty()) {\n                                    TextButton(onClick = { tasks[index] = task.copy(files = emptyList()) }) { Text("Убрать файлы") }\n                                }\n                                if (tasks.size > 1) {\n                                    TextButton(onClick = { tasks.removeAt(index) }) { Text("Удалить") }\n                                }\n                            }\n                        }\n                    }\n                }\n                FilledTonalButton(onClick = { tasks.add(BatchDraftTask()) }, modifier = Modifier.fillMaxWidth()) { Text("+ Добавить задачу") }\n            }\n        }\n\n        item {\n            Text("Быстро добавить списком", fontWeight = FontWeight.SemiBold)\n            Text("Если у вас уже есть список коротких задач, вставьте по одной задаче на строку.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)\n            OutlinedTextField(\n                value = bulkInput,\n                onValueChange = { bulkInput = it },\n                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),\n                label = { Text("Список задач") },\n                placeholder = { Text("Задача 1\\nЗадача 2\\nЗадача 3") },\n                minLines = 3,\n                maxLines = 8\n            )\n            FilledTonalButton(\n                onClick = {\n                    val imported = bulkInput.lineSequence().map(String::trim).filter(String::isNotBlank).toList()\n                    if (imported.isNotEmpty()) {\n                        if (tasks.size == 1 && tasks.first().text.isBlank() && tasks.first().files.isEmpty()) tasks.clear()\n                        tasks.addAll(imported.map { BatchDraftTask(text = it) })\n                        bulkInput = ""\n                    }\n                },\n                enabled = bulkInput.isNotBlank(),\n                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)\n            ) { Text("Разбить по строкам") }\n        }\n\n        item {\n            Button(\n                onClick = {\n                    val readyTasks = tasks.filter { it.text.isNotBlank() }\n                    val raw = readyTasks.joinToString("\\n---\\n") { it.text.trim() }\n                    controller.submitBatch(raw, readyTasks.map { it.files })\n                },\n                enabled = state.media.batchModel.endsWith(":batch", true) && readyCount > 0 && !state.loading,\n                modifier = Modifier.fillMaxWidth()\n            ) { Text("Запустить пакет · $readyCount") }\n        }\n\n        item {\n            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n                Text("История Batch", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)\n                TextButton(\n                    onClick = { clearHistoryConfirm = true },\n                    enabled = state.batches.any { it.status.terminal }\n                ) { Text("Очистить") }\n                TextButton(onClick = controller::refreshJobs) {\n                    Icon(Icons.Outlined.Refresh, null)\n                    Spacer(Modifier.width(4.dp))\n                    Text("Обновить")\n                }\n            }\n        }\n        if (state.batches.isEmpty()) item { Text("Пока нет Batch-заданий", color = MaterialTheme.colorScheme.onSurfaceVariant) }\n        items(state.batches, key = { it.id }) { job ->\n            ElevatedCard(Modifier.fillMaxWidth()) {\n                Column(Modifier.padding(12.dp)) {\n                    Text(job.title, fontWeight = FontWeight.SemiBold)\n                    Text("${batchLabel(job.status)} · ${job.completedItems}/${job.totalItems}", style = MaterialTheme.typography.bodySmall)\n                    Text(job.modelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)\n                    job.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }\n                }\n            }\n        }\n        item { HorizontalDivider(); Text("Видео-задания", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }\n        if (state.videos.isEmpty()) item { Text("Пока нет фоновых видео", color = MaterialTheme.colorScheme.onSurfaceVariant) }\n        items(state.videos, key = { it.id }) { job ->\n            ElevatedCard(Modifier.fillMaxWidth()) {\n                Column(Modifier.padding(12.dp)) {\n                    Text(job.modelId, fontWeight = FontWeight.SemiBold)\n                    Text(videoLabel(job.status), style = MaterialTheme.typography.bodySmall)\n                    Text(job.prompt, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)\n                    job.costUsd?.let { Text("Стоимость: ${formatUsdSmall(it)}", style = MaterialTheme.typography.bodySmall) }\n                }\n            }\n        }\n    }\n\n    if (clearHistoryConfirm) {\n        AlertDialog(\n            onDismissRequest = { clearHistoryConfirm = false },\n            title = { Text("Очистить историю Batch?") },\n            text = { Text("Готовые, ошибочные и отменённые записи будут удалены. Активные задания останутся и продолжат выполняться.") },\n            confirmButton = {\n                TextButton(onClick = {\n                    clearHistoryConfirm = false\n                    controller.clearFinishedBatchHistory()\n                }) { Text("Очистить") }\n            },\n            dismissButton = { TextButton(onClick = { clearHistoryConfirm = false }) { Text("Отмена") } }\n        )\n    }\n}\n\n'''
hub = replace_between(hub, old_jobs_start, old_jobs_end, new_jobs, "replace JobsPage")

# Root listens for the small OR speech button without opening the Hub dialog.
hub = replace_once(
    hub,
    '    val hubRequest by AsyncJobEvents.hubRequest.collectAsState()\n    val appState by viewModel.state.collectAsState()\n',
    '    val hubRequest by AsyncJobEvents.hubRequest.collectAsState()\n    val speechRequest by AsyncJobEvents.speechRequest.collectAsState()\n    val appState by viewModel.state.collectAsState()\n',
    "Hub speech request state"
)
root_marker = '    LaunchedEffect(hubRequest) {\n'
root_insert = '''    LaunchedEffect(speechRequest) {\n        val request = speechRequest ?: return@LaunchedEffect\n        AsyncJobEvents.consumeSpeechRequest()\n        controller.synthesizeAnswer(request.chatId, request.text)\n    }\n\n'''
if root_marker not in hub:
    raise SystemExit("Hub speech request effect marker not found")
hub = hub.replace(root_marker, root_insert + root_marker, 1)
write(hub_path, hub)


# 6) Main UI: web search and reasoning together at the bottom, OR speech action,
# compact guide setting, and a speech-model settings entry.
app_path = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
app = read(app_path)
web_top_block = '''                if (!imagePromptMode) {\n                    ComposerToolRow(\n                        icon = Icons.Outlined.Language,\n                        title = "Поиск в сети",\n                        subtitle = if (openRouterProfile) "OpenRouter web search" else "Недоступно для этого подключения",\n                        checked = state.webSearchEnabled,\n                        enabled = openRouterProfile,\n                        onCheckedChange = vm::setWebSearchEnabled\n                    )\n                }\n\n'''
app = replace_once(app, web_top_block, '', "remove top web search toggle")
reasoning_tail = '''                    ComposerToolRow(\n                        icon = Icons.Outlined.Psychology,\n                        title = "Размышление",\n                        subtitle = if (reasoningAvailable) "Использовать reasoning выбранной модели" else "Модель не поддерживает",\n                        checked = state.reasoningEnabled,\n                        enabled = reasoningAvailable,\n                        onCheckedChange = vm::setReasoningEnabled\n                    )\n\n                }'''
reasoning_with_web = '''                    ComposerToolRow(\n                        icon = Icons.Outlined.Psychology,\n                        title = "Размышление",\n                        subtitle = if (reasoningAvailable) "Использовать reasoning выбранной модели" else "Модель не поддерживает",\n                        checked = state.reasoningEnabled,\n                        enabled = reasoningAvailable,\n                        onCheckedChange = vm::setReasoningEnabled\n                    )\n                    ComposerToolRow(\n                        icon = Icons.Outlined.Language,\n                        title = "Поиск в сети",\n                        subtitle = if (openRouterProfile) "OpenRouter web search" else "Недоступно для этого подключения",\n                        checked = state.webSearchEnabled,\n                        enabled = openRouterProfile,\n                        onCheckedChange = vm::setWebSearchEnabled\n                    )\n                }'''
app = replace_once(app, reasoning_tail, reasoning_with_web, "put web search beside reasoning")

app = replace_once(
    app,
    '                    message = message,\n                    tts = tts,\n',
    '                    message = message,\n                    tts = tts,\n                    openRouterSpeechEnabled = state.openRouterSpeechModel.isNotBlank(),\n                    onOpenRouterSpeech = { com.ayuemin.ymnik.AsyncJobEvents.requestSpeech(state.currentChatId, message.text) },\n',
    "MessageCard OR speech args"
)
app = replace_once(
    app,
    'private fun MessageCard(\n    message: ChatMessage,\n    tts: TtsController,\n',
    'private fun MessageCard(\n    message: ChatMessage,\n    tts: TtsController,\n    openRouterSpeechEnabled: Boolean,\n    onOpenRouterSpeech: () -> Unit,\n',
    "MessageCard OR speech signature"
)
app = replace_once(
    app,
    '''                        onClick = { tts.toggle(message.id, message.text) }\n                    )\n                    CompactMessageAction(\n                        icon = Icons.Outlined.Download,''',
    '''                        onClick = { tts.toggle(message.id, message.text) }\n                    )\n                    OpenRouterSpeechAction(\n                        enabled = openRouterSpeechEnabled,\n                        onClick = onOpenRouterSpeech\n                    )\n                    CompactMessageAction(\n                        icon = Icons.Outlined.Download,''',
    "OR speech action under assistant reply"
)
openrouter_action_marker = '@Composable\nprivate fun WorkingStopIcon() {'
openrouter_action = '''@Composable\nprivate fun OpenRouterSpeechAction(enabled: Boolean, onClick: () -> Unit) {\n    val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)\n    IconButton(\n        onClick = onClick,\n        enabled = enabled,\n        modifier = Modifier.size(38.dp)\n    ) {\n        Box(Modifier.size(25.dp)) {\n            Icon(\n                Icons.Outlined.VolumeUp,\n                contentDescription = "Озвучить через OpenRouter",\n                modifier = Modifier.size(18.dp).align(Alignment.CenterStart),\n                tint = tint\n            )\n            Text(\n                "OR",\n                modifier = Modifier.align(Alignment.BottomEnd).scale(0.72f),\n                style = MaterialTheme.typography.labelSmall,\n                fontWeight = FontWeight.Bold,\n                color = tint\n            )\n        }\n    }\n}\n\n'''
if openrouter_action_marker not in app:
    raise SystemExit("OpenRouter speech action insertion marker not found")
app = app.replace(openrouter_action_marker, openrouter_action + openrouter_action_marker, 1)

app = replace_once(
    app,
    '    var soundExpanded by remember { mutableStateOf(false) }\n    var storageExpanded by remember { mutableStateOf(false) }\n',
    '    var soundExpanded by remember { mutableStateOf(false) }\n    var openRouterSpeechExpanded by remember { mutableStateOf(false) }\n    var storageExpanded by remember { mutableStateOf(false) }\n',
    "speech settings expanded state"
)

storage_item_marker = '''            item {\n                ExpandableSettingsCard(\n                    title = "Хранилище Umnik",'''
speech_settings_item = '''            item {\n                ExpandableSettingsCard(\n                    title = "Озвучивание OpenRouter",\n                    subtitle = state.openRouterSpeechModel.substringAfterLast('/').ifBlank { "Модель не выбрана" },\n                    icon = Icons.Outlined.VolumeUp,\n                    expanded = openRouterSpeechExpanded,\n                    onToggle = { openRouterSpeechExpanded = !openRouterSpeechExpanded }\n                ) {\n                    Text(\n                        "Эта модель используется кнопкой OR под ответами. Если модель не выбрана, кнопка остаётся неактивной.",\n                        style = MaterialTheme.typography.bodySmall,\n                        color = MaterialTheme.colorScheme.onSurfaceVariant\n                    )\n                    Spacer(Modifier.height(8.dp))\n                    FilledTonalButton(\n                        onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("speech") },\n                        modifier = Modifier.fillMaxWidth()\n                    ) {\n                        Icon(Icons.Outlined.VolumeUp, contentDescription = null)\n                        Spacer(Modifier.width(8.dp))\n                        Column(Modifier.weight(1f)) {\n                            Text("Выбрать модель озвучивания", fontWeight = FontWeight.Medium)\n                            Text(\n                                state.openRouterSpeechModel.ifBlank { "Не выбрана" },\n                                style = MaterialTheme.typography.bodySmall,\n                                maxLines = 1,\n                                overflow = TextOverflow.Ellipsis\n                            )\n                        }\n                    }\n                }\n            }\n\n'''
if storage_item_marker not in app:
    raise SystemExit("speech settings insertion marker not found")
app = app.replace(storage_item_marker, speech_settings_item + storage_item_marker, 1)

# Compact the guide card to one tappable row.
guide_title = 'Text("Памятка по использованию Umnik", fontWeight = FontWeight.Bold)'
guide_pos = app.find(guide_title)
if guide_pos < 0:
    raise SystemExit("guide title not found")
guide_start = app.rfind('            item {', 0, guide_pos)
guide_end_marker = '''\n\n            item {\n                ExpandableSettingsCard(\n                    title = "Диагностика и логи",'''
guide_end = app.find(guide_end_marker, guide_pos)
if guide_start < 0 or guide_end < 0:
    raise SystemExit("guide card boundaries not found")
compact_guide = '''            item {\n                ElevatedCard(\n                    modifier = Modifier.fillMaxWidth(),\n                    shape = RoundedCornerShape(18.dp),\n                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)\n                ) {\n                    TextButton(\n                        onClick = {\n                            vm.openUsageGuide()\n                            onBack()\n                        },\n                        modifier = Modifier.fillMaxWidth(),\n                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)\n                    ) {\n                        Icon(Icons.Outlined.Description, contentDescription = null)\n                        Spacer(Modifier.width(10.dp))\n                        Column(Modifier.weight(1f)) {\n                            Text("Памятка Umnik", fontWeight = FontWeight.Bold)\n                            Text("Краткое руководство", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)\n                        }\n                    }\n                }\n            }'''
app = app[:guide_start] + compact_guide + app[guide_end:]
write(app_path, app)


# 7) Sidebar chat actions: one overflow menu / long tap, favorites in their own section.
nav_path = "app/src/main/java/com/ayuemin/ymnik/ui/NavigationSidebar.kt"
nav = read(nav_path)
nav = replace_once(
    nav,
    'import androidx.compose.foundation.background\nimport androidx.compose.foundation.clickable\n',
    'import androidx.compose.foundation.ExperimentalFoundationApi\nimport androidx.compose.foundation.background\nimport androidx.compose.foundation.clickable\nimport androidx.compose.foundation.combinedClickable\n',
    "sidebar combinedClickable imports"
)
nav = replace_once(
    nav,
    'import androidx.compose.material.icons.outlined.Menu\n',
    'import androidx.compose.material.icons.outlined.Menu\nimport androidx.compose.material.icons.outlined.MoreVert\n',
    "sidebar MoreVert import"
)
nav = replace_once(
    nav,
    '''    val chats = state.chats\n        .filter { it.projectId == null }\n        .sortedWith(compareByDescending<ChatSession> { it.isFavorite }.thenByDescending { it.updatedAt })\n        .filter { chat ->\n            normalized.isBlank() ||\n                chat.title.contains(normalized, ignoreCase = true) ||\n                chat.projectId?.let { projectId ->\n                    state.projects.firstOrNull { it.id == projectId }?.name?.contains(normalized, ignoreCase = true)\n                } == true\n        }\n''',
    '''    val filteredChats = state.chats\n        .filter { it.projectId == null }\n        .sortedByDescending { it.updatedAt }\n        .filter { chat ->\n            normalized.isBlank() || chat.title.contains(normalized, ignoreCase = true)\n        }\n    val favoriteChats = filteredChats.filter { it.isFavorite }\n    val chats = filteredChats.filterNot { it.isFavorite }\n''',
    "sidebar favorite split"
)
history_start = '''                    item {\n                        HorizontalDivider(Modifier.padding(vertical = 8.dp))\n                        SidebarSectionTitle("История чатов")\n                    }\n'''
history_end = '''                }\n\n                HorizontalDivider()'''
new_history = '''                    if (favoriteChats.isNotEmpty()) {\n                        item {\n                            HorizontalDivider(Modifier.padding(vertical = 8.dp))\n                            SidebarSectionTitle("Избранные")\n                        }\n                        items(favoriteChats, key = { "favorite-${it.id}" }) { chat ->\n                            SidebarChatRow(\n                                chat = chat,\n                                state = state,\n                                vm = vm,\n                                onOpen = {\n                                    vm.switchChat(chat.id)\n                                    onDismiss()\n                                },\n                                onDelete = { deleteTarget = chat },\n                                onRename = {\n                                    renameTarget = chat\n                                    renameValue = chat.title\n                                }\n                            )\n                        }\n                    }\n\n                    item {\n                        HorizontalDivider(Modifier.padding(vertical = 8.dp))\n                        SidebarSectionTitle("История чатов")\n                    }\n\n                    if (chats.isEmpty()) {\n                        if (favoriteChats.isEmpty()) {\n                            item {\n                                Text(\n                                    if (normalized.isBlank()) "Чатов пока нет" else "Ничего не найдено",\n                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),\n                                    style = MaterialTheme.typography.bodySmall,\n                                    color = MaterialTheme.colorScheme.onSurfaceVariant\n                                )\n                            }\n                        }\n                    } else {\n                        items(chats, key = { "chat-${it.id}" }) { chat ->\n                            SidebarChatRow(\n                                chat = chat,\n                                state = state,\n                                vm = vm,\n                                onOpen = {\n                                    vm.switchChat(chat.id)\n                                    onDismiss()\n                                },\n                                onDelete = { deleteTarget = chat },\n                                onRename = {\n                                    renameTarget = chat\n                                    renameValue = chat.title\n                                }\n                            )\n                        }\n                    }\n'''
nav = replace_between(nav, history_start, history_end, new_history, "sidebar history groups")
new_chat_row = '''@OptIn(ExperimentalFoundationApi::class)\n@Composable\nprivate fun SidebarChatRow(\n    chat: ChatSession,\n    state: UiState,\n    vm: ChatViewModel,\n    onOpen: () -> Unit,\n    onDelete: () -> Unit,\n    onRename: () -> Unit\n) {\n    var actionsOpen by remember(chat.id) { mutableStateOf(false) }\n    val projectName = chat.projectId?.let { id -> state.projects.firstOrNull { it.id == id }?.name }\n    Surface(\n        modifier = Modifier\n            .fillMaxWidth()\n            .padding(vertical = 1.dp)\n            .combinedClickable(\n                onClick = onOpen,\n                onLongClick = { actionsOpen = true }\n            ),\n        shape = RoundedCornerShape(10.dp),\n        color = if (chat.id == state.currentChatId)\n            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)\n        else Color.Transparent\n    ) {\n        Row(\n            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 7.dp, bottom = 7.dp, end = 2.dp),\n            verticalAlignment = Alignment.CenterVertically\n        ) {\n            Column(Modifier.weight(1f)) {\n                Text(\n                    chat.title,\n                    maxLines = 1,\n                    overflow = TextOverflow.Ellipsis,\n                    fontWeight = if (chat.id == state.currentChatId) FontWeight.Bold else FontWeight.Medium\n                )\n                Text(\n                    buildString {\n                        if (projectName != null) append("$projectName · ")\n                        append(sidebarDate(chat.updatedAt))\n                    },\n                    maxLines = 1,\n                    overflow = TextOverflow.Ellipsis,\n                    style = MaterialTheme.typography.bodySmall,\n                    color = MaterialTheme.colorScheme.onSurfaceVariant\n                )\n            }\n            Box {\n                IconButton(\n                    onClick = { actionsOpen = true },\n                    modifier = Modifier.size(38.dp)\n                ) {\n                    Icon(Icons.Outlined.MoreVert, contentDescription = "Действия с чатом", modifier = Modifier.size(20.dp))\n                }\n                DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {\n                    DropdownMenuItem(\n                        text = { Text(if (chat.isFavorite) "Убрать из избранного" else "В избранное") },\n                        leadingIcon = { Icon(if (chat.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder, contentDescription = null) },\n                        onClick = {\n                            actionsOpen = false\n                            vm.setChatFavorite(chat.id, !chat.isFavorite)\n                        }\n                    )\n                    DropdownMenuItem(\n                        text = { Text("Переименовать") },\n                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },\n                        enabled = !state.isLoading,\n                        onClick = {\n                            actionsOpen = false\n                            onRename()\n                        }\n                    )\n                    DropdownMenuItem(\n                        text = { Text("Удалить") },\n                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },\n                        enabled = !state.isLoading,\n                        onClick = {\n                            actionsOpen = false\n                            onDelete()\n                        }\n                    )\n                }\n            }\n        }\n    }\n}\n\n'''
nav = replace_between(nav, '@Composable\nprivate fun SidebarChatRow(', 'private fun sidebarDate(', new_chat_row, "sidebar chat row")
write(nav_path, nav)


# 8) Version + changelog.
build_path = "app/build.gradle.kts"
build = read(build_path)
build = replace_once(build, '// Umnik v1.10.1', '// Umnik v1.10.2', "build comment version")
build = replace_once(build, 'versionCode = 101', 'versionCode = 102', "versionCode")
build = replace_once(build, 'versionName = "1.10.1"', 'versionName = "1.10.2"', "versionName")
write(build_path, build)

changelog_path = "CHANGELOG.md"
changelog = read(changelog_path)
release_notes = '''## v1.10.2 - 2026-09-14\n\n- «Размышление» и «Поиск в сети» теперь находятся вместе внизу меню `+`, на одном уровне.\n- Batch получил отдельные текстовые файлы для каждой задачи: материалы одной задачи больше не добавляются ко всему пакету.\n- В историю Batch добавлена очистка завершённых/ошибочных/отменённых записей с сохранением активных задач.\n- Пункт «Памятка Umnik» в настройках стал компактным.\n- Действия с чатами перенесены в меню `⋮` и на долгий тап. Избранные чаты вынесены в отдельный раздел «Избранные».\n- Под ответами добавлена отдельная кнопка озвучивания через OpenRouter с меткой `OR`. Она активна только при выбранной Speech-модели. В настройках появился отдельный пункт выбора модели озвучивания OpenRouter.\n- Версия: 1.10.2 / versionCode 102.\n\n'''
changelog = replace_once(changelog, '## Unreleased\n\n', '## Unreleased\n\n' + release_notes, "changelog release section")
write(changelog_path, changelog)

print("v1.10.2 migration applied")
