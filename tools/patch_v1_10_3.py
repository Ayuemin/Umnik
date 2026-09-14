from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


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


def regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    next_text, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one regex match, got {count}")
    return next_text


# 1) Version and changelog.
gradle_path = "app/build.gradle.kts"
gradle = read(gradle_path)
gradle = replace_once(gradle, "// Umnik v1.10.2", "// Umnik v1.10.3", "version comment")
gradle = replace_once(gradle, 'versionCode = 102\n        versionName = "1.10.2"', 'versionCode = 103\n        versionName = "1.10.3"', "app version")
write(gradle_path, gradle)

changelog_path = "CHANGELOG.md"
changelog = read(changelog_path)
entry = '''## v1.10.3 - 2026-09-14

- Очистка фоновых заданий теперь есть и для истории видео; активные видео-задания при очистке сохраняются.
- Обычный текстовый ответ больше не превращается в скачиваемый файл из-за длины: инструмент создания файла используется только по явному запросу пользователя либо по явному требованию проекта, навыка или инструкции.
- Файлы без доступного предпросмотра занимают одну компактную строку с иконкой и именем. Большие дублирующие кнопки «Скачать» и «Поделиться» убраны; маленькие действия под сообщением работают с единственным сгенерированным файлом.
- Кнопка `OR` под ответом теперь озвучивает ответ временно: во время подготовки она «дышит», готовое аудио запускается автоматически, повторное нажатие останавливает воспроизведение. Аудиофайл не добавляется в чат и не сохраняется в хранилище Umnik.
- «Размышление» и «Поиск в сети» подняты сразу под верхний ряд меню `+`, поэтому оба переключателя видны без прокрутки на обычном экране телефона.
- Версия: 1.10.3 / versionCode 103.

'''
changelog = replace_once(changelog, "## Unreleased\n\n", "## Unreleased\n\n" + entry, "changelog entry")
write(changelog_path, changelog)


# 2) Do not encourage automatic downloadable files for long text responses.
client_path = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"
client = read(client_path)
client = replace_once(
    client,
    '                    "Создать текстовый файл на устройстве пользователя. Используй для длинных материалов и когда пользователь просит результат файлом."',
    '                    "Создать текстовый файл на устройстве пользователя. Вызывай ТОЛЬКО если пользователь в текущем запросе прямо просит файл/скачивание либо системная, проектная или подключённая инструкция прямо требует вернуть результат файлом. Никогда не создавай файл автоматически только из-за длины ответа."',
    "create_file policy"
)
write(client_path, client)

vm_path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
vm = read(vm_path)
vm = replace_once(
    vm,
    '        appendLine("Ты работаешь внутри Android-приложения «Umnik». Отвечай на языке пользователя, если он не попросил иначе.")\n',
    '        appendLine("Ты работаешь внутри Android-приложения «Umnik». Отвечай на языке пользователя, если он не попросил иначе.")\n        appendLine("Не создавай скачиваемый файл автоматически из-за длины ответа. Используй create_file только если пользователь прямо просит файл/скачивание либо проект, навык или другая подключённая инструкция явно требует вернуть результат файлом.")\n',
    "system file policy"
)
write(vm_path, vm)


# 3) Clear finished video history in addition to Batch history.
controller_path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHubController.kt"
controller = read(controller_path)
submit_marker = "    fun submitBatch(raw: String, taskFileUris: List<List<Uri>> = emptyList()) {\n"
clear_video = '''    fun clearFinishedVideoHistory() {
        runCatching {
            val remaining = videoRepository.list().filterNot { it.status.terminal }
            videoRepository.save(remaining)
            remaining
        }.onSuccess { remaining ->
            mutableState.value = mutableState.value.copy(
                videos = remaining,
                status = if (remaining.isEmpty()) "История видео очищена" else "Завершённая история видео очищена; активные задачи сохранены"
            )
        }.onFailure { error ->
            mutableState.value = mutableState.value.copy(status = error.message ?: "Не удалось очистить историю видео")
        }
    }

'''
if submit_marker not in controller:
    raise SystemExit("video history clear marker not found")
controller = controller.replace(submit_marker, clear_video + submit_marker, 1)
write(controller_path, controller)

