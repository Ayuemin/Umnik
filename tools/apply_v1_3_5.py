from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def replace_block(text: str, start: str, end: str, new_block: str, label: str) -> str:
    start_index = text.find(start)
    if start_index < 0:
        raise RuntimeError(f"{label}: start marker not found")
    end_index = text.find(end, start_index)
    if end_index < 0:
        raise RuntimeError(f"{label}: end marker not found")
    return text[:start_index] + new_block.rstrip() + "\n\n" + text[end_index:]


ui_path = ROOT / "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
ui = ui_path.read_text(encoding="utf-8")

ui = replace_once(
    ui,
    "import androidx.compose.foundation.Image as ComposeImage\n",
    "import androidx.compose.foundation.Image as ComposeImage\nimport androidx.compose.foundation.gestures.detectHorizontalDragGestures\n",
    "gesture import",
)
ui = replace_once(
    ui,
    "import androidx.compose.material.icons.outlined.Mic\n",
    "import androidx.compose.material.icons.outlined.Mic\nimport androidx.compose.material.icons.outlined.Menu\n",
    "menu import",
)
ui = replace_once(
    ui,
    "import androidx.compose.ui.Modifier\n",
    "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.input.pointer.pointerInput\n",
    "pointer import",
)
ui = replace_once(
    ui,
    "import androidx.compose.ui.text.style.TextOverflow\n",
    "import androidx.compose.ui.text.style.TextAlign\nimport androidx.compose.ui.text.style.TextOverflow\n",
    "text align import",
)

ui = replace_once(
    ui,
    "    var requestElapsedSeconds by remember { mutableIntStateOf(0) }\n",
    "    var requestElapsedSeconds by remember { mutableIntStateOf(0) }\n    var menuSwipeSignal by remember { mutableIntStateOf(0) }\n",
    "swipe signal state",
)

ui = replace_once(
    ui,
    "    Column(Modifier.fillMaxSize()) {\n        ChatHeader(\n            state = state,\n            vm = vm,\n",
    """    val edgeSwipeWidthPx = with(LocalDensity.current) { 36.dp.toPx() }
    val edgeSwipeTriggerPx = with(LocalDensity.current) { 96.dp.toPx() }

    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(edgeSwipeWidthPx, edgeSwipeTriggerPx) {
                var startedAtLeftEdge = false
                var horizontalDistance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        startedAtLeftEdge = offset.x <= edgeSwipeWidthPx
                        horizontalDistance = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        if (startedAtLeftEdge) horizontalDistance += dragAmount
                    },
                    onDragEnd = {
                        if (startedAtLeftEdge && horizontalDistance >= edgeSwipeTriggerPx) {
                            menuSwipeSignal += 1
                        }
                        startedAtLeftEdge = false
                        horizontalDistance = 0f
                    },
                    onDragCancel = {
                        startedAtLeftEdge = false
                        horizontalDistance = 0f
                    }
                )
            }
    ) {
        ChatHeader(
            state = state,
            vm = vm,
            openMenuSignal = menuSwipeSignal,
""",
    "chat swipe wrapper",
)

ui = replace_once(
    ui,
    "                                modifier = Modifier.size(if (state.requestActive) 50.dp else 48.dp)\n                            ) {\n                                if (state.requestActive) {\n                                    WorkingStopTimer(requestElapsedSeconds)\n",
    """                                modifier = Modifier.size(48.dp)
                            ) {
                                if (state.requestActive) {
                                    WorkingStopIcon()
""",
    "stop button",
)

ui = replace_once(
    ui,
    "                    placeholder = if (imagePromptMode) { { Text(\"Опишите изображение\") } } else null,\n",
    """                    placeholder = {
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
""",
    "request timer placeholder",
)

ui = replace_block(
    ui,
    "@Composable\nprivate fun WorkingStopTimer(seconds: Int) {",
    "@Composable\nprivate fun ChatHeader(",
    r'''@Composable
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
}''',
    "working stop component",
)

