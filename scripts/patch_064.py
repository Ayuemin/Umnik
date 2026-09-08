from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    if old not in text:
        raise RuntimeError(f"Pattern not found in {path}: {old[:220]!r}")
    write(path, text.replace(old, new, 1))


# ---------------- Models ----------------
models = "app/src/main/java/com/ayuemin/ymnik/model/Models.kt"
replace_once(
    models,
    '''    val projectId: String? = null,\n    val mode: ChatMode? = null,\n    val isFavorite: Boolean = false,''',
    '''    val projectId: String? = null,\n    val mode: ChatMode? = null,\n    val textModelOverride: String? = null,\n    val isFavorite: Boolean = false,'''
)
replace_once(
    models,
    '''    val textModel: String = "openrouter/auto",\n    val imageModel: String = "bytedance-seed/seedream-4.5",''',
    '''    val textModel: String = "openrouter/auto",\n    val currentChatTextModel: String? = null,\n    val quickTextModels: List<String> = emptyList(),\n    val imageModel: String = "bytedance-seed/seedream-4.5",'''
)

# ---------------- ViewModel ----------------
vm = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
replace_once(
    vm,
    '''            textModel = prefs.getString("text_model", prefs.getString("model", "openrouter/auto")) ?: "openrouter/auto",\n            imageModel = prefs.getString("image_model", "bytedance-seed/seedream-4.5") ?: "bytedance-seed/seedream-4.5",''',
    '''            textModel = prefs.getString("text_model", prefs.getString("model", "openrouter/auto")) ?: "openrouter/auto",\n            currentChatTextModel = initialChat.textModelOverride,\n            quickTextModels = loadQuickTextModels(),\n            imageModel = prefs.getString("image_model", "bytedance-seed/seedream-4.5") ?: "bytedance-seed/seedream-4.5",'''
)

old_select = '''    fun selectModel(mode: ChatMode, model: String) {\n        val clean = model.trim()\n        if (clean.isBlank()) return\n        when (mode) {\n            ChatMode.TEXT -> {\n                val info = _state.value.availableTextModels.firstOrNull { it.id == clean }\n                val keepReasoning = _state.value.reasoningEnabled && info?.supportsReasoning == true &&\n                    (info.reasoningEfforts.isEmpty() || _state.value.reasoningEffort.apiValue in info.reasoningEfforts)\n                prefs.edit()\n                    .putString("text_model", clean)\n                    .putBoolean("reasoning_enabled", keepReasoning)\n                    .apply()\n                _state.value = _state.value.copy(textModel = clean, reasoningEnabled = keepReasoning)\n            }\n            ChatMode.IMAGE -> {\n                prefs.edit().putString("image_model", clean).apply()\n                _state.value = _state.value.copy(imageModel = clean)\n            }\n        }\n    }\n'''
new_select = '''    fun selectModel(mode: ChatMode, model: String) {\n        val clean = model.trim()\n        if (clean.isBlank()) return\n        when (mode) {\n            ChatMode.TEXT -> {\n                val effectiveId = _state.value.currentChatTextModel ?: clean\n                val info = _state.value.availableTextModels.firstOrNull { it.id == effectiveId }\n                val keepReasoning = reasoningStillValid(info)\n                prefs.edit()\n                    .putString("text_model", clean)\n                    .putBoolean("reasoning_enabled", keepReasoning)\n                    .apply()\n                _state.value = _state.value.copy(textModel = clean, reasoningEnabled = keepReasoning)\n            }\n            ChatMode.IMAGE -> {\n                prefs.edit().putString("image_model", clean).apply()\n                _state.value = _state.value.copy(imageModel = clean)\n            }\n        }\n    }\n\n    fun toggleQuickTextModel(model: String) {\n        val clean = model.trim()\n        if (clean.isBlank()) return\n        val current = _state.value.quickTextModels\n        val next = if (clean in current) {\n            current.filterNot { it == clean }\n        } else {\n            if (current.size >= 10) {\n                _state.value = _state.value.copy(status = "Можно закрепить до 10 быстрых моделей")\n                return\n            }\n            current + clean\n        }\n        prefs.edit().putString("quick_text_models_json", gson.toJson(next)).apply()\n        _state.value = _state.value.copy(quickTextModels = next)\n    }\n\n    fun selectQuickTextModel(model: String) {\n        if (_state.value.isLoading) return\n        val clean = model.trim()\n        if (clean.isBlank()) return\n        val info = _state.value.availableTextModels.firstOrNull { it.id == clean }\n        val keepReasoning = reasoningStillValid(info)\n        val chats = _state.value.chats.map { chat ->\n            if (chat.id == _state.value.currentChatId) chat.copy(\n                textModelOverride = clean,\n                updatedAt = System.currentTimeMillis()\n            ) else chat\n        }\n        chatsRepository.save(chats)\n        prefs.edit().putBoolean("reasoning_enabled", keepReasoning).apply()\n        _state.value = _state.value.copy(\n            chats = chats,\n            currentChatTextModel = clean,\n            reasoningEnabled = keepReasoning\n        )\n    }\n\n    fun useDefaultTextModelForChat() {\n        if (_state.value.isLoading) return\n        val info = _state.value.availableTextModels.firstOrNull { it.id == _state.value.textModel }\n        val keepReasoning = reasoningStillValid(info)\n        val chats = _state.value.chats.map { chat ->\n            if (chat.id == _state.value.currentChatId) chat.copy(\n                textModelOverride = null,\n                updatedAt = System.currentTimeMillis()\n            ) else chat\n        }\n        chatsRepository.save(chats)\n        prefs.edit().putBoolean("reasoning_enabled", keepReasoning).apply()\n        _state.value = _state.value.copy(\n            chats = chats,\n            currentChatTextModel = null,\n            reasoningEnabled = keepReasoning\n        )\n    }\n'''
replace_once(vm, old_select, new_select)

