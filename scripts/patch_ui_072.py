from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
BUILD = ROOT / "app/build.gradle.kts"
CHANGELOG = ROOT / "CHANGELOG.md"

text = UI.read_text(encoding="utf-8")

def replace_between(source: str, start: str, end: str, replacement: str) -> str:
    a = source.index(start)
    b = source.index(end, a)
    return source[:a] + replacement.rstrip() + "\n\n" + source[b:]

# Imports required by the redesigned settings and capability matrix.
if "import androidx.compose.material.icons.outlined.KeyboardArrowUp" not in text:
    text = text.replace(
        "import androidx.compose.material.icons.outlined.KeyboardArrowDown\n",
        "import androidx.compose.material.icons.outlined.KeyboardArrowDown\nimport androidx.compose.material.icons.outlined.KeyboardArrowUp\n"
    )
if "import androidx.compose.material3.FilterChipDefaults" not in text:
    text = text.replace(
        "import androidx.compose.material3.FilterChip\n",
        "import androidx.compose.material3.FilterChip\nimport androidx.compose.material3.FilterChipDefaults\n"
    )
if "import com.ayuemin.ymnik.model.ModelInfo" not in text:
    text = text.replace(
        "import com.ayuemin.ymnik.model.GeneratedFile\n",
        "import com.ayuemin.ymnik.model.GeneratedFile\nimport com.ayuemin.ymnik.model.ModelInfo\n"
    )

text = replace_between(text, "@Composable\nfun YmnikApp(viewModel: ChatViewModel) {", "@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun ChatScreen(", r'''@Composable
fun YmnikApp(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val tts = remember { TtsController(context) }
    var screen by remember { mutableIntStateOf(0) }

    DisposableEffect(tts) {
        onDispose { tts.shutdown() }
    }

    LaunchedEffect(state.status) {
        state.status?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissStatus()
        }
    }

    UmnikTheme(state.themeChoice) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    0 -> ChatScreen(
                        state = state,
                        vm = viewModel,
                        tts = tts,
                        onOpenSkills = { screen = 1 },
                        onOpenSettings = { screen = 2 }
                    )
                    1 -> SkillsScreen(state, viewModel, onBack = { screen = 0 })
                    else -> SettingsScreen(state, viewModel, onBack = { screen = 0 })
                }
            }
        }
    }
}''')

# Shorter action title requested by the user.
text = text.replace(
    'label = if (state.mode == ChatMode.TEXT) "Изображение" else "Текст",',
    'label = if (state.mode == ChatMode.TEXT) "Создать" else "Текст",',
    1
)

text = replace_between(text, "@Composable\nprivate fun ModelPickerDialog(", "@Composable\nprivate fun EmptyChatCard(", r'''@Composable
private fun ModelPickerDialog(
    mode: ChatMode,
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit
) {
    var query by remember(mode) { mutableStateOf("") }
    val models = if (mode == ChatMode.TEXT) state.availableTextModels else state.availableImageModels
    val current = if (mode == ChatMode.TEXT) state.textModel else state.imageModel

    LaunchedEffect(mode) {
        if (models.isEmpty()) vm.refreshModels(mode)
    }

    val filtered = remember(models, query) {
        models.filter { it.id.contains(query.trim(), ignoreCase = true) }.take(300)
    }

    FullScreenPanel(
        title = if (mode == ChatMode.TEXT) "Текстовая модель" else "Модель изображений",
        onBack = onDismiss
    ) {
        Text(
            "Сейчас: $current",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text("Поиск модели") }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { vm.refreshModels(mode) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("Обновить")
            }
        }
        if (filtered.isEmpty()) {
            Text(
                if (state.isLoading) "Загрузка списка…" else "Модели не найдены",
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                items(filtered, key = { it.id }) { modelInfo ->
                    TextButton(
                        onClick = {
                            vm.selectModel(mode, modelInfo.id)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                    ) {
                        Text(
                            modelInfo.id,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}''')