hub_path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHub.kt"
hub = read(hub_path)
hub = replace_once(
    hub,
    '        item { HorizontalDivider(); Text("Видео-задания", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }',
    '''        item {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Видео-задания", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = controller::clearFinishedVideoHistory,
                    enabled = state.videos.any { it.status.terminal }
                ) { Text("Очистить") }
            }
        }''',
    "video history clear button"
)
write(hub_path, hub)


# 4) Temporary OpenRouter speech player for the OR button under a response.
player_path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterSpeechPlayer.kt"
player_source = '''package com.ayuemin.ymnik.ui

import android.content.Context
import android.media.MediaPlayer
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.data.OpenRouterFeaturePrefs
import com.ayuemin.ymnik.data.SecretStore
import com.ayuemin.ymnik.model.ProviderType
import com.ayuemin.ymnik.network.OpenRouterAudioClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class OpenRouterSpeechPhase {
    IDLE,
    PREPARING,
    PLAYING
}

data class OpenRouterSpeechPlaybackState(
    val messageId: String? = null,
    val phase: OpenRouterSpeechPhase = OpenRouterSpeechPhase.IDLE,
    val error: String? = null
)

/**
 * One-tap neural speech for an assistant message.
 *
 * Audio exists only in cache for the current playback session. It is never
 * attached to a chat and never copied into Umnik's generated-file storage.
 */
class OpenRouterSpeechPlayer(
    context: Context,
    private val viewModel: ChatViewModel
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val secrets = SecretStore(appContext)
    private val featurePrefs = OpenRouterFeaturePrefs(appContext)
    private val audioClient = OpenRouterAudioClient(appContext)
    private val cacheDir = File(appContext.cacheDir, "openrouter_speech_playback").apply {
        mkdirs()
        listFiles()?.forEach { runCatching { it.delete() } }
    }

    private val mutableState = MutableStateFlow(OpenRouterSpeechPlaybackState())
    val state: StateFlow<OpenRouterSpeechPlaybackState> = mutableState.asStateFlow()

    private var generation = 0L
    private var player: MediaPlayer? = null
    private var tempFile: File? = null

    fun toggle(messageId: String, textRaw: String) {
        val text = textRaw.trim()
        if (messageId.isBlank() || text.isBlank()) return

        val current = mutableState.value
        if (current.messageId == messageId && current.phase != OpenRouterSpeechPhase.IDLE) {
            stop()
            return
        }

        stopInternal(resetState = false)
        val appState = viewModel.state.value
        val profile = appState.connectionProfiles.firstOrNull {
            it.type == ProviderType.OPENROUTER && it.id !in appState.disabledConnectionIds
        }
        val model = appState.openRouterSpeechModel.trim()
        val key = profile?.let { secrets.getProfileApiKey(it.id) }.orEmpty()
        if (profile == null || key.isBlank()) {
            mutableState.value = OpenRouterSpeechPlaybackState(error = "OpenRouter не настроен")
            return
        }
        if (model.isBlank()) {
            mutableState.value = OpenRouterSpeechPlaybackState(error = "Сначала выберите модель озвучивания OpenRouter")
            return
        }

        val requestGeneration = ++generation
        mutableState.value = OpenRouterSpeechPlaybackState(
            messageId = messageId,
            phase = OpenRouterSpeechPhase.PREPARING
        )

        scope.launch {
            runCatching {
                val media = featurePrefs.media()
                val result = audioClient.synthesize(
                    apiKey = key,
                    model = model,
                    input = text,
                    voice = media.voice.takeIf { it.isNotBlank() },
                    baseUrl = viewModel.connectionTextEndpoint(profile.id)
                )
                val extension = result.format.lowercase().replace(Regex("[^a-z0-9]"), "").ifBlank { "mp3" }
                val file = withContext(Dispatchers.IO) {
                    File(cacheDir, "speech_${UUID.randomUUID()}.$extension").apply { writeBytes(result.bytes) }
                }
                file
            }.onSuccess { file ->
                if (requestGeneration != generation || mutableState.value.messageId != messageId) {
                    runCatching { file.delete() }
                    return@onSuccess
                }
                tempFile = file
                runCatching {
                    val mediaPlayer = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnCompletionListener { stop() }
                        setOnErrorListener { _, _, _ ->
                            stop()
                            true
                        }
                        prepare()
                        start()
                    }
                    player = mediaPlayer
                    mutableState.value = OpenRouterSpeechPlaybackState(
                        messageId = messageId,
                        phase = OpenRouterSpeechPhase.PLAYING
                    )
                }.onFailure { error ->
                    stopInternal(resetState = false)
                    mutableState.value = OpenRouterSpeechPlaybackState(
                        error = error.message ?: "Не удалось воспроизвести озвучку OpenRouter"
                    )
                }
            }.onFailure { error ->
                if (requestGeneration == generation) {
                    stopInternal(resetState = false)
                    mutableState.value = OpenRouterSpeechPlaybackState(
                        error = error.message ?: "Не удалось озвучить ответ через OpenRouter"
                    )
                }
            }
        }
    }

    fun stop() {
        generation += 1L
        stopInternal(resetState = true)
    }

    private fun stopInternal(resetState: Boolean) {
        player?.let { current ->
            runCatching { if (current.isPlaying) current.stop() }
            runCatching { current.reset() }
            runCatching { current.release() }
        }
        player = null
        tempFile?.let { runCatching { it.delete() } }
        tempFile = null
        if (resetState) mutableState.value = OpenRouterSpeechPlaybackState()
    }

    fun clearError() {
        if (mutableState.value.error != null) mutableState.value = OpenRouterSpeechPlaybackState()
    }

    fun close() {
        stop()
        cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
        scope.cancel()
    }
}
'''
write(player_path, player_source)


