from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, got {count}: {old[:140]!r}")
    write(path, text.replace(old, new, 1))


# ---------------------------------------------------------------------------
# Version / changelog
# ---------------------------------------------------------------------------
replace_once("app/build.gradle.kts", "// Umnik v1.10.0", "// Umnik v1.10.1")
replace_once(
    "app/build.gradle.kts",
    'versionCode = 100\n        versionName = "1.10.0"',
    'versionCode = 101\n        versionName = "1.10.1"',
)

changelog = read("CHANGELOG.md")
marker = "## Unreleased\n"
entry = """## Unreleased\n\n## v1.10.1 - 2026-09-14\n\n- Переключатель веб-поиска поднят в верхнюю часть меню `+`, чтобы его было видно без прокрутки. Значки действий и инструментов теперь явно используют текущий Accent/Material цвет.\n- В Batch убрана лишняя подсказка про разделительные линии. Добавлено прикрепление текстовых файлов: Umnik безопасно вставляет их содержимое в каждую задачу, потому что OpenRouter Batch не принимает обычные file/image parts.\n- В озвучивании можно загрузить текстовый файл (`txt/md/csv/json/xml`) прямо в поле текста перед генерацией аудио.\n- Batch больше не пытается автоматически отправлять вложенные изображения/файлы текущего чата как multipart-контент, что устраняет ошибку `unsupported image_url/file content` в Batch API.\n- Свайп открытия боковой панели стал отзывчивее: уменьшен общий порог и добавлена приоритетная зона у левого края экрана, не конфликтующая с прокруткой чата.\n- Версия: 1.10.1 / versionCode 101.\n"""
if marker not in changelog:
    raise SystemExit("CHANGELOG.md: Unreleased marker not found")
write("CHANGELOG.md", changelog.replace(marker, entry, 1))

# ---------------------------------------------------------------------------
# Main chat + sheet: web search visible, themed icons, better swipe.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
text = read(path)

old_swipe = '''    val menuSwipeTriggerPx = with(LocalDensity.current) { 76.dp.toPx() }

    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(menuSwipeTriggerPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var horizontalDistance = 0f
                    var verticalDistance = 0f
                    var blockedByChild = false
                    var opened = false

                    while (true) {
                        // Final pass lets nested horizontally scrollable content consume
                        // the gesture first. A Markdown table therefore scrolls instead
                        // of opening the menu, while an ordinary chat area still swipes.
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) blockedByChild = true

                        horizontalDistance += change.position.x - change.previousPosition.x
                        verticalDistance += change.position.y - change.previousPosition.y

                        if (
                            !blockedByChild &&
                            !opened &&
                            horizontalDistance >= menuSwipeTriggerPx &&
                            horizontalDistance > kotlin.math.abs(verticalDistance) * 1.25f
                        ) {
                            sidebarOpen = true
                            opened = true
                        }

                        if (!change.pressed) break
                        if (horizontalDistance <= -menuSwipeTriggerPx) break
                        if (kotlin.math.abs(verticalDistance) > menuSwipeTriggerPx * 1.35f) break
                    }
                }
            }
    ) {'''
new_swipe = '''    val density = LocalDensity.current
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
    ) {'''
if old_swipe not in text:
    raise SystemExit("YmnikApp.kt: swipe block not found")
text = text.replace(old_swipe, new_swipe, 1)

web_row = '''                    ComposerToolRow(
                        icon = Icons.Outlined.Language,
                        title = "Поиск в сети",
                        subtitle = if (openRouterProfile) "OpenRouter web search" else "Недоступно для этого подключения",
                        checked = state.webSearchEnabled,
                        enabled = openRouterProfile,
                        onCheckedChange = vm::setWebSearchEnabled
                    )'''
if text.count(web_row) != 1:
    raise SystemExit(f"YmnikApp.kt: expected one web search row, got {text.count(web_row)}")
text = text.replace(web_row, "", 1)

insert_anchor = '''                Text(
                    "Инструменты OpenRouter",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )'''
visible_web = '''                if (!imagePromptMode) {
                    ComposerToolRow(
                        icon = Icons.Outlined.Language,
                        title = "Поиск в сети",
                        subtitle = if (openRouterProfile) "OpenRouter web search" else "Недоступно для этого подключения",
                        checked = state.webSearchEnabled,
                        enabled = openRouterProfile,
                        onCheckedChange = vm::setWebSearchEnabled
                    )
                }

''' + insert_anchor
if text.count(insert_anchor) != 1:
    raise SystemExit(f"YmnikApp.kt: tools title count={text.count(insert_anchor)}")
