from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
BUILD = ROOT / "app/build.gradle.kts"
CHANGELOG = ROOT / "CHANGELOG.md"

text = UI.read_text(encoding="utf-8")

# Imports for the new compact header and bottom action sheet.
text = text.replace(
    "import androidx.compose.material.icons.outlined.Image\n",
    "import androidx.compose.material.icons.outlined.Image\nimport androidx.compose.material.icons.outlined.KeyboardArrowDown\n"
)
text = text.replace(
    "import androidx.compose.material3.DropdownMenuItem\n",
    "import androidx.compose.material3.DropdownMenuItem\nimport androidx.compose.material3.ExperimentalMaterial3Api\n"
)
text = text.replace(
    "import androidx.compose.material3.MaterialTheme\n",
    "import androidx.compose.material3.MaterialTheme\nimport androidx.compose.material3.ModalBottomSheet\n"
)

# Main chat screen gets the full canvas; legacy bottom nav is only kept for secondary screens.
text = text.replace("if (!imeVisible) {", "if (!imeVisible && tab != 0) {", 1)
text = text.replace(
    "0 -> ChatScreen(state, viewModel, tts)",
    "0 -> ChatScreen(\n                        state = state,\n                        vm = viewModel,\n                        tts = tts,\n                        onOpenSkills = { tab = 1 },\n                        onOpenSettings = { tab = 2 }\n                    )"
)