replace_once(
    vm,
    '''            currentChatId = chat.id,\n            messages = emptyList(),\n            pendingAttachments = emptyList(),''',
    '''            currentChatId = chat.id,\n            messages = emptyList(),\n            currentChatTextModel = null,\n            pendingAttachments = emptyList(),'''
)
replace_once(
    vm,
    '''            currentChatId = id,\n            messages = chat.messages,\n            mode = nextMode,\n            pendingAttachments = emptyList()''',
    '''            currentChatId = id,\n            messages = chat.messages,\n            mode = nextMode,\n            currentChatTextModel = chat.textModelOverride,\n            pendingAttachments = emptyList()'''
)
replace_once(
    vm,
    '''            messages = selected.messages,\n            mode = selected.mode ?: _state.value.mode,\n            pendingAttachments = emptyList(),''',
    '''            messages = selected.messages,\n            mode = selected.mode ?: _state.value.mode,\n            currentChatTextModel = selected.textModelOverride,\n            pendingAttachments = emptyList(),'''
)

replace_once(
    vm,
    '''                        val current = infos.firstOrNull { it.id == _state.value.textModel }\n                        val keepReasoning = _state.value.reasoningEnabled && current?.supportsReasoning == true &&\n                            (current.reasoningEfforts.isEmpty() || _state.value.reasoningEffort.apiValue in current.reasoningEfforts)''',
    '''                        val effectiveId = _state.value.currentChatTextModel ?: _state.value.textModel\n                        val current = infos.firstOrNull { it.id == effectiveId }\n                        val keepReasoning = reasoningStillValid(current)'''
)
replace_once(
    vm,
    '''                val current = textInfos.firstOrNull { it.id == next.textModel }\n                val keepReasoning = next.reasoningEnabled && current?.supportsReasoning == true &&\n                    (current.reasoningEfforts.isEmpty() || next.reasoningEffort.apiValue in current.reasoningEfforts)''',
    '''                val effectiveId = next.currentChatTextModel ?: next.textModel\n                val current = textInfos.firstOrNull { it.id == effectiveId }\n                val keepReasoning = next.reasoningEnabled && current?.supportsReasoning == true &&\n                    (current.reasoningEfforts.isEmpty() || next.reasoningEffort.apiValue in current.reasoningEfforts)'''
)
replace_once(
    vm,
    '''    private fun currentTextModelInfo(): ModelInfo? =\n        _state.value.availableTextModels.firstOrNull { it.id == _state.value.textModel }''',
    '''    private fun currentTextModelId(): String = _state.value.currentChatTextModel ?: _state.value.textModel\n\n    private fun currentTextModelInfo(): ModelInfo? =\n        _state.value.availableTextModels.firstOrNull { it.id == currentTextModelId() }\n\n    private fun reasoningStillValid(info: ModelInfo?): Boolean =\n        _state.value.reasoningEnabled && info?.supportsReasoning == true &&\n            (info.reasoningEfforts.isEmpty() || _state.value.reasoningEffort.apiValue in info.reasoningEfforts)'''
)
replace_once(
    vm,
    '''        val textModel = _state.value.textModel\n        val imageModel = _state.value.imageModel''',
    '''        val textModel = currentTextModelId()\n        val imageModel = _state.value.imageModel'''
)