text = text.replace(insert_anchor, visible_web, 1)

old_action_icon = '            Icon(icon, contentDescription = null, modifier = Modifier.size(23.dp))'
new_action_icon = '''            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(23.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            )'''
if text.count(old_action_icon) != 1:
    raise SystemExit(f"YmnikApp.kt: action icon count={text.count(old_action_icon)}")
text = text.replace(old_action_icon, new_action_icon, 1)

old_tool_icon = '        Icon(icon, contentDescription = null, modifier = Modifier.size(25.dp))'
new_tool_icon = '''        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(25.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        )'''
if text.count(old_tool_icon) != 1:
    raise SystemExit(f"YmnikApp.kt: tool icon count={text.count(old_tool_icon)}")
text = text.replace(old_tool_icon, new_tool_icon, 1)
write(path, text)

# ---------------------------------------------------------------------------
# OpenRouter hub: Batch file picker + TTS text-file import; remove separator text.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHub.kt"
text = read(path)

old_jobs_head = '''private fun JobsPage(state: OpenRouterHubState, controller: OpenRouterHubController) {
    val tasks = remember { mutableStateListOf("") }
    var bulkInput by remember { mutableStateOf("") }
    val readyCount = tasks.count { it.isNotBlank() }
'''
new_jobs_head = '''private fun JobsPage(state: OpenRouterHubState, controller: OpenRouterHubController) {
    val tasks = remember { mutableStateListOf("") }
    val batchFiles = remember { mutableStateListOf<Uri>() }
    var bulkInput by remember { mutableStateOf("") }
    val batchFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        batchFiles.clear()
        batchFiles.addAll(uris.take(6))
    }
    val readyCount = tasks.count { it.isNotBlank() }
'''
if old_jobs_head not in text:
    raise SystemExit("OpenRouterHub.kt: JobsPage header not found")
text = text.replace(old_jobs_head, new_jobs_head, 1)

model_anchor = '''            CategoryModelPicker(
                title = "Модель для пакетных задач",
                current = state.media.batchModel,
                models = state.catalog.filter { it.isBatch && ModelCategory.TEXT in it.categories },
                onSelect = { controller.assignModel(it, ModelCategory.TEXT) }
            )
        }
'''
model_with_files = '''            CategoryModelPicker(
                title = "Модель для пакетных задач",
                current = state.media.batchModel,
                models = state.catalog.filter { it.isBatch && ModelCategory.TEXT in it.categories },
                onSelect = { controller.assignModel(it, ModelCategory.TEXT) }
            )
            FilledTonalButton(
                onClick = { batchFilePicker.launch(arrayOf("text/*", "application/json", "application/xml", "text/csv", "text/markdown")) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text(if (batchFiles.isEmpty()) "Добавить текстовые файлы" else "Файлы к пакету: ${batchFiles.size}")
            }
            if (batchFiles.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Содержимое будет добавлено к каждой задаче",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { batchFiles.clear() }) { Text("Убрать") }
                }
            }
        }
'''
if text.count(model_anchor) != 1:
    raise SystemExit(f"OpenRouterHub.kt: batch model anchor count={text.count(model_anchor)}")
text = text.replace(model_anchor, model_with_files, 1)

text = text.replace(
    '            Text("Каждое поле — отдельный запрос. Никакие разделительные линии вводить не нужно.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)',
    '            Text("Каждое поле — отдельный запрос.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)',
    1,
)

old_submit = '''                    controller.submitBatch(raw)
                },'''
new_submit = '''                    controller.submitBatch(raw, batchFiles.toList())
                    batchFiles.clear()
                },'''
if old_submit not in text:
    raise SystemExit("OpenRouterHub.kt: submitBatch call not found")
text = text.replace(old_submit, new_submit, 1)