new_header = r'''@Composable
private fun ChatHeader(
    state: UiState,
    vm: ChatViewModel,
    openMenuSignal: Int,
    onChats: () -> Unit,
    onProjects: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onClear: () -> Unit
) {
    var quickModelsOpen by remember { mutableStateOf(false) }
    var hubOpen by remember { mutableStateOf(false) }
    var usageOpen by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
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

    LaunchedEffect(openMenuSignal) {
        if (openMenuSignal > 0) hubOpen = true
    }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                IconButton(
                    onClick = { hubOpen = true },
                    enabled = !state.isLoading,
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        Icons.Outlined.Menu,
                        contentDescription = "Меню",
                        modifier = Modifier.size(25.dp),
                        tint = if (currentProjectId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(expanded = hubOpen, onDismissRequest = { hubOpen = false }) {
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
                            onClick = {
                                hubOpen = false
                                clearConfirm = true
                            }
                        )
                    }
                }
            }

            IconButton(onClick = onNewChat, enabled = !state.isLoading, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Outlined.AddComment, contentDescription = "Новый чат", modifier = Modifier.size(24.dp))
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

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("Очистить текущий чат?") },
            text = { Text("Переписка и файлы контекста этого чата будут удалены. Сгенерированные файлы в хранилище Umnik останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirm = false
                    onClear()
                }) { Text("Очистить") }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirm = false }) { Text("Отмена") }
            }
        )
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
            confirmButton = {
                TextButton(onClick = { usageOpen = false }) { Text("Закрыть") }
            },
            dismissButton = {
                TextButton(onClick = { vm.refreshProviderUsage() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Обновить")
                }
            }
        )
    }
}'''

ui = replace_block(
    ui,
    "@Composable\nprivate fun ChatHeader(",
    "@Composable\nprivate fun ComposerActionTile(",
    new_header,
    "chat header",
)

new_generated_file_card = r'''@Composable
private fun GeneratedFileCard(file: GeneratedFile, onSave: (GeneratedFile) -> Unit) {
    val context = LocalContext.current
    val isImage = file.mimeType.startsWith("image/")
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
        Spacer(Modifier.height(7.dp))
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    if (isImage) Icons.Outlined.Image else Icons.Outlined.Description,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (file.mimeType.lowercase() == "image/svg+xml") "Векторное изображение SVG" else "Предпросмотр недоступен",
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${file.name} · ${file.mimeType.ifBlank { "неизвестный формат" }} · ${humanSize(file.size)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(7.dp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalButton(
            onClick = { onSave(file) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("Скачать", maxLines = 1)
        }
        FilledTonalButton(
            onClick = { shareGeneratedFile(context, file) },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("Поделиться", maxLines = 1)
        }
    }
}'''

ui = replace_block(
    ui,
    "@Composable\nprivate fun GeneratedFileCard(",
    "@Composable\nprivate fun SkillsScreen(",
    new_generated_file_card,
    "generated file card",
)

old_share = '''private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться ответом"))
}
'''
new_share = r'''private fun shareText(context: Context, text: String) {
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
'''
ui = replace_once(ui, old_share, new_share, "sharing helpers")
ui_path.write_text(ui, encoding="utf-8")


nvidia_path = ROOT / "app/src/main/java/com/ayuemin/ymnik/network/NvidiaImageClient.kt"
nvidia = nvidia_path.read_text(encoding="utf-8")
nvidia = replace_once(nvidia, ".readTimeout(240, TimeUnit.SECONDS)\n        .writeTimeout(240, TimeUnit.SECONDS)", ".readTimeout(600, TimeUnit.SECONDS)\n        .writeTimeout(600, TimeUnit.SECONDS)", "NVIDIA timeout")
nvidia = replace_once(
    nvidia,
    '''        model.contains("flux.1-schnell") -> JsonObject().apply {
            addProperty("prompt", prompt)
            addProperty("seed", 0)
            addProperty("steps", 4)
            flux1Dimensions(aspectRatio)?.let { (width, height) ->
                addProperty("width", width)
                addProperty("height", height)
            }
        }
''',
    '''        model.contains("flux.1-schnell") -> JsonObject().apply {
            // Keep the hosted trial request identical to NVIDIA's current official
            // cloud example. In particular, do not send width/height here: the
            // hosted FLUX.1-schnell validator currently behaves more reliably with
            // its default 1024x1024 output.
            addProperty("prompt", prompt)
            addProperty("seed", 0)
            addProperty("steps", 4)
        }
''',
    "FLUX.1-schnell payload",
)
nvidia = replace_once(nvidia, "            readTimeout = 120_000\n", "            readTimeout = 300_000\n", "NVIDIA download timeout")
nvidia_path.write_text(nvidia, encoding="utf-8")