replace_once(
    vm,
    '''    private fun loadInitialChats(): List<ChatSession> {''',
    '''    private fun loadQuickTextModels(): List<String> = runCatching {\n        val type = object : TypeToken<List<String>>() {}.type\n        gson.fromJson<List<String>>(prefs.getString("quick_text_models_json", "[]") ?: "[]", type)\n            .orEmpty()\n            .map { it.trim() }\n            .filter { it.isNotBlank() }\n            .distinct()\n            .take(10)\n    }.getOrDefault(emptyList())\n\n    private fun loadInitialChats(): List<ChatSession> {'''
)

# ---------------- UI ----------------
ui = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
replace_once(
    ui,
    '''import androidx.compose.material.icons.outlined.StopCircle\nimport androidx.compose.material.icons.outlined.Storage\nimport androidx.compose.material.icons.outlined.TextFields''',
    '''import androidx.compose.material.icons.outlined.StopCircle\nimport androidx.compose.material.icons.outlined.Storage\nimport androidx.compose.material.icons.outlined.SwapHoriz\nimport androidx.compose.material.icons.outlined.TextFields'''
)
replace_once(
    ui,
    '''import androidx.compose.material3.CircularProgressIndicator\nimport androidx.compose.material3.ElevatedCard\nimport androidx.compose.material3.FilledTonalButton''',
    '''import androidx.compose.material3.CircularProgressIndicator\nimport androidx.compose.material3.DropdownMenu\nimport androidx.compose.material3.DropdownMenuItem\nimport androidx.compose.material3.ElevatedCard\nimport androidx.compose.material3.FilledTonalButton'''
)
replace_once(
    ui,
    '''    var projectsOpen by remember { mutableStateOf(false) }\n    var cameraTarget by remember { mutableStateOf<CameraTarget?>(null) }\n    val listState = rememberLazyListState()\n    val context = LocalContext.current\n    val textModelInfo = state.availableTextModels.firstOrNull { it.id == state.textModel }''',
    '''    var projectsOpen by remember { mutableStateOf(false) }\n    var cameraTarget by remember { mutableStateOf<CameraTarget?>(null) }\n    var quickModelsOpen by remember { mutableStateOf(false) }\n    val listState = rememberLazyListState()\n    val context = LocalContext.current\n    val activeTextModel = state.currentChatTextModel ?: state.textModel\n    val textModelInfo = state.availableTextModels.firstOrNull { it.id == activeTextModel }'''
)