text = replace_between(text, "@Composable\nprivate fun SkillsScreen(", "@Composable\nprivate fun SettingsScreen(", r'''@Composable
private fun SkillsScreen(state: UiState, vm: ChatViewModel, onBack: () -> Unit) {
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importSkillFile)
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::importSkillTree)
    }

    Column(Modifier.fillMaxSize()) {
        PinnedBackHeader(title = "Навыки", onBack = onBack)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "SKILL.md и папки с текстовыми материалами. Подключённые навыки применяются в текстовом режиме.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { filePicker.launch(arrayOf("text/*", "application/json", "application/yaml")) }) {
                        Icon(Icons.Outlined.Description, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Файл")
                    }
                    FilledTonalButton(onClick = { treePicker.launch(null) }) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Папка")
                    }
                }
            }

            if (state.skills.isEmpty()) {
                item {
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Text(
                            "Пока навыков нет. Импортируйте SKILL.md или папку навыка.",
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(state.skills, key = { it.id }) { skill ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(15.dp)) {
                        Text(skill.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(3.dp))
                        Text("Файлов: ${skill.files.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = skill.id in state.activeSkillIds,
                                onClick = { vm.toggleSkill(skill.id) },
                                label = { Text(if (skill.id in state.activeSkillIds) "Подключён" else "Подключить") },
                                leadingIcon = { Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            )
                            Spacer(Modifier.width(6.dp))
                            IconButton(onClick = { vm.deleteSkill(skill.id) }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить навык")
                            }
                        }
                    }
                }
            }
        }
    }
}''')