openrouter_path = ROOT / "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"
openrouter = openrouter_path.read_text(encoding="utf-8")
openrouter = replace_once(
    openrouter,
    '''                val mime = item.get("media_type")?.asString?.takeIf { it.startsWith("image/") } ?: "image/png"
                saveGeneratedImage(encoded, mime, index)
''',
    '''                val mime = item.get("media_type")?.asString?.takeIf { it.isNotBlank() } ?: "image/png"
                saveGeneratedImage(encoded, mime, index)
''',
    "OpenRouter media type",
)
openrouter = replace_once(
    openrouter,
    '''        val extension = when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }
''',
    '''        val extension = when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/svg+xml" -> "svg"
            "application/pdf" -> "pdf"
            else -> mimeType.substringAfter('/', "bin").substringBefore('+').lowercase()
                .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
        }
''',
    "OpenRouter generated extension",
)
openrouter_path.write_text(openrouter, encoding="utf-8")


paths_path = ROOT / "app/src/main/res/xml/file_paths.xml"
paths_xml = paths_path.read_text(encoding="utf-8")
paths_xml = replace_once(
    paths_xml,
    '    <cache-path name="camera" path="camera/" />\n',
    '    <cache-path name="camera" path="camera/" />\n    <files-path name="generated" path="generated/" />\n    <files-path name="exports" path="exports/" />\n',
    "FileProvider paths",
)
paths_path.write_text(paths_xml, encoding="utf-8")


registry_path = ROOT / "docs/provider-registry.json"
registry = json.loads(registry_path.read_text(encoding="utf-8"))
registry["version"] = 3
registry["updated"] = "2026-09-12"
for item in registry["providers"]["nvidia"]["imageModels"]:
    if item.get("id") == "black-forest-labs/flux.1-schnell":
        item["parameterOptions"] = {"aspect_ratio": ["1:1"]}
registry_path.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


gradle_path = ROOT / "app/build.gradle.kts"
gradle = gradle_path.read_text(encoding="utf-8")
gradle = replace_once(gradle, "// Umnik v1.3.4", "// Umnik v1.3.5", "version comment")
gradle = replace_once(gradle, "versionCode = 46", "versionCode = 47", "versionCode")
gradle = replace_once(gradle, 'versionName = "1.3.4"', 'versionName = "1.3.5"', "versionName")
gradle_path.write_text(gradle, encoding="utf-8")


changelog_path = ROOT / "CHANGELOG.md"
changelog = changelog_path.read_text(encoding="utf-8")
section = '''## v1.3.5 - 2026-09-12

- Возвращён компактный пульсирующий Stop: нажатием на него запрос по-прежнему можно остановить, а время работы модели теперь ненавязчиво показывается по центру пустого поля ввода.
- Верхняя панель стала компактнее: слева направо расположены меню, новый чат, баланс OpenRouter и быстрая модель; текст «Меню» заменён на значок из трёх полос.
- Меню можно открыть жестом слева направо от левого края экрана.
- Перед очисткой текущего чата теперь обязательно показывается подтверждение, защищающее от случайного удаления переписки.
- Исправлена интеграция NVIDIA FLUX.1-schnell: облачный запрос приведён к минимальному официальному формату NVIDIA без нестабильных width/height; ожидание NVIDIA Image API увеличено до 10 минут, при этом запрос всегда можно остановить вручную.
- Для FLUX.1-schnell временно оставлен безопасный формат 1:1 в обновляемом реестре, пока облачный endpoint NVIDIA нестабилен с произвольными размерами.
- OpenRouter SVG теперь сохраняется с расширением `.svg`. Непросматриваемые и неизвестные файлы больше не пропадают: Umnik показывает карточку-заглушку с кнопками «Скачать» и «Поделиться».
- FileProvider расширен на сгенерированные файлы, поэтому ими можно безопасно делиться прямо из чата.
- Кнопка «Поделиться» под текстовым ответом теперь отправляет читаемый текст без служебных Markdown-символов `#`, `*`, обратных кавычек и других маркеров; копирование и скачивание ответа остались без изменений.

'''
changelog = replace_once(changelog, "## Unreleased\n\n", "## Unreleased\n\n" + section, "changelog")
changelog_path.write_text(changelog, encoding="utf-8")

print("v1.3.5 patch applied successfully")
