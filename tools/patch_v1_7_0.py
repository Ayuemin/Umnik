from pathlib import Path
import re


def load(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def save(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, repl: str, label: str, flags: int = 0) -> str:
    compiled = re.compile(pattern, flags)
    result, count = compiled.subn(lambda _: repl, text, count=1)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one regex match, got {count}")
    return result


# 1. Sidebar: keyboard no longer covers navigation; New chat is always reachable near the top.
path = "app/src/main/java/com/ayuemin/ymnik/ui/NavigationSidebar.kt"
text = load(path)
text = replace_once(
    text,
    "import androidx.compose.material3.HorizontalDivider\n",
    "import androidx.compose.material3.FilledTonalButton\nimport androidx.compose.material3.HorizontalDivider\n",
    "sidebar FilledTonalButton import",
)
text = replace_once(
    text,
    "import androidx.compose.runtime.Composable\n",
    "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.LaunchedEffect\n",
    "sidebar LaunchedEffect import",
)
text = replace_once(
    text,
    "import androidx.compose.ui.Alignment\n",
    "import androidx.compose.ui.Alignment\nimport androidx.compose.ui.ExperimentalComposeUiApi\n",
    "sidebar experimental import",
)
text = replace_once(
    text,
    "import androidx.compose.ui.graphics.Color\n",
    "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.platform.LocalFocusManager\nimport androidx.compose.ui.platform.LocalSoftwareKeyboardController\n",
    "sidebar keyboard imports",
)
text = replace_once(
    text,
    "@Composable\nfun NavigationSidebar(",
    "@OptIn(ExperimentalComposeUiApi::class)\n@Composable\nfun NavigationSidebar(",
    "sidebar optin",
)
text = replace_once(
    text,
    "    var clearConfirm by remember { mutableStateOf(false) }\n\n",
    """    var clearConfirm by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

""",
    "sidebar hide keyboard",
)
text = replace_once(
    text,
    """                if (searchOpen) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text("Поиск по чатам") }
                    )
                }

                HorizontalDivider()
""",
    """                if (searchOpen) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        placeholder = { Text("Поиск по чатам") }
                    )
                }

                FilledTonalButton(
                    onClick = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        onNewChat()
                    },
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Outlined.AddComment, contentDescription = null, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Новый чат")
                }

                HorizontalDivider()
""",
    "sidebar top new chat",
)
text = replace_once(
    text,
    """                    TextButton(
                        onClick = onNewChat,
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.AddComment, contentDescription = null, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Новый чат", maxLines = 1)
                    }

""",
    "",
    "sidebar remove bottom new chat",
)
save(path, text)


# 2. Main chat: task actions are launched from +; model plumbing lives in Settings.
path = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
text = load(path)
text = regex_once(
    text,
    r'\n\s*FilledTonalButton\(\n\s*onClick = \{\n\s*actionsOpen = false\n\s*com\.ayuemin\.ymnik\.AsyncJobEvents\.requestHub\("models"\)\n\s*\},\n\s*modifier = Modifier\.fillMaxWidth\(\)\n\s*\) \{\n\s*Icon\(Icons\.Outlined\.Extension, contentDescription = null\)\n\s*Spacer\(Modifier\.width\(8\.dp\)\)\n\s*Text\("OpenRouter Hub"\)\n\s*\}\n',
    "\n",
    "remove Hub from composer",
)
text = replace_once(
    text,
    """                HorizontalDivider()

                if (!imagePromptMode) {
""",
    """                Text(
                    "Инструменты OpenRouter",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ComposerActionTile(
                        icon = Icons.Outlined.Mic,
                        label = "Речь → текст",
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            actionsOpen = false
                            com.ayuemin.ymnik.AsyncJobEvents.requestHub("stt")
                        }
                    )
                    ComposerActionTile(
                        icon = Icons.Outlined.VolumeUp,
                        label = "Озвучить",
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            actionsOpen = false
                            com.ayuemin.ymnik.AsyncJobEvents.requestHub("speech")
                        }
                    )
                    ComposerActionTile(
                        icon = Icons.Outlined.Image,
                        label = "Видео",
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            actionsOpen = false
                            com.ayuemin.ymnik.AsyncJobEvents.requestHub("video")
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ComposerActionTile(
                        icon = Icons.Outlined.Description,
                        label = "Пакет задач",
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            actionsOpen = false
                            com.ayuemin.ymnik.AsyncJobEvents.requestHub("jobs")
                        }
                    )
                    ComposerActionTile(
                        icon = Icons.Outlined.Storage,
                        label = "Shell",
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            actionsOpen = false
                            com.ayuemin.ymnik.AsyncJobEvents.requestHub("shell")
                        }
                    )
                    Spacer(Modifier.weight(1f))
                }

                HorizontalDivider()

                if (!imagePromptMode) {
""",
    "composer OpenRouter tools",
)
quick_button = """                    FilledTonalButton(onClick = { quickModelsSettingsOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.SwapHoriz, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Быстрые модели", fontWeight = FontWeight.Medium)
                            Text(
                                if (state.quickTextModels.isEmpty()) "Только модель по умолчанию" else "Добавлено: ${state.quickTextModels.size}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
"""
text = replace_once(
    text,
    quick_button,
    quick_button
    + """                    Spacer(Modifier.height(7.dp))
                    FilledTonalButton(
                        onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("models-settings") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Каталог и модели OpenRouter", fontWeight = FontWeight.Medium)
                            Text(
                                "Видео, речь, Batch, Embeddings, Rerank, маршрутизация и RAG",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
""",
    "settings OpenRouter catalog button",
)
save(path, text)


# 3. OpenRouter UI: settings-only catalog and task-oriented operation sheets.
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHub.kt"
text = load(path)
text = replace_once(
    text,
    "private enum class HubPage { MODELS, ROUTING, TOOLS, JOBS, MEDIA, SHELL }\n",
    "private enum class HubPage { MODELS, ROUTING, TOOLS, JOBS, MEDIA, SHELL }\nprivate enum class MediaSection { ALL, VIDEO, TRANSCRIPTION, SPEECH }\n",
    "media section enum",
)
text = replace_once(
    text,
    "    var requestedPage by remember { mutableStateOf(HubPage.MODELS) }\n",
    "    var requestedPage by remember { mutableStateOf(HubPage.MODELS) }\n    var requestedMediaSection by remember { mutableStateOf(MediaSection.ALL) }\n",
    "root media section state",
)
text = regex_once(
    text,
    r'    LaunchedEffect\(hubRequest\) \{.*?\n    \}\n\n    UmnikTheme',
    '''    LaunchedEffect(hubRequest) {
        when (hubRequest) {
            "jobs", "batch" -> {
                requestedPage = HubPage.JOBS
                requestedMediaSection = MediaSection.ALL
                controller.refreshJobs()
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "models", "models-settings" -> {
                requestedPage = HubPage.MODELS
                requestedMediaSection = MediaSection.ALL
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "routing" -> {
                requestedPage = HubPage.ROUTING
                requestedMediaSection = MediaSection.ALL
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "tools", "rag" -> {
                requestedPage = HubPage.TOOLS
                requestedMediaSection = MediaSection.ALL
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "media" -> {
                requestedPage = HubPage.MEDIA
                requestedMediaSection = MediaSection.ALL
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "video" -> {
                requestedPage = HubPage.MEDIA
                requestedMediaSection = MediaSection.VIDEO
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "stt", "transcription" -> {
                requestedPage = HubPage.MEDIA
                requestedMediaSection = MediaSection.TRANSCRIPTION
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "speech", "tts" -> {
                requestedPage = HubPage.MEDIA
                requestedMediaSection = MediaSection.SPEECH
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
            "shell" -> {
                requestedPage = HubPage.SHELL
                requestedMediaSection = MediaSection.ALL
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
        }
    }

    UmnikTheme''',
    "hub request routing",
    re.S,
)
text = replace_once(
    text,
    "            OpenRouterHubDialog(controller = controller, viewModel = viewModel, initialPage = requestedPage, onDismiss = { open = false })\n",
    "            OpenRouterHubDialog(controller = controller, viewModel = viewModel, initialPage = requestedPage, initialMediaSection = requestedMediaSection, onDismiss = { open = false })\n",
    "hub dialog call",
)
text = replace_once(
    text,
    "    initialPage: HubPage,\n    onDismiss: () -> Unit\n",
    "    initialPage: HubPage,\n    initialMediaSection: MediaSection,\n    onDismiss: () -> Unit\n",
    "hub dialog signature",
)
text = replace_once(
    text,
    "    var page by remember(initialPage) { mutableStateOf(initialPage) }\n",
    "    var page by remember(initialPage) { mutableStateOf(initialPage) }\n    val settingsMode = initialPage == HubPage.MODELS || initialPage == HubPage.ROUTING || initialPage == HubPage.TOOLS\n",
    "settings mode",
)
text = replace_once(
    text,
    '''                                Text("OpenRouter Hub", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("Umnik 1.6.4 · полный каталог и возможности", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
''',
    '''                                Text(
                                    if (settingsMode) {
                                        "OpenRouter: модели и настройки"
                                    } else {
                                        when (page) {
                                            HubPage.JOBS -> "Пакетные и фоновые задачи"
                                            HubPage.MEDIA -> when (initialMediaSection) {
                                                MediaSection.VIDEO -> "Создание видео"
                                                MediaSection.TRANSCRIPTION -> "Распознавание речи"
                                                MediaSection.SPEECH -> "Озвучивание текста"
                                                MediaSection.ALL -> "Медиа"
                                            }
                                            HubPage.SHELL -> "OpenRouter Shell"
                                            else -> "OpenRouter"
                                        }
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (settingsMode) "Каталог, маршрутизация, Tools и RAG" else "Результат возвращается в текущий чат",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
''',
    "hub header",
)
text = replace_once(
    text,
    "                        HubPageBar(page = page, onPage = { page = it })\n",
    "                        if (settingsMode) HubPageBar(page = page, onPage = { page = it })\n",
    "settings-only page bar",
)
text = replace_once(
    text,
    "                        HubPage.MEDIA -> MediaPage(state, controller)\n",
    "                        HubPage.MEDIA -> MediaPage(state, controller, initialMediaSection)\n",
    "media page section",
)
for line, label in [
    ('        item { HubPageChip("Задания", HubPage.JOBS, page, onPage) }\n', "remove jobs tab"),
    ('        item { HubPageChip("Медиа", HubPage.MEDIA, page, onPage) }\n', "remove media tab"),
    ('        item { HubPageChip("Shell", HubPage.SHELL, page, onPage) }\n', "remove shell tab"),
]:
    text = replace_once(text, line, "", label)
text = replace_once(
    text,
    '''                if (ModelCategory.TEXT in model.categories) SmallAssignButton(if (model.isBatch) "Чат / Batch" else "В чат") { controller.useAsTextModel(model) }
                if (ModelCategory.IMAGE in model.categories) SmallAssignButton("Изображения") { controller.useAsImageModel(model) }
                if (model.isBatch) SmallAssignButton("Batch") { controller.assignModel(model, ModelCategory.TEXT) }
                if (ModelCategory.VIDEO in model.categories) SmallAssignButton("Видео") { controller.assignModel(model, ModelCategory.VIDEO) }
                if (ModelCategory.SPEECH in model.categories || ModelCategory.AUDIO in model.categories) SmallAssignButton("Озвучка") { controller.assignModel(model, ModelCategory.SPEECH) }
                if (ModelCategory.TRANSCRIPTION in model.categories) SmallAssignButton("Распознавание") { controller.assignModel(model, ModelCategory.TRANSCRIPTION) }
                if (ModelCategory.EMBEDDINGS in model.categories) SmallAssignButton("Embedding") { controller.assignModel(model, ModelCategory.EMBEDDINGS) }
                if (ModelCategory.RERANK in model.categories) SmallAssignButton("Rerank") { controller.assignModel(model, ModelCategory.RERANK) }
''',
    '''                if (ModelCategory.TEXT in model.categories && !model.isBatch) SmallAssignButton("Использовать в чате") { controller.useAsTextModel(model) }
                if (model.isBatch) SmallAssignButton("Batch в чате") { controller.useAsTextModel(model) }
                if (ModelCategory.IMAGE in model.categories) SmallAssignButton("Для изображений") { controller.useAsImageModel(model) }
                if (model.isBatch) SmallAssignButton("Для пакета задач") { controller.assignModel(model, ModelCategory.TEXT) }
                if (ModelCategory.VIDEO in model.categories) SmallAssignButton("Для видео") { controller.assignModel(model, ModelCategory.VIDEO) }
                if (ModelCategory.SPEECH in model.categories || ModelCategory.AUDIO in model.categories) SmallAssignButton("Для озвучивания") { controller.assignModel(model, ModelCategory.SPEECH) }
                if (ModelCategory.TRANSCRIPTION in model.categories) SmallAssignButton("Для распознавания") { controller.assignModel(model, ModelCategory.TRANSCRIPTION) }
                if (ModelCategory.EMBEDDINGS in model.categories) SmallAssignButton("Для поиска по документам") { controller.assignModel(model, ModelCategory.EMBEDDINGS) }
                if (ModelCategory.RERANK in model.categories) SmallAssignButton("Для точной сортировки") { controller.assignModel(model, ModelCategory.RERANK) }
''',
    "clear model action labels",
)
jobs_model_line = '                Text("Модель: ${state.media.batchModel.ifBlank { "не выбрана" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)\n'
text = replace_once(
    text,
    jobs_model_line,
    jobs_model_line
    + '''                Spacer(Modifier.height(6.dp))
                CategoryModelPicker(
                    title = "Модель для пакетных задач",
                    current = state.media.batchModel,
                    models = state.catalog.filter { it.isBatch && ModelCategory.TEXT in it.categories },
                    onSelect = { controller.assignModel(it, ModelCategory.TEXT) }
                )
                Text(
                    "Готовые результаты автоматически добавляются в исходный чат. Здесь также хранится история фоновых задач.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
''',
    "batch model picker",
)
media_function = r'''@Composable
private fun MediaPage(state: OpenRouterHubState, controller: OpenRouterHubController, section: MediaSection) {
    var videoPrompt by remember { mutableStateOf("") }
    val videoRefs = remember { mutableStateListOf<Uri>() }
    var speechText by remember { mutableStateOf("") }
    var voice by remember(state.media.voice) { mutableStateOf(state.media.voice) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        videoRefs.clear(); videoRefs.addAll(uris.take(4))
    }
    val sttPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(controller::transcribe) }
    val context = LocalContext.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (section == MediaSection.ALL || section == MediaSection.VIDEO) {
            item {
                Text("Генерация видео", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                CategoryModelPicker(
                    title = "Модель видео",
                    current = state.media.videoModel,
                    models = state.catalog.filter { ModelCategory.VIDEO in it.categories },
                    onSelect = { controller.assignModel(it, ModelCategory.VIDEO) }
                )
                OutlinedTextField(videoPrompt, { videoPrompt = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Описание видео") }, minLines = 3, maxLines = 7)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilledTonalButton(onClick = { videoPicker.launch(arrayOf("image/*", "video/*", "audio/*")) }, modifier = Modifier.weight(1f)) { Text(if (videoRefs.isEmpty()) "Референсы" else "Референсы: ${videoRefs.size}") }
                    Button(onClick = { controller.submitVideo(videoPrompt, videoRefs.toList()); videoPrompt = ""; videoRefs.clear() }, enabled = state.media.videoModel.isNotBlank() && videoPrompt.isNotBlank() && !state.loading, modifier = Modifier.weight(1f)) { Text("Создать") }
                }
                Text("Видео продолжит создаваться в фоне, а готовый файл появится в исходном чате.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            if (section == MediaSection.ALL) item { HorizontalDivider() }
        }

        if (section == MediaSection.ALL || section == MediaSection.TRANSCRIPTION) {
            item {
                Text("Распознавание речи", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                CategoryModelPicker(
                    title = "Модель распознавания",
                    current = state.media.transcriptionModel,
                    models = state.catalog.filter { ModelCategory.TRANSCRIPTION in it.categories },
                    onSelect = { controller.assignModel(it, ModelCategory.TRANSCRIPTION) }
                )
                FilledTonalButton(onClick = { sttPicker.launch(arrayOf("audio/*")) }, enabled = state.media.transcriptionModel.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Выбрать аудиофайл") }
                if (state.transcription.isNotBlank()) {
                    ElevatedCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(state.transcription)
                            TextButton(onClick = { copyToClipboard(context, state.transcription) }) { Text("Копировать") }
                        }
                    }
                }
                Text("Расшифровка также добавляется в текущий чат.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
            if (section == MediaSection.ALL) item { HorizontalDivider() }
        }

        if (section == MediaSection.ALL || section == MediaSection.SPEECH) {
            item {
                Text("Нейросетевая озвучка", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                CategoryModelPicker(
                    title = "Модель озвучивания",
                    current = state.media.speechModel,
                    models = state.catalog.filter { ModelCategory.SPEECH in it.categories || ModelCategory.AUDIO in it.categories },
                    onSelect = { controller.assignModel(it, ModelCategory.SPEECH) }
                )
                OutlinedTextField(voice, { voice = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Voice, если модель поддерживает") }, singleLine = true)
                OutlinedTextField(speechText, { speechText = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Текст для озвучивания") }, minLines = 3, maxLines = 8)
                Button(onClick = { controller.updateMedia(state.media.copy(voice = voice.trim())); controller.synthesize(speechText) }, enabled = state.media.speechModel.isNotBlank() && speechText.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) { Text("Создать аудио") }
                state.speechFile?.let { file ->
                    Text("Готово и добавлено в чат: ${file.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun ShellPage'''
text = regex_once(
    text,
    r'@Composable\nprivate fun MediaPage\(state: OpenRouterHubState, controller: OpenRouterHubController\) \{.*?\n\}\n\n@Composable\nprivate fun ShellPage',
    media_function,
    "rewrite media page",
    re.S,
)
text = replace_once(
    text,
    '            Text("Shell использует Responses API. Загруженные файлы передаются во временный контейнер; созданные контейнером файлы Umnik скачивает в своё хранилище.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)\n',
    '            Text("Shell использует Responses API. Загруженные файлы передаются во временный контейнер; созданные контейнером файлы Umnik скачивает в своё хранилище. Результат и созданные файлы добавляются в текущий чат.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)\n',
    "shell result explanation",
)
text = replace_once(
    text,
    "@Composable\nprivate fun ToggleRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {\n",
    r'''@Composable
private fun CategoryModelPicker(
    title: String,
    current: String,
    models: List<ModelInfo>,
    onSelect: (ModelInfo) -> Unit
) {
    var open by remember(title, current) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            FilledTonalButton(
                onClick = { open = true },
                enabled = models.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    current.ifBlank { if (models.isEmpty()) "Нет подходящих моделей" else "Выбрать модель" },
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                models.sortedBy { it.id }.take(160).forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model.id, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            open = false
                            onSelect(model)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
''',
    "category model picker",
)
save(path, text)


# 4. Controller: explicit assignments; STT/TTS results become regular chat entries.
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHubController.kt"
text = load(path)
text = regex_once(
    text,
    r'    fun assignModel\(model: ModelInfo, category: ModelCategory\) \{.*?\n    \}\n\n    fun submitBatch',
    r'''    fun assignModel(model: ModelInfo, category: ModelCategory) {
        when (category) {
            ModelCategory.TEXT -> {
                if (model.isBatch) {
                    val media = mutableState.value.media.copy(batchModel = model.id)
                    featurePrefs.saveMedia(media)
                    mutableState.value = mutableState.value.copy(media = media, status = "${model.id} назначена для пакетных задач")
                } else {
                    useAsTextModel(model)
                }
            }
            ModelCategory.IMAGE -> useAsImageModel(model)
            ModelCategory.VIDEO -> {
                val media = mutableState.value.media.copy(videoModel = model.id)
                featurePrefs.saveMedia(media)
                mutableState.value = mutableState.value.copy(media = media, status = "${model.id} назначена для видео")
            }
            ModelCategory.SPEECH, ModelCategory.AUDIO -> {
                val media = mutableState.value.media.copy(speechModel = model.id)
                featurePrefs.saveMedia(media)
                mutableState.value = mutableState.value.copy(media = media, status = "${model.id} назначена для озвучивания")
            }
            ModelCategory.TRANSCRIPTION -> {
                val media = mutableState.value.media.copy(transcriptionModel = model.id)
                featurePrefs.saveMedia(media)
                mutableState.value = mutableState.value.copy(media = media, status = "${model.id} назначена для распознавания речи")
            }
            ModelCategory.EMBEDDINGS -> {
                val rag = mutableState.value.rag.copy(embeddingModel = model.id)
                featurePrefs.saveRag(rag)
                mutableState.value = mutableState.value.copy(rag = rag, status = "${model.id} назначена для поиска по документам")
            }
            ModelCategory.RERANK -> {
                val rag = mutableState.value.rag.copy(rerankModel = model.id)
                featurePrefs.saveRag(rag)
                mutableState.value = mutableState.value.copy(rag = rag, status = "${model.id} назначена для точной сортировки результатов")
            }
        }
    }

    fun submitBatch''',
    "assign model intent",
    re.S,
)
text = regex_once(
    text,
    r'    fun transcribe\(uri: Uri\) \{.*?\n    \}\n\n    fun synthesize',
    r'''    fun transcribe(uri: Uri) {
        val profile = openRouterProfile()
        val model = mutableState.value.media.transcriptionModel.trim()
        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()
        val chatId = viewModel.state.value.currentChatId
        if (profile == null || key.isBlank()) { mutableState.value = mutableState.value.copy(status = "OpenRouter не настроен"); return }
        if (model.isBlank()) { mutableState.value = mutableState.value.copy(status = "Сначала выберите модель распознавания речи"); return }
        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, operation = "Распознаю аудио…", status = null)
            runCatching {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Не удалось прочитать аудио") }
                val format = uri.lastPathSegment?.substringAfterLast('.', "mp3") ?: "mp3"
                audioClient.transcribe(key, model, bytes, format, baseUrl = viewModel.connectionTextEndpoint(profile.id))
            }.onSuccess { result ->
                appendHubExchange(chatId, "[Распознавание речи]", result.text, emptyList())
                mutableState.value = mutableState.value.copy(loading = false, operation = null, transcription = result.text, status = "Расшифровка готова и добавлена в чат")
                AsyncJobEvents.notifyChanged()
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(loading = false, operation = null, status = error.message ?: "Не удалось распознать аудио")
            }
        }
    }

    fun synthesize''',
    "transcription to chat",
    re.S,
)
text = regex_once(
    text,
    r'    fun synthesize\(textRaw: String\) \{.*?\n    \}\n\n    fun runShell',
    r'''    fun synthesize(textRaw: String) {
        val text = textRaw.trim()
        val profile = openRouterProfile()
        val media = mutableState.value.media
        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()
        val chatId = viewModel.state.value.currentChatId
        if (text.isBlank()) { mutableState.value = mutableState.value.copy(status = "Введите текст для озвучивания"); return }
        if (profile == null || key.isBlank()) { mutableState.value = mutableState.value.copy(status = "OpenRouter не настроен"); return }
        if (media.speechModel.isBlank()) { mutableState.value = mutableState.value.copy(status = "Сначала выберите speech-модель"); return }
        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, operation = "Создаю аудио…", status = null)
            runCatching {
                val result = audioClient.synthesize(
                    apiKey = key,
                    model = media.speechModel,
                    input = text,
                    voice = media.voice.takeIf { it.isNotBlank() },
                    baseUrl = viewModel.connectionTextEndpoint(profile.id)
                )
                saveGeneratedAudio(result.bytes, result.mimeType, result.format)
            }.onSuccess { file ->
                appendHubExchange(chatId, "[Озвучивание]\n$text", "Аудио готово: ${file.name}", listOf(file))
                mutableState.value = mutableState.value.copy(loading = false, operation = null, speechFile = file, status = "Аудио создано и добавлено в чат")
                AsyncJobEvents.notifyChanged()
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(loading = false, operation = null, status = error.message ?: "Не удалось создать аудио")
            }
        }
    }

    fun runShell''',
    "speech to chat",
    re.S,
)
save(path, text)


# 5. Version and release notes.
path = "app/build.gradle.kts"
text = load(path)
text = replace_once(text, "// Umnik v1.6.4", "// Umnik v1.7.0", "version comment")
text = replace_once(text, "versionCode = 64", "versionCode = 70", "version code")
text = replace_once(text, 'versionName = "1.6.4"', 'versionName = "1.7.0"', "version name")
save(path, text)

path = "CHANGELOG.md"
text = load(path)
notes = '''## Unreleased

## v1.7.0 - 2026-09-14

- Переработан основной сценарий Umnik: распознавание речи, озвучивание, видео, пакетные задачи и Shell запускаются прямо из меню `+` в чате.
- Каталог OpenRouter, маршрутизация, Tools и RAG перенесены в «Настройки → Модели → Каталог и модели OpenRouter»; технические режимы больше не конкурируют с основным чатом.
- Результаты STT и нейросетевой озвучки автоматически добавляются в текущий чат; Shell, Batch и видео явно показывают, куда вернётся результат.
- Для Speech/STT/Video/Batch можно выбрать подходящую модель прямо перед запуском задачи, не возвращаясь в общий каталог.
- Кнопки под моделями получили однозначные подписи: «Использовать в чате», «Batch в чате», «Для озвучивания», «Для распознавания», «Для пакета задач», «Для поиска по документам» и другие.
- Боковая панель при открытии закрывает клавиатуру, а «Новый чат» перенесён наверх и больше не перекрывается экранной клавиатурой.
- Версия: 1.7.0 / versionCode 70.

'''
text = replace_once(text, "## Unreleased\n\n", notes, "changelog")
save(path, text)

# One-time release helpers do not remain in the released source tree.
for generated in [
    ".github/workflows/release-v1.7.0-once.yml",
    "tools/patch_v1_7_0.py",
]:
    p = Path(generated)
    if p.exists():
        p.unlink()
