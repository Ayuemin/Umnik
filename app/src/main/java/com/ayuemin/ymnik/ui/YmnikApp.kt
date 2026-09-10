package com.ayuemin.ymnik.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.model.AnswerSoundChoice
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.ChatMode
import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.GeneratedFile
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.ReasoningEffort
import com.ayuemin.ymnik.model.StoredFile
import com.ayuemin.ymnik.model.ThemeChoice
import com.ayuemin.ymnik.model.UiState
import com.ayuemin.ymnik.model.UserProfileScope
import com.ayuemin.ymnik.tts.TtsController
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
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

    UmnikTheme(state.themeChoice, state.customThemeColor) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val currentChat = state.chats.firstOrNull { it.id == state.currentChatId }
    val currentChatFiles = currentChat?.chatFiles.orEmpty()
    val currentProjectSkillIds = currentChat?.projectId
        ?.let { projectId -> state.projects.firstOrNull { it.id == projectId }?.skillIds }
        .orEmpty()
    val activeSkillCount = (state.activeSkillIds + currentProjectSkillIds).size

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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        IconButton(
                            onClick = { actionsOpen = true },
                            enabled = !state.isLoading,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = "Добавить и инструменты",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (activeSkillCount > 0) {
                            ComposerInlineIndicator(
                                icon = Icons.Outlined.Extension,
                                description = "Активные навыки: $activeSkillCount",
                                count = activeSkillCount
                            )
                        }
                        if (state.reasoningEnabled) {
                            ComposerInlineIndicator(
                                icon = Icons.Outlined.Psychology,
                                description = "Размышление включено"
                            )
                        }
                        if (state.webSearchEnabled) {
                            ComposerInlineIndicator(
                                icon = Icons.Outlined.Language,
                                description = "Поиск в сети включён"
                            )
                        }
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
                        if (state.requestActive) {
                            WorkingStopIcon()
                        } else {
                            Icon(
                                Icons.Outlined.Send,
                                contentDescription = "Отправить"
                            )
                        }
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
                        label = if (state.mode == ChatMode.TEXT) "Создать" else "Текст",
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
private fun ComposerInlineIndicator(
    icon: ImageVector,
    description: String,
    count: Int? = null
) {
    Row(
        modifier = Modifier.padding(horizontal = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        if (count != null && count > 1) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun CompactMessageAction(
    icon: ImageVector,
    description: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WorkingStopIcon() {
    val transition = rememberInfiniteTransition(label = "workingStop")
    val pulse by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520),
            repeatMode = RepeatMode.Reverse
        ),
        label = "workingStopPulse"
    )

    Icon(
        Icons.Outlined.Stop,
        contentDescription = "Остановить работу модели",
        modifier = Modifier.scale(pulse),
        tint = MaterialTheme.colorScheme.primary
    )
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
private fun CompactModeIcon(
    selected: Boolean,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit
) {
    if (selected) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(36.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
        }
    } else {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun InlineComposerToggleIcon(
    selected: Boolean,
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    if (selected) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(32.dp),
            shape = RoundedCornerShape(9.dp)
        ) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(17.dp))
        }
    } else {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun ComposerToggleIcon(
    selected: Boolean,
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    if (selected) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(38.dp),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(19.dp))
        }
    } else {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(38.dp)
        ) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun ChatsDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }
    val chats = state.chats.sortedByDescending { it.updatedAt }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Диалоги") },
        text = {
            Column {
                FilledTonalButton(
                    onClick = {
                        vm.createChat()
                        onDismiss()
                    },
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Новый чат")
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(Modifier.heightIn(max = 430.dp)) {
                    items(chats, key = { it.id }) { chat ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    vm.switchChat(chat.id)
                                    onDismiss()
                                },
                                enabled = !state.isLoading,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        chat.title,
                                        fontWeight = if (chat.id == state.currentChatId) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${chat.messages.size} сообщ. · ${formatDate(chat.updatedAt)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = { deleteTarget = chat },
                                enabled = !state.isLoading
                            ) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить диалог")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )

    deleteTarget?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить диалог?") },
            text = { Text("«${chat.title}» будет удалён. Сгенерированные файлы останутся в хранилище Umnik.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteChat(chat.id)
                    deleteTarget = null
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
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
}

@Composable
private fun EmptyChatCard(mode: ChatMode) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                if (mode == ChatMode.TEXT) "Готов к работе" else "Режим изображений",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (mode == ChatMode.TEXT)
                    "Напишите сообщение, приложите файл или подключите навык."
                else
                    "Опишите изображение. При необходимости приложите изображение-референс.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessageCard(
    message: ChatMessage,
    tts: TtsController,
    onSaveGenerated: (GeneratedFile) -> Unit,
    onExportText: () -> Unit,
    onRetry: (() -> Unit)?
) {
    val context = LocalContext.current
    val user = message.role == "user"
    val container = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (user) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(
                topStart = 22.dp,
                topEnd = 22.dp,
                bottomStart = if (user) 22.dp else 6.dp,
                bottomEnd = if (user) 6.dp else 22.dp
            ),
            colors = CardDefaults.elevatedCardColors(containerColor = container)
        ) {
            Column(Modifier.padding(horizontal = 15.dp, vertical = 12.dp)) {
                Text(
                    if (user) "Вы" else "Umnik",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    color = content
                )
                Spacer(Modifier.height(6.dp))
                MessageBody(message.text, content)

                message.attachmentNames.forEach { name ->
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(17.dp), tint = content)
                        Spacer(Modifier.width(5.dp))
                        Text(name, style = MaterialTheme.typography.bodySmall, color = content)
                    }
                }

                message.generatedFiles.forEach { file ->
                    Spacer(Modifier.height(10.dp))
                    GeneratedFileCard(file, onSaveGenerated)
                }
            }
        }

        if (user && message.text.isNotBlank()) {
            Row(
                modifier = Modifier.padding(end = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactMessageAction(
                    icon = Icons.Outlined.ContentCopy,
                    description = "Копировать своё сообщение",
                    onClick = { copyText(context, message.text) }
                )
                if (onRetry != null) {
                    CompactMessageAction(
                        icon = Icons.Outlined.Refresh,
                        description = "Спросить ещё раз",
                        onClick = onRetry
                    )
                }
            }
        }

        if (!user && (message.text.isNotBlank() || message.generatedFiles.isNotEmpty())) {
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactMessageAction(
                    icon = Icons.Outlined.ContentCopy,
                    description = "Копировать ответ",
                    onClick = { copyText(context, message.text) }
                )
                CompactMessageAction(
                    icon = Icons.Outlined.Share,
                    description = "Поделиться",
                    onClick = { shareText(context, message.text) }
                )
                if (message.text.isNotBlank()) {
                    CompactMessageAction(
                        icon = if (tts.speakingMessageId == message.id) Icons.Outlined.StopCircle else Icons.Outlined.VolumeUp,
                        description = if (tts.speakingMessageId == message.id) "Остановить озвучку" else "Озвучить",
                        active = tts.speakingMessageId == message.id,
                        onClick = { tts.toggle(message.id, message.text) }
                    )
                    CompactMessageAction(
                        icon = Icons.Outlined.Download,
                        description = "Сохранить ответ файлом",
                        onClick = onExportText
                    )
                }
            }
        }
    }
}

