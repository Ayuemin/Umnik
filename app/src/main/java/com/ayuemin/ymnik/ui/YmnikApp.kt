package com.ayuemin.ymnik.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.outlined.AddComment
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
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.PlayArrow
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
import androidx.compose.material3.Snackbar
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.audio.WavRecorder
import com.ayuemin.ymnik.R
import com.ayuemin.ymnik.model.AnswerSoundChoice
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.ChatMode
import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.ConnectionProfile
import com.ayuemin.ymnik.model.GeneratedFile
import com.ayuemin.ymnik.model.ImageApiProtocol
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.ProviderType
import com.ayuemin.ymnik.model.ReasoningEffort
import com.ayuemin.ymnik.model.Skill
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

private const val QUICK_MODEL_SEPARATOR = "\u001F"
private fun quickModelRef(connectionId: String, modelId: String): String = connectionId + QUICK_MODEL_SEPARATOR + modelId
private fun quickModelConnectionId(ref: String, fallback: String = "openrouter"): String =
    if (QUICK_MODEL_SEPARATOR in ref) ref.substringBefore(QUICK_MODEL_SEPARATOR) else fallback
private fun quickModelId(ref: String): String =
    if (QUICK_MODEL_SEPARATOR in ref) ref.substringAfter(QUICK_MODEL_SEPARATOR) else ref

private fun imageParameterSummary(state: UiState): String =
    listOfNotNull(state.imageAspectRatio, state.imageResolution)
        .ifEmpty { listOf("Авто") }
        .joinToString(" · ")

private fun formatUsd(value: Double): String =
    "$" + "%.2f".format(Locale.US, value.coerceAtLeast(0.0))