old_media_head = '''    var speechText by remember { mutableStateOf("") }
    var voice by remember(state.media.voice) { mutableStateOf(state.media.voice) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        videoRefs.clear(); videoRefs.addAll(uris.take(4))
    }
    val sttPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(controller::transcribe) }
    val context = LocalContext.current
'''
new_media_head = '''    var speechText by remember { mutableStateOf("") }
    var voice by remember(state.media.voice) { mutableStateOf(state.media.voice) }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        videoRefs.clear(); videoRefs.addAll(uris.take(4))
    }
    val sttPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(controller::transcribe) }
    val speechTextPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { controller.loadTextFileForInput(it) { loaded -> speechText = loaded } }
    }
    val context = LocalContext.current
'''
if old_media_head not in text:
    raise SystemExit("OpenRouterHub.kt: MediaPage header not found")
text = text.replace(old_media_head, new_media_head, 1)

speech_anchor = '''                OutlinedTextField(speechText, { speechText = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Текст для озвучивания") }, minLines = 3, maxLines = 8)
                Button(onClick = { controller.updateMedia(state.media.copy(voice = voice.trim())); controller.synthesize(speechText) }, enabled = state.media.speechModel.isNotBlank() && speechText.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) { Text("Создать аудио") }
'''
speech_with_file = '''                OutlinedTextField(speechText, { speechText = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Текст для озвучивания") }, minLines = 3, maxLines = 8)
                FilledTonalButton(
                    onClick = { speechTextPicker.launch(arrayOf("text/*", "application/json", "application/xml", "text/csv", "text/markdown")) },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                ) { Text("Загрузить текстовый файл") }
                Button(onClick = { controller.updateMedia(state.media.copy(voice = voice.trim())); controller.synthesize(speechText) }, enabled = state.media.speechModel.isNotBlank() && speechText.isNotBlank() && !state.loading, modifier = Modifier.fillMaxWidth().padding(top = 7.dp)) { Text("Создать аудио") }
'''
if speech_anchor not in text:
    raise SystemExit("OpenRouterHub.kt: speech field anchor not found")
text = text.replace(speech_anchor, speech_with_file, 1)
write(path, text)

# ---------------------------------------------------------------------------
# Controller: Batch files are inlined as text; TTS can import a text file.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHubController.kt"
text = read(path)

old_signature = '    fun submitBatch(raw: String) {\n        DiagnosticLog.action(context, "batch_submit", "inputChars=${raw.length}")'
new_signature = '    fun submitBatch(raw: String, fileUris: List<Uri> = emptyList()) {\n        DiagnosticLog.action(context, "batch_submit", "inputChars=${raw.length}; files=${fileUris.size}")'
if old_signature not in text:
    raise SystemExit("OpenRouterHubController.kt: submitBatch signature not found")
text = text.replace(old_signature, new_signature, 1)

old_batch_context = '''                val modelInfo = mutableState.value.catalog.firstOrNull { it.id == model }
                val attachments = buildPersistentAttachments(chat?.chatFiles.orEmpty(), project?.files.orEmpty(), modelInfo)
                val system = buildSystemPrompt(chat, project)
                val requests = prompts.mapIndexed { index, prompt ->
                    OpenRouterBatchClient.BatchRequest(
                        customId = "hub-${index + 1}-${UUID.randomUUID()}",
                        label = prompt.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "Задание ${index + 1}",
                        body = batchBuilder.build(
                            model = model,
                            history = chat?.messages.orEmpty(),
                            prompt = prompt,
                            attachments = attachments,
                            systemPrompt = system,
'''
new_batch_context = '''                val modelInfo = mutableState.value.catalog.firstOrNull { it.id == model }
                // OpenRouter Batch rejects ordinary file/image content parts. Selected text
                // files are therefore inserted into the prompt as plain text instead.
                val inlineFiles = withContext(Dispatchers.IO) { batchTextContext(fileUris.take(6)) }
                val system = buildSystemPrompt(chat, project)
                val requests = prompts.mapIndexed { index, prompt ->
                    val promptWithFiles = if (inlineFiles.isBlank()) prompt else "$prompt\\n\\n===== ПРИЛОЖЕННЫЕ ФАЙЛЫ =====\\n$inlineFiles"
                    OpenRouterBatchClient.BatchRequest(
                        customId = "hub-${index + 1}-${UUID.randomUUID()}",
                        label = prompt.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "Задание ${index + 1}",
                        body = batchBuilder.build(
                            model = model,
                            history = chat?.messages.orEmpty(),
                            prompt = promptWithFiles,
                            attachments = emptyList(),
                            systemPrompt = system,
'''
if old_batch_context not in text:
    raise SystemExit("OpenRouterHubController.kt: batch attachment block not found")