private enum class MessagePartKind { PLAIN, CODE, COPY }

private data class MessagePart(
    val text: String,
    val kind: MessagePartKind,
    val language: String = ""
)

@Composable
private fun MessageBody(text: String, color: androidx.compose.ui.graphics.Color) {
    val parts = remember(text) { splitRichBlocks(text) }
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            when (part.kind) {
                MessagePartKind.PLAIN -> if (part.text.isNotBlank()) {
                    MarkdownText(part.text.trim(), color)
                }
                MessagePartKind.CODE -> IsolatedBlock(
                    title = part.language.ifBlank { "Код" },
                    text = part.text,
                    monospace = true,
                    onCopy = { copyText(context, part.text) }
                )
                MessagePartKind.COPY -> IsolatedBlock(
                    title = "Для копирования",
                    text = part.text,
                    monospace = false,
                    onCopy = { copyText(context, part.text) }
                )
            }
        }
    }
}

@Composable
private fun MarkdownText(text: String, color: androidx.compose.ui.graphics.Color) {
    val lines = remember(text) { text.replace("\r\n", "\n").split("\n") }
    val paragraph = mutableListOf<String>()

    @Composable
    fun paragraphBlock(raw: String) {
        if (raw.isBlank()) return
        SelectionContainer {
            Text(
                text = markdownInline(raw),
                color = color,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    @Composable
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            paragraphBlock(paragraph.joinToString("\n").trim())
            paragraph.clear()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var index = 0
        while (index < lines.size) {
            val rawLine = lines[index]
            val line = rawLine.trimEnd()
            val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line.trimStart())
            val unordered = Regex("^\\s*[-+*]\\s+(.+)$").matchEntire(line)
            val ordered = Regex("^\\s*(\\d+)[.)]\\s+(.+)$").matchEntire(line)
            val quote = Regex("^\\s*>\\s?(.*)$").matchEntire(line)
            val horizontalRule = Regex("^\\s*((-{3,})|(\\*{3,})|(_{3,}))\\s*$").matches(line)
            val possibleHeader = markdownTableCells(line)
            val tableStart = possibleHeader.size >= 2 && index + 1 < lines.size &&
                isMarkdownTableSeparator(lines[index + 1], possibleHeader.size)

            when {
                tableStart -> {
                    flushParagraph()
                    val rows = mutableListOf(possibleHeader)
                    index += 2 // skip header separator
                    while (index < lines.size) {
                        val cells = markdownTableCells(lines[index])
                        if (cells.size != possibleHeader.size) break
                        rows += cells
                        index++
                    }
                    MarkdownTable(rows = rows, color = color)
                    continue
                }
                line.isBlank() -> flushParagraph()
                heading != null -> {
                    flushParagraph()
                    val level = heading.groupValues[1].length
                    val style = when (level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    SelectionContainer {
                        Text(
                            text = markdownInline(heading.groupValues[2]),
                            color = color,
                            style = style,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                horizontalRule -> {
                    flushParagraph()
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                }
                unordered != null -> {
                    flushParagraph()
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text("•", color = color, modifier = Modifier.width(20.dp))
                        SelectionContainer {
                            Text(
                                markdownInline(unordered.groupValues[1]),
                                color = color,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                ordered != null -> {
                    flushParagraph()
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text("${ordered.groupValues[1]}.", color = color, modifier = Modifier.width(30.dp))
                        SelectionContainer {
                            Text(
                                markdownInline(ordered.groupValues[2]),
                                color = color,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                quote != null -> {
                    flushParagraph()
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text("│", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            SelectionContainer {
                                Text(
                                    markdownInline(quote.groupValues[1]),
                                    color = color,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                else -> paragraph += line
            }
            index++
        }
        flushParagraph()
    }
}

private fun markdownTableCells(line: String): List<String> {
    val trimmed = line.trim()
    if (!trimmed.contains('|')) return emptyList()
    val body = trimmed.removePrefix("|").removeSuffix("|")
    val cells = body.split('|').map { it.trim() }
    return if (cells.size >= 2) cells else emptyList()
}

private fun isMarkdownTableSeparator(line: String, columns: Int): Boolean {
    val cells = markdownTableCells(line)
    if (cells.size != columns) return false
    return cells.all { Regex("^:?-{3,}:?$").matches(it.replace(" ", "")) }
}

@Composable
private fun MarkdownTable(rows: List<List<String>>, color: androidx.compose.ui.graphics.Color) {
    if (rows.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
        ) {
            rows.forEachIndexed { rowIndex, row ->
                Row {
                    row.forEach { cell ->
                        SelectionContainer {
                            Text(
                                text = markdownInline(cell),
                                modifier = Modifier.width(160.dp).padding(horizontal = 9.dp, vertical = 8.dp),
                                color = color,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                if (rowIndex < rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = if (rowIndex == 0) 0.45f else 0.22f))
                }
            }
        }
    }
}

private fun markdownInline(source: String) = buildAnnotatedString {
    val regex = Regex("`([^`\\n]+)`|\\*\\*([^*\\n]+)\\*\\*|__([^_\\n]+)__|~~([^~\\n]+)~~|\\[([^]\\n]+)]\\(([^)\\n]+)\\)|(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)|(?<!_)_([^_\\n]+)_(?!_)")
    var cursor = 0
    regex.findAll(source).forEach { match ->
        if (match.range.first > cursor) append(source.substring(cursor, match.range.first))
        when {
            match.groupValues[1].isNotEmpty() -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.16f)
                )
            ) { append(match.groupValues[1]) }
            match.groupValues[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[2]) }
            match.groupValues[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[3]) }
            match.groupValues[4].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(match.groupValues[4]) }
            match.groupValues[5].isNotEmpty() -> withStyle(
                SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)
            ) { append(match.groupValues[5]) }
            match.groupValues[7].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groupValues[7]) }
            match.groupValues[8].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groupValues[8]) }
            else -> append(match.value)
        }
        cursor = match.range.last + 1
    }
    if (cursor < source.length) append(source.substring(cursor))
}

@Composable
private fun IsolatedBlock(
    title: String,
    text: String,
    monospace: Boolean,
    onCopy: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Копировать блок", modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            SelectionContainer {
                Text(
                    text.trim(),
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

private fun splitRichBlocks(text: String): List<MessagePart> {
    val regex = Regex(":::copy[ \\t]*\\n([\\s\\S]*?)\\n:::[ \\t]*|```([^\\n`]*)\\n?([\\s\\S]*?)```")
    val result = mutableListOf<MessagePart>()
    var cursor = 0

    regex.findAll(text).forEach { match ->
        if (match.range.first > cursor) {
            result += MessagePart(text.substring(cursor, match.range.first), MessagePartKind.PLAIN)
        }
        if (match.value.startsWith(":::copy")) {
            result += MessagePart(match.groupValues[1].trim(), MessagePartKind.COPY)
        } else {
            result += MessagePart(
                text = match.groupValues[3].trimEnd(),
                kind = MessagePartKind.CODE,
                language = match.groupValues[2].trim()
            )
        }
        cursor = match.range.last + 1
    }

    if (cursor < text.length) result += MessagePart(text.substring(cursor), MessagePartKind.PLAIN)
    if (result.isEmpty()) result += MessagePart(text, MessagePartKind.PLAIN)
    return result
}

@Composable
private fun GeneratedFileCard(file: GeneratedFile, onSave: (GeneratedFile) -> Unit) {
    if (file.mimeType.startsWith("image/")) {
        val bitmap = remember(file.localPath) { BitmapFactory.decodeFile(file.localPath) }
        if (bitmap != null) {
            ComposeImage(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = file.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat())
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(7.dp))
        }
    }

    FilledTonalButton(
        onClick = { onSave(file) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(
            if (file.mimeType.startsWith("image/")) Icons.Outlined.Image else Icons.Outlined.Description,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "${file.name} · ${humanSize(file.size)}",
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.Download, contentDescription = "Сохранить")
    }
}

@Composable
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
                    "Навык — это постоянная инструкция и текстовые материалы для модели. После подключения Umnik добавляет их к каждому текстовому запросу. Навык сам ничего не запускает и не изменяет файлы.",
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
                                label = {
                                    Text(
                                        if (skill.id in state.activeSkillIds)
                                            "Подключён к каждому чату"
                                        else
                                            "Подключить к каждому чату"
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (skill.id in state.activeSkillIds) Icons.Outlined.Check else Icons.Outlined.Extension,
                                        contentDescription = if (skill.id in state.activeSkillIds) "Навык активен" else null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
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
}

@Composable
private fun SettingsScreen(state: UiState, vm: ChatViewModel, onBack: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var storageOpen by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf<ChatMode?>(null) }
    var quickModelsSettingsOpen by remember { mutableStateOf(false) }
    var reasoningExpanded by remember { mutableStateOf(false) }
    var profileExpanded by remember { mutableStateOf(false) }
    var apiExpanded by remember(state.apiKeyConfigured) { mutableStateOf(!state.apiKeyConfigured) }
    var customColorExpanded by remember { mutableStateOf(state.themeChoice == ThemeChoice.CUSTOM) }
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
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Звук готового ответа", fontWeight = FontWeight.Medium)
                                Text(
                                    "Сигнал после завершения ответа модели",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = state.answerSoundEnabled, onCheckedChange = vm::setAnswerSoundEnabled)
                        }
                        if (state.answerSoundEnabled) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AnswerSoundChoice.entries.forEach { choice ->
                                    FilterChip(
                                        selected = state.answerSoundChoice == choice,
                                        onClick = { vm.setAnswerSoundChoice(choice) },
                                        label = { Text(answerSoundLabel(choice)) },
                                        leadingIcon = if (state.answerSoundChoice == choice) {
                                            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Громкость", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text("${state.answerSoundVolume}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Slider(
                                value = state.answerSoundVolume.toFloat(),
                                onValueChange = { vm.setAnswerSoundVolume(it.toInt()) },
                                valueRange = 0f..100f
                            )
                        }
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
                                    onClick = {
                                        vm.setThemeChoice(choice)
                                        if (choice == ThemeChoice.CUSTOM) customColorExpanded = true
                                    },
                                    label = { Text(themeLabel(choice)) },
                                    leadingIcon = if (choice == ThemeChoice.CUSTOM) {
                                        {
                                            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                                                listOf(0xFFE53935, 0xFF7E57C2, 0xFF1E88E5, 0xFF43A047).forEach { c ->
                                                    Surface(shape = CircleShape, color = Color(c.toInt()), modifier = Modifier.size(5.dp)) {}
                                                }
                                            }
                                        }
                                    } else null
                                )
                            }
                        }
                        if (state.themeChoice == ThemeChoice.CUSTOM || customColorExpanded) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Свой цвет",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                val palette = listOf(
                                    0xFFD32F2F.toInt(), 0xFFF57C00.toInt(), 0xFFF9A825.toInt(),
                                    0xFF388E3C.toInt(), 0xFF00897B.toInt(), 0xFF0288D1.toInt(),
                                    0xFF1976D2.toInt(), 0xFF5E35B1.toInt(), 0xFF8E24AA.toInt(),
                                    0xFFC2185B.toInt(), 0xFF6D4C41.toInt(), 0xFF546E7A.toInt()
                                )
                                items(palette) { colorValue ->
                                    FilterChip(
                                        selected = state.customThemeColor == colorValue,
                                        onClick = { vm.setCustomThemeColor(colorValue) },
                                        label = {
                                            Surface(
                                                shape = CircleShape,
                                                color = Color(colorValue),
                                                modifier = Modifier.size(22.dp)
                                            ) {}
                                        },
                                        leadingIcon = if (state.customThemeColor == colorValue) {
                                            { Icon(Icons.Outlined.Check, contentDescription = "Выбран") }
                                        } else null
                                    )
                                }
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
}

@Composable
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
}

@Composable
private fun StorageDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var fileToSave by remember { mutableStateOf<StoredFile?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    var deleteSelectedConfirm by remember { mutableStateOf(false) }
    var protectedExpanded by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val file = fileToSave
        if (uri != null && file != null) vm.saveGeneratedFile(vm.storedFileAsGenerated(file), uri)
        fileToSave = null
    }

    val q = query.trim()
    val workingFiles = remember(state.storedFiles, q) {
        state.storedFiles.filter { file ->
            file.deletable && (q.isBlank() || file.name.contains(q, true) || file.category.contains(q, true))
        }
    }
    val protectedFiles = remember(state.storedFiles, q) {
        state.storedFiles.filter { file ->
            !file.deletable && (q.isBlank() || file.name.contains(q, true) || file.category.contains(q, true))
        }
    }
    val protectedTotal = state.storedFiles.filterNot { it.deletable }
    val protectedBytes = protectedTotal.sumOf { it.size }
    val showProtectedContents = protectedExpanded || q.isNotBlank()

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
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            TextButton(onClick = vm::refreshStorage) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Обновить")
            }
            if (selectedIds.isNotEmpty()) {
                TextButton(onClick = { deleteSelectedConfirm = true }) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Удалить (${selectedIds.size})")
                }
            } else {
                TextButton(onClick = { clearConfirm = true }, enabled = !state.isLoading && workingFiles.isNotEmpty()) {
                    Icon(Icons.Outlined.DeleteForever, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Очистить все")
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            if (workingFiles.isEmpty()) {
                item {
                    Text(
                        if (q.isBlank()) "Нет отдельных рабочих файлов" else "Рабочих файлов не найдено",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(workingFiles, key = { it.id }) { file ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = file.id in selectedIds,
                            onCheckedChange = { checked ->
                                selectedIds = if (checked) selectedIds + file.id else selectedIds - file.id
                            }
                        )
                        Icon(
                            if (file.mimeType.startsWith("image/")) Icons.Outlined.Image else Icons.Outlined.Description,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(7.dp))
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
                        IconButton(onClick = {
                            vm.deleteStoredFile(file)
                            selectedIds = selectedIds - file.id
                        }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить файл")
                        }
                    }
                    HorizontalDivider()
                }
            }

            if (protectedTotal.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        TextButton(
                            onClick = { protectedExpanded = !protectedExpanded },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Системные данные", modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Навыки, проекты и файлы чатов · ${protectedTotal.size} · ${humanSize(protectedBytes)}",
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                if (showProtectedContents) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                contentDescription = if (showProtectedContents) "Свернуть" else "Развернуть"
                            )
                        }
                    }
                }
                if (showProtectedContents) {
                    if (protectedFiles.isEmpty()) {
                        item { Text("В системных данных совпадений нет", modifier = Modifier.padding(12.dp)) }
                    } else {
                        items(protectedFiles, key = { "protected-${it.id}" }) { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${file.category} · ${humanSize(file.size)}",
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
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (deleteSelectedConfirm) {
        AlertDialog(
            onDismissRequest = { deleteSelectedConfirm = false },
            title = { Text("Удалить выбранные файлы?") },
            text = { Text("Будет удалено файлов: ${selectedIds.size}.") },
            confirmButton = {
                TextButton(onClick = {
                    state.storedFiles.filter { it.deletable && it.id in selectedIds }.forEach(vm::deleteStoredFile)
                    selectedIds = emptySet()
                    deleteSelectedConfirm = false
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteSelectedConfirm = false }) { Text("Отмена") } }
        )
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("Очистить рабочие файлы?") },
            text = { Text("Будут удалены сохранённые внутри Umnik изображения, сгенерированные файлы и экспорт. Чаты, проекты, навыки и API-ключ останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearWorkingFiles()
                    selectedIds = emptySet()
                    clearConfirm = false
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("Отмена") } }
        )
    }
}

private fun reasoningEffortLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.MINIMAL -> "Минимальная"
    ReasoningEffort.LOW -> "Низкая"
    ReasoningEffort.MEDIUM -> "Средняя"
    ReasoningEffort.HIGH -> "Высокая"
    ReasoningEffort.XHIGH -> "Максимальная"
}

