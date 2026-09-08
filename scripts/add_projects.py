from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace(path: str, old: str, new: str):
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Pattern not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# Models: configurable reasoning effort and per-chat mode.
replace(
    "app/src/main/java/com/ayuemin/ymnik/model/Models.kt",
    '''enum class ChatMode {\n    TEXT,\n    IMAGE\n}\n\nenum class ThemeChoice {''',
    '''enum class ChatMode {\n    TEXT,\n    IMAGE\n}\n\nenum class ReasoningEffort(val apiValue: String) {\n    MINIMAL("minimal"),\n    LOW("low"),\n    MEDIUM("medium"),\n    HIGH("high"),\n    XHIGH("xhigh")\n}\n\nenum class ThemeChoice {'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/model/Models.kt",
    '''    val projectId: String? = null,\n    val isFavorite: Boolean = false,''',
    '''    val projectId: String? = null,\n    val mode: ChatMode? = null,\n    val isFavorite: Boolean = false,'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/model/Models.kt",
    '''    val reasoningEnabled: Boolean = false,\n    val apiKeyConfigured: Boolean = false,''',
    '''    val reasoningEnabled: Boolean = false,\n    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,\n    val apiKeyConfigured: Boolean = false,'''
)

# ViewModel: per-chat mode, configurable reasoning, persistent generated/exported files.
replace(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''import com.ayuemin.ymnik.model.ProjectFile\nimport com.ayuemin.ymnik.model.StoredFile''',
    '''import com.ayuemin.ymnik.model.ProjectFile\nimport com.ayuemin.ymnik.model.ReasoningEffort\nimport com.ayuemin.ymnik.model.StoredFile'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''            mode = runCatching {\n                ChatMode.valueOf(prefs.getString("chat_mode", ChatMode.TEXT.name) ?: ChatMode.TEXT.name)\n            }.getOrDefault(ChatMode.TEXT),''',
    '''            mode = initialChat.mode ?: runCatching {\n                ChatMode.valueOf(prefs.getString("chat_mode", ChatMode.TEXT.name) ?: ChatMode.TEXT.name)\n            }.getOrDefault(ChatMode.TEXT),'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''            reasoningEnabled = prefs.getBoolean("reasoning_enabled", false),\n            apiKeyConfigured = !secrets.getApiKey().isNullOrBlank(),''',
    '''            reasoningEnabled = prefs.getBoolean("reasoning_enabled", false),\n            reasoningEffort = runCatching {\n                ReasoningEffort.valueOf(prefs.getString("reasoning_effort", ReasoningEffort.MEDIUM.name) ?: ReasoningEffort.MEDIUM.name)\n            }.getOrDefault(ReasoningEffort.MEDIUM),\n            apiKeyConfigured = !secrets.getApiKey().isNullOrBlank(),'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''    fun setMode(mode: ChatMode) {\n        prefs.edit().putString("chat_mode", mode.name).apply()\n        _state.value = _state.value.copy(mode = mode)\n    }''',
    '''    fun setMode(mode: ChatMode) {\n        prefs.edit().putString("chat_mode", mode.name).apply()\n        val chats = _state.value.chats.map { chat ->\n            if (chat.id == _state.value.currentChatId) chat.copy(mode = mode) else chat\n        }\n        chatsRepository.save(chats)\n        _state.value = _state.value.copy(mode = mode, chats = chats)\n    }'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''    fun setReasoningEnabled(enabled: Boolean) {\n        prefs.edit().putBoolean("reasoning_enabled", enabled).apply()\n        _state.value = _state.value.copy(reasoningEnabled = enabled)\n    }''',
    '''    fun setReasoningEnabled(enabled: Boolean) {\n        prefs.edit().putBoolean("reasoning_enabled", enabled).apply()\n        _state.value = _state.value.copy(reasoningEnabled = enabled)\n    }\n\n    fun setReasoningEffort(effort: ReasoningEffort) {\n        prefs.edit().putString("reasoning_effort", effort.name).apply()\n        _state.value = _state.value.copy(reasoningEffort = effort)\n    }'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''        val chat = ChatSession(\n            id = UUID.randomUUID().toString(),\n            title = "Новый чат",\n            projectId = projectId\n        )''',
    '''        val chat = ChatSession(\n            id = UUID.randomUUID().toString(),\n            title = "Новый чат",\n            projectId = projectId,\n            mode = _state.value.mode\n        )'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''        prefs.edit().putString("current_chat_id", id).apply()\n        _state.value = _state.value.copy(\n            currentChatId = id,\n            messages = chat.messages,\n            pendingAttachments = emptyList()\n        )''',
    '''        val nextMode = chat.mode ?: _state.value.mode\n        prefs.edit()\n            .putString("current_chat_id", id)\n            .putString("chat_mode", nextMode.name)\n            .apply()\n        _state.value = _state.value.copy(\n            currentChatId = id,\n            messages = chat.messages,\n            mode = nextMode,\n            pendingAttachments = emptyList()\n        )'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''        val target = _state.value.chats.firstOrNull { it.id == id } ?: return\n        target.messages.flatMap { it.generatedFiles }.forEach { File(it.localPath).delete() }\n\n        var remaining''',
    '''        if (_state.value.chats.none { it.id == id }) return\n\n        var remaining'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''            remaining = listOf(ChatSession(UUID.randomUUID().toString(), "Новый чат"))''',
    '''            remaining = listOf(ChatSession(UUID.randomUUID().toString(), "Новый чат", mode = _state.value.mode))'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''            currentChatId = selected.id,\n            messages = selected.messages,\n            pendingAttachments = emptyList(),''',
    '''            currentChatId = selected.id,\n            messages = selected.messages,\n            mode = selected.mode ?: _state.value.mode,\n            pendingAttachments = emptyList(),'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''        val reasoningEnabled = _state.value.reasoningEnabled\n\n        viewModelScope.launch {''',
    '''        val reasoningEnabled = _state.value.reasoningEnabled\n        val reasoningEffort = _state.value.reasoningEffort\n\n        viewModelScope.launch {'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''                            webSearchEnabled,\n                            reasoningEnabled\n                        )''',
    '''                            webSearchEnabled,\n                            reasoningEnabled,\n                            reasoningEffort.apiValue\n                        )'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''        val dir = File(context.cacheDir, "exports").apply { mkdirs() }''',
    '''        val dir = File(context.filesDir, "exports").apply { mkdirs() }'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''        _state.value.messages.flatMap { it.generatedFiles }.forEach { File(it.localPath).delete() }\n        val chats = replaceChatMessages''',
    '''        val chats = replaceChatMessages'''
)