# 5) Chat UI: use the temporary player, compact file placeholders and keep OR speech out of chat history.
ui_path = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
ui = read(ui_path)
ui = replace_once(
    ui,
    '    val tts = remember { TtsController(context) }\n    var screen by remember { mutableIntStateOf(0) }\n\n    DisposableEffect(tts) {\n        onDispose { tts.shutdown() }\n    }',
    '''    val tts = remember { TtsController(context) }
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
    }''',
    "temporary speech player root"
)
ui = replace_once(
    ui,
    '''                        state = state,
                        vm = viewModel,
                        tts = tts,
                        onOpenSkills = { screen = 1 },''',
    '''                        state = state,
                        vm = viewModel,
                        tts = tts,
                        openRouterSpeech = openRouterSpeech,
                        openRouterSpeechState = openRouterSpeechState,
                        onOpenSkills = { screen = 1 },''',
    "ChatScreen speech args"
)
ui = replace_once(
    ui,
    '''private fun ChatScreen(
    state: UiState,
    vm: ChatViewModel,
    tts: TtsController,
    onOpenSkills: () -> Unit,''',
    '''private fun ChatScreen(
    state: UiState,
    vm: ChatViewModel,
    tts: TtsController,
    openRouterSpeech: OpenRouterSpeechPlayer,
    openRouterSpeechState: OpenRouterSpeechPlaybackState,
    onOpenSkills: () -> Unit,''',
    "ChatScreen signature"
)
ui = replace_once(
    ui,
    '''                    openRouterSpeechEnabled = state.openRouterSpeechModel.isNotBlank(),
                    onOpenRouterSpeech = { com.ayuemin.ymnik.AsyncJobEvents.requestSpeech(state.currentChatId, message.text) },''',
    '''                    openRouterSpeechEnabled = state.openRouterSpeechModel.isNotBlank(),
                    openRouterSpeechPhase = if (openRouterSpeechState.messageId == message.id) openRouterSpeechState.phase else OpenRouterSpeechPhase.IDLE,
                    onOpenRouterSpeech = { openRouterSpeech.toggle(message.id, message.text) },''',
    "MessageCard speech invocation"
)
ui = replace_once(
    ui,
    '''private fun MessageCard(
    message: ChatMessage,
    tts: TtsController,
    openRouterSpeechEnabled: Boolean,
    onOpenRouterSpeech: () -> Unit,''',
    '''private fun MessageCard(
    message: ChatMessage,
    tts: TtsController,
    openRouterSpeechEnabled: Boolean,
    openRouterSpeechPhase: OpenRouterSpeechPhase,
    onOpenRouterSpeech: () -> Unit,''',
    "MessageCard speech phase"
)
ui = replace_once(
    ui,
    '''                    OpenRouterSpeechAction(
                        enabled = openRouterSpeechEnabled,
                        onClick = onOpenRouterSpeech
                    )''',
    '''                    OpenRouterSpeechAction(
                        enabled = openRouterSpeechEnabled,
                        phase = openRouterSpeechPhase,
                        onClick = onOpenRouterSpeech
                    )''',
    "OpenRouter speech action call"
)