text = replace_between(text, "@Composable\nprivate fun SettingsScreen(", "@Composable\nprivate fun QuickModelsSettingsDialog(", r'''@Composable
private fun SettingsScreen(state: UiState, vm: ChatViewModel, onBack: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var storageOpen by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf<ChatMode?>(null) }
    var quickModelsSettingsOpen by remember { mutableStateOf(false) }
    var reasoningExpanded by remember { mutableStateOf(false) }
    var profileExpanded by remember { mutableStateOf(false) }
    var apiExpanded by remember(state.apiKeyConfigured) { mutableStateOf(!state.apiKeyConfigured) }
    var profileName by remember(state.userProfile.name) { mutableStateOf(state.userProfile.name) }
    var profileGender by remember(state.userProfile.gender) { mutableStateOf(state.userProfile.gender) }
    var profileAge by remember(state.userProfile.age) { mutableStateOf(state.userProfile.age) }
    var profileOccupation by remember(state.userProfile.occupation) { mutableStateOf(state.userProfile.occupation) }
    var profileNote by remember(state.userProfile.note) { mutableStateOf(state.userProfile.note) }
    val themes = ThemeChoice.entries
    val profileScopes = UserProfileScope.entries

    Column(Modifier.fillMaxSize()) {
        PinnedBackHeader(title = "Настройки", onBack = onBack)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Модели", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(9.dp))
                        FilledTonalButton(onClick = { modelPicker = ChatMode.TEXT }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.TextFields, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Текстовая по умолчанию", fontWeight = FontWeight.Medium)
                                Text(state.textModel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        FilledTonalButton(onClick = { quickModelsSettingsOpen = true }, modifier = Modifier.fillMaxWidth()) {
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
                        Spacer(Modifier.height(7.dp))
                        FilledTonalButton(onClick = { modelPicker = ChatMode.IMAGE }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Image, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Генерация изображений", fontWeight = FontWeight.Medium)
                                Text(state.imageModel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            item {
                ReasoningSettingsCard(
                    state = state,
                    vm = vm,
                    expanded = reasoningExpanded,
                    onToggle = { reasoningExpanded = !reasoningExpanded }
                )
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Звук готового ответа", fontWeight = FontWeight.Medium)
                            Text(
                                "Короткий сигнал после ответа модели",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = state.answerSoundEnabled, onCheckedChange = vm::setAnswerSoundEnabled)
                    }
                }
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Storage, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Хранилище Umnik", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "${state.storedFiles.size} файлов · ${humanSize(state.storageStats.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton(
                            onClick = {
                                vm.refreshStorage()
                                storageOpen = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Открыть хранилище")
                        }
                    }
                }
            }

            item {
                ExpandableSettingsCard(
                    title = "Коротко обо мне",
                    subtitle = if (state.userProfile.isEmpty()) "Не задано" else "Профиль заполнен · ${profileScopeLabel(state.userProfileScope)}",
                    icon = Icons.Outlined.Description,
                    expanded = profileExpanded,
                    onToggle = { profileExpanded = !profileExpanded }
                ) {
                    Text(
                        "Необязательно. Передаётся модели только в выбранной области.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(9.dp))
                    OutlinedTextField(profileName, { profileName = it }, Modifier.fillMaxWidth(), label = { Text("Имя") }, singleLine = true)
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedTextField(profileGender, { profileGender = it }, Modifier.weight(1f), label = { Text("Пол") }, singleLine = true)
                        OutlinedTextField(profileAge, { profileAge = it }, Modifier.weight(1f), label = { Text("Возраст") }, singleLine = true)
                    }
                    Spacer(Modifier.height(7.dp))
                    OutlinedTextField(profileOccupation, { profileOccupation = it }, Modifier.fillMaxWidth(), label = { Text("Род занятий") }, singleLine = true)
                    Spacer(Modifier.height(7.dp))
                    OutlinedTextField(
                        profileNote,
                        { profileNote = it.take(240) },
                        Modifier.fillMaxWidth(),
                        label = { Text("Короткая установка") },
                        minLines = 2,
                        maxLines = 3
                    )
                    Spacer(Modifier.height(9.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(profileScopes) { scope ->
                            FilterChip(
                                selected = state.userProfileScope == scope,
                                onClick = { vm.setUserProfileScope(scope) },
                                label = { Text(profileScopeLabel(scope)) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { vm.saveUserProfile(profileName, profileGender, profileAge, profileOccupation, profileNote) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Сохранить профиль")
                    }
                }
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Palette, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text("Цветовая схема", fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(themes) { choice ->
                                FilterChip(
                                    selected = state.themeChoice == choice,
                                    onClick = { vm.setThemeChoice(choice) },
                                    label = { Text(themeLabel(choice)) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                ExpandableSettingsCard(
                    title = "API-ключ OpenRouter",
                    subtitle = if (state.apiKeyConfigured) "Сохранён и зашифрован" else "Ключ ещё не задан",
                    icon = Icons.Outlined.Settings,
                    expanded = apiExpanded,
                    onToggle = { apiExpanded = !apiExpanded }
                ) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(if (state.apiKeyConfigured) "Новый ключ" else "sk-or-v1-…") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            vm.saveApiKey(key.takeIf { it.isNotBlank() })
                            key = ""
                            apiExpanded = false
                        },
                        enabled = key.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сохранить ключ") }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Ключ хранится локально и шифруется через Android Keystore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (storageOpen) StorageDialog(state = state, vm = vm, onDismiss = { storageOpen = false })
    modelPicker?.let { mode ->
        ModelPickerDialog(mode = mode, state = state, vm = vm, onDismiss = { modelPicker = null })
    }
    if (quickModelsSettingsOpen) {
        QuickModelsSettingsDialog(state = state, vm = vm, onDismiss = { quickModelsSettingsOpen = false })
    }
}

@Composable
private fun ExpandableSettingsCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        TextButton(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "Свернуть" else "Развернуть"
            )
        }
        if (expanded) {
            HorizontalDivider()
            Column(Modifier.padding(16.dp)) { content() }
        }
    }
}

@Composable
private fun ReasoningSettingsCard(
    state: UiState,
    vm: ChatViewModel,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val currentId = state.currentChatTextModel ?: state.textModel
    val modelIds = (listOf(currentId, state.textModel) + state.quickTextModels)
        .filter { it.isNotBlank() }
        .distinct()
    val imageInfo = state.availableImageModels.firstOrNull { it.id == state.imageModel }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        TextButton(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(Icons.Outlined.Psychology, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Сила размышления", modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold)
                Text(
                    "Отдельная настройка для каждой быстрой модели",
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "Свернуть" else "Развернуть"
            )
        }

        if (expanded) {
            HorizontalDivider()
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.availableTextModels.isEmpty()) {
                    Text(
                        "Сведения о возможностях моделей ещё не загружены.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { vm.refreshModels(ChatMode.TEXT) }) { Text("Обновить модели") }
                }
                modelIds.forEach { id ->
                    val info = state.availableTextModels.firstOrNull { it.id == id }
                    val selected = state.reasoningEffortsByModel[id]
                        ?: if (id == currentId) state.reasoningEffort else ReasoningEffort.MEDIUM
                    ReasoningModelRow(
                        modelId = id,
                        info = info,
                        selected = selected,
                        subtitle = when {
                            id == currentId -> "Текущая модель чата"
                            id == state.textModel -> "По умолчанию"
                            else -> "Быстрая модель"
                        },
                        onSelect = { effort -> vm.setReasoningEffortForModel(id, effort) }
                    )
                }

                if (imageInfo?.supportsReasoning == true) {
                    HorizontalDivider()
                    ReasoningModelRow(
                        modelId = state.imageModel,
                        info = imageInfo,
                        selected = null,
                        subtitle = "Модель генерации изображений · возможности API",
                        onSelect = null
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningModelRow(
    modelId: String,
    info: ModelInfo?,
    selected: ReasoningEffort?,
    subtitle: String,
    onSelect: ((ReasoningEffort) -> Unit)?
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            modelId.substringAfter('/').ifBlank { modelId },
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))

        when {
            info == null -> Text(
                "Возможности не загружены",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            !info.supportsReasoning -> Text(
                "Размышление не поддерживается",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            !info.supportsReasoningEffort -> Text(
                "Размышление поддерживается, но уровень выбирает сама модель",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            else -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(ReasoningEffort.entries) { effort ->
                        val supported = info.reasoningEfforts.isEmpty() || effort.apiValue in info.reasoningEfforts
                        FilterChip(
                            selected = selected == effort,
                            onClick = { if (supported) onSelect?.invoke(effort) },
                            enabled = supported && onSelect != null,
                            label = { Text(reasoningEffortShortLabel(effort)) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = if (supported) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                disabledContainerColor = if (supported) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            )
                        )
                    }
                }
            }
        }
    }
}

private fun reasoningEffortShortLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.MINIMAL -> "Мин"
    ReasoningEffort.LOW -> "Низк"
    ReasoningEffort.MEDIUM -> "Средн"
    ReasoningEffort.HIGH -> "Высок"
    ReasoningEffort.XHIGH -> "Макс"
}''')

