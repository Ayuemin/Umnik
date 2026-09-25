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
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ayuemin.ymnik.AsyncJobEvents
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.LocalShellActivity
import com.ayuemin.ymnik.RequestExecutionManager
import com.ayuemin.ymnik.RequestKeepAliveService
import com.ayuemin.ymnik.ShellActivity
import com.ayuemin.ymnik.audio.WavRecorder
import com.ayuemin.ymnik.browser.LocalBrowserActivity
import com.ayuemin.ymnik.browser.LocalBrowserLifecycle
import com.ayuemin.ymnik.browser.LocalBrowserRuntime
import com.ayuemin.ymnik.R
import com.ayuemin.ymnik.data.BatchJobRepository
import com.ayuemin.ymnik.data.VideoJobRepository
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.ChatMode
import com.ayuemin.ymnik.model.GeneratedFile
import com.ayuemin.ymnik.model.InternetMode
import com.ayuemin.ymnik.model.ModelInfo
import com.ayuemin.ymnik.model.ProviderType
import com.ayuemin.ymnik.model.ReasoningEffort
import com.ayuemin.ymnik.model.Skill
import com.ayuemin.ymnik.model.StoredFile
import com.ayuemin.ymnik.model.UiState
import com.ayuemin.ymnik.model.WebSearchPreset
import com.ayuemin.ymnik.tts.TtsController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal
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
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) RequestKeepAliveService.update(context.applicationContext)
    }
    val tts = remember { TtsController(context) }
    val openRouterSpeech = remember(viewModel) { OpenRouterSpeechPlayer(context.applicationContext, viewModel) }
    val openRouterSpeechState by openRouterSpeech.state.collectAsState()
    var screen by remember { mutableIntStateOf(0) }

    LaunchedEffect(state.requestActive) {
        if (state.requestActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = context.getSharedPreferences("ymnik", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("notification_permission_prompted_v1171", false)) {
                prefs.edit().putBoolean("notification_permission_prompted_v1171", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

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
                LocalBrowserHost()
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
    var text by remember(state.currentChatId) { mutableStateOf("") }
    var fileToSave by remember { mutableStateOf<GeneratedFile?>(null) }
    var sidebarOpen by remember { mutableStateOf(false) }
    var teamsOpen by remember { mutableStateOf(false) }
    var selectedTeamId by remember { mutableStateOf<String?>(null) }
    var createTeamDirect by remember { mutableStateOf(false) }
    var teamNavigationOriginChatId by remember { mutableStateOf<String?>(null) }
    var teamsOpenedFromSidebar by remember { mutableStateOf(false) }
    var specialistChatReturnTeamId by remember { mutableStateOf<String?>(null) }
    var actionsOpen by remember { mutableStateOf(false) }
    var reasoningModeOpen by remember(state.currentChatId) { mutableStateOf(false) }
    var reasoningModeInfoOpen by remember(state.currentChatId) { mutableStateOf(false) }
    var webSearchModeOpen by remember(state.currentChatId) { mutableStateOf(false) }
    var webSearchModeInfoOpen by remember(state.currentChatId) { mutableStateOf(false) }
    var attachmentsExpanded by remember(state.currentChatId) { mutableStateOf(false) }
    var chatSearchOpen by remember(state.currentChatId) { mutableStateOf(false) }
    var chatSearchQuery by remember(state.currentChatId) { mutableStateOf("") }
    var chatSearchResultPosition by remember(state.currentChatId) { mutableIntStateOf(-1) }
    var openRouterToolsExpanded by remember { mutableStateOf(false) }
    var skillsDialogOpen by remember(state.currentChatId) { mutableStateOf(false) }
    var teamToolsExpanded by remember { mutableStateOf(false) }
    var teamSkillsExpanded by remember { mutableStateOf(false) }
    var imagePromptMode by remember(state.currentChatId) { mutableStateOf(false) }
    var cameraForImageGeneration by remember { mutableStateOf(false) }
    var cameraTarget by remember { mutableStateOf<CameraTarget?>(null) }
    val listState = rememberLazyListState()
    val chatScope = rememberCoroutineScope()
    var scrollToBottomVisible by remember(state.currentChatId) { mutableStateOf(false) }
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
    val reasoningAvailable = !imagePromptMode && textModelInfo?.supportsReasoning == true
    val reasoningLevelSelectable = reasoningAvailable &&
        textModelInfo?.supportsReasoningEffort == true &&
        textModelInfo.reasoningEfforts.isNotEmpty()
    val webSearchAvailable = !imagePromptMode && openRouterProfile && textModelInfo?.supportsTools == true
    val currentChat = state.chats.firstOrNull { it.id == state.currentChatId }
    val currentSpecialistId = currentChat?.let { vm.specialistIdForChat(it.id) }
    val chatSearchMatches = remember(state.messages, chatSearchQuery) {
        chatSearchMatchIndices(state.messages, chatSearchQuery)
    }
    val selectedSearchMessageIndex = chatSearchMatches.getOrNull(chatSearchResultPosition)
    val selectedSearchMessageId = selectedSearchMessageIndex?.let { state.messages.getOrNull(it)?.id }

    LaunchedEffect(chatSearchQuery, chatSearchMatches) {
        chatSearchResultPosition = if (chatSearchMatches.isEmpty()) -1 else 0
    }
    LaunchedEffect(chatSearchOpen, chatSearchResultPosition, chatSearchMatches) {
        if (!chatSearchOpen) return@LaunchedEffect
        val messageIndex = chatSearchMatches.getOrNull(chatSearchResultPosition) ?: return@LaunchedEffect
        listState.animateScrollToItem(messageIndex)
    }
    BackHandler(enabled = chatSearchOpen) {
        chatSearchOpen = false
        chatSearchQuery = ""
    }
    BackHandler(
        enabled = currentSpecialistId != null &&
            specialistChatReturnTeamId != null &&
            !sidebarOpen &&
            !teamsOpen &&
            !chatSearchOpen
    ) {
        selectedTeamId = specialistChatReturnTeamId
        specialistChatReturnTeamId = null
        createTeamDirect = false
        teamsOpen = true
    }
    val requestActiveHere = vm.isChatRequestActive(state.currentChatId)
    val asyncJobSequence by AsyncJobEvents.sequence.collectAsState()
    val shellActivity by AsyncJobEvents.shellActivity.collectAsState()
    val localShellActivity by AsyncJobEvents.localShellActivity.collectAsState()
    val browserActivity by LocalBrowserRuntime.activity.collectAsState()
    val hubToolActivity by AsyncJobEvents.hubToolActivity.collectAsState()
    val batchRepository = remember(context) { BatchJobRepository(context.applicationContext) }
    val videoRepository = remember(context) { VideoJobRepository(context.applicationContext) }
    val activeBatchForChat = remember(state.currentChatId, asyncJobSequence) {
        batchRepository.list()
            .filter { it.chatId == state.currentChatId && !it.status.terminal }
            .maxByOrNull { it.updatedAt }
    }
    val activeVideoForChat = remember(state.currentChatId, asyncJobSequence) {
        videoRepository.list()
            .filter { it.chatId == state.currentChatId && !it.status.terminal }
            .maxByOrNull { it.updatedAt }
    }
    val activeHubToolHere = hubToolActivity?.takeIf { it.chatId == state.currentChatId }
    val shellActiveHere = shellActivity?.chatId == state.currentChatId
    val requestSnapshots by RequestExecutionManager.snapshots.collectAsState()
    val streamingText = requestSnapshots.firstOrNull { it.chatId == state.currentChatId }?.partialText.orEmpty()
    val nonRequestBusy = state.isLoading && !state.requestActive
    val isUsageGuide = currentChat?.title == "Памятка по Umnik"
    var guideScrollTarget by remember(state.currentChatId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(guideScrollTarget) {
        val target = guideScrollTarget ?: return@LaunchedEffect
        listState.animateScrollToItem(target)
        guideScrollTarget = null
    }
    BackHandler(enabled = isUsageGuide && listState.firstVisibleItemIndex > 0 && !chatSearchOpen) {
        guideScrollTarget = 0
    }
    val currentChatFiles = currentChat?.chatFiles.orEmpty()
    val availableRetryAttachmentNames = (
        currentChatFiles.map { it.name } + state.pendingAttachments.map { it.name }
    ).toSet()
    LaunchedEffect(state.currentChatId, listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            scrollToBottomVisible = true
        } else if (scrollToBottomVisible) {
            delay(1_500)
            if (!listState.isScrollInProgress) {
                scrollToBottomVisible = false
            }
        }
    }
    val currentTeam = currentChat?.teamId
        ?.let { teamId -> state.teams.firstOrNull { it.id == teamId } }
    val activeSkillCount = state.activeSkillIds.size
    val teamAvailableSkills = emptyList<com.ayuemin.ymnik.model.Skill>()
    val activeTeamSkillCount = 0

    fun startVoiceRecording() {
        if (!microphoneAvailable || nonRequestBusy || requestActiveHere || imagePromptMode) return
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

    LaunchedEffect(requestActiveHere, state.currentChatId) {
        if (!requestActiveHere) {
            requestElapsedSeconds = 0
            return@LaunchedEffect
        }
        val startedAt = vm.activeRequestStartedAt(state.currentChatId) ?: System.currentTimeMillis()
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
        if (chatSearchOpen) return@LaunchedEffect
        if (state.messages.isNotEmpty()) {
            delay(180)
            if (isUsageGuide) listState.scrollToItem(0) else listState.scrollToItem(state.messages.size)
        }
    }

    val streamFollowThresholdPx = with(LocalDensity.current) { 180.dp.roundToPx() }
    LaunchedEffect(streamingText.length) {
        if (streamingText.isBlank() || isUsageGuide || chatSearchOpen) return@LaunchedEffect
        val layout = listState.layoutInfo
        val total = layout.totalItemsCount
        val lastVisible = layout.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        val streamOrEndVisible = lastVisible.index >= (total - 2).coerceAtLeast(0)
        val bottomDistance = (lastVisible.offset + lastVisible.size - layout.viewportEndOffset).coerceAtLeast(0)
        if (streamOrEndVisible && bottomDistance <= streamFollowThresholdPx && total > 0) {
            listState.scrollToItem(total - 1)
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
            onOpenSidebar = { sidebarOpen = true },
            onSearchChat = { chatSearchOpen = true }
        )

        if (chatSearchOpen) {
            ChatSearchBar(
                query = chatSearchQuery,
                onQueryChange = { chatSearchQuery = it },
                currentResult = chatSearchResultPosition,
                resultCount = chatSearchMatches.size,
                onPrevious = {
                    if (chatSearchMatches.isNotEmpty()) {
                        chatSearchResultPosition = if (chatSearchResultPosition <= 0) {
                            chatSearchMatches.lastIndex
                        } else {
                            chatSearchResultPosition - 1
                        }
                    }
                },
                onNext = {
                    if (chatSearchMatches.isNotEmpty()) {
                        chatSearchResultPosition = if (chatSearchResultPosition >= chatSearchMatches.lastIndex) {
                            0
                        } else {
                            chatSearchResultPosition + 1
                        }
                    }
                },
                onClose = {
                    chatSearchOpen = false
                    chatSearchQuery = ""
                }
            )
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            if (state.messages.isEmpty()) {
                item { EmptyChatWelcome(Modifier.fillParentMaxSize()) }
            }
            items(state.messages, key = { it.id }) { message ->
                MessageCard(
                    message = message,
                    searchMatch = chatSearchOpen && chatSearchQuery.isNotBlank() && message.text.contains(chatSearchQuery.trim(), ignoreCase = true),
                    searchSelected = chatSearchOpen && selectedSearchMessageId == message.id,
                    pendingLabel = if (message.deliveryState == "pending") {
                        if (requestActiveHere && state.messages.lastOrNull { it.deliveryState == "pending" }?.id == message.id) {
                            state.busyLabel ?: "Модель работает…"
                        } else {
                            "Восстанавливаю ответ в фоне…"
                        }
                    } else null,
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
                    onGuideLink = if (isUsageGuide && message.providerName == "Umnik") {
                        { target ->
                            guideScrollTarget = target.coerceIn(0, state.messages.lastIndex.coerceAtLeast(0))
                        }
                    } else null,
onBranch = if (message.role == "assistant") {
    { vm.branchFromMessage(message.id) }
} else null,
                    onRetry = when {
                        message.role != "user" || message.text.isBlank() -> null
                        message.deliveryState == "pending" -> null
                        message.deliveryState == "failed" && message.attachmentNames.all { it in availableRetryAttachmentNames } ->
                            { { vm.retryFailedMessage(message.id) } }
                        message.imageGeneration && message.attachmentNames.isEmpty() -> {
                            { vm.sendImagePrompt(message.text) }
                        }
                        !message.imageGeneration && message.attachmentNames.all { it in availableRetryAttachmentNames } -> {
                            { vm.send(message.text) }
                        }
                        else -> null
                    }
                )
            }
            if (requestActiveHere && streamingText.isNotBlank()) {
                item(key = "streaming-${state.currentChatId}") {
                    StreamingAssistantMessage(streamingText)
                }
            }
                item(key = "chat-end") { Spacer(Modifier.height(1.dp)) }
            }

            if (scrollToBottomVisible && listState.layoutInfo.totalItemsCount > 1) {
                Surface(
                    onClick = {
                        chatScope.launch {
                            val target = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            listState.animateScrollToItem(target)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .size(44.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "Прокрутить чат вниз",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        val visibleChatFiles = emptyList<com.ayuemin.ymnik.model.ChatFile>()
        val attachmentCount = state.pendingAttachments.size
        if (attachmentCount > 0) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth()) {
                    Surface(
                        onClick = { attachmentsExpanded = !attachmentsExpanded },
                        color = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                "Вложения · $attachmentCount",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Icon(
                                if (attachmentsExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                contentDescription = if (attachmentsExpanded) "Свернуть вложения" else "Показать вложения",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (attachmentsExpanded) {
                        visibleChatFiles.forEach { file ->
                            AttachmentListRow(
                                name = file.name,
                                subtitle = "В контексте чата",
                                icon = Icons.Outlined.Description,
                                onRemove = { vm.removeChatFile(file.id) }
                            )
                        }
                        state.pendingAttachments.forEach { attachment ->
                            AttachmentListRow(
                                name = attachment.name,
                                subtitle = "К отправке",
                                icon = Icons.Outlined.AttachFile,
                                onRemove = { vm.removeAttachment(attachment.uri) }
                            )
                        }
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
                            UmnikInfoHint(
                                title = "Изолированная генерация изображения",
                                text = "Этот режим использует отдельно выбранную модель изображений и отдельный промпт. Обычная чат-модель и история диалога в запрос генерации не подмешиваются; готовое изображение возвращается в текущий чат."
                            )
                            IconButton(
                                onClick = { imagePromptMode = false },
                                enabled = !requestActiveHere
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

                browserActivity?.takeIf { it.chatId == state.currentChatId }?.let { activity ->
                    LocalBrowserInlineBanner(
                        activity = activity,
                        onShowPage = { LocalBrowserRuntime.showUserControl(activity.chatId) },
                        onConfirm = { LocalBrowserRuntime.confirmPendingUserAction(activity.chatId) },
                        onCancel = { LocalBrowserRuntime.cancelPendingUserAction(activity.chatId) }
                    )
                }

                shellActivity?.takeIf { it.chatId == state.currentChatId }?.let { activity ->
                    ShellBackgroundOperationBanner(
                        activity = activity,
                        onClick = { AsyncJobEvents.requestHub("shell", "Вернуться в чат") }
                    )
                }

                localShellActivity?.let { activity ->
                    LocalShellInlineBanner(
                        activity = activity,
                        currentChatId = state.currentChatId,
                        onClick = { AsyncJobEvents.requestHub("local-shell", "Вернуться в чат") }
                    )
                }

                activeHubToolHere?.let { activity ->
                    BackgroundOperationBanner(
                        title = activity.title,
                        subtitle = "Чат доступен · операция продолжается",
                        onClick = { AsyncJobEvents.requestHub(activity.page, "Вернуться в чат") }
                    )
                }

                activeBatchForChat?.let { batch ->
                    BackgroundOperationBanner(
                        title = if (batch.completedItems > 0) {
                            "Batch · ${batchStatusUiLabel(batch.status)} · ${batch.completedItems}/${batch.totalItems}"
                        } else {
                            "Batch · ${batchStatusUiLabel(batch.status)} · ${batch.totalItems} заданий"
                        },
                        subtitle = "Чат доступен · результат появится здесь",
                        onClick = { AsyncJobEvents.requestHub("batch", "Вернуться в чат") }
                    )
                }

                activeVideoForChat?.let {
                    BackgroundOperationBanner(
                        title = "Видео создаётся",
                        subtitle = "Чат доступен · результат появится здесь",
                        onClick = { AsyncJobEvents.requestHub("video", "Вернуться в чат") }
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
                                    tint = MaterialTheme.colorScheme.primary
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
                                    enabled = isRecording || (!nonRequestBusy && !requestActiveHere && microphoneAvailable),
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
                                    if (requestActiveHere) {
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
                                enabled = requestActiveHere || isRecording || (!nonRequestBusy && (
                                    text.isNotBlank() || state.pendingAttachments.isNotEmpty() || (!imagePromptMode && currentChatFiles.isNotEmpty())
                                )),
                                modifier = Modifier.size(48.dp)
                            ) {
                                if (requestActiveHere) {
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
                            requestActiveHere -> Text(
                                text = formatRequestDuration(requestElapsedSeconds),
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.34f)
                            )
                            imagePromptMode -> Text("Опишите изображение")
                            currentChat != null && vm.isOrchestratorChat(currentChat.id) -> Text("Поручите работу команде обычным языком")
                        }
                    },
                    shape = UmnikFieldShape,
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
      onOpenTeams = {
          teamNavigationOriginChatId = state.currentChatId
          teamsOpenedFromSidebar = true
          selectedTeamId = null
          createTeamDirect = false
          teamsOpen = true
          sidebarOpen = false
      },
      onCreateTeam = {
          teamNavigationOriginChatId = state.currentChatId
          teamsOpenedFromSidebar = true
          selectedTeamId = null
          createTeamDirect = true
          teamsOpen = true
          sidebarOpen = false
      },
      onOpenTeam = { teamId ->
          teamNavigationOriginChatId = state.currentChatId
          teamsOpenedFromSidebar = true
          createTeamDirect = false
          selectedTeamId = teamId
          teamsOpen = true
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
                        label = "Камера",
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
                        label = "Создать",
                        enabled = !state.isLoading && imageConnectionAvailable && state.imageModel.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            actionsOpen = false
                            if (vm.prepareImageGeneration()) imagePromptMode = true
                        }
                    )
                }

                if (!imagePromptMode && currentSpecialistId == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ComposerToggleTile(
                            icon = Icons.Outlined.Psychology,
                            level = if (reasoningLevelSelectable) reasoningEffortIndicatorLevel(state.reasoningEffort) else 0,
                            levelCount = 3,
                            levelDescription = if (state.reasoningEnabled) "Размышление: " + reasoningEffortUiLabel(state.reasoningEffort) else "Размышление выключено",
                            checked = state.reasoningEnabled,
                            enabled = reasoningAvailable,
                            modifier = Modifier.weight(1f),
                            onOpenSettings = { reasoningModeOpen = true },
                            onCheckedChange = vm::setReasoningEnabled
                        )
                        ComposerToggleTile(
                            icon = Icons.Outlined.Language,
                            level = when (state.internetMode) {
                                InternetMode.BROWSER -> 4
                                else -> webSearchPresetIndicatorLevel(state.webSearchPreset)
                            },
                            levelCount = 4,
                            indicatorText = when (state.internetMode) {
                                InternetMode.SEARCH_ONLY -> null
                                InternetMode.AUTO -> "AUTO"
                                InternetMode.BROWSER -> "BROW"
                            },
                            levelDescription = when {
                                !state.webSearchEnabled -> "Интернет выключен"
                                state.internetMode == InternetMode.SEARCH_ONLY -> "Только поиск: " + webSearchPresetUiLabel(state.webSearchPreset)
                                state.internetMode == InternetMode.AUTO -> "Автоматически: " + webSearchPresetUiLabel(state.webSearchPreset)
                                else -> "Браузер"
                            },
                            checked = state.webSearchEnabled,
                            enabled = webSearchAvailable,
                            modifier = Modifier.weight(1f),
                            onOpenSettings = { webSearchModeOpen = true },
                            onCheckedChange = vm::setWebSearchEnabled
                        )
                    }
                }

                if (currentSpecialistId == null) {
                    ComposerSectionHeader(
                        icon = Icons.Outlined.Storage,
                        label = "Инструменты",
                        expanded = openRouterToolsExpanded,
                        onClick = { openRouterToolsExpanded = !openRouterToolsExpanded }
                    )
                    if (openRouterToolsExpanded) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CompactComposerTool(Icons.Outlined.Storage, "Shell", !state.isLoading, Modifier.weight(1f)) {
                                actionsOpen = false
                                com.ayuemin.ymnik.AsyncJobEvents.requestHub("local-shell", "Вернуться в чат")
                            }
                            CompactComposerTool(Icons.Outlined.Extension, "Навыки", !state.isLoading, Modifier.weight(1f)) {
                                actionsOpen = false
                                skillsDialogOpen = true
                            }
                            CompactComposerTool(Icons.Outlined.Mic, "В текст", !state.isLoading, Modifier.weight(1f)) {
                                actionsOpen = false
                                com.ayuemin.ymnik.AsyncJobEvents.requestHub("stt", "Вернуться в чат")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            CompactComposerTool(Icons.Outlined.VolumeUp, "Озвучить", !state.isLoading, Modifier.weight(1f)) {
                                actionsOpen = false
                                com.ayuemin.ymnik.AsyncJobEvents.requestHub("speech", "Вернуться в чат")
                            }
                            CompactComposerTool(Icons.Outlined.Image, "Видео", !state.isLoading, Modifier.weight(1f)) {
                                actionsOpen = false
                                com.ayuemin.ymnik.AsyncJobEvents.requestHub("video", "Вернуться в чат")
                            }
                            CompactComposerTool(Icons.Outlined.Description, "Пакет задач", !state.isLoading, Modifier.weight(1f)) {
                                actionsOpen = false
                                com.ayuemin.ymnik.AsyncJobEvents.requestHub("jobs", "Вернуться в чат")
                            }
                        }
                    }
                }

                // Team stages and shared team skills were removed in the specialist-first architecture.
                // Specialist-owned tools/skills are configured inside the specialist itself.
            }
        }
    }

    if (skillsDialogOpen) {
        AlertDialog(
            onDismissRequest = { skillsDialogOpen = false },
            title = { Text("Навыки") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Включённые навыки применяются к следующим запросам только в этом чате.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.skills.isEmpty()) {
                        Text(
                            "Навыков пока нет. Добавьте их в общих настройках.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            state.skills.forEachIndexed { index, skill ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        skill.name,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Switch(
                                        checked = skill.id in state.activeSkillIds,
                                        onCheckedChange = { vm.toggleSkill(skill.id) }
                                    )
                                }
                                if (index < state.skills.lastIndex) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { skillsDialogOpen = false }) { Text("Закрыть") }
            }
        )
    }

    if (reasoningModeOpen) {
        val supportedEfforts = if (textModelInfo?.supportsReasoningEffort == true) {
            ReasoningEffort.entries.filter { effort ->
                textModelInfo.reasoningEfforts.isNotEmpty() && effort.apiValue in textModelInfo.reasoningEfforts
            }
        } else {
            emptyList()
        }
        val controllableEfforts = if (supportedEfforts.size <= 3) {
            supportedEfforts
        } else {
            listOfNotNull(
                supportedEfforts.firstOrNull { it == ReasoningEffort.LOW } ?: supportedEfforts.firstOrNull(),
                supportedEfforts.firstOrNull { it == ReasoningEffort.HIGH } ?: supportedEfforts.getOrNull(supportedEfforts.lastIndex / 2),
                supportedEfforts.firstOrNull { it == ReasoningEffort.MAX } ?: supportedEfforts.lastOrNull()
            ).distinct()
        }
        AlertDialog(
            onDismissRequest = { reasoningModeOpen = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Уровень размышления", modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            reasoningModeOpen = false
                            reasoningModeInfoOpen = true
                        }
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = "Об уровне размышления")
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (controllableEfforts.isEmpty()) {
                        Text("Модель умеет размышлять, но не даёт выбирать уровень.")
                    } else {
                        controllableEfforts.forEach { effort ->
                            FilterChip(
                                selected = state.reasoningEffort == effort,
                                onClick = {
                                    vm.setReasoningEffort(effort)
                                    reasoningModeOpen = false
                                },
                                label = { Text(reasoningEffortUiLabel(effort)) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { reasoningModeOpen = false }) { Text("Закрыть") }
            }
        )
    }

    if (reasoningModeInfoOpen) {
        AlertDialog(
            onDismissRequest = { reasoningModeInfoOpen = false },
            title = { Text("О размышлении") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Уровень размышления относится только к текущему чату.")
                    Text("Доступные уровни зависят от выбранной модели.")
                    Text("Более высокий уровень может работать дольше и стоить дороже.")
                    Text("Если модель не позволяет выбирать уровень, Umnik не показывает выбор, которого у неё нет.")
                }
            },
            confirmButton = {
                TextButton(onClick = { reasoningModeInfoOpen = false }) { Text("Понятно") }
            }
        )
    }

    if (webSearchModeOpen) {
        AlertDialog(
            onDismissRequest = { webSearchModeOpen = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Интернет", modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            webSearchModeOpen = false
                            webSearchModeInfoOpen = true
                        }
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = "О режимах интернета")
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Режим",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    InternetMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.internetMode == mode,
                            onClick = { vm.setInternetMode(mode) },
                            label = {
                                Text(
                                    when (mode) {
                                        InternetMode.SEARCH_ONLY -> "Только поиск"
                                        InternetMode.AUTO -> "Автоматически"
                                        InternetMode.BROWSER -> "Браузер"
                                    }
                                )
                            }
                        )
                    }
                    if (state.internetMode != InternetMode.BROWSER) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Уровень поиска",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        WebSearchPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = state.webSearchPreset == preset,
                                onClick = { vm.setWebSearchPreset(preset) },
                                label = { Text(webSearchPresetUiLabel(preset)) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { webSearchModeOpen = false }) { Text("Закрыть") }
            }
        )
    }

    if (webSearchModeInfoOpen) {
        AlertDialog(
            onDismissRequest = { webSearchModeInfoOpen = false },
            title = { Text("Об интернете") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Режим и уровень относятся только к текущему чату.")
                    Text(
                        "• Только поиск — поиск и чтение найденных страниц без интерактивного WebView.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "• Автоматически — модель сама выбирает поиск, чтение страницы или Browser.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "• Браузер — интерактивная работа со страницей. Уровень поиска здесь не применяется.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    WebSearchPreset.entries.forEach { preset ->
                        Text(
                            "• ${webSearchPresetUiLabel(preset)} — ${webSearchPresetDescription(preset)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { webSearchModeInfoOpen = false }) { Text("Закрыть") }
            }
        )
    }

    if (teamsOpen) {
        TeamsDialog(
            state = state,
            vm = vm,
            onDismiss = {
                teamsOpen = false
                selectedTeamId = null
                createTeamDirect = false
                if (teamsOpenedFromSidebar) {
                    val origin = teamNavigationOriginChatId
                    if (origin != null && state.chats.any { it.id == origin }) {
                        vm.switchChat(origin)
                    }
                    teamsOpenedFromSidebar = false
                    teamNavigationOriginChatId = null
                    sidebarOpen = true
                }
            },
            initialTeamId = selectedTeamId,
            startCreate = createTeamDirect,
            onSpecialistConversationOpened = { teamId, _ ->
                specialistChatReturnTeamId = teamId
                teamsOpen = false
                selectedTeamId = null
                createTeamDirect = false
            }
        )
    }
}


@Composable
private fun LocalShellInlineBanner(
    activity: LocalShellActivity,
    currentChatId: String,
    onClick: () -> Unit
) {
    BackgroundOperationBanner(
        title = "Local Shell · работает · " + activity.turn + "/" + activity.maxTurns,
        subtitle = if (activity.chatId == currentChatId) {
            activity.status
        } else {
            "Задача выполняется в другом чате"
        },
        onClick = onClick
    )
}

@Composable
private fun ShellBackgroundOperationBanner(
    activity: ShellActivity,
    onClick: () -> Unit
) {
    var now by remember(activity.startedAt) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(activity.startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsedSeconds = ((now - activity.startedAt).coerceAtLeast(0L) / 1_000L)
    val elapsed = if (elapsedSeconds >= 60L) {
        (elapsedSeconds / 60L).toString() + ":" + (elapsedSeconds % 60L).toString().padStart(2, '0')
    } else {
        elapsedSeconds.toString() + " с"
    }
    val signal = activity.lastRemoteEventAt?.let { eventAt ->
        val ago = ((now - eventAt).coerceAtLeast(0L) / 1_000L)
        when {
            ago < 5L -> "сигнал только что"
            ago < 60L -> "сигнал " + ago + " с назад"
            else -> "сигнал " + (ago / 60L) + " мин назад"
        }
    }

    BackgroundOperationBanner(
        title = "Shell · " + elapsed,
        subtitle = buildString {
            append(activity.status)
            if (signal != null) append(" · ").append(signal)
        },
        onClick = onClick
    )
}

@Composable
private fun LocalBrowserInlineBanner(
    activity: LocalBrowserActivity,
    onShowPage: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val waiting = activity.lifecycle == LocalBrowserLifecycle.WAITING_USER
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(
            1.dp,
            if (waiting) MaterialTheme.colorScheme.primary.copy(alpha = 0.48f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (waiting) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (waiting) "Требуется действие · " + activity.host else "Браузер · " + activity.host,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        activity.attentionMessage ?: activity.status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (waiting) 2 else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (waiting) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onShowPage) { Text("Посмотреть страницу") }
                    TextButton(onClick = onCancel) { Text("Отмена") }
                    if (activity.attentionKind == "CONFIRM_ACTION") {
                        Button(onClick = onConfirm) { Text("Подтвердить") }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundOperationBanner(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Outlined.KeyboardArrowRight,
                contentDescription = "Открыть",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun batchStatusUiLabel(status: com.ayuemin.ymnik.model.BatchJobStatus): String = when (status) {
    com.ayuemin.ymnik.model.BatchJobStatus.VALIDATING -> "проверка"
    com.ayuemin.ymnik.model.BatchJobStatus.QUEUED -> "в очереди"
    com.ayuemin.ymnik.model.BatchJobStatus.IN_PROGRESS -> "выполняется"
    com.ayuemin.ymnik.model.BatchJobStatus.FINALIZING -> "завершается"
    else -> "выполняется"
}

@Composable
private fun ComposerToggleTile(
    icon: ImageVector,
    level: Int,
    levelCount: Int,
    indicatorText: String? = null,
    levelDescription: String,
    checked: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
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
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (enabled && onOpenSettings != null) {
                            Modifier.clickable(onClick = onOpenSettings)
                        } else {
                            Modifier
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = levelDescription,
                    modifier = Modifier.size(20.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
                Spacer(Modifier.width(8.dp))
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val count = levelCount.coerceAtLeast(1)
                    repeat(count) { index ->
                        val active = enabled && checked && index < level
                        if (!indicatorText.isNullOrBlank() && indicatorText.length >= count) {
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    indicatorText[index].toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (active) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = if (enabled) 0.18f else 0.08f
                                        )
                                    },
                                    maxLines = 1
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    modifier = Modifier.width(14.dp).height(6.dp),
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (active) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = if (enabled) 0.16f else 0.08f
                                        )
                                    }
                                ) {}
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

private fun reasoningEffortUiLabel(effort: ReasoningEffort): String = when (effort) {
    ReasoningEffort.MINIMAL -> "Минимальный"
    ReasoningEffort.LOW -> "Низкий"
    ReasoningEffort.MEDIUM -> "Средний"
    ReasoningEffort.HIGH -> "Высокий"
    ReasoningEffort.XHIGH -> "Очень высокий"
    ReasoningEffort.MAX -> "Максимальный"
}

private fun reasoningEffortIndicatorLevel(effort: ReasoningEffort): Int = when (effort) {
    ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> 1
    ReasoningEffort.MEDIUM, ReasoningEffort.HIGH -> 2
    ReasoningEffort.XHIGH, ReasoningEffort.MAX -> 3
}

private fun webSearchPresetIndicatorLevel(preset: WebSearchPreset): Int = when (preset) {
    WebSearchPreset.ON_DEMAND -> 1
    WebSearchPreset.FAST -> 2
    WebSearchPreset.NORMAL -> 3
    WebSearchPreset.DEEP -> 4
}

private fun webSearchPresetUiLabel(preset: WebSearchPreset): String = when (preset) {
    WebSearchPreset.ON_DEMAND -> "По необходимости"
    WebSearchPreset.FAST -> "Быстрый"
    WebSearchPreset.NORMAL -> "Обычный"
    WebSearchPreset.DEEP -> "Глубокий"
}

private fun webSearchPresetDescription(preset: WebSearchPreset): String = when (preset) {
    WebSearchPreset.ON_DEMAND -> "Модель сама решает, когда обращаться к интернету."
    WebSearchPreset.FAST -> "Один короткий поиск, до 3 результатов."
    WebSearchPreset.NORMAL -> "Обычный поиск, до 5 результатов за обращение."
    WebSearchPreset.DEEP -> "Несколько поисковых шагов для сложных вопросов. Может работать дольше и стоить дороже."
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
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
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
        Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
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
private fun AttachmentListRow(
    name: String,
    subtitle: String,
    icon: ImageVector,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Убрать $name",
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ChatHeader(
    state: UiState,
    vm: ChatViewModel,
    onOpenSidebar: () -> Unit,
    onSearchChat: () -> Unit
) {
    var overflowOpen by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var usageOpen by remember { mutableStateOf(false) }
    var clearSpecialistChatConfirm by remember(state.currentChatId) { mutableStateOf(false) }
    val activeProfile = state.connectionProfiles.firstOrNull { it.id == state.activeConnectionProfileId }
    val activeUsage = state.providerUsage?.takeIf { activeProfile?.type == ProviderType.OPENROUTER }
    val activeTextModel = state.currentChatTextModel ?: state.textModel
    val shortModelName = activeTextModel.substringAfter('/').ifBlank { activeTextModel }
    val currentChat = state.chats.firstOrNull { it.id == state.currentChatId }
    val currentSpecialistId = currentChat?.let { vm.specialistIdForChat(it.id) }
    val currentSpecialist = state.specialists.firstOrNull { it.id == currentSpecialistId }
    val currentRef = quickModelRef(state.activeConnectionProfileId, activeTextModel)
    val defaultRef = quickModelRef(state.activeConnectionProfileId, state.textModel)
    val specialistRefs = currentSpecialist?.let { specialist ->
        buildList {
            specialist.primaryModel?.let { add(quickModelRef(it.connectionProfileId, it.modelId)) }
            specialist.quickModels.forEach { add(quickModelRef(it.connectionProfileId, it.modelId)) }
        }
    }.orEmpty()
    val quickCandidates = if (currentSpecialist != null) {
        (listOf(currentRef) + specialistRefs)
            .filter { quickModelId(it).isNotBlank() }
            .distinct()
    } else {
        (listOf(currentRef, defaultRef) + state.quickTextModels)
            .filter { quickModelId(it).isNotBlank() }
            .distinct()
    }
    val chatTitle = currentChat?.title
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: currentSpecialist?.name
        ?: "Новый чат"

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UmnikCircleAction(
                icon = Icons.Outlined.Menu,
                contentDescription = "Открыть команды и историю",
                onClick = onOpenSidebar
            )

            Box(
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    chatTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            Box {
                UmnikCircleAction(
                    icon = Icons.Outlined.MoreVert,
                    contentDescription = "Действия чата",
                    enabled = !state.isLoading || state.requestActive,
                    onClick = { overflowOpen = true }
                )

                DropdownMenu(
                    expanded = overflowOpen,
                    onDismissRequest = { overflowOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Поиск по чату") },
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        enabled = state.messages.isNotEmpty(),
                        onClick = {
                            overflowOpen = false
                            onSearchChat()
                        }
                    )
                    HorizontalDivider(color = umnikDividerColor())
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Списано OpenRouter", fontWeight = FontWeight.Medium)
                                Text(
                                    activeUsage?.let { "Сегодня ${formatUsd(it.daily)} · всего ${formatUsd(it.total)}" }
                                        ?: "Нажмите, чтобы обновить",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            overflowOpen = false
                            usageOpen = true
                            vm.refreshProviderUsage()
                        }
                    )
                    HorizontalDivider(color = umnikDividerColor())
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Модель", fontWeight = FontWeight.Medium)
                                Text(
                                    shortModelName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        trailingIcon = { Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = null) },
                        enabled = !state.isLoading,
                        onClick = {
                            overflowOpen = false
                            modelMenuOpen = true
                        }
                    )
                    HorizontalDivider(color = umnikDividerColor())
                    DropdownMenuItem(
                        text = { Text("Очистить чат") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                        enabled = currentChat != null && !state.isLoading && !vm.isChatRequestActive(state.currentChatId),
                        onClick = {
                            overflowOpen = false
                            clearSpecialistChatConfirm = true
                        }
                    )
                }

                DropdownMenu(
                    expanded = modelMenuOpen,
                    onDismissRequest = { modelMenuOpen = false }
                ) {
                    Text(
                        "Выбрать модель",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    quickCandidates.forEach { ref ->
                        val id = quickModelId(ref)
                        val current = ref == currentRef
                        val connectionId = quickModelConnectionId(ref, state.activeConnectionProfileId)
                        val connection = state.connectionProfiles.firstOrNull { it.id == connectionId }
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        id.substringAfter('/').ifBlank { id },
                                        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        when {
                                            current -> "Текущая модель"
                                            currentSpecialist != null && currentSpecialist.primaryModel?.modelId == id -> "Основная модель специалиста"
                                            currentSpecialist != null -> "Дополнительная модель специалиста"
                                            ref == defaultRef -> "Модель по умолчанию"
                                            else -> connection?.name ?: "OpenRouter"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            leadingIcon = {
                                if (current) Icon(Icons.Outlined.Check, contentDescription = null)
                                else Spacer(Modifier.size(24.dp))
                            },
                            onClick = {
                                if (currentSpecialist != null) {
                                    vm.selectSpecialistQuickModel(currentSpecialist.id, ref)
                                } else if (ref == defaultRef) {
                                    vm.useDefaultTextModelForChat()
                                } else {
                                    vm.selectQuickTextModel(ref)
                                }
                                modelMenuOpen = false
                            }
                        )
                    }
                }
            }
        }
    }

    if (clearSpecialistChatConfirm && currentChat != null) {
        AlertDialog(
            onDismissRequest = { clearSpecialistChatConfirm = false },
            title = { Text("Очистить переписку?") },
            text = {
                Text(
                    if (currentSpecialist != null) {
                        "История разговора и временный контекст будут удалены. " +
                            "Инструкция, модель, навыки, постоянные файлы и база знаний специалиста останутся."
                    } else {
                        "История разговора и временные файлы контекста текущего чата будут удалены."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clearSpecialistChatConfirm = false
                    vm.clearChat()
                }) { Text("Очистить") }
            },
            dismissButton = {
                TextButton(onClick = { clearSpecialistChatConfirm = false }) { Text("Отмена") }
            }
        )
    }

    if (usageOpen) {
        AlertDialog(
            onDismissRequest = { usageOpen = false },
            title = { Text(activeUsage?.providerName ?: "OpenRouter") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (activeUsage == null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (state.isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                            Text(
                                if (state.isLoading) "Обновляю баланс…" else "Баланс пока не загружен.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
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
                TextButton(onClick = { vm.refreshProviderUsage() }, enabled = !state.isLoading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Обновить")
                }
            }
        )
    }
}

@Composable
private fun ChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    currentResult: Int,
    resultCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = UmnikFieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f)),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Box(
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 13.dp)
            ) {
                if (query.isBlank()) {
                    Text(
                        "Найти в чате",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
            }
            Text(
                when {
                    query.isBlank() -> ""
                    resultCount == 0 -> "0"
                    else -> "${currentResult + 1}/$resultCount"
                },
                modifier = Modifier.padding(horizontal = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onPrevious, enabled = resultCount > 0) {
                Icon(
                    Icons.Outlined.KeyboardArrowUp,
                    contentDescription = "Предыдущее совпадение",
                    tint = if (resultCount > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
            }
            IconButton(onClick = onNext, enabled = resultCount > 0) {
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "Следующее совпадение",
                    tint = if (resultCount > 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Закрыть поиск",
                    tint = MaterialTheme.colorScheme.primary
                )
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
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(80.dp),
        shape = UmnikItemShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
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
private fun StreamingAssistantMessage(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        MessageBody(text, MaterialTheme.colorScheme.onSurface, null)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Ответ поступает…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessageCard(
    message: ChatMessage,
    searchMatch: Boolean = false,
    searchSelected: Boolean = false,
    pendingLabel: String? = null,
    tts: TtsController,
    openRouterSpeechEnabled: Boolean,
    openRouterSpeechPhase: OpenRouterSpeechPhase,
    onOpenRouterSpeech: () -> Unit,
    onSaveGenerated: (GeneratedFile) -> Unit,
    onExportText: () -> Unit,
    onGuideLink: ((Int) -> Unit)?,
    onBranch: (() -> Unit)?,
    onRetry: (() -> Unit)?
) {
    val context = LocalContext.current
    val user = message.role == "user"
    val content = MaterialTheme.colorScheme.onSurface
    var answerInfoOpen by remember(message.id) { mutableStateOf(false) }
    val hasAnswerInfo = !user && (
        !message.modelId.isNullOrBlank() ||
            !message.providerName.isNullOrBlank() ||
            message.inputTokens != null ||
            message.outputTokens != null ||
            message.costUsd != null ||
            message.costBreakdown != null ||
            message.responseDurationMs != null ||
            message.knowledgeHitCount != null ||
            message.knowledgeSearchAttempted != null ||
            message.knowledgeBaseOnly != null ||
            message.webSearchEnabled != null ||
            message.reasoningEnabled != null ||
            message.memoryContextUsed != null ||
            message.activeSkillCount != null ||
            message.teamContextUsed != null ||
            message.attachmentCount != null ||
            !message.connectionName.isNullOrBlank() ||
            !message.requestId.isNullOrBlank()
        )

    val searchShape = RoundedCornerShape(20.dp)
    val searchModifier = when {
        searchSelected -> Modifier
            .border(2.dp, MaterialTheme.colorScheme.primary, searchShape)
            .padding(4.dp)
        searchMatch -> Modifier
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), searchShape)
            .padding(4.dp)
        else -> Modifier
    }

    Column(
        modifier = Modifier.fillMaxWidth().then(searchModifier),
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
                        MessageBody(message.text, content, onGuideLink)
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
                    if (message.deliveryState == "pending") {
                        Spacer(Modifier.height(7.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                pendingLabel ?: "Модель работает…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (message.deliveryState == "failed") {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Ответ не получен. Можно повторить вручную.",
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
                    MessageBody(message.text, content, onGuideLink)
                }
                if (message.deliveryState == "interrupted") {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Ответ прерван пользователем. Показанная часть сохранена.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                message.generatedFiles.forEach { file ->
                    Spacer(Modifier.height(10.dp))
                    GeneratedFileCard(file, onSave = { onSaveGenerated(file) })
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
                        icon = Icons.Outlined.Description,
                        description = "Сохранить текст ответа файлом",
                        onClick = onExportText
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
                if (hasAnswerInfo) {
                    CompactMessageAction(
                        icon = Icons.Outlined.Info,
                        description = "Об ответе",
                        onClick = { answerInfoOpen = true }
                    )
                }
            }
        }
    }

    if (answerInfoOpen) {
        AnswerInfoSheet(
            message = message,
            onDismiss = { answerInfoOpen = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnswerInfoSheet(
    message: ChatMessage,
    onDismiss: () -> Unit
) {
    var technicalOpen by remember(message.id) { mutableStateOf(false) }
    var serviceCostsOpen by remember(message.id) { mutableStateOf(false) }
    val knowledgeCount = message.knowledgeHitCount
    val knowledgeSearchAttempted = message.knowledgeSearchAttempted == true
    val knowledgeBaseOnly = message.knowledgeBaseOnly == true
    val sources = message.knowledgeSources.orEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "Об ответе",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Техническая и контекстная информация скрыта здесь, чтобы не загромождать чат.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            AnswerInfoSectionTitle("Ответ")
            message.modelId?.takeIf { it.isNotBlank() }?.let { AnswerInfoRow("Модель", it) }
            message.providerName?.takeIf { it.isNotBlank() }?.let { AnswerInfoRow("Провайдер", it) }
            message.connectionName?.takeIf { it.isNotBlank() }?.let { AnswerInfoRow("Подключение", it) }
            message.responseDurationMs?.let { AnswerInfoRow("Время", formatAnswerDuration(it)) }

            if (
                message.inputTokens != null ||
                message.outputTokens != null ||
                message.costUsd != null ||
                message.costBreakdown != null
            ) {
                Spacer(Modifier.height(8.dp))
                AnswerInfoSectionTitle("Расходы")
                message.inputTokens?.let { AnswerInfoRow("Вход", "$it токенов") }
                message.outputTokens?.let { AnswerInfoRow("Выход", "$it токенов") }
                if (message.inputTokens != null && message.outputTokens != null) {
                    AnswerInfoRow("Всего токенов", "${message.inputTokens + message.outputTokens}")
                }

                val costs = message.costBreakdown
                if (costs != null) {
                    costs.primaryUsd?.let {
                        AnswerInfoRow("Основной ответ", formatExactUsd(it))
                    }

                    if (costs.systemCalls + costs.embeddingCalls > 0) {
                        TextButton(
                            onClick = { serviceCostsOpen = !serviceCostsOpen },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                        ) {
                            Text("Служебные операции")
                            costs.serviceUsd?.let {
                                Text(
                                    "  " + formatExactUsd(it),
                                    modifier = Modifier.padding(start = 6.dp)
                                )
                            }
                            Icon(
                                if (serviceCostsOpen) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        if (serviceCostsOpen) {
                            if (costs.systemCalls > 0) {
                                AnswerInfoRow(
                                    "Системная модель",
                                    costs.systemUsd?.let(::formatExactUsd) ?: "Стоимость не определена"
                                )
                                AnswerInfoRow("Вызовов системы", costs.systemCalls.toString())
                            }
                            if (costs.embeddingCalls > 0) {
                                AnswerInfoRow(
                                    "Embeddings",
                                    costs.embeddingsUsd?.let(::formatExactUsd) ?: "Стоимость не определена"
                                )
                                AnswerInfoRow("Embeddings-вызовов", costs.embeddingCalls.toString())
                            }
                        }
                    }

                    costs.knownTotalUsd?.let {
                        AnswerInfoRow(
                            if (costs.incomplete) "Учтено" else "Итого за ответ",
                            formatExactUsd(it)
                        )
                    }
                    if (costs.incomplete) {
                        Text(
                            "OpenRouter не сообщил стоимость части операций, поэтому показана только точно известная сумма.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                    }
                } else {
                    message.costUsd?.takeIf { it >= 0.0 }?.let {
                        AnswerInfoRow("Стоимость", formatAnswerCost(it))
                    }
                }
            }

            if (
                knowledgeCount != null ||
                message.knowledgeSearchAttempted != null ||
                message.knowledgeBaseOnly != null ||
                message.webSearchEnabled != null ||
                message.reasoningEnabled != null ||
                message.memoryContextUsed != null ||
                message.activeSkillCount != null ||
                message.teamContextUsed != null ||
                message.attachmentCount != null
            ) {
                Spacer(Modifier.height(8.dp))
                AnswerInfoSectionTitle("Контекст")
                if (knowledgeCount != null || knowledgeSearchAttempted || knowledgeBaseOnly) {
                    val knowledgeStatus = when {
                        knowledgeSearchAttempted && (knowledgeCount ?: 0) > 0 ->
                            "База участвовала · подобрано ${knowledgeCount ?: 0} фрагм."
                        knowledgeSearchAttempted ->
                            "Поиск выполнен · подходящего не найдено"
                        knowledgeBaseOnly ->
                            "Запрошена, но база недоступна или выключена"
                        (knowledgeCount ?: 0) > 0 ->
                            "База участвовала · подобрано ${knowledgeCount ?: 0} фрагм."
                        else ->
                            "Фрагменты не добавлялись"
                    }
                    AnswerInfoRow("База знаний", knowledgeStatus)
                    if (knowledgeBaseOnly) {
                        AnswerInfoRow("Режим базы", "Только по загруженным документам")
                    }
                    if (sources.isNotEmpty()) {
                        Column(
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            sources.forEach { source ->
                                Text(
                                    "• $source",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                message.webSearchEnabled?.let {
                    AnswerInfoRow("Веб-поиск", if (it) "Включён для запроса" else "Выключен")
                }
                message.reasoningEnabled?.let { enabled ->
                    val suffix = message.reasoningEffort?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                    AnswerInfoRow("Размышление", if (enabled) "Включено$suffix" else "Выключено")
                }
                message.memoryContextUsed?.let {
                    AnswerInfoRow("Память чата", if (it) "Добавлена в контекст" else "Не добавлялась")
                }
                message.activeSkillCount?.let {
                    AnswerInfoRow("Навыки", if (it > 0) "$it активн." else "Не использовались")
                }
                message.teamContextUsed?.let {
                    AnswerInfoRow("Команда", if (it) "Контекст команды добавлен" else "Без команды")
                }
                message.attachmentCount?.let {
                    AnswerInfoRow("Вложения", if (it > 0) "$it" else "Нет")
                }
                if (message.generatedFiles.isNotEmpty()) {
                    AnswerInfoRow("Создано файлов", message.generatedFiles.size.toString())
                }
            }

            if (!message.requestId.isNullOrBlank() || message.id.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { technicalOpen = !technicalOpen },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                ) {
                    Text(if (technicalOpen) "Скрыть техническое" else "Техническое")
                    Icon(
                        if (technicalOpen) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                if (technicalOpen) {
                    message.requestId?.takeIf { it.isNotBlank() }?.let { AnswerInfoRow("ID запроса", it) }
                    AnswerInfoRow("ID сообщения", message.id)
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AnswerInfoSectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
    )
}

@Composable
private fun AnswerInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp)
        )
        SelectionContainer(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun formatAnswerDuration(milliseconds: Long): String =
    if (milliseconds < 1_000L) {
        "$milliseconds мс"
    } else {
        "%.1f с".format(Locale.US, milliseconds / 1000.0)
    }

private fun formatAnswerCost(value: Double): String =
    formatExactUsd(BigDecimal.valueOf(value).toPlainString())

private fun formatExactUsd(raw: String): String = runCatching {
    val decimal = BigDecimal(raw.trim()).stripTrailingZeros()
    val plain = if (decimal.compareTo(BigDecimal.ZERO) == 0) "0" else decimal.toPlainString()
    "$" + plain
}.getOrElse {
    "$" + raw.trim()
}

private enum class MessagePartKind { PLAIN, CODE, COPY }

private data class MessagePart(
    val text: String,
    val kind: MessagePartKind,
    val language: String = ""
)

@Composable
private fun MessageBody(text: String, color: androidx.compose.ui.graphics.Color, onGuideLink: ((Int) -> Unit)? = null) {
    val parts = remember(text) { splitRichBlocks(text) }
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { part ->
            when (part.kind) {
                MessagePartKind.PLAIN -> if (part.text.isNotBlank()) {
                    MarkdownText(part.text.trim(), color, onGuideLink)
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
private fun MarkdownText(text: String, color: androidx.compose.ui.graphics.Color, onGuideLink: ((Int) -> Unit)? = null) {
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
            val guideLink = Regex("^\\[([^]]+)]\\(umnik://guide/(\\d+)\\)$").matchEntire(line.trim())
            val possibleHeader = markdownTableCells(line)
            val tableStart = possibleHeader.size >= 2 && index + 1 < lines.size &&
                isMarkdownTableSeparator(lines[index + 1], possibleHeader.size)

            when {
                guideLink != null -> {
                    flushParagraph()
                    val label = guideLink.groupValues[1]
                    val target = guideLink.groupValues[2].toIntOrNull()
                    TextButton(
                        onClick = { target?.let { onGuideLink?.invoke(it) } },
                        enabled = onGuideLink != null && target != null,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
                    ) {
                        Text(
                            label,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
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

@Composable
private fun markdownInline(source: String): androidx.compose.ui.text.AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    return buildAnnotatedString {
    val regex = Regex("`([^`\\n]+)`|\\*\\*([^*\\n]+)\\*\\*|__([^_\\n]+)__|~~([^~\\n]+)~~|\\[([^]\\n]+)]\\(([^)\\n]+)\\)|(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)|(?<!_)_([^_\\n]+)_(?!_)")
    var cursor = 0
    regex.findAll(source).forEach { match ->
        if (match.range.first > cursor) append(source.substring(cursor, match.range.first))
        when {
            match.groupValues[1].isNotEmpty() -> withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = codeBackground
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
private fun GeneratedFileCard(
    file: GeneratedFile,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    val isImage = file.mimeType.startsWith("image/")
    val isAudio = file.mimeType.startsWith("audio/")
    val isVideo = file.mimeType.startsWith("video/")
    val bitmap = remember(file.localPath, file.mimeType) {
        if (isImage && file.mimeType.lowercase() != "image/svg+xml") {
            BitmapFactory.decodeFile(file.localPath)
        } else null
    }
    var imageViewerOpen by remember(file.id) { mutableStateOf(false) }

    if (bitmap != null) {
        ComposeImage(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = file.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1).toFloat())
                .clip(RoundedCornerShape(14.dp))
                .clickable { imageViewerOpen = true },
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.height(6.dp))
    }

    Surface(
        onClick = onSave,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isImage) Icons.Outlined.Image else Icons.Outlined.Description,
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
            Icon(
                Icons.Outlined.Download,
                contentDescription = "Скачать файл",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isVideo) {
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = { openGeneratedFile(context, file) },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Outlined.PlayArrow,
                        contentDescription = "Открыть видео",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    if (isAudio) {
        Spacer(Modifier.height(6.dp))
        GeneratedAudioPlayer(file)
    }

    if (imageViewerOpen && bitmap != null) {
        FullScreenImageViewer(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = file.name,
            onDismiss = { imageViewerOpen = false }
        )
    }
}

@Composable
private fun FullScreenImageViewer(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    contentDescription: String,
    onDismiss: () -> Unit
) {
    var scale by remember(bitmap) { mutableStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Box(Modifier.fillMaxSize()) {
                ComposeImage(
                    bitmap = bitmap,
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .pointerInput(bitmap) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(1f, 5f)
                                scale = nextScale
                                offset = if (nextScale <= 1.01f) {
                                    Offset.Zero
                                } else {
                                    offset + pan
                                }
                            }
                        }
                        .pointerInput(bitmap) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.05f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                }
                            )
                        },
                    contentScale = ContentScale.Fit
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Закрыть изображение",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
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
                    "Навыки хранятся в общей библиотеке. Здесь их можно добавлять и удалять, а включение для конкретного чата доступно через «+ → Инструменты → Навыки». Навык сам ничего не запускает и не изменяет файлы.",
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
                    UmnikPanel {
                        Text(
                            "Пока навыков нет. Импортируйте SKILL.md или папку навыка.",
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(state.skills, key = { it.id }) { skill ->
                UmnikPanel {
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
                                            "Активен в текущем чате"
                                        else
                                            "Включить в текущем чате"
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

private data class CameraTarget(val uri: Uri, val file: File)

private fun createCameraTarget(context: Context): CameraTarget {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
    dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return CameraTarget(uri, file)
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