private data class CameraTarget(val uri: Uri, val file: File)

private fun createCameraTarget(context: Context): CameraTarget {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
    dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return CameraTarget(uri, file)
}

private fun profileScopeLabel(scope: UserProfileScope): String = when (scope) {
    UserProfileScope.OFF -> "Выкл"
    UserProfileScope.PROJECTS -> "Только проекты"
    UserProfileScope.EVERYWHERE -> "Везде"
}

private fun themeLabel(choice: ThemeChoice): String = when (choice) {
    ThemeChoice.DYNAMIC -> "Material You"
    ThemeChoice.CUSTOM -> "Свой цвет"
    ThemeChoice.GRAPHITE -> "Графит"
    ThemeChoice.OCEAN -> "Синяя"
    ThemeChoice.FOREST -> "Зелёная"
    ThemeChoice.AMBER -> "Янтарная"
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Umnik", text))
    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться ответом"))
}

private fun answerSoundLabel(choice: AnswerSoundChoice): String = when (choice) {
    AnswerSoundChoice.DEFAULT -> "Основной"
    AnswerSoundChoice.SOFT -> "Мягкий"
    AnswerSoundChoice.BRIGHT -> "Ясный"
    AnswerSoundChoice.DOUBLE -> "Двойной"
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> String.format(Locale.getDefault(), "%.1f МБ", bytes / 1024.0 / 1024.0)
}