speech_action = '''@Composable
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

'''
ui = replace_between(
    ui,
    "@Composable\nprivate fun OpenRouterSpeechAction(",
    "@Composable\nprivate fun GeneratedAudioPlayer(",
    speech_action,
    "OpenRouter speech action function"
)

ui = replace_once(
    ui,
    "                    GeneratedFileCard(file, onSaveGenerated)\n",
    "                    GeneratedFileCard(file)\n",
    "generated file card call"
)

compact_file_card = '''@Composable
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

'''
ui = replace_between(
    ui,
    "@Composable\nprivate fun GeneratedFileCard(",
    "@Composable\nprivate fun SkillsScreen(",
    compact_file_card,
    "compact generated file card"
)

ui = replace_once(
    ui,
    '''                CompactMessageAction(
                    icon = Icons.Outlined.Share,
                    description = "Поделиться",
                    onClick = { shareText(context, message.text) }
                )''',
    '''                CompactMessageAction(
                    icon = Icons.Outlined.Share,
                    description = "Поделиться",
                    onClick = {
                        message.generatedFiles.singleOrNull()?.let { shareGeneratedFile(context, it) }
                            ?: shareText(context, message.text)
                    }
                )''',
    "compact share generated file"
)
ui = replace_once(
    ui,
    '''                    CompactMessageAction(
                        icon = Icons.Outlined.Download,
                        description = "Сохранить ответ файлом",
                        onClick = onExportText
                    )''',
    '''                    CompactMessageAction(
                        icon = Icons.Outlined.Download,
                        description = if (message.generatedFiles.size == 1) "Скачать файл" else "Сохранить ответ файлом",
                        onClick = {
                            message.generatedFiles.singleOrNull()?.let(onSaveGenerated) ?: onExportText()
                        }
                    )''',
    "compact download generated file"
)

# Put reasoning and web search immediately under the first action row so both are visible without scrolling.
visible_toggles = '''
                if (!imagePromptMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = state.reasoningEnabled,
                            onClick = { vm.setReasoningEnabled(!state.reasoningEnabled) },
                            enabled = reasoningAvailable,
                            modifier = Modifier.weight(1f),
                            leadingIcon = {
                                Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            label = { Text("Размышление", maxLines = 1) }
                        )
                        FilterChip(
                            selected = state.webSearchEnabled,
                            onClick = { vm.setWebSearchEnabled(!state.webSearchEnabled) },
                            enabled = openRouterProfile,
                            modifier = Modifier.weight(1f),
                            leadingIcon = {
                                Icon(Icons.Outlined.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            label = { Text("Поиск в сети", maxLines = 1) }
                        )
                    }
                }

'''
openrouter_tools_marker = '''                Text(
                    "Инструменты OpenRouter",
'''
if openrouter_tools_marker not in ui:
    raise SystemExit("OpenRouter tools marker not found")
ui = ui.replace(openrouter_tools_marker, visible_toggles + openrouter_tools_marker, 1)

old_bottom_toggles_pattern = r'''\n\s*HorizontalDivider\(\)\n\n\s*if \(!imagePromptMode\) \{\n\s*ComposerToolRow\(\n\s*icon = Icons\.Outlined\.Psychology,\n\s*title = "Размышление",\n\s*subtitle = if \(reasoningAvailable\) "Использовать reasoning выбранной модели" else "Модель не поддерживает",\n\s*checked = state\.reasoningEnabled,\n\s*enabled = reasoningAvailable,\n\s*onCheckedChange = vm::setReasoningEnabled\n\s*\)\n\s*ComposerToolRow\(\n\s*icon = Icons\.Outlined\.Language,\n\s*title = "Поиск в сети",\n\s*subtitle = if \(openRouterProfile\) "OpenRouter web search" else "Недоступно для этого подключения",\n\s*checked = state\.webSearchEnabled,\n\s*enabled = openRouterProfile,\n\s*onCheckedChange = vm::setWebSearchEnabled\n\s*\)\n\s*\}\n'''
ui = regex_once(ui, old_bottom_toggles_pattern, "\n                HorizontalDivider()\n", "remove old bottom toggles")
write(ui_path, ui)

print("Applied Umnik v1.10.3 migration")