new_block = r'''@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    state: UiState,
    vm: ChatViewModel,
    tts: TtsController,
    onOpenSkills: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var fileToSave by remember { mutableStateOf<GeneratedFile?>(null) }
    var chatsOpen by remember { mutableStateOf(false) }
    var projectsOpen by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    var cameraTarget by remember { mutableStateOf<CameraTarget?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val activeTextModel = state.currentChatTextModel ?: state.textModel
    val textModelInfo = state.availableTextModels.firstOrNull { it.id == activeTextModel }
    val imageModelInfo = state.availableImageModels.firstOrNull { it.id == state.imageModel }
    val cameraAvailable = when (state.mode) {
        ChatMode.TEXT -> textModelInfo?.accepts("image") == true
        ChatMode.IMAGE -> imageModelInfo?.accepts("image") == true
    }
    val reasoningAvailable = state.mode == ChatMode.TEXT && textModelInfo?.supportsReasoning == true &&
        (textModelInfo.reasoningEfforts.isEmpty() || state.reasoningEffort.apiValue in textModelInfo.reasoningEfforts)
    val currentChatFiles = state.chats.firstOrNull { it.id == state.currentChatId }?.chatFiles.orEmpty()

    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach(vm::addAttachment)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        cameraTarget?.let { target ->
            if (ok) vm.addCameraAttachment(target.uri, target.file.absolutePath) else target.file.delete()
        }
        cameraTarget = null
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val file = fileToSave
        if (uri != null && file != null) vm.saveGeneratedFile(file, uri)
        fileToSave = null
    }

    LaunchedEffect(state.currentChatId, state.messages.lastOrNull()?.id) {
        if (state.messages.isNotEmpty()) {
            delay(180)
            listState.scrollToItem(state.messages.size)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ChatHeader(
            state = state,
            vm = vm,
            onChats = { chatsOpen = true },
            onProjects = { projectsOpen = true },
            onOpenSkills = onOpenSkills,
            onOpenSettings = onOpenSettings,
            onNewChat = {
                val projectId = state.chats.firstOrNull { it.id == state.currentChatId }?.projectId
                vm.createChat(projectId)
            },
            onClear = vm::clearChat
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.messages.isEmpty()) {
                item { EmptyChatCard(state.mode) }
            }
            items(state.messages, key = { it.id }) { message ->
                MessageCard(
                    message = message,
                    tts = tts,
                    onSaveGenerated = { file ->
                        fileToSave = file
                        save.launch(file.name)
                    },
                    onExportText = {
                        val file = vm.exportMessage(message)
                        fileToSave = file
                        save.launch(file.name)
                    },
                    onRetry = if (
                        message.role == "user" &&
                        message.text.isNotBlank() &&
                        message.attachmentNames.all { name ->
                            currentChatFiles.any { file -> file.name == name }
                        }
                    ) {
                        { vm.send(message.text) }
                    } else null
                )
            }
            item(key = "chat-end") { Spacer(Modifier.height(1.dp)) }
        }

        if (currentChatFiles.isNotEmpty() || state.pendingAttachments.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(currentChatFiles, key = { "chat-${it.id}" }) { file ->
                        AssistChip(
                            onClick = { vm.removeChatFile(file.id) },
                            label = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = {
                                Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            trailingIcon = {
                                Icon(Icons.Outlined.Close, contentDescription = "Убрать файл из контекста чата", modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                    items(state.pendingAttachments, key = { "pending-${it.uri}" }) { attachment ->
                        AssistChip(
                            onClick = { vm.removeAttachment(attachment.uri) },
                            label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = {
                                Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            trailingIcon = {
                                Icon(Icons.Outlined.Close, contentDescription = "Убрать", modifier = Modifier.size(18.dp))
                            }
                        )
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.imePadding(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                leadingIcon = {
                    IconButton(
                        onClick = { actionsOpen = true },
                        enabled = !state.isLoading
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "Добавить и инструменты",
                            tint = if (state.webSearchEnabled || state.reasoningEnabled || state.mode == ChatMode.IMAGE) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (state.requestActive) {
                                vm.stopGeneration()
                            } else {
                                vm.send(text)
                                text = ""
                            }
                        },
                        enabled = state.requestActive || (!state.isLoading && (
                            text.isNotBlank() || state.pendingAttachments.isNotEmpty() || currentChatFiles.isNotEmpty()
                        ))
                    ) {
                        Icon(
                            if (state.requestActive) Icons.Outlined.Stop else Icons.Outlined.Send,
                            contentDescription = if (state.requestActive) "Остановить работу модели" else "Отправить"
                        )
                    }
                },
                shape = RoundedCornerShape(28.dp),
                maxLines = 6
            )
        }
    }

    if (actionsOpen) {
        ModalBottomSheet(onDismissRequest = { actionsOpen = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Добавить", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ComposerActionTile(
                        icon = Icons.Outlined.AttachFile,
                        label = "Файл",
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            actionsOpen = false
                            val types = if (state.mode == ChatMode.IMAGE) arrayOf("image/*") else arrayOf("*/*")
                            attach.launch(types)
                        }
                    )
                    ComposerActionTile(
                        icon = Icons.Outlined.CameraAlt,
                        label = "Камера",
                        enabled = !state.isLoading && cameraAvailable,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            actionsOpen = false
                            runCatching { createCameraTarget(context) }
                                .onSuccess { target ->
                                    cameraTarget = target
                                    camera.launch(target.uri)
                                }
                                .onFailure {
                                    Toast.makeText(context, it.message ?: "Не удалось открыть камеру", Toast.LENGTH_SHORT).show()
                                }
                        }
                    )
                    ComposerActionTile(
                        icon = if (state.mode == ChatMode.TEXT) Icons.Outlined.Image else Icons.Outlined.TextFields,
                        label = if (state.mode == ChatMode.TEXT) "Изображение" else "Текст",
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            vm.setMode(if (state.mode == ChatMode.TEXT) ChatMode.IMAGE else ChatMode.TEXT)
                            actionsOpen = false
                        }
                    )
                }

                HorizontalDivider()

                if (state.mode == ChatMode.TEXT) {
                    ComposerToolRow(
                        icon = Icons.Outlined.Psychology,
                        title = "Размышление",
                        subtitle = if (reasoningAvailable) "Использовать reasoning выбранной модели" else "Модель не поддерживает",
                        checked = state.reasoningEnabled,
                        enabled = reasoningAvailable,
                        onCheckedChange = vm::setReasoningEnabled
                    )
                    ComposerToolRow(
                        icon = Icons.Outlined.Language,
                        title = "Поиск в сети",
                        subtitle = "OpenRouter web search",
                        checked = state.webSearchEnabled,
                        enabled = true,
                        onCheckedChange = vm::setWebSearchEnabled
                    )
                }
            }
        }
    }

    if (chatsOpen) {
        ChatsHubDialog(
            state = state,
            vm = vm,
            onDismiss = { chatsOpen = false }
        )
    }

    if (projectsOpen) {
        ProjectsDialog(
            state = state,
            vm = vm,
            onDismiss = { projectsOpen = false }
        )
    }
}

@Composable
private fun ChatHeader(
    state: UiState,
    vm: ChatViewModel,
    onChats: () -> Unit,
    onProjects: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onClear: () -> Unit
) {
    var quickModelsOpen by remember { mutableStateOf(false) }
    var hubOpen by remember { mutableStateOf(false) }
    val activeTextModel = state.currentChatTextModel ?: state.textModel
    val displayedModel = if (state.mode == ChatMode.TEXT) activeTextModel else state.imageModel
    val shortModelName = displayedModel.substringAfter('/').ifBlank { displayedModel }
    val quickCandidates = (listOf(activeTextModel, state.textModel) + state.quickTextModels)
        .filter { it.isNotBlank() }
        .distinct()

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                TextButton(
                    onClick = { if (state.mode == ChatMode.TEXT) quickModelsOpen = true },
                    enabled = !state.isLoading,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        shortModelName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.mode == ChatMode.TEXT) {
                        Spacer(Modifier.width(3.dp))
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Выбрать модель", modifier = Modifier.size(22.dp))
                    }
                }

                DropdownMenu(
                    expanded = quickModelsOpen,
                    onDismissRequest = { quickModelsOpen = false }
                ) {
                    quickCandidates.forEach { id ->
                        val current = id == activeTextModel
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        id.substringAfter('/').ifBlank { id },
                                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        when {
                                            current -> "Текущая модель"
                                            id == state.textModel -> "По умолчанию"
                                            else -> id
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            onClick = {
                                if (id == state.textModel) vm.useDefaultTextModelForChat() else vm.selectQuickTextModel(id)
                                quickModelsOpen = false
                            }
                        )
                    }
                }
            }

            IconButton(
                onClick = onNewChat,
                enabled = !state.isLoading,
                modifier = Modifier.size(42.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Новый чат", modifier = Modifier.size(24.dp))
            }

            Box {
                FilledTonalButton(
                    onClick = { hubOpen = true },
                    enabled = !state.isLoading,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    val currentProjectId = state.chats.firstOrNull { it.id == state.currentChatId }?.projectId
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = if (currentProjectId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Меню")
                }

                DropdownMenu(
                    expanded = hubOpen,
                    onDismissRequest = { hubOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Проекты") },
                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                        onClick = { hubOpen = false; onProjects() }
                    )
                    DropdownMenuItem(
                        text = { Text("История чатов") },
                        leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                        onClick = { hubOpen = false; onChats() }
                    )
                    DropdownMenuItem(
                        text = { Text("Навыки") },
                        leadingIcon = { Icon(Icons.Outlined.Extension, contentDescription = null) },
                        onClick = { hubOpen = false; onOpenSkills() }
                    )
                    DropdownMenuItem(
                        text = { Text("Настройки") },
                        leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        onClick = { hubOpen = false; onOpenSettings() }
                    )
                    if (state.messages.isNotEmpty()) {
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Очистить чат") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                            onClick = { hubOpen = false; onClear() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerActionTile(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(96.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun ComposerToolRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(25.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

@Composable
private fun CompactModeIcon'''