old_composer = '''                if (state.mode == ChatMode.TEXT) {\n                    ComposerToggleIcon(\n                        selected = state.webSearchEnabled,\n                        icon = Icons.Outlined.Language,\n                        description = if (state.webSearchEnabled) "Веб-поиск включён" else "Включить веб-поиск",\n                        onClick = { vm.setWebSearchEnabled(!state.webSearchEnabled) }\n                    )\n                    ComposerToggleIcon(\n                        selected = state.reasoningEnabled,\n                        icon = Icons.Outlined.Psychology,\n                        description = if (state.reasoningEnabled) "Размышление включено" else "Включить размышление",\n                        enabled = reasoningAvailable,\n                        onClick = { vm.setReasoningEnabled(!state.reasoningEnabled) }\n                    )\n                }\n\n                OutlinedTextField(\n                    value = text,\n                    onValueChange = { text = it },\n                    modifier = Modifier.weight(1f),\n                    placeholder = {\n                        Text(if (state.mode == ChatMode.IMAGE) "Опишите изображение…" else "Сообщение…")\n                    },\n                    shape = RoundedCornerShape(24.dp),\n                    maxLines = 6\n                )'''
new_composer = '''                if (state.mode == ChatMode.TEXT) {\n                    Box {\n                        IconButton(\n                            onClick = { quickModelsOpen = true },\n                            enabled = !state.isLoading,\n                            modifier = Modifier.size(42.dp)\n                        ) {\n                            Icon(Icons.Outlined.SwapHoriz, contentDescription = "Быстрая смена модели")\n                        }\n                        val quickCandidates = (listOf(activeTextModel, state.textModel) + state.quickTextModels)\n                            .filter { it.isNotBlank() }\n                            .distinct()\n                        DropdownMenu(\n                            expanded = quickModelsOpen,\n                            onDismissRequest = { quickModelsOpen = false }\n                        ) {\n                            quickCandidates.forEach { id ->\n                                val current = id == activeTextModel\n                                DropdownMenuItem(\n                                    text = {\n                                        Column {\n                                            Text(\n                                                id,\n                                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,\n                                                maxLines = 2,\n                                                overflow = TextOverflow.Ellipsis\n                                            )\n                                            if (id == state.textModel) {\n                                                Text(\n                                                    if (state.currentChatTextModel == null && current) "По умолчанию · текущая" else "Модель по умолчанию",\n                                                    style = MaterialTheme.typography.bodySmall,\n                                                    color = MaterialTheme.colorScheme.onSurfaceVariant\n                                                )\n                                            } else if (current) {\n                                                Text(\n                                                    "Текущая модель этого чата",\n                                                    style = MaterialTheme.typography.bodySmall,\n                                                    color = MaterialTheme.colorScheme.onSurfaceVariant\n                                                )\n                                            }\n                                        }\n                                    },\n                                    onClick = {\n                                        if (id == state.textModel) vm.useDefaultTextModelForChat() else vm.selectQuickTextModel(id)\n                                        quickModelsOpen = false\n                                    }\n                                )\n                            }\n                        }\n                    }\n                }\n\n                OutlinedTextField(\n                    value = text,\n                    onValueChange = { text = it },\n                    modifier = Modifier.weight(1f),\n                    placeholder = {\n                        Text(if (state.mode == ChatMode.IMAGE) "Опишите изображение…" else "Сообщение…")\n                    },\n                    trailingIcon = if (state.mode == ChatMode.TEXT) {\n                        {\n                            Row(\n                                verticalAlignment = Alignment.CenterVertically,\n                                horizontalArrangement = Arrangement.spacedBy(1.dp)\n                            ) {\n                                InlineComposerToggleIcon(\n                                    selected = state.webSearchEnabled,\n                                    icon = Icons.Outlined.Language,\n                                    description = if (state.webSearchEnabled) "Веб-поиск включён" else "Включить веб-поиск",\n                                    onClick = { vm.setWebSearchEnabled(!state.webSearchEnabled) }\n                                )\n                                InlineComposerToggleIcon(\n                                    selected = state.reasoningEnabled,\n                                    icon = Icons.Outlined.Psychology,\n                                    description = if (state.reasoningEnabled) "Размышление включено" else "Включить размышление",\n                                    enabled = reasoningAvailable,\n                                    onClick = { vm.setReasoningEnabled(!state.reasoningEnabled) }\n                                )\n                            }\n                        }\n                    } else null,\n                    shape = RoundedCornerShape(24.dp),\n                    maxLines = 6\n                )'''
replace_once(ui, old_composer, new_composer)