# OpenRouter: pass selected reasoning effort.
replace(
    "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt",
    '''        systemPrompt: String,\n        webSearchEnabled: Boolean = false,\n        reasoningEnabled: Boolean = false\n    ): Result''',
    '''        systemPrompt: String,\n        webSearchEnabled: Boolean = false,\n        reasoningEnabled: Boolean = false,\n        reasoningEffort: String = "medium"\n    ): Result'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt",
    '''                        addProperty("enabled", true)\n                        addProperty("effort", "medium")\n                        addProperty("exclude", true)''',
    '''                        addProperty("enabled", true)\n                        addProperty("effort", reasoningEffort)\n                        addProperty("exclude", true)'''
)

# Storage: exports are persistent app files, not cache.
replace(
    "app/src/main/java/com/ayuemin/ymnik/data/StorageRepository.kt",
    '''    private val exportsRoot = File(context.cacheDir, "exports").apply { mkdirs() }''',
    '''    private val exportsRoot = File(context.filesDir, "exports").apply { mkdirs() }'''
)

# UI: top new-chat button; mode switches no longer open model picker; models/reasoning moved to Settings.
replace(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''import com.ayuemin.ymnik.model.GeneratedFile\nimport com.ayuemin.ymnik.model.StoredFile''',
    '''import com.ayuemin.ymnik.model.GeneratedFile\nimport com.ayuemin.ymnik.model.ReasoningEffort\nimport com.ayuemin.ymnik.model.StoredFile'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''    var fileToSave by remember { mutableStateOf<GeneratedFile?>(null) }\n    var pickerMode by remember { mutableStateOf<ChatMode?>(null) }\n    var chatsOpen''',
    '''    var fileToSave by remember { mutableStateOf<GeneratedFile?>(null) }\n    var chatsOpen'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''            onProjects = { projectsOpen = true },\n            onSelectMode = { mode ->\n                vm.setMode(mode)\n                pickerMode = mode\n            },\n            onClear = vm::clearChat''',
    '''            onProjects = { projectsOpen = true },\n            onSelectMode = vm::setMode,\n            onNewChat = {\n                val projectId = state.chats.firstOrNull { it.id == state.currentChatId }?.projectId\n                vm.createChat(projectId)\n            },\n            onClear = vm::clearChat'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''    pickerMode?.let { mode ->\n        ModelPickerDialog(\n            mode = mode,\n            state = state,\n            vm = vm,\n            onDismiss = { pickerMode = null }\n        )\n    }\n\n''',
    ''''''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''    onProjects: () -> Unit,\n    onSelectMode: (ChatMode) -> Unit,\n    onClear: () -> Unit''',
    '''    onProjects: () -> Unit,\n    onSelectMode: (ChatMode) -> Unit,\n    onNewChat: () -> Unit,\n    onClear: () -> Unit'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''                CompactModeIcon(\n                    selected = state.mode == ChatMode.IMAGE,\n                    icon = Icons.Outlined.Image,\n                    description = "Модель изображений",\n                    onClick = { onSelectMode(ChatMode.IMAGE) }\n                )\n\n                if (state.isLoading) {''',
    '''                CompactModeIcon(\n                    selected = state.mode == ChatMode.IMAGE,\n                    icon = Icons.Outlined.Image,\n                    description = "Режим изображений",\n                    onClick = { onSelectMode(ChatMode.IMAGE) }\n                )\n\n                IconButton(\n                    onClick = onNewChat,\n                    enabled = !state.isLoading,\n                    modifier = Modifier.size(36.dp)\n                ) {\n                    Icon(Icons.Outlined.Add, contentDescription = "Новый чат", modifier = Modifier.size(21.dp))\n                }\n\n                if (state.isLoading) {'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''                    description = "Текстовая модель",''',
    '''                    description = "Текстовый режим",'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''private fun SettingsScreen(state: UiState, vm: ChatViewModel) {\n    var key by remember { mutableStateOf("") }\n    var storageOpen by remember { mutableStateOf(false) }\n    val themes = ThemeChoice.entries''',
    '''private fun SettingsScreen(state: UiState, vm: ChatViewModel) {\n    var key by remember { mutableStateOf("") }\n    var storageOpen by remember { mutableStateOf(false) }\n    var modelPicker by remember { mutableStateOf<ChatMode?>(null) }\n    val themes = ThemeChoice.entries\n    val reasoningEfforts = ReasoningEffort.entries'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''                        "Ключ шифруется через Android Keystore. Модель выбирается значками текста и изображения в шапке чата.",''',
    '''                        "Ключ шифруется через Android Keystore. Модели выбираются ниже в настройках.",'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''                        Switch(\n                            checked = state.answerSoundEnabled,\n                            onCheckedChange = vm::setAnswerSoundEnabled\n                        )\n                    }\n\n                    Spacer(Modifier.height(16.dp))\n                    Row(verticalAlignment = Alignment.CenterVertically) {\n                        Icon(Icons.Outlined.Palette, contentDescription = null)''',
    '''                        Switch(\n                            checked = state.answerSoundEnabled,\n                            onCheckedChange = vm::setAnswerSoundEnabled\n                        )\n                    }\n\n                    Spacer(Modifier.height(16.dp))\n                    Row(verticalAlignment = Alignment.CenterVertically) {\n                        Icon(Icons.Outlined.Psychology, contentDescription = null)\n                        Spacer(Modifier.width(10.dp))\n                        Column(Modifier.weight(1f)) {\n                            Text("Сила размышления", fontWeight = FontWeight.Medium)\n                            Text(\n                                "Используется, когда значок размышления включён в чате",\n                                style = MaterialTheme.typography.bodySmall,\n                                color = MaterialTheme.colorScheme.onSurfaceVariant\n                            )\n                        }\n                    }\n                    Spacer(Modifier.height(8.dp))\n                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                        items(reasoningEfforts) { effort ->\n                            FilterChip(\n                                selected = state.reasoningEffort == effort,\n                                onClick = { vm.setReasoningEffort(effort) },\n                                label = { Text(reasoningEffortLabel(effort)) }\n                            )\n                        }\n                    }\n                    Text(\n                        "Не каждая модель поддерживает все уровни. Средний — наиболее совместимый вариант.",\n                        style = MaterialTheme.typography.bodySmall,\n                        color = MaterialTheme.colorScheme.onSurfaceVariant\n                    )\n\n                    Spacer(Modifier.height(16.dp))\n                    Row(verticalAlignment = Alignment.CenterVertically) {\n                        Icon(Icons.Outlined.Palette, contentDescription = null)'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''                    Text("Текущие модели", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)\n                    Spacer(Modifier.height(7.dp))\n                    Text("Текст: ${state.textModel}", style = MaterialTheme.typography.bodyMedium)\n                    Spacer(Modifier.height(4.dp))\n                    Text("Изображения: ${state.imageModel}", style = MaterialTheme.typography.bodyMedium)''',
    '''                    Text("Модели", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)\n                    Spacer(Modifier.height(9.dp))\n                    FilledTonalButton(\n                        onClick = { modelPicker = ChatMode.TEXT },\n                        modifier = Modifier.fillMaxWidth()\n                    ) {\n                        Icon(Icons.Outlined.TextFields, contentDescription = null)\n                        Spacer(Modifier.width(8.dp))\n                        Column(Modifier.weight(1f)) {\n                            Text("Текстовая", fontWeight = FontWeight.Medium)\n                            Text(state.textModel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)\n                        }\n                    }\n                    Spacer(Modifier.height(7.dp))\n                    FilledTonalButton(\n                        onClick = { modelPicker = ChatMode.IMAGE },\n                        modifier = Modifier.fillMaxWidth()\n                    ) {\n                        Icon(Icons.Outlined.Image, contentDescription = null)\n                        Spacer(Modifier.width(8.dp))\n                        Column(Modifier.weight(1f)) {\n                            Text("Изображения", fontWeight = FontWeight.Medium)\n                            Text(state.imageModel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)\n                        }\n                    }'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''    if (storageOpen) {\n        StorageDialog(state = state, vm = vm, onDismiss = { storageOpen = false })\n    }\n}\n\n@Composable\nprivate fun StorageDialog''',
    '''    if (storageOpen) {\n        StorageDialog(state = state, vm = vm, onDismiss = { storageOpen = false })\n    }\n\n    modelPicker?.let { mode ->\n        ModelPickerDialog(\n            mode = mode,\n            state = state,\n            vm = vm,\n            onDismiss = { modelPicker = null }\n        )\n    }\n}\n\n@Composable\nprivate fun StorageDialog'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''private fun themeLabel(choice: ThemeChoice): String = when (choice) {''',
    '''private fun reasoningEffortLabel(effort: ReasoningEffort): String = when (effort) {\n    ReasoningEffort.MINIMAL -> "Минимальная"\n    ReasoningEffort.LOW -> "Низкая"\n    ReasoningEffort.MEDIUM -> "Средняя"\n    ReasoningEffort.HIGH -> "Высокая"\n    ReasoningEffort.XHIGH -> "Максимальная"\n}\n\nprivate fun themeLabel(choice: ThemeChoice): String = when (choice) {'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''«${chat.title}» и связанные с ним сгенерированные файлы будут удалены.''',
    '''«${chat.title}» будет удалён. Сгенерированные файлы останутся в хранилище Umnik.'''
)
replace(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''Text("Будут удалены сгенерированные изображения/файлы и временный экспорт. Тексты диалогов, API-ключ и навыки останутся.")''',
    '''Text("Будут удалены сохранённые внутри Umnik изображения, сгенерированные файлы и экспорт. Тексты диалогов, API-ключ, проекты и навыки останутся.")'''
)

replace(
    "app/src/main/java/com/ayuemin/ymnik/ui/ProjectDialogs.kt",
    '''text = { Text("«${chat.title}» будет удалён вместе с его локальными сгенерированными файлами.") },''',
    '''text = { Text("«${chat.title}» будет удалён. Его сгенерированные файлы останутся в хранилище Umnik.") },'''
)

print("Umnik 0.6.1 patch applied")
