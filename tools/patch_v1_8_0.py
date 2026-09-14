from pathlib import Path
import re


def load(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def save(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


def regex_once(text: str, pattern: str, repl: str, label: str, flags: int = 0) -> str:
    result, count = re.subn(pattern, lambda _: repl, text, count=1, flags=flags)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one regex match, got {count}")
    return result


# 1. Sidebar: keep keyboard protection, restore New chat next to Menu at the bottom.
path = "app/src/main/java/com/ayuemin/ymnik/ui/NavigationSidebar.kt"
text = load(path)
text = replace_once(
    text,
    '''                FilledTonalButton(
                    onClick = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        onNewChat()
                    },
                    enabled = !state.isLoading,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Outlined.AddComment, contentDescription = null, modifier = Modifier.size(21.dp))
                    Spacer(Modifier.width(7.dp))
                    Text("Новый чат")
                }

                HorizontalDivider()
''',
    '''                HorizontalDivider()
''',
    "remove top new chat",
)
text = replace_once(
    text,
    '''                ) {
                    Box(Modifier.weight(1f)) {
''',
    '''                ) {
                    TextButton(
                        onClick = {
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onNewChat()
                        },
                        enabled = !state.isLoading,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Outlined.AddComment, contentDescription = null, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Новый чат", maxLines = 1)
                    }
                    Box(Modifier.weight(1f)) {
''',
    "restore bottom new chat",
)
save(path, text)


# 2. ChatViewModel: OpenRouter-only UX, unlimited quick models, refresh external chat results,
#    no Batch variants as ordinary chat models, and allow clearing image model.
path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
text = load(path)
text = replace_once(
    text,
    '''    fun toggleQuickTextModelForConnection(profileId: String, model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        val stored = loadQuickTextModels(profileId)
        val alreadySelected = clean in stored
        if (!alreadySelected && totalStoredQuickModels(_state.value.connectionProfiles) >= 10) {
            _state.value = _state.value.copy(status = "Можно закрепить до 10 быстрых моделей")
            return
        }
        val nextForProfile = if (alreadySelected) stored.filterNot { it == clean } else stored + clean
        prefs.edit().putString(
            profilePrefKey("quick_text_models_json", profileId),
            gson.toJson(nextForProfile.distinct().take(10))
        ).apply()
        _state.value = _state.value.copy(
            quickTextModels = loadAllQuickTextModels(_state.value.connectionProfiles, _state.value.disabledConnectionIds),
            status = if (!alreadySelected) "Модель добавлена в быстрые · ${profile.name}" else "Модель убрана из быстрых"
        )
    }
''',
    '''    fun toggleQuickTextModelForConnection(profileId: String, model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
        if (clean.endsWith(":batch", ignoreCase = true)) {
            _state.value = _state.value.copy(status = "Batch-модель можно использовать только для пакетных задач")
            return
        }
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        val stored = loadQuickTextModels(profileId).filterNot { it.endsWith(":batch", ignoreCase = true) }
        val alreadySelected = clean in stored
        val nextForProfile = if (alreadySelected) stored.filterNot { it == clean } else stored + clean
        prefs.edit().putString(
            profilePrefKey("quick_text_models_json", profileId),
            gson.toJson(nextForProfile.distinct())
        ).apply()
        _state.value = _state.value.copy(
            quickTextModels = loadAllQuickTextModels(_state.value.connectionProfiles, _state.value.disabledConnectionIds),
            status = if (!alreadySelected) "Модель добавлена в быстрые · ${profile.name}" else "Модель убрана из быстрых"
        )
    }
''',
    "remove quick model limit",
)
text = replace_once(
    text,
    '''    ): List<String> = profiles
        .asSequence()
        .filter { it.id !in disabled }
        .flatMap { profile -> loadQuickTextModels(profile.id).asSequence().map { quickModelRef(profile.id, it) } }
        .distinct()
        .take(10)
        .toList()
''',
    '''    ): List<String> = profiles
        .asSequence()
        .filter { it.id !in disabled }
        .flatMap { profile -> loadQuickTextModels(profile.id).asSequence().map { quickModelRef(profile.id, it) } }
        .filterNot { it.substringAfter(QUICK_MODEL_SEPARATOR).endsWith(":batch", ignoreCase = true) }
        .distinct()
        .toList()
''',
    "load all quick models",
)
text = replace_once(
    text,
    '''    private fun loadDisabledConnectionIds(): Set<String> =
        prefs.getStringSet("disabled_connection_profiles", emptySet())?.toSet() ?: emptySet()
''',
    '''    private fun loadDisabledConnectionIds(): Set<String> = emptySet()
''',
    "always enable OpenRouter",
)
text = replace_once(
    text,
    '''        listOf(openRouter, nvidia) + remaining
    }.getOrElse { listOf(defaultOpenRouterProfile(), defaultNvidiaProfile()) }
''',
    '''        listOf(openRouter)
    }.getOrElse { listOf(defaultOpenRouterProfile()) }
''',
    "OpenRouter only profiles",
)
text = replace_once(
    text,
    '''    private fun loadTextModelForProfile(profile: ConnectionProfile): String {
        val fallback = if (profile.type == ProviderType.OPENROUTER) "openrouter/auto" else ""
        return prefs.getString(profilePrefKey("text_model", profile.id), fallback) ?: fallback
    }
''',
    '''    private fun loadTextModelForProfile(profile: ConnectionProfile): String {
        val fallback = if (profile.type == ProviderType.OPENROUTER) "openrouter/auto" else ""
        val stored = prefs.getString(profilePrefKey("text_model", profile.id), fallback)?.trim().orEmpty()
        val safe = if (stored.endsWith(":batch", ignoreCase = true)) stored.removeSuffix(":batch") else stored
        return safe.ifBlank { fallback }
    }
''',
    "sanitize default batch model",
)
text = replace_once(
    text,
    '''    fun selectDefaultTextModel(profileId: String, model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
''',
    '''    fun selectDefaultTextModel(profileId: String, model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
        if (clean.endsWith(":batch", ignoreCase = true)) {
            _state.value = _state.value.copy(status = "Batch-модель нельзя назначить обычному чату")
            return
        }
''',
    "reject batch default model",
)
text = replace_once(
    text,
    '''    private fun loadInitialChats(): List<ChatSession> {
        val existing = chatsRepository.list()
        if (existing.isNotEmpty()) return existing
''',
    '''    private fun loadInitialChats(): List<ChatSession> {
        val storedChats = chatsRepository.list()
        val existing = storedChats.map { chat ->
            val override = chat.textModelOverride
            if (override?.endsWith(":batch", ignoreCase = true) == true) {
                chat.copy(textModelOverride = override.removeSuffix(":batch"), mode = ChatMode.TEXT)
            } else chat
        }
        if (existing != storedChats && existing.isNotEmpty()) chatsRepository.save(existing)
        if (existing.isNotEmpty()) return existing
''',
    "migrate saved batch chats",
)
text = replace_once(
    text,
    '''        val original = _state.value.chats.firstOrNull { it.id == id } ?: return
''',
    '''        val refreshedChats = chatsRepository.list()
        val original = refreshedChats.firstOrNull { it.id == id }
            ?: _state.value.chats.firstOrNull { it.id == id }
            ?: return
''',
    "reload chat repository on switch",
)
text = replace_once(
    text,
    '''        val chats = if (migrated) _state.value.chats.map { if (it.id == id) chat else it } else _state.value.chats
''',
    '''        val baseChats = if (refreshedChats.isNotEmpty()) refreshedChats else _state.value.chats
        val chats = if (migrated) baseChats.map { if (it.id == id) chat else it } else baseChats
''',
    "use refreshed chat list",
)
text = replace_once(
    text,
    '''    private fun chooseImageModel(profile: ConnectionProfile, infos: List<ModelInfo>): String {
        var selected = loadImageModelForProfile(profile.id)
        if (infos.isNotEmpty() && infos.none { it.id == selected }) {
            selected = infos.first().id
            prefs.edit().putString(profilePrefKey("image_model", profile.id), selected).apply()
        }
        return selected
    }
''',
    '''    private fun chooseImageModel(profile: ConnectionProfile, infos: List<ModelInfo>): String {
        val key = profilePrefKey("image_model", profile.id)
        var selected = loadImageModelForProfile(profile.id)
        if (selected.isBlank() && prefs.contains(key)) return ""
        if (infos.isNotEmpty() && infos.none { it.id == selected }) {
            selected = infos.first().id
            prefs.edit().putString(key, selected).apply()
        }
        return selected
    }
''',
    "respect cleared image model",
)
text = replace_once(
    text,
    '''    fun setImageAspectRatio(value: String?) {
''',
    '''    fun clearImageModel() {
        val profileId = _state.value.imageConnectionProfileId
        prefs.edit().putString(profilePrefKey("image_model", profileId), "").apply()
        _state.value = _state.value.copy(
            imageModel = "",
            imageAspectRatio = null,
            imageResolution = null,
            status = "Модель изображений снята. Выберите новую в каталоге OpenRouter."
        )
    }

    fun setImageAspectRatio(value: String?) {
''',
    "clear image model",
)
save(path, text)


# 3. Main UI: compact + menu, safer camera, centralized model choices, inline file playback/opening.
path = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
text = load(path)
text = replace_once(text, "import android.graphics.BitmapFactory\n", "import android.graphics.BitmapFactory\nimport android.media.MediaPlayer\n", "MediaPlayer import")
text = replace_once(
    text,
    '''    val cameraAvailable = if (imagePromptMode) {
        imageProfile.type == ProviderType.OPENROUTER && imageModelInfo?.accepts("image") != false
    } else {
        textModelInfo?.accepts("image") == true
    }
''',
    '''    val cameraAvailable = if (imagePromptMode) {
        state.imageModel.isNotBlank() && imageProfile.type == ProviderType.OPENROUTER && imageModelInfo?.accepts("image") != false
    } else {
        !activeTextModel.endsWith(":batch", ignoreCase = true) && textModelInfo?.accepts("image") == true
    }
''',
    "camera capability guard",
)
text = replace_once(text, 'label = "Речь → текст",', 'label = "В текст",', "compact STT label")
text = replace_once(text, "modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp),\n                verticalArrangement = Arrangement.spacedBy(14.dp)", "modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 18.dp),\n                verticalArrangement = Arrangement.spacedBy(10.dp)", "compact sheet spacing")
text = replace_once(text, "horizontalArrangement = Arrangement.spacedBy(10.dp)", "horizontalArrangement = Arrangement.spacedBy(8.dp)", "first compact row spacing")
# The sheet has three action rows; compact the next two occurrences as well.
text = text.replace("horizontalArrangement = Arrangement.spacedBy(10.dp)", "horizontalArrangement = Arrangement.spacedBy(8.dp)", 2)
text = replace_once(
    text,
    '''        modifier = modifier.height(96.dp),
        shape = RoundedCornerShape(18.dp)
''',
    '''        modifier = modifier.height(80.dp),
        shape = RoundedCornerShape(16.dp)
''',
    "compact action tile card",
)
text = replace_once(
    text,
    '''            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
''',
    '''            modifier = Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(23.dp))
            Spacer(Modifier.height(5.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis
            )
''',
    "compact tile content",
)
text = replace_once(text, "enabled = !state.isLoading && imageConnectionAvailable,", "enabled = !state.isLoading && imageConnectionAvailable && state.imageModel.isNotBlank(),", "disable create without image model")

# Model settings no longer open separate duplicated pickers.
text = replace_once(text, "FilledTonalButton(onClick = { modelPicker = ChatMode.TEXT }, modifier = Modifier.fillMaxWidth())", "FilledTonalButton(onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub(\"models-settings\") }, modifier = Modifier.fillMaxWidth())", "route default model to catalog")
text = replace_once(text, "FilledTonalButton(onClick = { quickModelsSettingsOpen = true }, modifier = Modifier.fillMaxWidth())", "FilledTonalButton(onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub(\"models-settings\") }, modifier = Modifier.fillMaxWidth())", "route quick models to catalog")
text = replace_once(text, "onClick = { modelPicker = ChatMode.IMAGE },", "onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub(\"models-settings\") },", "route image model to catalog")
text = replace_once(text, 'Text("Текстовая по умолчанию", fontWeight = FontWeight.Medium)', 'Text("Чат по умолчанию", fontWeight = FontWeight.Medium)', "default model label")
text = replace_once(
    text,
    '''                            Text(
                                if (state.quickTextModels.isEmpty()) "Только модель по умолчанию" else "Добавлено: ${state.quickTextModels.size}",
                                style = MaterialTheme.typography.bodySmall
                            )
''',
    '''                            Text(
                                if (state.quickTextModels.isEmpty()) "Не выбраны" else "Выбрано: ${state.quickTextModels.size} · нажмите для каталога",
                                style = MaterialTheme.typography.bodySmall
                            )
''',
    "quick models summary",
)
# Show removable quick choices directly in Settings.
text = replace_once(
    text,
    '''                    Spacer(Modifier.height(7.dp))
                    FilledTonalButton(
                        onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("models-settings") },
''',
    '''                    if (state.quickTextModels.isNotEmpty()) {
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
''',
    "selected quick rows",
)
text = replace_once(
    text,
    '''                    Text(
                        "Модель используется только для «+ → Создать». Обычная модель чата не меняется.",
''',
    '''                    Text(
                        "Выбор модели выполняется в общем каталоге OpenRouter. Здесь показана текущая модель для «+ → Создать».",
''',
    "image setting explanation",
)
# Add an explicit clear action after the image-model catalog button.
text = replace_once(
    text,
    '''                        Column(Modifier.weight(1f)) {
                            Text("Модель изображений", fontWeight = FontWeight.Medium)
                            Text(state.imageModel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
''',
    '''                        Column(Modifier.weight(1f)) {
                            Text("Модель изображений", fontWeight = FontWeight.Medium)
                            Text(state.imageModel.ifBlank { "Не выбрана" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (state.imageModel.isNotBlank()) {
                        TextButton(onClick = { vm.clearImageModel() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Снять выбор модели изображений")
                        }
                    }
''',
    "clear image model setting",
)
# OpenRouter-only connection wording and remove add-provider entry.
text = replace_once(text, 'title = "Подключения",', 'title = "OpenRouter",', "OpenRouter settings title")
text = replace_once(text, 'subtitle = "Включено: $enabledCount из ${state.connectionProfiles.size}",', 'subtitle = "API-ключ и соединение",', "OpenRouter settings subtitle")
text = regex_once(
    text,
    r'\n\s*Spacer\(Modifier\.height\(8\.dp\)\)\n\s*FilledTonalButton\(\n\s*onClick = \{\n\s*val id = vm\.addCompatibleProfile\(\)\n\s*editingProfileId = id\n\s*connectionAdvancedExpanded = true\n\s*connectionKey = ""\n\s*connectionImageKey = ""\n\s*\},\n\s*modifier = Modifier\.fillMaxWidth\(\)\n\s*\) \{\n\s*Icon\(Icons\.Outlined\.Add, contentDescription = null\)\n\s*Spacer\(Modifier\.width\(6\.dp\)\)\n\s*Text\("Добавить подключение"\)\n\s*\}\n',
    "\n",
    "remove add provider button",
)
text = replace_once(text, '"Закрепите до 10 моделей из разных подключений. В чате Umnik сам выберет нужное подключение при нажатии на модель."', '"Быстрые модели выбираются в общем каталоге OpenRouter. Ограничения по количеству больше нет."', "legacy quick dialog text")

# Generated files stay usable directly from the chat: audio player + open button for other media.
text = replace_once(
    text,
    '''private fun GeneratedFileCard(file: GeneratedFile, onSave: (GeneratedFile) -> Unit) {
    val context = LocalContext.current
    val isImage = file.mimeType.startsWith("image/")
''',
    '''private fun GeneratedFileCard(file: GeneratedFile, onSave: (GeneratedFile) -> Unit) {
    val context = LocalContext.current
    val isImage = file.mimeType.startsWith("image/")
    val isAudio = file.mimeType.startsWith("audio/")
    val isVideo = file.mimeType.startsWith("video/")
''',
    "generated file media flags",
)
# Insert media controls before the existing download/share row.
text = replace_once(
    text,
    '''    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalButton(
            onClick = { onSave(file) },
''',
    '''    if (isAudio) {
        GeneratedAudioPlayer(file)
        Spacer(Modifier.height(8.dp))
    } else if (isVideo) {
        FilledTonalButton(
            onClick = { openGeneratedFile(context, file) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("Открыть видео")
        }
        Spacer(Modifier.height(8.dp))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledTonalButton(
            onClick = { onSave(file) },
''',
    "inline generated media controls",
)
text = replace_once(
    text,
    '''private fun shareGeneratedFile(context: Context, file: GeneratedFile) {
''',
    '''@Composable
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
''',
    "audio player helper",
)
save(path, text)


# 4. OpenRouter controller: Batch is never a normal chat model, support role reset.
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHubController.kt"
text = load(path)
text = replace_once(
    text,
    '''    fun useAsTextModel(model: ModelInfo) {
        if (ModelCategory.TEXT !in model.categories) {
            mutableState.value = mutableState.value.copy(status = "Эта модель не является текстовой")
            return
        }
        val profile = openRouterProfile() ?: return
        viewModel.selectDefaultTextModel(profile.id, model.id)
        val media = mutableState.value.media.let { if (model.isBatch) it.copy(batchModel = model.id) else it }
        if (media != mutableState.value.media) {
            featurePrefs.saveMedia(media)
            mutableState.value = mutableState.value.copy(media = media)
        }
        mutableState.value = mutableState.value.copy(status = "${model.id} выбрана для обычного чата")
    }
''',
    '''    fun useAsTextModel(model: ModelInfo) {
        if (ModelCategory.TEXT !in model.categories) {
            mutableState.value = mutableState.value.copy(status = "Эта модель не является текстовой")
            return
        }
        if (model.isBatch) {
            val media = mutableState.value.media.copy(batchModel = model.id)
            featurePrefs.saveMedia(media)
            mutableState.value = mutableState.value.copy(media = media, status = "${model.id} выбрана только для пакетных задач")
            return
        }
        val profile = openRouterProfile() ?: return
        viewModel.selectDefaultTextModel(profile.id, model.id)
        mutableState.value = mutableState.value.copy(status = "${model.id} выбрана для обычного чата")
    }
''',
    "Batch not normal chat",
)
text = replace_once(
    text,
    '''    fun toggleQuickTextModel(model: ModelInfo) {
        if (ModelCategory.TEXT !in model.categories) return
        val profile = openRouterProfile() ?: return
''',
    '''    fun toggleQuickTextModel(model: ModelInfo) {
        if (ModelCategory.TEXT !in model.categories) return
        if (model.isBatch) {
            mutableState.value = mutableState.value.copy(status = "Batch-модель нельзя добавить в быстрые")
            return
        }
        val profile = openRouterProfile() ?: return
''',
    "Batch not quick",
)
text = replace_once(
    text,
    '''    fun submitBatch(raw: String) {
''',
    '''    fun clearAssignedModel(category: ModelCategory) {
        when (category) {
            ModelCategory.TEXT -> {
                val profile = openRouterProfile() ?: return
                viewModel.selectDefaultTextModel(profile.id, "openrouter/auto")
                mutableState.value = mutableState.value.copy(status = "Модель чата сброшена на OpenRouter Auto")
            }
            ModelCategory.IMAGE -> {
                viewModel.clearImageModel()
                mutableState.value = mutableState.value.copy(status = "Модель изображений снята")
            }
            ModelCategory.VIDEO -> {
                val media = mutableState.value.media.copy(videoModel = "")
                featurePrefs.saveMedia(media); mutableState.value = mutableState.value.copy(media = media, status = "Модель видео снята")
            }
            ModelCategory.SPEECH, ModelCategory.AUDIO -> {
                val media = mutableState.value.media.copy(speechModel = "")
                featurePrefs.saveMedia(media); mutableState.value = mutableState.value.copy(media = media, status = "Модель озвучивания снята")
            }
            ModelCategory.TRANSCRIPTION -> {
                val media = mutableState.value.media.copy(transcriptionModel = "")
                featurePrefs.saveMedia(media); mutableState.value = mutableState.value.copy(media = media, status = "Модель распознавания снята")
            }
            ModelCategory.EMBEDDINGS -> {
                val rag = mutableState.value.rag.copy(embeddingModel = "")
                featurePrefs.saveRag(rag); mutableState.value = mutableState.value.copy(rag = rag, status = "Embedding-модель снята")
            }
            ModelCategory.RERANK -> {
                val rag = mutableState.value.rag.copy(rerankModel = "")
                featurePrefs.saveRag(rag); mutableState.value = mutableState.value.copy(rag = rag, status = "Rerank-модель снята")
            }
        }
    }

    fun clearBatchModel() {
        val media = mutableState.value.media.copy(batchModel = "")
        featurePrefs.saveMedia(media)
        mutableState.value = mutableState.value.copy(media = media, status = "Batch-модель снята")
    }

    fun submitBatch(raw: String) {
''',
    "role clear actions",
)
save(path, text)


# 5. OpenRouter model catalog: selected first, filters collapse while scrolling, richer model menu.
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHub.kt"
text = load(path)
text = replace_once(text, "import androidx.compose.foundation.lazy.items\n", "import androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.lazy.rememberLazyListState\n", "hub lazy list import")
text = replace_once(text, "import com.ayuemin.ymnik.model.VideoJobStatus\n", "import com.ayuemin.ymnik.model.UiState\nimport com.ayuemin.ymnik.model.VideoJobStatus\n", "hub UiState import")
text = replace_once(text, "HubPage.MODELS -> ModelsPage(state, controller)", "HubPage.MODELS -> ModelsPage(state, controller, appState)", "hub model page state")
# Dialog already has app state at root only; collect it locally.
text = replace_once(
    text,
    '''    val state by controller.state.collectAsState()
    var page by remember(initialPage) { mutableStateOf(initialPage) }
''',
    '''    val state by controller.state.collectAsState()
    val appState by viewModel.state.collectAsState()
    var page by remember(initialPage) { mutableStateOf(initialPage) }
''',
    "hub dialog app state",
)
models_page = r'''@Composable
private fun ModelsPage(state: OpenRouterHubState, controller: OpenRouterHubController, appState: UiState) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<ModelCategory?>(null) }
    var variant by remember { mutableStateOf<ModelVariant?>(null) }
    var price by remember { mutableStateOf(ModelPriceFilter.ALL) }
    var capabilities by remember { mutableStateOf(ModelCapabilityFilter()) }
    var filtersExpanded by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val availableCategories = remember(state.catalog) {
        ModelCategory.entries.filter { candidate -> state.catalog.any { candidate in it.categories } }
    }
    val variantOrder = remember {
        listOf(ModelVariant.BATCH, ModelVariant.FREE, ModelVariant.THINKING, ModelVariant.EXTENDED, ModelVariant.ONLINE, ModelVariant.NITRO, ModelVariant.FLOOR)
    }
    val availableVariants = remember(state.catalog) {
        variantOrder.filter { candidate -> state.catalog.any { candidate in it.variants } }
    }
    val selectedIds = remember(
        appState.textModel,
        appState.currentChatTextModel,
        appState.quickTextModels,
        appState.imageModel,
        state.media,
        state.rag
    ) {
        buildSet {
            add(appState.textModel)
            appState.currentChatTextModel?.let(::add)
            appState.quickTextModels.forEach { add(it.substringAfter('\u001F')) }
            add(appState.imageModel)
            add(state.media.batchModel)
            add(state.media.videoModel)
            add(state.media.speechModel)
            add(state.media.transcriptionModel)
            add(state.rag.embeddingModel)
            add(state.rag.rerankModel)
        }.filter(String::isNotBlank).toSet()
    }
    val filtered = remember(state.catalog, query, category, variant, price, capabilities, selectedIds) {
        ModelCatalogFilter.apply(state.catalog, query, category, variant, price, capabilities, limit = 700)
            .sortedWith(compareByDescending<ModelInfo> { it.id in selectedIds }.thenBy { it.id })
    }

    LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) {
        if (filtersExpanded && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 48)) {
            filtersExpanded = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Поиск по OpenRouter") }
            )
            TextButton(onClick = { filtersExpanded = !filtersExpanded }) {
                Text(if (filtersExpanded) "Свернуть" else "Фильтры")
            }
            IconButton(onClick = { controller.refreshCatalog(forceMessage = true) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Обновить каталог")
            }
        }
        if (filtersExpanded) {
            Text("Категории", modifier = Modifier.padding(start = 14.dp), style = MaterialTheme.typography.labelMedium)
            LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = category == null, onClick = { category = null }, label = { Text("Все") }) }
                items(availableCategories) { item ->
                    FilterChip(selected = category == item, onClick = { category = item }, label = { Text(categoryLabel(item)) })
                }
            }
            Text("Варианты", modifier = Modifier.padding(start = 14.dp, top = 3.dp), style = MaterialTheme.typography.labelMedium)
            LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = variant == null, onClick = { variant = null }, label = { Text("Все") }) }
                items(availableVariants) { item ->
                    FilterChip(selected = variant == item, onClick = { variant = item }, label = { Text(variantLabel(item)) })
                }
            }
            val priceOptions = when (category) {
                ModelCategory.TEXT, ModelCategory.IMAGE -> ModelPriceFilter.entries.toList()
                else -> listOf(ModelPriceFilter.ALL, ModelPriceFilter.FREE)
            }
            Text(priceSectionLabel(category), modifier = Modifier.padding(start = 14.dp, top = 3.dp), style = MaterialTheme.typography.labelMedium)
            LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(priceOptions) { item ->
                    FilterChip(selected = price == item, onClick = { price = item }, label = { Text(priceFilterLabel(item, category)) })
                }
            }
            LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { CapabilityChip("Vision", capabilities.imageInput) { capabilities = capabilities.copy(imageInput = !capabilities.imageInput) } }
                item { CapabilityChip("Audio", capabilities.audioInput) { capabilities = capabilities.copy(audioInput = !capabilities.audioInput) } }
                item { CapabilityChip("Video", capabilities.videoInput) { capabilities = capabilities.copy(videoInput = !capabilities.videoInput) } }
                item { CapabilityChip("Reasoning", capabilities.reasoning) { capabilities = capabilities.copy(reasoning = !capabilities.reasoning) } }
                item { CapabilityChip("Tools", capabilities.tools) { capabilities = capabilities.copy(tools = !capabilities.tools) } }
            }
        }
        Text(
            "Показано ${filtered.size} из ${state.catalog.size} · выбранные модели сверху",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (filtered.isEmpty()) {
                item { Text(if (state.loading) "Каталог загружается…" else "По фильтрам моделей нет", modifier = Modifier.padding(16.dp)) }
            }
            items(filtered, key = { it.id }) { model -> ModelCatalogCard(model, controller, appState, state) }
        }
    }
}

@Composable
private fun CapabilityChip'''
text = regex_once(
    text,
    r'@Composable\nprivate fun ModelsPage\(state: OpenRouterHubState, controller: OpenRouterHubController\) \{.*?\n\}\n\n@Composable\nprivate fun CapabilityChip',
    models_page,
    "rewrite model catalog layout",
    re.S,
)
text = replace_once(
    text,
    "private fun ModelCatalogCard(model: ModelInfo, controller: OpenRouterHubController) {",
    "private fun ModelCatalogCard(model: ModelInfo, controller: OpenRouterHubController, appState: UiState, hubState: OpenRouterHubState) {",
    "model card signature",
)
old_menu = '''                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (ModelCategory.TEXT in model.categories) {
                            DropdownMenuItem(
                                text = { Text("Выбрать для чата") },
                                onClick = { menuOpen = false; controller.useAsTextModel(model) }
                            )
                            DropdownMenuItem(
                                text = { Text("Добавить / убрать из быстрых") },
                                onClick = { menuOpen = false; controller.toggleQuickTextModel(model) }
                            )
                        }
                        if (ModelCategory.IMAGE in model.categories) {
                            DropdownMenuItem(
                                text = { Text("Выбрать для изображений") },
                                onClick = { menuOpen = false; controller.useAsImageModel(model) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Копировать ID модели") },
                            onClick = { menuOpen = false; copyToClipboard(context, model.id) }
                        )
                    }
'''
new_menu = '''                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (ModelCategory.TEXT in model.categories && !model.isBatch) {
                            DropdownMenuItem(
                                text = { Text("Выбрать для чата") },
                                onClick = { menuOpen = false; controller.useAsTextModel(model) }
                            )
                            DropdownMenuItem(
                                text = { Text("Добавить / убрать из быстрых") },
                                onClick = { menuOpen = false; controller.toggleQuickTextModel(model) }
                            )
                            if (appState.textModel == model.id) {
                                DropdownMenuItem(
                                    text = { Text("Сбросить чат на OpenRouter Auto") },
                                    onClick = { menuOpen = false; controller.clearAssignedModel(ModelCategory.TEXT) }
                                )
                            }
                        }
                        if (model.isBatch) {
                            DropdownMenuItem(
                                text = { Text("Выбрать для пакетных задач") },
                                onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.TEXT) }
                            )
                            if (hubState.media.batchModel == model.id) {
                                DropdownMenuItem(text = { Text("Снять с пакетных задач") }, onClick = { menuOpen = false; controller.clearBatchModel() })
                            }
                        }
                        if (ModelCategory.IMAGE in model.categories) {
                            DropdownMenuItem(
                                text = { Text("Выбрать для создания изображений") },
                                onClick = { menuOpen = false; controller.useAsImageModel(model) }
                            )
                            if (appState.imageModel == model.id) {
                                DropdownMenuItem(text = { Text("Снять с изображений") }, onClick = { menuOpen = false; controller.clearAssignedModel(ModelCategory.IMAGE) })
                            }
                        }
                        if (ModelCategory.VIDEO in model.categories) {
                            DropdownMenuItem(text = { Text("Выбрать для видео") }, onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.VIDEO) })
                            if (hubState.media.videoModel == model.id) DropdownMenuItem(text = { Text("Снять с видео") }, onClick = { menuOpen = false; controller.clearAssignedModel(ModelCategory.VIDEO) })
                        }
                        if (ModelCategory.SPEECH in model.categories || ModelCategory.AUDIO in model.categories) {
                            DropdownMenuItem(text = { Text("Выбрать для озвучивания") }, onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.SPEECH) })
                            if (hubState.media.speechModel == model.id) DropdownMenuItem(text = { Text("Снять с озвучивания") }, onClick = { menuOpen = false; controller.clearAssignedModel(ModelCategory.SPEECH) })
                        }
                        if (ModelCategory.TRANSCRIPTION in model.categories) {
                            DropdownMenuItem(text = { Text("Выбрать для распознавания") }, onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.TRANSCRIPTION) })
                            if (hubState.media.transcriptionModel == model.id) DropdownMenuItem(text = { Text("Снять с распознавания") }, onClick = { menuOpen = false; controller.clearAssignedModel(ModelCategory.TRANSCRIPTION) })
                        }
                        if (ModelCategory.EMBEDDINGS in model.categories) {
                            DropdownMenuItem(text = { Text("Выбрать для поиска по документам") }, onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.EMBEDDINGS) })
                            if (hubState.rag.embeddingModel == model.id) DropdownMenuItem(text = { Text("Снять с поиска по документам") }, onClick = { menuOpen = false; controller.clearAssignedModel(ModelCategory.EMBEDDINGS) })
                        }
                        if (ModelCategory.RERANK in model.categories) {
                            DropdownMenuItem(text = { Text("Выбрать для точной сортировки") }, onClick = { menuOpen = false; controller.assignModel(model, ModelCategory.RERANK) })
                            if (hubState.rag.rerankModel == model.id) DropdownMenuItem(text = { Text("Снять с точной сортировки") }, onClick = { menuOpen = false; controller.clearAssignedModel(ModelCategory.RERANK) })
                        }
                        DropdownMenuItem(
                            text = { Text("Копировать ID модели") },
                            onClick = { menuOpen = false; copyToClipboard(context, model.id) }
                        )
                    }
'''
text = replace_once(text, old_menu, new_menu, "expanded model menu")
text = replace_once(text, '                if (model.isBatch) SmallAssignButton("Batch в чате") { controller.useAsTextModel(model) }\n', '', "remove Batch in chat action")
save(path, text)


# 6. Finished async video must also wake the chat UI.
path = "app/src/main/java/com/ayuemin/ymnik/OpenRouterBackgroundWorker.kt"
text = load(path)
text = replace_once(
    text,
    '''                videos.upsert(current)
            }.onFailure { retry = true }
''',
    '''                videos.upsert(current)
                AsyncJobEvents.notifyChanged()
            }.onFailure { retry = true }
''',
    "notify completed video",
)
save(path, text)


# 7. Theme: derive Material 3 container roles from the selected accent instead of hardcoded beige/gray.
path = "app/src/main/java/com/ayuemin/ymnik/ui/UmnikTheme.kt"
text = load(path)
light = '''private fun baseLight(
    primary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    tertiary: Color
): ColorScheme = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = Color.White,
    secondaryContainer = lerp(primary, Color.White, 0.86f),
    onSecondaryContainer = lerp(primary, Color.Black, 0.76f),
    tertiary = tertiary,
    onTertiary = Color.White,
    tertiaryContainer = lerp(tertiary, Color.White, 0.84f),
    onTertiaryContainer = lerp(tertiary, Color.Black, 0.76f),
    background = Color(0xFFF9F9FA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF9F9FA),
    onSurface = Color(0xFF1A1C1E),
    surfaceTint = primary,
    surfaceVariant = lerp(primary, Color.White, 0.90f),
    onSurfaceVariant = lerp(primary, Color.Black, 0.70f),
    outline = lerp(primary, Color.Gray, 0.68f),
    outlineVariant = lerp(primary, Color.White, 0.72f),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = lerp(primary, Color.White, 0.965f),
    surfaceContainer = lerp(primary, Color.White, 0.94f),
    surfaceContainerHigh = lerp(primary, Color.White, 0.91f),
    surfaceContainerHighest = lerp(primary, Color.White, 0.87f)
)
'''
dark = '''private fun baseDark(
    primary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    tertiary: Color
): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = Color(0xFF17181A),
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = Color(0xFF17181A),
    secondaryContainer = lerp(primary, Color.Black, 0.62f),
    onSecondaryContainer = lerp(primary, Color.White, 0.84f),
    tertiary = tertiary,
    onTertiary = Color(0xFF17181A),
    tertiaryContainer = lerp(tertiary, Color.Black, 0.60f),
    onTertiaryContainer = lerp(tertiary, Color.White, 0.84f),
    background = Color(0xFF111315),
    onBackground = Color(0xFFE3E3E6),
    surface = Color(0xFF111315),
    onSurface = Color(0xFFE3E3E6),
    surfaceTint = primary,
    surfaceVariant = lerp(primary, Color.Black, 0.72f),
    onSurfaceVariant = lerp(primary, Color.White, 0.73f),
    outline = lerp(primary, Color.Gray, 0.58f),
    outlineVariant = lerp(primary, Color.Black, 0.50f),
    surfaceContainerLowest = lerp(primary, Color.Black, 0.91f),
    surfaceContainerLow = lerp(primary, Color.Black, 0.86f),
    surfaceContainer = lerp(primary, Color.Black, 0.82f),
    surfaceContainerHigh = lerp(primary, Color.Black, 0.77f),
    surfaceContainerHighest = lerp(primary, Color.Black, 0.72f)
)
'''
text = regex_once(text, r'private fun baseLight\(.*?\n\)\n\nprivate fun baseDark', light + '\nprivate fun baseDark', "theme light palette", re.S)
text = regex_once(text, r'private fun baseDark\(.*?\n\)\n\nprivate fun customLight', dark + '\nprivate fun customLight', "theme dark palette", re.S)
save(path, text)


# 8. Version and release notes.
path = "app/build.gradle.kts"
text = load(path)
text = replace_once(text, "// Umnik v1.7.0", "// Umnik v1.8.0", "version comment")
text = replace_once(text, "versionCode = 70", "versionCode = 80", "version code")
text = replace_once(text, 'versionName = "1.7.0"', 'versionName = "1.8.0"', "version name")
save(path, text)

path = "CHANGELOG.md"
text = load(path)
notes = '''## Unreleased

## v1.8.0 - 2026-09-14

- «Новый чат» возвращён вниз боковой панели рядом с «Меню», при этом защита от перекрывающей клавиатуры сохранена.
- Исправлена синхронизация результатов OpenRouter с исходным чатом: озвучка, распознавание, Shell, Batch и видео больше не требуют искать готовый результат в хранилище. Аудио можно воспроизводить прямо в сообщении, видео открывается из чата.
- Снят лимит на 10 быстрых моделей. Выбранные модели поднимаются наверх общего каталога, быстрые модели можно убрать прямо из настроек.
- Выбор моделей централизован в «Каталог и модели OpenRouter»: обычный чат, изображения, Batch, видео, озвучивание, распознавание, Embeddings и Rerank назначаются из одного места.
- Пользовательский интерфейс подключений упрощён до OpenRouter; дополнительные поставщики больше не показываются и не загружаются как рабочие подключения.
- При прокрутке каталога блок фильтров автоматически сворачивается, освобождая экран списку моделей. Меню «⋮» модели расширено назначениями для всех поддерживаемых ролей и снятием выбора.
- Меню `+` стало компактнее, подпись распознавания речи сокращена до «В текст».
- Batch-модели больше нельзя назначить обычному или быстрому чату; старые сохранённые `:batch`-выборы автоматически переводятся на базовую модель. Кнопка камеры доступна только когда текущая модель действительно принимает изображения.
- Цветовая схема теперь окрашивает Material 3 контейнеры, карточки, tonal-кнопки и чипы согласованно с выбранным акцентом.
- Версия: 1.8.0 / versionCode 80.

'''
text = replace_once(text, "## Unreleased\n\n", notes, "changelog")
save(path, text)

# Remove the one-time migration helper from the released source tree.
Path("tools/patch_v1_8_0.py").unlink(missing_ok=True)