replace_once(
    ui,
    '''@Composable\nprivate fun ComposerToggleIcon(\n    selected: Boolean,\n    icon: ImageVector,\n    description: String,\n    enabled: Boolean = true,\n    onClick: () -> Unit\n) {''',
    '''@Composable\nprivate fun InlineComposerToggleIcon(\n    selected: Boolean,\n    icon: ImageVector,\n    description: String,\n    enabled: Boolean = true,\n    onClick: () -> Unit\n) {\n    if (selected) {\n        FilledTonalIconButton(\n            onClick = onClick,\n            enabled = enabled,\n            modifier = Modifier.size(32.dp),\n            shape = RoundedCornerShape(9.dp)\n        ) {\n            Icon(icon, contentDescription = description, modifier = Modifier.size(17.dp))\n        }\n    } else {\n        IconButton(\n            onClick = onClick,\n            enabled = enabled,\n            modifier = Modifier.size(32.dp)\n        ) {\n            Icon(icon, contentDescription = description, modifier = Modifier.size(17.dp))\n        }\n    }\n}\n\n@Composable\nprivate fun ComposerToggleIcon(\n    selected: Boolean,\n    icon: ImageVector,\n    description: String,\n    enabled: Boolean = true,\n    onClick: () -> Unit\n) {'''
)