@Composable
fun YmnikApp(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val tts = remember { TtsController(context) }
    val openRouterSpeech = remember(viewModel) { OpenRouterSpeechPlayer(context.applicationContext, viewModel) }
    val openRouterSpeechState by openRouterSpeech.state.collectAsState()
    var screen by remember { mutableIntStateOf(0) }

    DisposableEffect(tts, openRouterSpeech) {
        onDispose {
            tts.shutdown()
            openRouterSpeech.close()
        }
    }

    LaunchedEffect(openRouterSpeechState.error) {
        openRouterSpeechState.error?.let { error ->
            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
            openRouterSpeech.clearError()
        }
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
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbar,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 86.dp)
                ) { data ->
                    Snackbar(
                        snackbarData = data,
                        shape = RoundedCornerShape(14.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    0 -> ChatScreen(
                        state = state,
                        vm = viewModel,
                        tts = tts,
                        openRouterSpeech = openRouterSpeech,
                        openRouterSpeechState = openRouterSpeechState,
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
    openRouterSpeech: OpenRouterSpeechPlayer,
    openRouterSpeechState: OpenRouterSpeechPlaybackState,
    onOpenSkills: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var fileToSave by remember { mutableStateOf<GeneratedFile?>(null) }
    var sidebarOpen by remember { mutableStateOf(false) }
    var projectsOpen by remember { mutableStateOf(false) }
    var selectedProjectId by remember { mutableStateOf<String?>(null) }
    var createProjectDirect by remember { mutableStateOf(false) }
    var actionsOpen by remember { mutableStateOf(false) }
    var openRouterToolsExpanded by remember { mutableStateOf(false) }
    var skillsExpanded by remember { mutableStateOf(false) }
    var projectToolsExpanded by remember { mutableStateOf(false) }
    var projectSkillsExpanded by remember { mutableStateOf(false) }
    var imagePromptMode by remember(state.currentChatId) { mutableStateOf(false) }
    var cameraForImageGeneration by remember { mutableStateOf(false) }
    var cameraTarget by remember { mutableStateOf<CameraTarget?>(null) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val voiceRecorder = remember(context) { WavRecorder(context) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingStartedAt by remember { mutableStateOf(0L) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var requestElapsedSeconds by remember { mutableIntStateOf(0) }

    DisposableEffect(voiceRecorder) {
        onDispose { voiceRecorder.cancel() }
    }
    val activeProfile = state.connectionProfiles.firstOrNull { it.id == state.activeConnectionProfileId }
        ?: state.connectionProfiles.first()
    val openRouterProfile = activeProfile.type == ProviderType.OPENROUTER
    val imageProfile = state.connectionProfiles.firstOrNull { it.id == state.imageConnectionProfileId }
        ?: state.connectionProfiles.first()
    val imageConnectionAvailable = imageProfile.id !in state.disabledConnectionIds && vm.connectionImageEnabled(imageProfile.id)
    val activeTextModel = state.currentChatTextModel ?: state.textModel
    val textModelInfo = state.availableTextModels.firstOrNull { it.id == activeTextModel }
    val microphoneAvailable = !imagePromptMode && activeProfile.type == ProviderType.OPENROUTER && textModelInfo?.accepts("audio") == true
    val imageModelInfo = state.availableImageModels.firstOrNull { it.id == state.imageModel }
    val cameraAvailable = if (imagePromptMode) {
        state.imageModel.isNotBlank() && imageProfile.type == ProviderType.OPENROUTER && imageModelInfo?.accepts("image") != false
    } else {
        !activeTextModel.endsWith(":batch", ignoreCase = true) && textModelInfo?.accepts("image") == true
    }
    val reasoningAvailable = !imagePromptMode && textModelInfo?.supportsReasoning == true &&
        (textModelInfo.reasoningEfforts.isEmpty() || state.reasoningEffort.apiValue in textModelInfo.reasoningEfforts)
    val currentChat = state.chats.firstOrNull { it.id == state.currentChatId }
    val currentChatFiles = currentChat?.chatFiles.orEmpty()
    val currentProject = currentChat?.projectId
        ?.let { projectId -> state.projects.firstOrNull { it.id == projectId } }
    val activeSkillCount = state.activeSkillIds.size
    val projectAvailableSkills = currentProject?.let { project ->
        state.skills.filter { it.id in project.skillIds }
    }.orEmpty()
    val activeProjectSkillCount = projectAvailableSkills.count { it.id in state.activeSkillIds }

    fun startVoiceRecording() {
        if (!microphoneAvailable || state.isLoading || state.requestActive || imagePromptMode) return
        runCatching { voiceRecorder.start() }
            .onSuccess {
                recordingStartedAt = System.currentTimeMillis()
                recordingSeconds = 0
                isRecording = true
            }
            .onFailure {
                Toast.makeText(context, it.message ?: "Не удалось начать запись", Toast.LENGTH_SHORT).show()
            }
    }

    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceRecording()
        else Toast.makeText(context, "Для голосового сообщения нужен доступ к микрофону", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(isRecording, recordingStartedAt) {
        while (isRecording) {
            recordingSeconds = ((System.currentTimeMillis() - recordingStartedAt) / 1000L).toInt().coerceAtLeast(0)
            if (recordingSeconds >= 600) {
                val file = voiceRecorder.stop()
                isRecording = false
                file?.let { vm.addVoiceRecording(it.absolutePath) }
                Toast.makeText(context, "Достигнут максимум записи 10 минут", Toast.LENGTH_SHORT).show()
                break
            }
            delay(250)
        }
    }

    LaunchedEffect(state.requestActive) {
        if (!state.requestActive) {
            requestElapsedSeconds = 0
            return@LaunchedEffect
        }
        val startedAt = System.currentTimeMillis()
        while (true) {
            requestElapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L)
                .toInt()
                .coerceAtLeast(0)
            delay(250)
        }
    }

    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri -> vm.addAttachment(uri, imagePromptMode) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        cameraTarget?.let { target ->
            if (ok) vm.addCameraAttachment(target.uri, target.file.absolutePath, cameraForImageGeneration) else target.file.delete()
        }
        cameraTarget = null
        cameraForImageGeneration = false
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

    val density = LocalDensity.current
    val menuSwipeTriggerPx = with(density) { 52.dp.toPx() }
    val menuEdgeTriggerPx = with(density) { 30.dp.toPx() }
    val menuEdgeWidthPx = with(density) { 76.dp.toPx() }

    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(menuSwipeTriggerPx, menuEdgeTriggerPx, menuEdgeWidthPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startedAtEdge = down.position.x <= menuEdgeWidthPx
                    val requiredDistance = if (startedAtEdge) menuEdgeTriggerPx else menuSwipeTriggerPx
                    var horizontalDistance = 0f
                    var verticalDistance = 0f
                    var blockedByChild = false
                    var opened = false

                    while (true) {
                        // Свайп от левого края имеет приоритет над LazyColumn: это делает
                        // открытие панели надёжным даже когда палец попал на сообщение.
                        // В остальной области сохраняем защиту горизонтальных таблиц/списков.
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed && !startedAtEdge) blockedByChild = true

                        horizontalDistance += change.position.x - change.previousPosition.x
                        verticalDistance += change.position.y - change.previousPosition.y

                        if (
                            !blockedByChild &&
                            !opened &&
                            horizontalDistance >= requiredDistance &&
                            horizontalDistance > kotlin.math.abs(verticalDistance) * 1.05f
                        ) {
                            sidebarOpen = true
                            opened = true
                        }

                        if (!change.pressed) break
                        if (horizontalDistance <= -requiredDistance) break
                        if (!startedAtEdge && kotlin.math.abs(verticalDistance) > requiredDistance * 1.8f) break
                    }
                }
            }
    ) {
        ChatHeader(
    state = state,
    vm = vm,
    onOpenSidebar = { sidebarOpen = true }
)

LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.messages.isEmpty()) {
                item { EmptyChatWelcome(Modifier.fillParentMaxSize()) }
            }
            items(state.messages, key = { it.id }) { message ->
                MessageCard(
                    message = message,
                    tts = tts,
                    openRouterSpeechEnabled = state.openRouterSpeechModel.isNotBlank(),
                    openRouterSpeechPhase = if (openRouterSpeechState.messageId == message.id) openRouterSpeechState.phase else OpenRouterSpeechPhase.IDLE,
                    onOpenRouterSpeech = { openRouterSpeech.toggle(message.id, message.text) },
                    onSaveGenerated = { file ->
                        fileToSave = file
                        save.launch(file.name)
                    },
                    onExportText = {
                        val file = vm.exportMessage(message)
                        fileToSave = file
                        save.launch(file.name)
                    },
onBranch = if (message.role == "assistant") {
    { vm.branchFromMessage(message.id) }
} else null,
                    onRetry = when {
                        message.role != "user" || message.text.isBlank() -> null
                        message.deliveryState == "pending" -> null
                        message.deliveryState == "failed" && message.attachmentNames.all { name ->
                            currentChatFiles.any { file -> file.name == name }
                        } -> { { vm.retryFailedMessage(message.id) } }
                        message.imageGeneration && message.attachmentNames.isEmpty() -> {
                            { vm.sendImagePrompt(message.text) }
                        }
                        !message.imageGeneration && message.attachmentNames.all { name ->
                            currentChatFiles.any { file -> file.name == name }
                        } -> {
                            { vm.send(message.text) }
                        }
                        else -> null
                    }
                )
            }
            item(key = "chat-end") { Spacer(Modifier.height(1.dp)) }
        }

        if ((!imagePromptMode && currentChatFiles.isNotEmpty()) || state.pendingAttachments.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!imagePromptMode) {
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
            Column {
                if (imagePromptMode) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Image,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Генерация изображения", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Опишите задачу · ${state.imageModel.substringAfterLast('/').ifBlank { state.imageModel }} · ${imageParameterSummary(state)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { imagePromptMode = false },
                                enabled = !state.requestActive
                            ) {
                                Icon(Icons.Outlined.Close, contentDescription = "Отменить генерацию изображения")
                            }
                        }
                    }
                }

                if (isRecording) {
                    RecordingStatusBar(
                        seconds = recordingSeconds,
                        onCancel = {
                            voiceRecorder.cancel()
                            isRecording = false
                            recordingStartedAt = 0L
                            recordingSeconds = 0
                        }
                    )
                }

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
                            if (!imagePromptMode && activeSkillCount > 0) {
                                ComposerInlineIndicator(
                                    icon = Icons.Outlined.Extension,
                                    description = "Активные навыки: $activeSkillCount",
                                    count = activeSkillCount
                                )
                            }
                            if (!imagePromptMode && state.reasoningEnabled) {
                                ComposerInlineIndicator(
                                    icon = Icons.Outlined.Psychology,
                                    description = "Размышление включено"
                                )
                            }
                            if (!imagePromptMode && state.webSearchEnabled) {
                                ComposerInlineIndicator(
                                    icon = Icons.Outlined.Language,
                                    description = "Поиск в сети включён"
                                )
                            }
                        }
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!imagePromptMode) {
                                IconButton(
                                    onClick = {
                                        if (isRecording) {
                                            val file = voiceRecorder.stop()
                                            isRecording = false
                                            recordingStartedAt = 0L
                                            recordingSeconds = 0
                                            file?.let { vm.addVoiceRecording(it.absolutePath) }
                                        } else if (microphoneAvailable) {
                                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                                startVoiceRecording()
                                            } else {
                                                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                                            }
                                        }
                                    },
                                    enabled = isRecording || (!state.isLoading && !state.requestActive && microphoneAvailable),
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Mic,
                                        contentDescription = when {
                                            isRecording -> "Остановить запись и прикрепить"
                                            microphoneAvailable -> "Записать голосовое сообщение"
                                            else -> "Выбранная модель не поддерживает аудио"
                                        },
                                        tint = when {
                                            isRecording -> MaterialTheme.colorScheme.error
                                            microphoneAvailable -> MaterialTheme.colorScheme.onSurfaceVariant
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
                                        }
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    if (state.requestActive) {
                                        vm.stopGeneration()
                                    } else if (isRecording) {
                                        val file = voiceRecorder.stop()
                                        isRecording = false
                                        recordingStartedAt = 0L
                                        recordingSeconds = 0
                                        if (file != null && vm.addVoiceRecording(file.absolutePath)) {
                                            vm.send(text)
                                            text = ""
                                        }
                                    } else if (imagePromptMode) {
                                        if (vm.sendImagePrompt(text)) {
                                            text = ""
                                            imagePromptMode = false
                                        }
                                    } else {
                                        vm.send(text)
                                        text = ""
                                    }
                                },
                                enabled = state.requestActive || isRecording || (!state.isLoading && (
                                    text.isNotBlank() || state.pendingAttachments.isNotEmpty() || (!imagePromptMode && currentChatFiles.isNotEmpty())
                                )),
                                modifier = Modifier.size(48.dp)
                            ) {
                                if (state.requestActive) {
                                    WorkingStopIcon()
                                } else {
                                    Icon(
                                        Icons.Outlined.Send,
                                        contentDescription = when {
                                            isRecording -> "Остановить запись и отправить"
                                            imagePromptMode -> "Создать изображение"
                                            else -> "Отправить"
                                        }
                                    )
                                }
                            }
                        }
                    },
                    placeholder = {
                        when {
                            state.requestActive -> Text(
                                text = formatRequestDuration(requestElapsedSeconds),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f)
                            )
                            imagePromptMode -> Text("Опишите изображение")
                        }
                    },
                    shape = RoundedCornerShape(28.dp),
                    maxLines = 6
                )
            }
        }
    }

    if (sidebarOpen) {
        NavigationSidebar(
      state = state,
      vm = vm,
      onDismiss = { sidebarOpen = false },
      onNewChat = {
          vm.createChat()
          sidebarOpen = false
      },
      onOpenProjects = {
          selectedProjectId = null
          createProjectDirect = false
          projectsOpen = true
          sidebarOpen = false
      },
      onCreateProject = {
          selectedProjectId = null
          createProjectDirect = true
          projectsOpen = true
          sidebarOpen = false
      },
      onOpenProject = { projectId ->
          createProjectDirect = false
          selectedProjectId = projectId
          projectsOpen = true
          sidebarOpen = false
      },
      onOpenSkills = {
          sidebarOpen = false
          onOpenSkills()
      },
      onOpenSettings = {
          sidebarOpen = false
          onOpenSettings()
      },
      onClearChat = {
          vm.clearChat()
          sidebarOpen = false
      }
        )
    }

    if (actionsOpen) {
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
                    icon = Icons.Outlined.Extension,
                    label = if (activeSkillCount > 0) "Навыки · $activeSkillCount" else "Навыки",
                    expanded = skillsExpanded,
                    onClick = { skillsExpanded = !skillsExpanded }
                )
                if (skillsExpanded) {
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

                ComposerSectionHeader(
                    icon = Icons.Outlined.FolderOpen,
                    label = currentProject?.let { "Проект · ${it.name}" } ?: "Проект",
                    expanded = projectToolsExpanded,
                    enabled = currentProject != null,
                    onClick = { projectToolsExpanded = !projectToolsExpanded }
                )
                if (projectToolsExpanded && currentProject != null) {
                    val orchestratorSteps = if (currentChat != null && vm.isOrchestratorChat(currentChat.id)) vm.orchestratorSteps(currentChat.id) else emptyList()
                    if (orchestratorSteps.isNotEmpty()) {
                        Button(
                            onClick = {
                                val chatId = vm.runOrchestrator(currentProject.id)
                                if (chatId != null) { text = ""; actionsOpen = false }
                            },
                            enabled = !state.isLoading && !state.requestActive,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Выполнить сценарий (${orchestratorSteps.size})")
                        }
                    }
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
            }
        }
    }

    if (projectsOpen) {
        ProjectsDialog(
            state = state,
            vm = vm,
            onDismiss = {
      projectsOpen = false
      selectedProjectId = null
      createProjectDirect = false
            },
            initialProjectId = selectedProjectId,
            startCreate = createProjectDirect
        )
    }
}


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
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
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