text = text.replace(old_batch_context, new_batch_context, 1)

old_batch_message = '                appendHubUserMessage(chat?.id, "[Batch: ${requests.size}]\\n$input")'
new_batch_message = '                appendHubUserMessage(chat?.id, "[Batch: ${requests.size}${if (fileUris.isNotEmpty()) " · файлов: ${fileUris.size}" else ""}]\\n$input")'
if old_batch_message not in text:
    raise SystemExit("OpenRouterHubController.kt: batch message anchor not found")
text = text.replace(old_batch_message, new_batch_message, 1)

synth_anchor = '''    fun synthesize(textRaw: String) {
'''
load_method = '''    fun loadTextFileForInput(uri: Uri, maxChars: Int = 60_000, onReady: (String) -> Unit) {
        scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, operation = "Читаю текстовый файл…", status = null)
            runCatching {
                withContext(Dispatchers.IO) {
                    val data = uriBytes(uri)
                    ensureTextFile(data)
                    val value = data.bytes.toString(Charsets.UTF_8).trim()
                    if (value.isBlank()) error("В выбранном файле нет текста")
                    if (value.length > maxChars) error("Текстовый файл слишком большой: максимум $maxChars символов для этого поля")
                    value
                }
            }.onSuccess { value ->
                onReady(value)
                mutableState.value = mutableState.value.copy(loading = false, operation = null, status = "Текст загружен из файла")
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(loading = false, operation = null, status = error.message ?: "Не удалось прочитать файл")
            }
        }
    }

''' + synth_anchor
if synth_anchor not in text:
    raise SystemExit("OpenRouterHubController.kt: synthesize anchor not found")
text = text.replace(synth_anchor, load_method, 1)

uri_anchor = '''    private data class UriData(val name: String, val mime: String, val bytes: ByteArray)

    private fun uriBytes(uri: Uri): UriData {
'''
helpers = '''    private fun ensureTextFile(data: UriData) {
        val mime = data.mime.lowercase()
        val lowerName = data.name.lowercase()
        val supported = mime.startsWith("text/") ||
            mime == "application/json" || mime == "application/xml" ||
            lowerName.endsWith(".txt") || lowerName.endsWith(".md") || lowerName.endsWith(".csv") ||
            lowerName.endsWith(".json") || lowerName.endsWith(".xml") || lowerName.endsWith(".yaml") || lowerName.endsWith(".yml")
        if (!supported) error("Этот режим принимает текстовые файлы: TXT, MD, CSV, JSON, XML или YAML")
    }

    private fun batchTextContext(uris: List<Uri>): String {
        if (uris.isEmpty()) return ""
        var totalChars = 0
        return buildString {
            uris.forEachIndexed { index, uri ->
                val data = uriBytes(uri)
                ensureTextFile(data)
                if (data.bytes.size > 512 * 1024) error("Файл ${data.name} слишком большой для Batch-вложения")
                val value = data.bytes.toString(Charsets.UTF_8).trim()
                totalChars += value.length
                if (totalChars > 120_000) error("Суммарный текст файлов слишком большой для Batch. Уменьшите объём материалов")
                if (index > 0) append("\\n\\n")
                append("===== ${data.name} =====\\n")
                append(value)
            }
        }
    }

''' + uri_anchor
if uri_anchor not in text:
    raise SystemExit("OpenRouterHubController.kt: UriData anchor not found")
text = text.replace(uri_anchor, helpers, 1)
write(path, text)

# ---------------------------------------------------------------------------
# Guide: mention new file affordances without turning it into API jargon.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/ayuemin/ymnik/help/UmnikUsageGuide.kt"
text = read(path)
text = text.replace(
    "На экране будут отдельные поля **Задача 1**, **Задача 2** и так далее. Нажимайте **+ Добавить задачу**. Никакие линии-разделители вводить вручную не нужно.",
    "На экране будут отдельные поля **Задача 1**, **Задача 2** и так далее. Нажимайте **+ Добавить задачу**. При необходимости добавьте текстовые файлы — Umnik передаст их содержание каждой задаче.",
    1,
)
text = text.replace(
    "3. Введите текст.\n4. Нажмите **Создать аудио**.",
    "3. Введите текст или нажмите **Загрузить текстовый файл**.\n4. Нажмите **Создать аудио**.",
    1,
)
write(path, text)

print("Umnik v1.10.1 polish migration applied")