replace_once(
    ui,
    '''    var storageOpen by remember { mutableStateOf(false) }\n    var modelPicker by remember { mutableStateOf<ChatMode?>(null) }''',
    '''    var storageOpen by remember { mutableStateOf(false) }\n    var modelPicker by remember { mutableStateOf<ChatMode?>(null) }\n    var quickModelsSettingsOpen by remember { mutableStateOf(false) }'''
)
replace_once(
    ui,
    '''    val profileScopes = UserProfileScope.entries\n    val selectedTextModelInfo = state.availableTextModels.firstOrNull { it.id == state.textModel }''',
    '''    val profileScopes = UserProfileScope.entries'''
)
replace_once(
    ui,
    '''                            FilterChip(\n                                selected = state.reasoningEffort == effort,\n                                onClick = { vm.setReasoningEffort(effort) },\n                                enabled = selectedTextModelInfo?.supportsReasoning == true &&\n                                    (!selectedTextModelInfo.supportsReasoningEffort || selectedTextModelInfo.reasoningEfforts.isEmpty() || effort.apiValue in selectedTextModelInfo.reasoningEfforts),\n                                label = { Text(reasoningEffortLabel(effort)) }\n                            )''',
    '''                            FilterChip(\n                                selected = state.reasoningEffort == effort,\n                                onClick = { vm.setReasoningEffort(effort) },\n                                label = { Text(reasoningEffortLabel(effort)) }\n                            )'''
)
replace_once(
    ui,
    '''                    FilledTonalButton(\n                        onClick = { modelPicker = ChatMode.IMAGE },\n                        modifier = Modifier.fillMaxWidth()\n                    ) {\n                        Icon(Icons.Outlined.Image, contentDescription = null)\n                        Spacer(Modifier.width(8.dp))\n                        Column(Modifier.weight(1f)) {\n                            Text("Изображения", fontWeight = FontWeight.Medium)\n                            Text(state.imageModel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)\n                        }\n                    }''',
    '''                    FilledTonalButton(\n                        onClick = { modelPicker = ChatMode.IMAGE },\n                        modifier = Modifier.fillMaxWidth()\n                    ) {\n                        Icon(Icons.Outlined.Image, contentDescription = null)\n                        Spacer(Modifier.width(8.dp))\n                        Column(Modifier.weight(1f)) {\n                            Text("Изображения", fontWeight = FontWeight.Medium)\n                            Text(state.imageModel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)\n                        }\n                    }\n                    Spacer(Modifier.height(7.dp))\n                    FilledTonalButton(\n                        onClick = { quickModelsSettingsOpen = true },\n                        modifier = Modifier.fillMaxWidth()\n                    ) {\n                        Icon(Icons.Outlined.SwapHoriz, contentDescription = null)\n                        Spacer(Modifier.width(8.dp))\n                        Column(Modifier.weight(1f)) {\n                            Text("Быстрые модели", fontWeight = FontWeight.Medium)\n                            Text(\n                                if (state.quickTextModels.isEmpty()) "Только модель по умолчанию" else "Дополнительно: ${state.quickTextModels.size}",\n                                style = MaterialTheme.typography.bodySmall\n                            )\n                        }\n                    }'''
)
replace_once(
    ui,
    '''    modelPicker?.let { mode ->\n        ModelPickerDialog(\n            mode = mode,\n            state = state,\n            vm = vm,\n            onDismiss = { modelPicker = null }\n        )\n    }\n}\n\n@Composable\nprivate fun StorageDialog''',
    '''    modelPicker?.let { mode ->\n        ModelPickerDialog(\n            mode = mode,\n            state = state,\n            vm = vm,\n            onDismiss = { modelPicker = null }\n        )\n    }\n\n    if (quickModelsSettingsOpen) {\n        QuickModelsSettingsDialog(\n            state = state,\n            vm = vm,\n            onDismiss = { quickModelsSettingsOpen = false }\n        )\n    }\n}\n\n@Composable\nprivate fun QuickModelsSettingsDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {\n    var query by remember { mutableStateOf("") }\n\n    LaunchedEffect(Unit) {\n        if (state.availableTextModels.isEmpty()) vm.refreshModels(ChatMode.TEXT)\n    }\n\n    val filtered = remember(state.availableTextModels, query) {\n        state.availableTextModels\n            .filter { it.id.contains(query.trim(), ignoreCase = true) }\n            .take(300)\n    }\n\n    AlertDialog(\n        onDismissRequest = onDismiss,\n        title = { Text("Быстрые модели") },\n        text = {\n            Column {\n                Text(\n                    "Модель по умолчанию всегда доступна. Здесь можно закрепить до 10 дополнительных моделей для мгновенной смены внутри чата.",\n                    style = MaterialTheme.typography.bodySmall,\n                    color = MaterialTheme.colorScheme.onSurfaceVariant\n                )\n                Spacer(Modifier.height(8.dp))\n                OutlinedTextField(\n                    value = query,\n                    onValueChange = { query = it },\n                    modifier = Modifier.fillMaxWidth(),\n                    singleLine = true,\n                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },\n                    placeholder = { Text("Поиск модели") }\n                )\n                Spacer(Modifier.height(8.dp))\n                LazyColumn(Modifier.heightIn(max = 430.dp)) {\n                    items(filtered, key = { it.id }) { modelInfo ->\n                        FilterChip(\n                            selected = modelInfo.id in state.quickTextModels,\n                            onClick = { vm.toggleQuickTextModel(modelInfo.id) },\n                            label = {\n                                Text(\n                                    modelInfo.id,\n                                    modifier = Modifier.fillMaxWidth(),\n                                    maxLines = 2,\n                                    overflow = TextOverflow.Ellipsis\n                                )\n                            },\n                            modifier = Modifier.fillMaxWidth()\n                        )\n                        Spacer(Modifier.height(4.dp))\n                    }\n                }\n            }\n        },\n        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } }\n    )\n}\n\n@Composable\nprivate fun StorageDialog'''
)

# ---------------- Version ----------------
build = "app/build.gradle.kts"
replace_once(build, '// Umnik v0.6.3 capability-aware multimodal', '// Umnik v0.6.4 quick per-chat model switching')
replace_once(build, 'versionCode = 12', 'versionCode = 13')
replace_once(build, 'versionName = "0.6.3"', 'versionName = "0.6.4"')

print("Umnik 0.6.4 patch applied")