pattern = re.compile(
    r'@Composable\nprivate fun ChatScreen\(.*?\n@Composable\nprivate fun CompactModeIcon',
    re.S,
)
text2, count = pattern.subn(new_block, text, count=1)
if count != 1:
    raise SystemExit(f"ChatScreen/Header replacement failed: {count}")
text = text2
UI.write_text(text, encoding="utf-8")

# Bump to the next signed test release.
build = BUILD.read_text(encoding="utf-8")
build = build.replace("// Umnik v0.7.0", "// Umnik v0.7.1")
build = build.replace("versionCode = 17", "versionCode = 18")
build = build.replace('versionName = "0.7.0"', 'versionName = "0.7.1"')
BUILD.write_text(build, encoding="utf-8")

if CHANGELOG.exists():
    ch = CHANGELOG.read_text(encoding="utf-8")
    if "## v0.7.1" not in ch:
        marker = "## v0.7.0"
        section = """## v0.7.1\n\n- Полностью переработан главный экран чата в компактном стиле.\n- Быстрый выбор модели перенесён в верхнюю левую часть экрана.\n- Новый чат доступен отдельной кнопкой сверху.\n- Проекты, история чатов, навыки и настройки собраны в единое меню.\n- Нижняя панель навигации скрыта на главном экране.\n- Скрепка, камера, режим изображений, web search и reasoning перенесены в панель по кнопке `+`.\n- Поле ввода стало единым компактным блоком с динамической кнопкой Send/Stop.\n\n"""
        if marker in ch:
            ch = ch.replace(marker, section + marker, 1)
        else:
            ch = section + ch
        CHANGELOG.write_text(ch, encoding="utf-8")

print("Main UI redesign patch applied")