text = replace_between(text, "@Composable\nprivate fun QuickModelsSettingsDialog(", "@Composable\nprivate fun StorageDialog(", r'''@Composable
private fun QuickModelsSettingsDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (state.availableTextModels.isEmpty()) vm.refreshModels(ChatMode.TEXT)
    }

    val filtered = remember(state.availableTextModels, query) {
        state.availableTextModels
            .filter { it.id.contains(query.trim(), ignoreCase = true) }
            .take(300)
    }

    FullScreenPanel(title = "Быстрые модели", onBack = onDismiss) {
        Text(
            "Модель по умолчанию доступна всегда. Можно закрепить до 10 дополнительных моделей для мгновенной смены в чате.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text("Поиск модели") }
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        ) {
            items(filtered, key = { it.id }) { modelInfo ->
                FilterChip(
                    selected = modelInfo.id in state.quickTextModels,
                    onClick = { vm.toggleQuickTextModel(modelInfo.id) },
                    label = {
                        Text(
                            modelInfo.id,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(5.dp))
            }
        }
    }
}''')

text = replace_between(text, "@Composable\nprivate fun StorageDialog(", "private fun reasoningEffortLabel(", r'''@Composable
private fun StorageDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var fileToSave by remember { mutableStateOf<StoredFile?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val file = fileToSave
        if (uri != null && file != null) vm.saveGeneratedFile(vm.storedFileAsGenerated(file), uri)
        fileToSave = null
    }

    val filtered = remember(state.storedFiles, query) {
        val q = query.trim()
        if (q.isBlank()) state.storedFiles else state.storedFiles.filter {
            it.name.contains(q, ignoreCase = true) || it.category.contains(q, ignoreCase = true)
        }
    }

    FullScreenPanel(title = "Хранилище Umnik", onBack = onDismiss) {
        Text(
            "Всего ${humanSize(state.storageStats.totalBytes)}",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            placeholder = { Text("Найти файл") }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TextButton(onClick = vm::refreshStorage) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("Обновить")
            }
            TextButton(onClick = { clearConfirm = true }, enabled = !state.isLoading) {
                Icon(Icons.Outlined.DeleteForever, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("Очистить файлы")
            }
        }

        if (filtered.isEmpty()) {
            Text("Файлов не найдено", modifier = Modifier.padding(20.dp))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                items(filtered, key = { it.id }) { file ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (file.mimeType.startsWith("image/")) Icons.Outlined.Image else Icons.Outlined.Description,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${file.category} · ${humanSize(file.size)} · ${formatDate(file.modifiedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = {
                            fileToSave = file
                            save.launch(file.name)
                        }) { Icon(Icons.Outlined.Download, contentDescription = "Сохранить копию") }
                        if (file.deletable) {
                            IconButton(onClick = { vm.deleteStoredFile(file) }) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить файл")
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("Очистить рабочие файлы?") },
            text = { Text("Будут удалены сохранённые внутри Umnik изображения, сгенерированные файлы и экспорт. Тексты диалогов, API-ключ, проекты и навыки останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearWorkingFiles()
                    clearConfirm = false
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("Отмена") } }
        )
    }
}''')