@Composable
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
private fun RecordingStatusBar(seconds: Int, onCancel: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "voiceRecording")
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 620),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voicePulse"
    )
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.62f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Mic,
                contentDescription = null,
                modifier = Modifier.size(20.dp).scale(pulse),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.width(9.dp))
            Text(
                "Запись ${formatRecordingDuration(seconds)}",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            TextButton(onClick = onCancel) { Text("Отмена") }
        }
    }
}

private fun formatRecordingDuration(seconds: Int): String =
    "%02d:%02d".format(seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)

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
private fun OpenRouterSpeechAction(
    enabled: Boolean,
    phase: OpenRouterSpeechPhase,
    onClick: () -> Unit
) {
    val preparing = phase == OpenRouterSpeechPhase.PREPARING
    val playing = phase == OpenRouterSpeechPhase.PLAYING
    val transition = rememberInfiniteTransition(label = "openRouterSpeechPulse")
    val pulse by transition.animateFloat(
        initialValue = if (preparing) 0.84f else 1f,
        targetValue = if (preparing) 1.12f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 620),
            repeatMode = RepeatMode.Reverse
        ),
        label = "openRouterSpeechScale"
    )
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
        preparing || playing -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(38.dp).scale(pulse)
    ) {
        Box(Modifier.size(25.dp)) {
            Icon(
                if (playing) Icons.Outlined.StopCircle else Icons.Outlined.VolumeUp,
                contentDescription = when {
                    preparing -> "OpenRouter готовит озвучку; нажмите ещё раз для отмены"
                    playing -> "Остановить озвучку OpenRouter"
                    else -> "Озвучить через OpenRouter"
                },
                modifier = Modifier.size(20.dp).align(Alignment.CenterStart),
                tint = tint
            )
            Text(
                "OR",
                modifier = Modifier.align(Alignment.BottomEnd),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = tint
            )
        }
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

private fun formatRequestDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "%d:%02d".format(Locale.US, safe / 60, safe % 60)
}

@Composable
private fun ChatHeader(
    state: UiState,
    vm: ChatViewModel,
    onOpenSidebar: () -> Unit
) {
    var quickModelsOpen by remember { mutableStateOf(false) }
    var usageOpen by remember { mutableStateOf(false) }
    val activeProfile = state.connectionProfiles.firstOrNull { it.id == state.activeConnectionProfileId }
    val activeUsage = state.providerUsage?.takeIf { activeProfile?.type == ProviderType.OPENROUTER }
    val activeTextModel = state.currentChatTextModel ?: state.textModel
    val shortModelName = activeTextModel.substringAfter('/').ifBlank { activeTextModel }
    val currentRef = quickModelRef(state.activeConnectionProfileId, activeTextModel)
    val defaultRef = quickModelRef(state.activeConnectionProfileId, state.textModel)
    val quickCandidates = (listOf(currentRef, defaultRef) + state.quickTextModels)
        .filter { quickModelId(it).isNotBlank() }
        .distinct()
    val currentProjectId = state.chats.firstOrNull { it.id == state.currentChatId }?.projectId

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Row(
  modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
  verticalAlignment = Alignment.CenterVertically
        ) {
  IconButton(onClick = onOpenSidebar, modifier = Modifier.size(42.dp)) {
      Icon(
Icons.Outlined.Menu,
contentDescription = "Открыть проекты и историю",
modifier = Modifier.size(25.dp),
tint = if (currentProjectId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
      )
  }

  activeUsage?.let { usage ->
      Spacer(Modifier.width(2.dp))
      Surface(
onClick = {
    usageOpen = true
    vm.refreshProviderUsage()
},
shape = RoundedCornerShape(10.dp),
color = MaterialTheme.colorScheme.surfaceContainerHigh
      ) {
Text(
    formatUsd(usage.daily),
    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1
)
      }
      Spacer(Modifier.width(4.dp))
  }

  Box(modifier = Modifier.weight(1f)) {
      TextButton(
onClick = { quickModelsOpen = true },
enabled = !state.isLoading,
modifier = Modifier.fillMaxWidth(),
contentPadding = PaddingValues(horizontal = 5.dp, vertical = 2.dp)
      ) {
Text(
    shortModelName,
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.SemiBold,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis
)
Spacer(Modifier.width(3.dp))
Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Выбрать модель", modifier = Modifier.size(20.dp))
      }

      DropdownMenu(expanded = quickModelsOpen, onDismissRequest = { quickModelsOpen = false }) {
quickCandidates.forEach { ref ->
    val id = quickModelId(ref)
    val connectionId = quickModelConnectionId(ref, state.activeConnectionProfileId)
    val connection = state.connectionProfiles.firstOrNull { it.id == connectionId }
    val current = ref == currentRef
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
    ref == defaultRef -> "По умолчанию · ${connection?.name ?: "Подключение"}"
    else -> connection?.name ?: id
},
style = MaterialTheme.typography.bodySmall,
color = MaterialTheme.colorScheme.onSurfaceVariant,
maxLines = 1,
overflow = TextOverflow.Ellipsis
      )
  }
        },
        onClick = {
  if (ref == defaultRef) vm.useDefaultTextModelForChat() else vm.selectQuickTextModel(ref)
  quickModelsOpen = false
        }
    )
}
      }
  }
        }
    }

    if (usageOpen && activeUsage != null) {
        AlertDialog(
  onDismissRequest = { usageOpen = false },
  title = { Text(activeUsage.providerName) },
  text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
listOf(
    "Сегодня" to activeUsage.daily,
    "Неделя" to activeUsage.weekly,
    "Месяц" to activeUsage.monthly,
    "Всего этим ключом" to activeUsage.total
).forEach { (label, value) ->
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatUsd(value), fontWeight = FontWeight.SemiBold)
    }
}
Text(
    "Периоды OpenRouter считаются по UTC. Данные берутся напрямую для текущего API-ключа.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)
      }
  },
  confirmButton = { TextButton(onClick = { usageOpen = false }) { Text("Закрыть") } },
  dismissButton = {
      TextButton(onClick = { vm.refreshProviderUsage() }) {
Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
Spacer(Modifier.width(5.dp))
Text("Обновить")
      }
  }
        )
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
        modifier = modifier.height(80.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(23.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
            Spacer(Modifier.height(5.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis
            )
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
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(25.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        )
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
    val chats = state.chats.filter { it.projectId == null }.sortedByDescending { it.updatedAt }

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
    val enabledConnections = state.connectionProfiles.filter { profile ->
        profile.id !in state.disabledConnectionIds && (mode == ChatMode.TEXT || vm.connectionImageEnabled(profile.id))
    }
    var selectedTextConnectionId by remember(mode, enabledConnections.map { it.id }) {
        mutableStateOf(
            state.activeConnectionProfileId.takeIf { id -> enabledConnections.any { it.id == id } }
                ?: enabledConnections.firstOrNull()?.id
        )
    }
    var selectedImageConnectionId by remember(mode, enabledConnections.map { it.id }) {
        mutableStateOf(
            state.imageConnectionProfileId.takeIf { id -> enabledConnections.any { it.id == id } }
                ?: enabledConnections.firstOrNull()?.id
        )
    }
    val selectedTextConnection = enabledConnections.firstOrNull { it.id == selectedTextConnectionId }
    val selectedImageConnection = enabledConnections.firstOrNull { it.id == selectedImageConnectionId }
    val selectedConnectionId = if (mode == ChatMode.TEXT) selectedTextConnectionId else selectedImageConnectionId
    val selectedConnection = if (mode == ChatMode.TEXT) selectedTextConnection else selectedImageConnection
    val models = when {
        state.modelCatalogConnectionId == selectedConnectionId -> state.modelCatalog
        mode == ChatMode.TEXT && selectedConnectionId == state.activeConnectionProfileId -> state.availableTextModels
        mode == ChatMode.IMAGE && selectedConnectionId == state.imageConnectionProfileId -> state.availableImageModels
        else -> emptyList()
    }
    val current = if (mode == ChatMode.TEXT) {
        selectedTextConnectionId?.let(vm::defaultTextModelForConnection).orEmpty()
    } else {
        selectedImageConnectionId?.let(vm::defaultImageModelForConnection).orEmpty()
    }
    var manualImageModel by remember(selectedImageConnectionId, current) { mutableStateOf(current) }
    var pendingModel by remember(mode, selectedConnectionId, current) { mutableStateOf(current) }

    LaunchedEffect(mode, selectedTextConnectionId, selectedImageConnectionId) {
        if (mode == ChatMode.TEXT) selectedTextConnectionId?.let(vm::loadConnectionModels)
        else selectedImageConnectionId?.let(vm::loadImageConnectionModels)
    }

    val filtered = remember(models, query) {
        models.filter { it.id.contains(query.trim(), ignoreCase = true) }.take(300)
    }
    val customImageConnection = mode == ChatMode.IMAGE && selectedConnection?.type == ProviderType.OPENAI_COMPATIBLE

    FullScreenPanel(
        title = if (mode == ChatMode.TEXT) "Текстовая модель" else "Модель изображений",
        onBack = onDismiss
    ) {
        Text(
            "Сейчас: ${selectedConnection?.name ?: "Подключение"} · ${current.ifBlank { "не выбрана" }}",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (enabledConnections.isEmpty()) {
            Text(
                if (mode == ChatMode.IMAGE) "Нет подключений с включённой генерацией изображений." else "Нет включённых подключений.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(enabledConnections, key = { it.id }) { connection ->
                    FilterChip(
                        selected = selectedConnectionId == connection.id,
                        onClick = {
                            if (mode == ChatMode.TEXT) selectedTextConnectionId = connection.id
                            else selectedImageConnectionId = connection.id
                            query = ""
                        },
                        label = { Text(connection.name, maxLines = 1) }
                    )
                }
            }
            if (mode == ChatMode.IMAGE) {
                Text(
                    when (selectedConnection?.type) {
                        ProviderType.OPENROUTER -> "Показаны только модели OpenRouter Image API."
                        ProviderType.NVIDIA -> "Показаны только проверенные генераторы NVIDIA NIM. Модели с нестабильным hosted endpoint временно скрываются реестром Umnik."
                        ProviderType.OPENAI_COMPATIBLE -> "У произвольного API нет универсального каталога генераторов. Укажите ID image-модели вручную; Umnik не будет выдавать общий /models за список генераторов."
                        null -> ""
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Выберите поставщика и модель, затем нажмите «Сохранить выбор». Выбранный поставщик и модель сразу применятся к текущему чату и станут значениями по умолчанию для новых чатов.",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (customImageConnection) {
            OutlinedTextField(
                value = manualImageModel,
                onValueChange = { manualImageModel = it.trim().take(180) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                label = { Text("ID модели изображений") },
                placeholder = { Text("provider/image-model") },
                singleLine = true
            )
            FilledTonalButton(
                onClick = {
                    selectedImageConnectionId?.let { vm.selectImageModel(it, manualImageModel) }
                    onDismiss()
                },
                enabled = manualImageModel.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) { Text("Сохранить модель") }
        }

        if (!customImageConnection) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Поиск модели") }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = {
                if (mode == ChatMode.TEXT) selectedTextConnectionId?.let(vm::loadConnectionModels)
                else selectedImageConnectionId?.let(vm::loadImageConnectionModels)
            }) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("Обновить")
            }
        }
        if (!customImageConnection) {
            FilledTonalButton(
                onClick = {
                    val chosen = pendingModel.trim()
                    if (chosen.isNotBlank()) {
                        if (mode == ChatMode.TEXT) {
                            selectedTextConnectionId?.let { vm.selectDefaultTextModel(it, chosen) }
                        } else {
                            selectedImageConnectionId?.let { vm.selectImageModel(it, chosen) }
                        }
                        onDismiss()
                    }
                },
                enabled = pendingModel.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("Сохранить выбор")
            }
        }
        if (filtered.isEmpty()) {
            if (!customImageConnection) {
                Text(
                    if (state.isLoading) "Загрузка списка…" else "Модели не найдены",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                items(filtered, key = { it.id }) { modelInfo ->
                    val selected = pendingModel == modelInfo.id
                    TextButton(
                        onClick = { pendingModel = modelInfo.id },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                    ) {
                        Text(
                            modelInfo.id,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (selected) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = "Выбрано",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
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
    openRouterSpeechEnabled: Boolean,
    openRouterSpeechPhase: OpenRouterSpeechPhase,
    onOpenRouterSpeech: () -> Unit,
    onSaveGenerated: (GeneratedFile) -> Unit,
    onExportText: () -> Unit,
    onBranch: (() -> Unit)?,
    onRetry: (() -> Unit)?
) {
    val context = LocalContext.current
    val user = message.role == "user"
    val content = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start
    ) {
        if (user) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.86f),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f),
                shape = RoundedCornerShape(18.dp),
                tonalElevation = 0.dp
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (message.text.isNotBlank()) {
                        MessageBody(message.text, content)
                    }
                    message.attachmentNames.forEach { name ->
                        Spacer(Modifier.height(7.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (message.deliveryState == "failed" || message.deliveryState == "pending") {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (message.deliveryState == "pending") "Ожидается ответ…" else "Ответ не получен. Можно повторить вручную.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            ) {
                if (message.text.isNotBlank()) {
                    MessageBody(message.text, content)
                }
                message.generatedFiles.forEach { file ->
                    Spacer(Modifier.height(10.dp))
                    GeneratedFileCard(file)
                }
                val usageMeta = listOfNotNull(
                    message.modelId?.takeIf { it.isNotBlank() }?.substringAfterLast('/'),
                    message.providerName?.takeIf { it.isNotBlank() },
                    message.inputTokens?.let { "in $it" },
                    message.outputTokens?.let { "out $it" },
                    message.costUsd?.takeIf { it >= 0.0 }?.let { formatUsd(it) }
                ).joinToString(" · ")
                if (usageMeta.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        usageMeta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    )
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
                        description = if (message.imageGeneration) "Сгенерировать снова" else "Спросить ещё раз",
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
                    onClick = {
                        message.generatedFiles.singleOrNull()?.let { shareGeneratedFile(context, it) }
                            ?: shareText(context, message.text)
                    }
                )
                if (message.text.isNotBlank()) {
                    CompactMessageAction(
                        icon = if (tts.speakingMessageId == message.id) Icons.Outlined.StopCircle else Icons.Outlined.VolumeUp,
                        description = if (tts.speakingMessageId == message.id) "Остановить озвучку" else "Озвучить",
                        active = tts.speakingMessageId == message.id,
                        onClick = { tts.toggle(message.id, message.text) }
                    )
                    OpenRouterSpeechAction(
                        enabled = openRouterSpeechEnabled,
                        phase = openRouterSpeechPhase,
                        onClick = onOpenRouterSpeech
                    )
                    CompactMessageAction(
                        icon = Icons.Outlined.Download,
                        description = if (message.generatedFiles.size == 1) "Скачать файл" else "Сохранить ответ файлом",
                        onClick = {
                            message.generatedFiles.singleOrNull()?.let(onSaveGenerated) ?: onExportText()
                        }
                    )
                }
                if (onBranch != null) {
                    IconButton(
                        onClick = onBranch,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_branch_chat),
                            contentDescription = "Ветка в новом чате",
                            modifier = Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
private fun GeneratedFileCard(file: GeneratedFile) {
    val context = LocalContext.current
    val isImage = file.mimeType.startsWith("image/")
    val isAudio = file.mimeType.startsWith("audio/")
    val isVideo = file.mimeType.startsWith("video/")
    val bitmap = remember(file.localPath, file.mimeType) {
        if (isImage && file.mimeType.lowercase() != "image/svg+xml") {
            BitmapFactory.decodeFile(file.localPath)
        } else null
    }

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
    } else {
        val rowContent: @Composable () -> Unit = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    file.name,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        if (isVideo) {
            Surface(
                onClick = { openGeneratedFile(context, file) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) { rowContent() }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) { rowContent() }
        }
    }

    if (isAudio) {
        Spacer(Modifier.height(6.dp))
        GeneratedAudioPlayer(file)
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
private fun SkillLibrarySettings(state: UiState, vm: ChatViewModel) {
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importSkillFile)
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::importSkillTree)
    }

    Text(
        "Здесь хранится общая библиотека навыков. Включение навыка выполняется отдельно в каждом чате через + → Навыки.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(9.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { filePicker.launch(arrayOf("text/*", "application/json", "application/yaml")) }) {
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
    Spacer(Modifier.height(9.dp))
    if (state.skills.isEmpty()) {
        Text("Пока навыков нет. Импортируйте SKILL.md или папку навыка.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        state.skills.forEach { skill ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 13.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(skill.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Файлов: ${skill.files.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.deleteSkill(skill.id) }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить навык")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: UiState, vm: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
        }.getOrDefault("—")
    }
    var storageOpen by remember { mutableStateOf(false) }
    var modelPicker by remember { mutableStateOf<ChatMode?>(null) }
    var quickModelsSettingsOpen by remember { mutableStateOf(false) }
    var modelsExpanded by remember { mutableStateOf(false) }
    var imageModelsExpanded by remember { mutableStateOf(false) }
    var imageParametersOpen by remember { mutableStateOf(false) }
    var reasoningExpanded by remember { mutableStateOf(false) }
    var skillsLibraryExpanded by remember { mutableStateOf(false) }
    var soundExpanded by remember { mutableStateOf(false) }
    var openRouterSpeechExpanded by remember { mutableStateOf(false) }
    var openRouterDocumentSpeechExpanded by remember { mutableStateOf(false) }
    var storageExpanded by remember { mutableStateOf(false) }
    var profileExpanded by remember { mutableStateOf(false) }
    var themeExpanded by remember { mutableStateOf(false) }
    var connectionsExpanded by remember { mutableStateOf(false) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    var diagnosticLoggingEnabled by remember { mutableStateOf(vm.isDiagnosticLoggingEnabled()) }
    var diagnosticLogBytes by remember { mutableStateOf(vm.diagnosticLogSize()) }
    var diagnosticFileToSave by remember { mutableStateOf<GeneratedFile?>(null) }
    var editingProfileId by remember { mutableStateOf("openrouter") }
    val editingProfile = state.connectionProfiles.firstOrNull { it.id == editingProfileId }
        ?: state.connectionProfiles.first()
    var connectionName by remember(editingProfile.id, editingProfile.name) { mutableStateOf(editingProfile.name) }
    var connectionUrl by remember(editingProfile.id, editingProfile.baseUrl, editingProfile.useProviderDefaults) {
        mutableStateOf(vm.connectionTextEndpoint(editingProfile.id))
    }
    var connectionKey by remember(editingProfile.id) { mutableStateOf("") }
    var connectionAdvancedExpanded by remember(editingProfile.id) { mutableStateOf(false) }
    var connectionImageEnabled by remember(editingProfile.id, editingProfile.imageEnabled) {
        mutableStateOf(vm.connectionImageEnabled(editingProfile.id))
    }
    var connectionImageUrl by remember(editingProfile.id, editingProfile.imageBaseUrl, editingProfile.useProviderDefaults) {
        mutableStateOf(vm.connectionImageEndpoint(editingProfile.id))
    }
    var connectionImageProtocol by remember(editingProfile.id, editingProfile.imageProtocol) {
        mutableStateOf(editingProfile.imageProtocol ?: ImageApiProtocol.AUTO)
    }
    var connectionSameImageKey by remember(editingProfile.id, editingProfile.useSameImageApiKey) {
        mutableStateOf(vm.connectionUsesSameImageKey(editingProfile.id))
    }
    var connectionImageKey by remember(editingProfile.id) { mutableStateOf("") }
    var connectionUseProviderDefaults by remember(editingProfile.id, editingProfile.useProviderDefaults, editingProfile.baseUrl) {
        mutableStateOf(vm.connectionUsesProviderDefaults(editingProfile.id))
    }
    var connectionContextWindow by remember(editingProfile.id, editingProfile.contextLimitTokens) {
        mutableStateOf(editingProfile.contextLimitTokens?.toString().orEmpty())
    }
    var customColorText by remember(state.customThemeColor) {
        mutableStateOf("#%06X".format(state.customThemeColor and 0xFFFFFF))
    }
    var profileName by remember(state.userProfile.name) { mutableStateOf(state.userProfile.name) }
    var profileGender by remember(state.userProfile.gender) { mutableStateOf(state.userProfile.gender) }
    var profileAge by remember(state.userProfile.age) { mutableStateOf(state.userProfile.age) }
    var profileOccupation by remember(state.userProfile.occupation) { mutableStateOf(state.userProfile.occupation) }
    var profileNote by remember(state.userProfile.note) { mutableStateOf(state.userProfile.note) }
    val themes = ThemeChoice.entries
    val profileScopes = UserProfileScope.entries
    val importedSounds = state.storedFiles.filter { it.category == "Звуки" }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importAnswerSound)
    }
    val diagnosticSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        val file = diagnosticFileToSave
        if (uri != null && file != null) vm.saveGeneratedFile(file, uri)
        diagnosticFileToSave = null
        diagnosticLogBytes = vm.diagnosticLogSize()
    }

    Column(Modifier.fillMaxSize()) {
        PinnedBackHeader(title = "Настройки", onBack = onBack)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                ExpandableSettingsCard(
                    title = "Модели",
                    subtitle = state.textModel.substringAfterLast('/'),
                    icon = Icons.Outlined.TextFields,
                    expanded = modelsExpanded,
                    onToggle = { modelsExpanded = !modelsExpanded }
                ) {
                    FilledTonalButton(onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("models-settings") }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.TextFields, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Чат по умолчанию", fontWeight = FontWeight.Medium)
                            Text(state.textModel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    FilledTonalButton(onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("models-settings") }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.SwapHoriz, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Быстрые модели", fontWeight = FontWeight.Medium)
                            Text(
                                if (state.quickTextModels.isEmpty()) "Не выбраны" else "Выбрано: ${state.quickTextModels.size} · нажмите для каталога",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    if (state.quickTextModels.isNotEmpty()) {
                        Spacer(Modifier.height(5.dp))
                        state.quickTextModels.forEach { ref ->
                            val modelId = quickModelId(ref)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    modelId,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                TextButton(onClick = { vm.toggleQuickTextModelForConnection("openrouter", modelId) }) {
                                    Text("Убрать")
                                }
                            }
                        }
                    }
                    if (state.textModel != "openrouter/auto") {
                        TextButton(
                            onClick = { vm.selectDefaultTextModel("openrouter", "openrouter/auto") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Сбросить модель чата на Auto") }
                    }
                    Spacer(Modifier.height(7.dp))
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
                }
            }

            item {
                val imageConnectionName = state.connectionProfiles.firstOrNull { it.id == state.imageConnectionProfileId }?.name ?: "Подключение"
                ExpandableSettingsCard(
                    title = "Генерация изображений",
                    subtitle = "${state.imageModel.substringAfterLast('/').ifBlank { "не выбрана" }} · $imageConnectionName",
                    icon = Icons.Outlined.Image,
                    expanded = imageModelsExpanded,
                    onToggle = { imageModelsExpanded = !imageModelsExpanded }
                ) {
                    Text(
                        "Выбор модели выполняется в общем каталоге OpenRouter. Здесь показана текущая модель для «+ → Создать».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(9.dp))
                    FilledTonalButton(
                        onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("models-settings") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Image, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Модель изображений", fontWeight = FontWeight.Medium)
                            Text(
                                state.imageModel.ifBlank { "Не выбрана" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (state.imageModel.isNotBlank()) {
                        TextButton(onClick = { vm.clearImageModel() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Снять выбор модели изображений")
                        }
                    }
                    Spacer(Modifier.height(7.dp))
                    FilledTonalButton(
                        onClick = { imageParametersOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Параметры изображения", fontWeight = FontWeight.Medium)
                            Text(
                                imageParameterSummary(state),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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
                ExpandableSettingsCard(
                    title = "Навыки",
                    subtitle = if (state.skills.isEmpty()) "Библиотека пуста" else "${state.skills.size} навыков",
                    icon = Icons.Outlined.Extension,
                    expanded = skillsLibraryExpanded,
                    onToggle = { skillsLibraryExpanded = !skillsLibraryExpanded }
                ) {
                    SkillLibrarySettings(state, vm)
                }
            }

            item {
                ExpandableSettingsCard(
                    title = "Звук готового ответа",
                    subtitle = when {
                        !state.answerSoundEnabled -> "Выключен"
                        state.answerSoundChoice == AnswerSoundChoice.CUSTOM -> state.answerSoundCustomName ?: "Свой звук"
                        else -> "Основной сигнал"
                    },
                    icon = Icons.Outlined.VolumeUp,
                    expanded = soundExpanded,
                    onToggle = { soundExpanded = !soundExpanded }
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Уведомлять после ответа", modifier = Modifier.weight(1f))
                        Switch(checked = state.answerSoundEnabled, onCheckedChange = vm::setAnswerSoundEnabled)
                    }
                    if (state.answerSoundEnabled) {
                        Spacer(Modifier.height(10.dp))
                        FilterChip(
                            selected = state.answerSoundChoice == AnswerSoundChoice.DEFAULT,
                            onClick = { vm.setAnswerSoundChoice(AnswerSoundChoice.DEFAULT) },
                            label = { Text("Основной") },
                            leadingIcon = if (state.answerSoundChoice == AnswerSoundChoice.DEFAULT) {
                                { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                        if (importedSounds.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(importedSounds, key = { it.id }) { sound ->
                                    FilterChip(
                                        selected = state.answerSoundCustomPath == sound.localPath && state.answerSoundChoice == AnswerSoundChoice.CUSTOM,
                                        onClick = { vm.selectAnswerSound(sound) },
                                        label = { Text(sound.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = if (state.answerSoundCustomPath == sound.localPath && state.answerSoundChoice == AnswerSoundChoice.CUSTOM) {
                                            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = { soundPicker.launch(arrayOf("audio/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("Добавить звук с телефона")
                        }
                        Text(
                            "Выбранный файл копируется в память Umnik и остаётся доступным, пока вы не удалите его в «Хранилище Umnik».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 7.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Громкость", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("${state.answerSoundVolume}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(
                            value = state.answerSoundVolume.toFloat(),
                            onValueChange = { vm.setAnswerSoundVolume(it.toInt()) },
                            valueRange = 0f..100f
                        )
                        FilledTonalButton(
                            onClick = vm::previewAnswerSound,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("Проверить звук")
                        }
                    }
                }
            }

            item {
                ExpandableSettingsCard(
                    title = "Озвучивание ответов OpenRouter",
                    subtitle = when {
                        state.openRouterSpeechModel.isBlank() -> "Модель не выбрана"
                        else -> buildList {
                            add(state.openRouterSpeechModel.substringAfterLast('/'))
                            if (state.openRouterSpeechVoice.isNotBlank()) add(state.openRouterSpeechVoice)
                            add(state.openRouterSpeechResponseFormat.ifBlank { "Авто" }.uppercase())
                        }.joinToString(" · ")
                    },
                    icon = Icons.Outlined.VolumeUp,
                    expanded = openRouterSpeechExpanded,
                    onToggle = { openRouterSpeechExpanded = !openRouterSpeechExpanded }
                ) {
                    Text(
                        "Используется только кнопкой OR под ответами.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("reply-speech") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Настроить модель и параметры")
                    }
                }
            }

            item {
                ExpandableSettingsCard(
                    title = "Озвучивание текста и документов",
                    subtitle = "Отдельная модель и параметры",
                    icon = Icons.Outlined.Description,
                    expanded = openRouterDocumentSpeechExpanded,
                    onToggle = { openRouterDocumentSpeechExpanded = !openRouterDocumentSpeechExpanded }
                ) {
                    Text(
                        "Используется режимом «+ → Озвучить» и не меняет озвучивание ответов.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("speech") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Открыть настройки озвучивания")
                    }
                }
            }

            item {
                ExpandableSettingsCard(
                    title = "Хранилище Umnik",
                    subtitle = "${state.storedFiles.size} файлов · ${humanSize(state.storageStats.totalBytes)}",
                    icon = Icons.Outlined.Storage,
                    expanded = storageExpanded,
                    onToggle = { storageExpanded = !storageExpanded }
                ) {
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
                ExpandableSettingsCard(
                    title = "Цветовая схема",
                    subtitle = if (state.themeChoice == ThemeChoice.CUSTOM) {
                        "Свой цвет · #%06X".format(state.customThemeColor and 0xFFFFFF)
                    } else {
                        themeLabel(state.themeChoice)
                    },
                    icon = Icons.Outlined.Palette,
                    expanded = themeExpanded,
                    onToggle = { themeExpanded = !themeExpanded }
                ) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(themes) { choice ->
                            FilterChip(
                                selected = state.themeChoice == choice,
                                onClick = { vm.setThemeChoice(choice) },
                                label = { Text(themeLabel(choice)) },
                                leadingIcon = if (choice == ThemeChoice.CUSTOM) {
                                    {
                                        Surface(
                                            shape = CircleShape,
                                            color = Color(state.customThemeColor),
                                            modifier = Modifier.size(14.dp)
                                        ) {}
                                    }
                                } else null
                            )
                        }
                    }
                    if (state.themeChoice == ThemeChoice.CUSTOM) {
                        Spacer(Modifier.height(10.dp))
                        val cleanHex = customColorText.trim().removePrefix("#")
                        val parsedColor = cleanHex
                            .takeIf { value ->
                                value.length == 6 && value.all { ch -> ch.isDigit() || ch.uppercaseChar() in 'A'..'F' }
                            }
                            ?.toLongOrNull(16)
                            ?.let { rgb -> (0xFF000000L or rgb).toInt() }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = Color(parsedColor ?: state.customThemeColor),
                                modifier = Modifier.size(38.dp)
                            ) {}
                            OutlinedTextField(
                                value = customColorText,
                                onValueChange = { customColorText = it.trim().uppercase().take(7) },
                                modifier = Modifier.weight(1f),
                                label = { Text("HEX-код") },
                                placeholder = { Text("#6750A4") },
                                singleLine = true,
                                isError = customColorText.isNotBlank() && parsedColor == null
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = { parsedColor?.let(vm::setCustomThemeColor) },
                            enabled = parsedColor != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Check, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("Применить цвет")
                        }
                        Text(
                            "Введите стандартный HEX-код цвета, например #1E88E5.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }

            item {
                val openRouterSettingsProfile = state.connectionProfiles.firstOrNull { it.type == ProviderType.OPENROUTER }
                    ?: state.connectionProfiles.first()
                ExpandableSettingsCard(
                    title = "OpenRouter",
                    subtitle = "API-ключ и соединение",
                    icon = Icons.Outlined.Language,
                    expanded = connectionsExpanded,
                    onToggle = { connectionsExpanded = !connectionsExpanded }
                ) {
                    Text(
                        "Umnik работает через OpenRouter. Здесь настраивается единственное подключение приложения; выбор моделей находится в разделе «Модели».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = connectionKey,
                        onValueChange = { connectionKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API-ключ OpenRouter") },
                        placeholder = { Text("Оставьте пустым, чтобы не менять сохранённый ключ") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            vm.saveApiKey(connectionKey.takeIf { it.isNotBlank() })
                            connectionKey = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Check, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Сохранить API-ключ")
                    }
                    Spacer(Modifier.height(7.dp))
                    FilledTonalButton(
                        onClick = { vm.checkConnection(openRouterSettingsProfile.id) },
                        enabled = !state.isLoading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Проверить подключение")
                    }
                    Spacer(Modifier.height(5.dp))
                    TextButton(
                        onClick = { connectionAdvancedExpanded = !connectionAdvancedExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            if (connectionAdvancedExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Технические настройки OpenRouter")
                    }
                    if (connectionAdvancedExpanded) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Автоматический адрес API")
                                Text(
                                    "Рекомендуется. Umnik использует актуальный стандартный адрес OpenRouter.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = connectionUseProviderDefaults,
                                onCheckedChange = { connectionUseProviderDefaults = it }
                            )
                        }
                        if (!connectionUseProviderDefaults) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = connectionUrl,
                                onValueChange = { connectionUrl = it.trim().take(300) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Адрес API OpenRouter") },
                                placeholder = { Text("https://openrouter.ai/api/v1") },
                                singleLine = true
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = connectionContextWindow,
                            onValueChange = { value -> connectionContextWindow = value.filter(Char::isDigit).take(7) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Ручной предел контекста, токенов") },
                            placeholder = { Text("Необязательно · обычно определяется по модели") },
                            singleLine = true
                        )
                        Text(
                            "Оставьте поле пустым, если не требуется вручную ограничивать контекст.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                        Spacer(Modifier.height(9.dp))
                        FilledTonalButton(
                            onClick = {
                                vm.saveConnectionProfile(
                                    profileId = openRouterSettingsProfile.id,
                                    name = "OpenRouter",
                                    baseUrl = connectionUrl,
                                    apiKey = connectionKey.takeIf { it.isNotBlank() },
                                    imageEnabled = true,
                                    imageBaseUrl = null,
                                    imageProtocol = ImageApiProtocol.AUTO,
                                    useSameImageApiKey = true,
                                    imageApiKey = null,
                                    useProviderDefaults = connectionUseProviderDefaults,
                                    contextLimitTokens = connectionContextWindow.toIntOrNull()
                                )
                                connectionKey = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Сохранить технические настройки") }
                    }
                    Text(
                        "API-ключ хранится локально и шифруется через Android Keystore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    TextButton(
                        onClick = {
                            vm.openUsageGuide()
                            onBack()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Outlined.Description, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Памятка Umnik", fontWeight = FontWeight.Bold)
                            Text("Краткое руководство", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                ExpandableSettingsCard(
                    title = "Диагностика и логи",
                    subtitle = if (diagnosticLoggingEnabled)
                        "Запись включена · ${humanSize(diagnosticLogBytes)}"
                    else
                        "Выключено · включайте только при поиске ошибки",
                    icon = Icons.Outlined.Description,
                    expanded = diagnosticsExpanded,
                    onToggle = {
                        diagnosticsExpanded = !diagnosticsExpanded
                        diagnosticLoggingEnabled = vm.isDiagnosticLoggingEnabled()
                        diagnosticLogBytes = vm.diagnosticLogSize()
                    }
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Запись логов", fontWeight = FontWeight.Medium)
                            Text(
                                "Включите, повторите действия с ошибкой и затем отправьте лог.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = diagnosticLoggingEnabled,
                            onCheckedChange = { enabled ->
                                vm.setDiagnosticLoggingEnabled(enabled)
                                diagnosticLoggingEnabled = enabled
                                diagnosticLogBytes = vm.diagnosticLogSize()
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Для ручной проверки журнал фиксирует сетевые стадии, действия, выбранную модель, типы вложений, фоновые задания и возврат результатов в чат. Тексты сообщений, содержимое файлов и API-ключи не записываются. Журнал хранит до ~8 МБ последних событий.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                val file = vm.diagnosticLogFile()
                                if (file != null) {
                                    shareGeneratedFile(context, file)
                                    diagnosticLogBytes = vm.diagnosticLogSize()
                                }
                            },
                            enabled = diagnosticLogBytes > 0L,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Поделиться", maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = {
                                val file = vm.diagnosticLogFile()
                                if (file != null) {
                                    diagnosticFileToSave = file
                                    diagnosticSave.launch(file.name)
                                }
                            },
                            enabled = diagnosticLogBytes > 0L,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Сохранить", maxLines = 1)
                        }
                    }
                    TextButton(
                        onClick = {
                            vm.clearDiagnosticLog()
                            diagnosticLogBytes = 0L
                        },
                        enabled = diagnosticLogBytes > 0L,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Очистить лог")
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Umnik", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Версия $appVersion",
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
    if (imageParametersOpen) {
        ImageParametersDialog(state = state, vm = vm, onDismiss = { imageParametersOpen = false })
    }
}

@Composable
private fun ImageParametersDialog(
    state: UiState,
    vm: ChatViewModel,
    onDismiss: () -> Unit
) {
    val info = state.availableImageModels.firstOrNull { it.id == state.imageModel }
    val aspectRatios = info?.parameterValues("aspect_ratio").orEmpty()
    val resolutions = info?.parameterValues("resolution").orEmpty()
    val imageProfile = state.connectionProfiles.firstOrNull { it.id == state.imageConnectionProfileId }

    LaunchedEffect(state.imageConnectionProfileId, state.imageModel) {
        if (state.imageModel.isNotBlank() && info == null && !state.isLoading) {
            vm.refreshModels(ChatMode.IMAGE)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Параметры изображения") },
        text = {
            Column {
                Text(
                    "${state.imageModel.substringAfterLast('/').ifBlank { state.imageModel }} · ${imageProfile?.name ?: "Подключение"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Text("Соотношение сторон", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(listOf("Авто") + aspectRatios) { option ->
                        val auto = option == "Авто"
                        FilterChip(
                            selected = if (auto) state.imageAspectRatio == null else state.imageAspectRatio == option,
                            onClick = { vm.setImageAspectRatio(if (auto) null else option) },
                            label = { Text(option) }
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Разрешение", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(listOf("Авто") + resolutions) { option ->
                        val auto = option == "Авто"
                        FilterChip(
                            selected = if (auto) state.imageResolution == null else state.imageResolution == option,
                            onClick = { vm.setImageResolution(if (auto) null else option) },
                            label = { Text(option) }
                        )
                    }
                }
                if (aspectRatios.isEmpty() && resolutions.isEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (imageProfile?.type == ProviderType.OPENROUTER)
                            "Модель не сообщила доступные параметры размера. Umnik оставит режим «Авто»."
                        else
                            "Совместимый API не сообщает Umnik единый список параметров размера. Для него используется режим «Авто».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Готово") }
        },
        dismissButton = {
            if (state.imageAspectRatio != null || state.imageResolution != null) {
                TextButton(onClick = {
                    vm.setImageAspectRatio(null)
                    vm.setImageResolution(null)
                }) { Text("Сбросить") }
            }
        }
    )
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
    val activeQuickModels = state.quickTextModels
        .filter { quickModelConnectionId(it, state.activeConnectionProfileId) == state.activeConnectionProfileId }
        .map(::quickModelId)
    val modelIds = (listOf(currentId, state.textModel) + activeQuickModels)
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
    val enabledConnections = state.connectionProfiles.filter { it.id !in state.disabledConnectionIds }
    var selectedConnectionId by remember(enabledConnections.map { it.id }) {
        mutableStateOf(
            state.activeConnectionProfileId.takeIf { id -> enabledConnections.any { it.id == id } }
                ?: enabledConnections.firstOrNull()?.id
        )
    }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(selectedConnectionId) {
        selectedConnectionId?.let(vm::loadConnectionModels)
    }

    val selectedConnection = enabledConnections.firstOrNull { it.id == selectedConnectionId }
    val models = if (state.modelCatalogConnectionId == selectedConnectionId) state.modelCatalog else emptyList()
    val filtered = remember(models, query) {
        models.filter { it.id.contains(query.trim(), ignoreCase = true) }.take(300)
    }

    FullScreenPanel(title = "Быстрые модели", onBack = onDismiss) {
        Text(
            "Быстрые модели выбираются в общем каталоге OpenRouter. Ограничения по количеству больше нет.",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (enabledConnections.isEmpty()) {
            Text(
                "Нет включённых подключений. Включите хотя бы одно в разделе «Подключения».",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(enabledConnections, key = { it.id }) { connection ->
                    FilterChip(
                        selected = selectedConnectionId == connection.id,
                        onClick = {
                            selectedConnectionId = connection.id
                            query = ""
                        },
                        label = { Text(connection.name, maxLines = 1) }
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Поиск модели") }
            )
            if (filtered.isEmpty()) {
                Text(
                    if (state.isLoading) "Загрузка списка…" else "Модели не найдены или подключение ещё не настроено",
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    items(filtered, key = { it.id }) { modelInfo ->
                        val ref = selectedConnection?.let { quickModelRef(it.id, modelInfo.id) }.orEmpty()
                        FilterChip(
                            selected = ref in state.quickTextModels,
                            onClick = {
                                selectedConnection?.let { vm.toggleQuickTextModelForConnection(it.id, modelInfo.id) }
                            },
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

private fun providerTypeLabel(type: ProviderType): String = when (type) {
    ProviderType.OPENROUTER -> "OpenRouter"
    ProviderType.NVIDIA -> "NVIDIA NIM"
    ProviderType.OPENAI_COMPATIBLE -> "OpenAI-совместимое"
}

private fun imageProtocolLabel(protocol: ImageApiProtocol): String = when (protocol) {
    ImageApiProtocol.AUTO -> "Авто"
    ImageApiProtocol.OPENAI_COMPATIBLE -> "OpenAI-compatible"
    ImageApiProtocol.NVIDIA_NIM -> "NVIDIA NIM"
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
        putExtra(Intent.EXTRA_TEXT, markdownToShareText(text))
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться ответом"))
}

private fun markdownToShareText(markdown: String): String {
    val tableSeparator = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$")
    var inCodeFence = false
    return markdown
        .replace("\\r\\n", "\\n")
        .lineSequence()
        .mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                return@mapNotNull null
            }
            if (!inCodeFence && tableSeparator.matches(raw)) return@mapNotNull null
            if (inCodeFence) return@mapNotNull raw

            var line = raw
            line = Regex("^\\s*#{1,6}\\s+").replace(line, "")
            line = Regex("^\\s*>\\s?").replace(line, "")
            line = Regex("^\\s*[-+*]\\s+").replace(line, "• ")
            line = Regex("^\\s*(\\d+)[.)]\\s+").replace(line, "$1. ")
            if (Regex("^\\s*((-{3,})|(\\*{3,})|(_{3,}))\\s*$").matches(line)) {
                return@mapNotNull "────────"
            }
            line = Regex("\\[([^]\\n]+)]\\(([^)\\n]+)\\)").replace(line, "$1 ($2)")
            line = line.replace("**", "").replace("__", "").replace("~~", "")
            line = Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)").replace(line, "$1")
            line = Regex("(?<!_)_([^_\\n]+)_(?!_)").replace(line, "$1")
            line = Regex("`([^`\\n]+)`").replace(line, "$1")
            line
        }
        .joinToString("\\n")
        .trim()
}

@Composable
private fun GeneratedAudioPlayer(file: GeneratedFile) {
    var playing by remember(file.localPath) { mutableStateOf(false) }
    val player = remember(file.localPath) {
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.localPath)
                prepare()
            }
        }.getOrNull()
    }
    DisposableEffect(player) {
        player?.setOnCompletionListener { playing = false }
        onDispose { runCatching { player?.release() } }
    }
    FilledTonalButton(
        onClick = {
            player?.let {
                if (playing) {
                    it.pause()
                    playing = false
                } else {
                    it.start()
                    playing = true
                }
            }
        },
        enabled = player != null,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Icon(if (playing) Icons.Outlined.Stop else Icons.Outlined.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(if (playing) "Пауза" else "Слушать в чате")
    }
}

private fun openGeneratedFile(context: Context, file: GeneratedFile) {
    val localFile = File(file.localPath)
    if (!localFile.isFile) {
        Toast.makeText(context, "Файл больше недоступен", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", localFile)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, file.mimeType.ifBlank { "application/octet-stream" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "На устройстве нет приложения для открытия файла", Toast.LENGTH_SHORT).show() }
}

private fun shareGeneratedFile(context: Context, file: GeneratedFile) {
    val localFile = File(file.localPath)
    if (!localFile.isFile) {
        Toast.makeText(context, "Файл больше недоступен", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", localFile)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = file.mimeType.ifBlank { "application/octet-stream" }
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться файлом"))
}

private fun answerSoundLabel(choice: AnswerSoundChoice): String = when (choice) {
    AnswerSoundChoice.DEFAULT -> "Основной"
    AnswerSoundChoice.CUSTOM -> "Свой звук"
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