UI.write_text(text, encoding="utf-8")

build = BUILD.read_text(encoding="utf-8")
build = build.replace("// Umnik v0.7.1", "// Umnik v0.7.2", 1)
build = build.replace('versionCode = 18', 'versionCode = 19', 1)
build = build.replace('versionName = "0.7.1"', 'versionName = "0.7.2"', 1)
BUILD.write_text(build, encoding="utf-8")

changelog = CHANGELOG.read_text(encoding="utf-8")
section = '''## v0.7.2

- В панели `+` действие «Изображение» переименовано в «Создать».
- Нижняя навигация в настройках и навыках удалена; возврат в чат выполняется закреплённой стрелкой «Назад».
- Настройки перестроены по частоте использования: модели и размышления выше, профиль и цветовая схема ниже, API-ключ в самом низу.
- API-ключ и «Коротко обо мне» стали сворачиваемыми блоками.
- Сила размышления настраивается отдельно для каждой модели из быстрого списка; поддерживаемые уровни визуально выделяются, неподдерживаемые затемняются.
- При смене модели Umnik автоматически использует сохранённый для неё уровень reasoning.
- Для модели генерации изображений в настройках показываются её reasoning-возможности, если OpenRouter сообщает о них.
- Проекты, история чатов, настройки чата/проекта, выбор моделей, быстрые модели и хранилище открываются на весь экран с неподвижной кнопкой «Назад».

'''
if "## v0.7.2" not in changelog:
    changelog = changelog.replace("## Unreleased\n\n", "## Unreleased\n\n" + section, 1)
CHANGELOG.write_text(changelog, encoding="utf-8")

print("Umnik 0.7.2 UI patch applied")
