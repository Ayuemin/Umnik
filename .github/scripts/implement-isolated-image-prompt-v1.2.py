from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    i = text.find(start)
    if i < 0:
        raise RuntimeError(f"{label}: start marker not found")
    j = text.find(end, i + len(start))
    if j < 0:
        raise RuntimeError(f"{label}: end marker not found")
    return text[:i] + replacement + text[j:]


# -----------------------------------------------------------------------------
# Models.kt: mark image-generation turns so UI retry never resends them as text.
# -----------------------------------------------------------------------------
path = "app/src/main/java/com/ayuemin/ymnik/model/Models.kt"
s = read(path)
s = replace_once(
    s,
    '''    val attachmentNames: List<String> = emptyList(),\n    val generatedFiles: List<GeneratedFile> = emptyList(),\n    val timestamp: Long = System.currentTimeMillis()\n''',
    '''    val attachmentNames: List<String> = emptyList(),\n    val generatedFiles: List<GeneratedFile> = emptyList(),\n    val imageGeneration: Boolean = false,\n    val timestamp: Long = System.currentTimeMillis()\n''',
    "ChatMessage imageGeneration flag",
)
write(path, s)


# -----------------------------------------------------------------------------
# ChatViewModel.kt: keep chats in text mode and add one-shot isolated image send.
# -----------------------------------------------------------------------------
path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
s = read(path)

s = replace_once(
    s,
    '''            mode = initialChat.mode ?: runCatching {\n                ChatMode.valueOf(prefs.getString("chat_mode", ChatMode.TEXT.name) ?: ChatMode.TEXT.name)\n            }.getOrDefault(ChatMode.TEXT),\n''',
    '''            mode = ChatMode.TEXT,\n''',
    "initial chat mode normalization",
)

s = replace_once(
    s,
    '''                mode = if (chat.mode == ChatMode.IMAGE && profile.type != ProviderType.OPENROUTER) ChatMode.TEXT else chat.mode,\n''',
    '''                mode = ChatMode.TEXT,\n''',
    "connection switch chat mode",
)

s = replace_once(
    s,
    '''            mode = if (profile.type == ProviderType.OPENROUTER) _state.value.mode else ChatMode.TEXT,\n''',
    '''            mode = ChatMode.TEXT,\n''',
    "connection switch state mode",
)

s = replace_once(
    s,
    '''            mode = _state.value.mode,\n            connectionProfileId = _state.value.activeConnectionProfileId\n''',
    '''            mode = ChatMode.TEXT,\n            connectionProfileId = _state.value.activeConnectionProfileId\n''',
    "new chat text mode",
)

s = replace_once(
    s,
    '''            mode = source.mode ?: _state.value.mode,\n            connectionProfileId = source.connectionProfileId ?: _state.value.activeConnectionProfileId,\n''',
    '''            mode = ChatMode.TEXT,\n            connectionProfileId = source.connectionProfileId ?: _state.value.activeConnectionProfileId,\n''',
    "branch text mode",
)

s = replace_once(
    s,
    '''            .putString("chat_mode", (branch.mode ?: _state.value.mode).name)\n''',
    '''            .putString("chat_mode", ChatMode.TEXT.name)\n''',
    "branch pref text mode",
)

s = replace_once(
    s,
    '''            mode = branch.mode ?: _state.value.mode,\n            currentChatTextModel = branch.textModelOverride,\n''',
    '''            mode = ChatMode.TEXT,\n            currentChatTextModel = branch.textModelOverride,\n''',
    "branch state text mode",
)

old_switch = '''        val migrated = profile.id != requestedProfileId || original.connectionProfileId == null\n        val chat = if (migrated) original.copy(\n            connectionProfileId = profile.id,\n            textModelOverride = if (profile.id == requestedProfileId) original.textModelOverride else null,\n            updatedAt = System.currentTimeMillis()\n        ) else original\n        val chats = if (migrated) _state.value.chats.map { if (it.id == id) chat else it } else _state.value.chats\n        if (migrated) chatsRepository.save(chats)\n        val openRouter = openRouterProfile()\n        val canImage = openRouter.id !in _state.value.disabledConnectionIds && isProfileConfigured(openRouter)\n        val nextMode = if ((chat.mode ?: ChatMode.TEXT) == ChatMode.IMAGE && !canImage) ChatMode.TEXT else (chat.mode ?: ChatMode.TEXT)\n'''
new_switch = '''        val migrated = profile.id != requestedProfileId || original.connectionProfileId == null || original.mode == ChatMode.IMAGE\n        val chat = if (migrated) original.copy(\n            connectionProfileId = profile.id,\n            textModelOverride = if (profile.id == requestedProfileId) original.textModelOverride else null,\n            mode = ChatMode.TEXT,\n            updatedAt = System.currentTimeMillis()\n        ) else original\n        val chats = if (migrated) _state.value.chats.map { if (it.id == id) chat else it } else _state.value.chats\n        if (migrated) chatsRepository.save(chats)\n        val nextMode = ChatMode.TEXT\n'''
s = replace_once(s, old_switch, new_switch, "switchChat image mode migration")

# Dedicated validation for explicit image references. Missing metadata is treated
# permissively until OpenRouter's image-model catalog finishes loading.
marker = '''    private fun attachmentAllowed(attachment: PendingAttachment): Pair<Boolean, String?> {\n'''
insert = '''    private fun imageAttachmentAllowed(attachment: PendingAttachment): Pair<Boolean, String?> {\n        if (!attachment.mimeType.startsWith("image/")) {\n            return false to "Для генерации изображения можно добавить только изображение-референс"\n        }\n        val info = currentImageModelInfo()\n        return if (info == null || info.accepts("image")) true to null\n        else false to "Выбранная модель изображений не принимает изображения-референсы"\n    }\n\n'''
s = replace_once(s, marker, insert + marker, "image attachment validator")

# Attachment APIs receive an explicit intent instead of consulting global mode.
s = replace_once(
    s,
    '''    fun addAttachment(uri: Uri) {\n        runCatching { api.attachmentFromUri(uri) }\n            .onSuccess { attachment ->\n                if (attachment.size > 25L * 1024 * 1024) {\n                    _state.value = _state.value.copy(status = "Ограничение Umnik сейчас 25 МБ на один файл")\n                } else {\n                    val (allowed, reason) = attachmentAllowed(attachment)\n                    if (!allowed) {\n                        _state.value = _state.value.copy(status = reason)\n                    } else if (shouldPersistInChat(attachment)) {\n                        persistChatAttachment(attachment)\n                    } else {\n                        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)\n                    }\n                }\n            }\n            .onFailure { _state.value = _state.value.copy(status = it.message) }\n    }\n\n    fun addCameraAttachment(uri: Uri, localPath: String) {\n        runCatching { api.attachmentFromUri(uri).copy(localPath = localPath) }\n            .onSuccess { attachment ->\n                if (attachment.size > 25L * 1024 * 1024) {\n                    File(localPath).delete()\n                    _state.value = _state.value.copy(status = "Фото превышает ограничение 25 МБ")\n                } else {\n                    val (allowed, reason) = attachmentAllowed(attachment)\n                    if (!allowed) {\n                        File(localPath).delete()\n                        _state.value = _state.value.copy(status = reason)\n                    } else {\n                        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)\n                    }\n                }\n            }\n            .onFailure {\n                File(localPath).delete()\n                _state.value = _state.value.copy(status = it.message ?: "Не удалось добавить фото")\n            }\n    }\n''',
    '''    fun addAttachment(uri: Uri, forImageGeneration: Boolean = false) {\n        runCatching { api.attachmentFromUri(uri) }\n            .onSuccess { attachment ->\n                if (attachment.size > 25L * 1024 * 1024) {\n                    _state.value = _state.value.copy(status = "Ограничение Umnik сейчас 25 МБ на один файл")\n                } else {\n                    val (allowed, reason) = if (forImageGeneration) imageAttachmentAllowed(attachment) else attachmentAllowed(attachment)\n                    if (!allowed) {\n                        _state.value = _state.value.copy(status = reason)\n                    } else if (!forImageGeneration && shouldPersistInChat(attachment)) {\n                        persistChatAttachment(attachment)\n                    } else {\n                        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)\n                    }\n                }\n            }\n            .onFailure { _state.value = _state.value.copy(status = it.message) }\n    }\n\n    fun addCameraAttachment(uri: Uri, localPath: String, forImageGeneration: Boolean = false) {\n        runCatching { api.attachmentFromUri(uri).copy(localPath = localPath) }\n            .onSuccess { attachment ->\n                if (attachment.size > 25L * 1024 * 1024) {\n                    File(localPath).delete()\n                    _state.value = _state.value.copy(status = "Фото превышает ограничение 25 МБ")\n                } else {\n                    val (allowed, reason) = if (forImageGeneration) imageAttachmentAllowed(attachment) else attachmentAllowed(attachment)\n                    if (!allowed) {\n                        File(localPath).delete()\n                        _state.value = _state.value.copy(status = reason)\n                    } else {\n                        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)\n                    }\n                }\n            }\n            .onFailure {\n                File(localPath).delete()\n                _state.value = _state.value.copy(status = it.message ?: "Не удалось добавить фото")\n            }\n    }\n''',
    "attachment APIs",
)

# One-shot entry point used by the composer. It validates OpenRouter but does not
# modify ChatMode or the text model selected for the chat.
insert_before_send = '''    fun prepareImageGeneration(): Boolean {\n        if (_state.value.isLoading) return false\n        val openRouter = openRouterProfile()\n        if (openRouter.id in _state.value.disabledConnectionIds) {\n            _state.value = _state.value.copy(status = "Для генерации изображений включите подключение OpenRouter")\n            return false\n        }\n        if (!isProfileConfigured(openRouter)) {\n            _state.value = _state.value.copy(status = connectionSetupMessage(openRouter))\n            return false\n        }\n        if (_state.value.availableImageModels.isEmpty()) refreshModels(ChatMode.IMAGE)\n        return true\n    }\n\n'''
s = replace_once(s, '''    fun send(text: String) {\n''', insert_before_send + '''    fun send(text: String) {\n''', "prepare image generation")

# Add isolated image request beside regular send. The network call deliberately
# receives no chat history, skills, project/chat prompts, user profile, or stored
# chat/project files. Only this prompt and explicitly pending image refs are sent.
image_send = '''\n    fun sendImagePrompt(text: String): Boolean {\n        if (_state.value.isLoading) return false\n        val profile = openRouterProfile()\n        if (profile.id in _state.value.disabledConnectionIds) {\n            _state.value = _state.value.copy(status = "Для генерации изображений включите подключение OpenRouter")\n            return false\n        }\n        if (!isProfileConfigured(profile)) {\n            _state.value = _state.value.copy(status = connectionSetupMessage(profile))\n            return false\n        }\n\n        val clean = text.trim()\n        val pending = _state.value.pendingAttachments\n        if (clean.isBlank() && pending.isEmpty()) return false\n\n        val invalidPending = pending.firstOrNull { !imageAttachmentAllowed(it).first }\n        if (invalidPending != null) {\n            _state.value = _state.value.copy(\n                status = imageAttachmentAllowed(invalidPending).second ?: "Вложение не подходит для генерации изображения"\n            )\n            return false\n        }\n\n        val prompt = clean.ifBlank { "Создай вариант приложенного изображения" }\n        val chatId = _state.value.currentChatId\n        val before = _state.value.messages\n        val user = ChatMessage(\n            id = UUID.randomUUID().toString(),\n            role = "user",\n            text = prompt,\n            attachmentNames = pending.map { it.name }.distinct(),\n            imageGeneration = true\n        )\n        val nextMessages = before + user\n        val title = if (before.isEmpty()) makeChatTitle(prompt, pending.map { it.name }) else null\n        val nextChats = replaceChatMessages(_state.value.chats, chatId, nextMessages, title)\n        chatsRepository.save(nextChats)\n\n        _state.value = _state.value.copy(\n            messages = nextMessages,\n            chats = nextChats,\n            pendingAttachments = emptyList(),\n            isLoading = true,\n            requestActive = true,\n            busyLabel = "Генерирую изображение…",\n            status = null,\n            storageStats = storageRepository.stats()\n        )\n\n        val key = secrets.getProfileApiKey(profile.id).orEmpty()\n        val imageModel = _state.value.imageModel\n        val requestId = ++requestGeneration\n        activeRequestPending = pending\n\n        activeRequestJob = viewModelScope.launch {\n            val operation = runCatching {\n                api.generateImage(\n                    key = key,\n                    model = imageModel,\n                    prompt = prompt,\n                    attachments = pending,\n                    baseUrl = profile.baseUrl\n                )\n            }\n\n            if (requestId != requestGeneration) return@launch\n\n            operation.onSuccess { result ->\n                val assistant = ChatMessage(\n                    id = UUID.randomUUID().toString(),\n                    role = "assistant",\n                    text = result.text.ifBlank {\n                        if (result.files.isNotEmpty()) "Готово." else "Пустой ответ модели."\n                    },\n                    generatedFiles = result.files,\n                    imageGeneration = true\n                )\n                val messages = _state.value.messages + assistant\n                val chats = replaceChatMessages(_state.value.chats, chatId, messages, null)\n                chatsRepository.save(chats)\n                _state.value = _state.value.copy(\n                    messages = messages,\n                    chats = chats,\n                    isLoading = false,\n                    requestActive = false,\n                    busyLabel = null,\n                    storedFiles = storageRepository.list(),\n                    storageStats = storageRepository.stats()\n                )\n                playReadySound()\n            }.onFailure {\n                _state.value = _state.value.copy(\n                    isLoading = false,\n                    requestActive = false,\n                    busyLabel = null,\n                    status = it.message ?: "Ошибка генерации изображения"\n                )\n            }\n            cleanupTempAttachments(pending)\n            if (requestId == requestGeneration) {\n                activeRequestJob = null\n                activeRequestPending = emptyList()\n            }\n        }\n        return true\n    }\n'''
s = replace_once(s, '''\n    override fun onCleared() {\n''', image_send + '''\n    override fun onCleared() {\n''', "isolated image send")

write(path, s)


# -----------------------------------------------------------------------------
# YmnikApp.kt: local image-prompt intent + guidance banner + settings split.
# -----------------------------------------------------------------------------
path = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
s = read(path)

s = replace_once(
    s,
    '''    var actionsOpen by remember { mutableStateOf(false) }\n    var cameraTarget by remember { mutableStateOf<CameraTarget?>(null) }\n''',
    '''    var actionsOpen by remember { mutableStateOf(false) }\n    var imagePromptMode by remember(state.currentChatId) { mutableStateOf(false) }\n    var cameraForImageGeneration by remember { mutableStateOf(false) }\n    var cameraTarget by remember { mutableStateOf<CameraTarget?>(null) }\n''',
    "composer image prompt state",
)

s = replace_once(
    s,
    '''    val imageModelInfo = state.availableImageModels.firstOrNull { it.id == state.imageModel }\n    val cameraAvailable = when (state.mode) {\n        ChatMode.TEXT -> textModelInfo?.accepts("image") == true\n        ChatMode.IMAGE -> imageModelInfo?.accepts("image") == true\n    }\n    val reasoningAvailable = state.mode == ChatMode.TEXT && textModelInfo?.supportsReasoning == true &&\n''',
    '''    val imageModelInfo = state.availableImageModels.firstOrNull { it.id == state.imageModel }\n    val cameraAvailable = if (imagePromptMode) {\n        imageModelInfo?.accepts("image") != false\n    } else {\n        textModelInfo?.accepts("image") == true\n    }\n    val reasoningAvailable = !imagePromptMode && textModelInfo?.supportsReasoning == true &&\n''',
    "camera and reasoning availability",
)

s = replace_once(
    s,
    '''    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->\n        uris.forEach(vm::addAttachment)\n    }\n    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->\n        cameraTarget?.let { target ->\n            if (ok) vm.addCameraAttachment(target.uri, target.file.absolutePath) else target.file.delete()\n        }\n        cameraTarget = null\n    }\n''',
    '''    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->\n        uris.forEach { uri -> vm.addAttachment(uri, imagePromptMode) }\n    }\n    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->\n        cameraTarget?.let { target ->\n            if (ok) vm.addCameraAttachment(target.uri, target.file.absolutePath, cameraForImageGeneration) else target.file.delete()\n        }\n        cameraTarget = null\n        cameraForImageGeneration = false\n    }\n''',
    "composer attachment launchers",
)

s = replace_once(
    s,
    '''                    onRetry = if (\n                        message.role == "user" &&\n''',
    '''                    onRetry = if (\n                        !message.imageGeneration &&\n                        message.role == "user" &&\n''',
    "disable text retry for image prompts",
)

s = replace_once(
    s,
    '''        if (currentChatFiles.isNotEmpty() || state.pendingAttachments.isNotEmpty()) {\n''',
    '''        if ((!imagePromptMode && currentChatFiles.isNotEmpty()) || state.pendingAttachments.isNotEmpty()) {\n''',
    "hide persistent chat files during image prompt",
)

s = replace_once(
    s,
    '''                    items(currentChatFiles, key = { "chat-${it.id}" }) { file ->\n                        AssistChip(\n                            onClick = { vm.removeChatFile(file.id) },\n                            label = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },\n                            leadingIcon = {\n                                Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(18.dp))\n                            },\n                            trailingIcon = {\n                                Icon(Icons.Outlined.Close, contentDescription = "Убрать файл из контекста чата", modifier = Modifier.size(18.dp))\n                            }\n                        )\n                    }\n''',
    '''                    if (!imagePromptMode) {\n                        items(currentChatFiles, key = { "chat-${it.id}" }) { file ->\n                            AssistChip(\n                                onClick = { vm.removeChatFile(file.id) },\n                                label = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },\n                                leadingIcon = {\n                                    Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(18.dp))\n                                },\n                                trailingIcon = {\n                                    Icon(Icons.Outlined.Close, contentDescription = "Убрать файл из контекста чата", modifier = Modifier.size(18.dp))\n                                }\n                            )\n                        }\n                    }\n''',
    "persistent chat file chips",
)

composer_start = '''        Surface(\n            modifier = Modifier.imePadding(),\n            color = MaterialTheme.colorScheme.surface,\n            tonalElevation = 0.dp\n        ) {\n            OutlinedTextField(\n'''
composer_end = '''        }\n    }\n\n    if (actionsOpen) {\n'''
new_composer = '''        Surface(\n            modifier = Modifier.imePadding(),\n            color = MaterialTheme.colorScheme.surface,\n            tonalElevation = 0.dp\n        ) {\n            Column {\n                if (imagePromptMode) {\n                    Surface(\n                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),\n                        shape = RoundedCornerShape(16.dp),\n                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)\n                    ) {\n                        Row(\n                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),\n                            verticalAlignment = Alignment.CenterVertically\n                        ) {\n                            Icon(\n                                Icons.Outlined.Image,\n                                contentDescription = null,\n                                modifier = Modifier.size(22.dp),\n                                tint = MaterialTheme.colorScheme.primary\n                            )\n                            Spacer(Modifier.width(10.dp))\n                            Column(Modifier.weight(1f)) {\n                                Text("Генерация изображения", fontWeight = FontWeight.SemiBold)\n                                Text(\n                                    "Опишите задачу. Модель: ${state.imageModel.substringAfterLast('/').ifBlank { state.imageModel }}",\n                                    style = MaterialTheme.typography.bodySmall,\n                                    color = MaterialTheme.colorScheme.onSurfaceVariant,\n                                    maxLines = 2,\n                                    overflow = TextOverflow.Ellipsis\n                                )\n                            }\n                            IconButton(\n                                onClick = { imagePromptMode = false },\n                                enabled = !state.requestActive\n                            ) {\n                                Icon(Icons.Outlined.Close, contentDescription = "Отменить генерацию изображения")\n                            }\n                        }\n                    }\n                }\n\n                OutlinedTextField(\n                    value = text,\n                    onValueChange = { text = it },\n                    modifier = Modifier\n                        .fillMaxWidth()\n                        .padding(horizontal = 12.dp, vertical = 8.dp),\n                    leadingIcon = {\n                        Row(\n                            verticalAlignment = Alignment.CenterVertically,\n                            horizontalArrangement = Arrangement.spacedBy(1.dp)\n                        ) {\n                            IconButton(\n                                onClick = { actionsOpen = true },\n                                enabled = !state.isLoading,\n                                modifier = Modifier.size(40.dp)\n                            ) {\n                                Icon(\n                                    Icons.Outlined.Add,\n                                    contentDescription = "Добавить и инструменты",\n                                    tint = MaterialTheme.colorScheme.onSurfaceVariant\n                                )\n                            }\n                            if (!imagePromptMode && activeSkillCount > 0) {\n                                ComposerInlineIndicator(\n                                    icon = Icons.Outlined.Extension,\n                                    description = "Активные навыки: $activeSkillCount",\n                                    count = activeSkillCount\n                                )\n                            }\n                            if (!imagePromptMode && state.reasoningEnabled) {\n                                ComposerInlineIndicator(\n                                    icon = Icons.Outlined.Psychology,\n                                    description = "Размышление включено"\n                                )\n                            }\n                            if (!imagePromptMode && state.webSearchEnabled) {\n                                ComposerInlineIndicator(\n                                    icon = Icons.Outlined.Language,\n                                    description = "Поиск в сети включён"\n                                )\n                            }\n                        }\n                    },\n                    trailingIcon = {\n                        IconButton(\n                            onClick = {\n                                if (state.requestActive) {\n                                    vm.stopGeneration()\n                                } else if (imagePromptMode) {\n                                    if (vm.sendImagePrompt(text)) {\n                                        text = ""\n                                        imagePromptMode = false\n                                    }\n                                } else {\n                                    vm.send(text)\n                                    text = ""\n                                }\n                            },\n                            enabled = state.requestActive || (!state.isLoading && (\n                                text.isNotBlank() || state.pendingAttachments.isNotEmpty() || (!imagePromptMode && currentChatFiles.isNotEmpty())\n                            ))\n                        ) {\n                            if (state.requestActive) {\n                                WorkingStopIcon()\n                            } else {\n                                Icon(\n                                    Icons.Outlined.Send,\n                                    contentDescription = if (imagePromptMode) "Создать изображение" else "Отправить"\n                                )\n                            }\n                        }\n                    },\n                    placeholder = if (imagePromptMode) { { Text("Опишите изображение") } } else null,\n                    shape = RoundedCornerShape(28.dp),\n                    maxLines = 6\n                )\n            }\n        }\n    }\n\n    if (actionsOpen) {\n'''
s = replace_between(s, composer_start, composer_end, new_composer, "composer with image banner")

s = replace_once(
    s,
    '''                            val types = if (state.mode == ChatMode.IMAGE) arrayOf("image/*") else arrayOf("*/*")\n                            attach.launch(types)\n''',
    '''                            val types = if (imagePromptMode) arrayOf("image/*") else arrayOf("*/*")\n                            attach.launch(types)\n''',
    "file chooser image prompt types",
)

s = replace_once(
    s,
    '''                                .onSuccess { target ->\n                                    cameraTarget = target\n                                    camera.launch(target.uri)\n''',
    '''                                .onSuccess { target ->\n                                    cameraForImageGeneration = imagePromptMode\n                                    cameraTarget = target\n                                    camera.launch(target.uri)\n''',
    "camera image prompt intent",
)

s = replace_once(
    s,
    '''                    ComposerActionTile(\n                        icon = if (state.mode == ChatMode.TEXT) Icons.Outlined.Image else Icons.Outlined.TextFields,\n                        label = if (state.mode == ChatMode.TEXT) "Создать" else "Текст",\n                        enabled = !state.isLoading && (state.mode == ChatMode.IMAGE || openRouterAvailable),\n                        modifier = Modifier.weight(1f),\n                        onClick = {\n                            vm.setMode(if (state.mode == ChatMode.TEXT) ChatMode.IMAGE else ChatMode.TEXT)\n                            actionsOpen = false\n                        }\n                    )\n''',
    '''                    ComposerActionTile(\n                        icon = Icons.Outlined.Image,\n                        label = "Создать",\n                        enabled = !state.isLoading && openRouterAvailable,\n                        modifier = Modifier.weight(1f),\n                        onClick = {\n                            actionsOpen = false\n                            if (vm.prepareImageGeneration()) imagePromptMode = true\n                        }\n                    )\n''',
    "create image action tile",
)

s = replace_once(
    s,
    '''                if (state.mode == ChatMode.TEXT) {\n                    ComposerToolRow(\n''',
    '''                if (!imagePromptMode) {\n                    ComposerToolRow(\n''',
    "hide text tools during image prompt",
)

# Header is always the current text model; image generation is a one-shot action.
s = replace_once(
    s,
    '''    val activeTextModel = state.currentChatTextModel ?: state.textModel\n    val displayedModel = if (state.mode == ChatMode.TEXT) activeTextModel else state.imageModel\n    val shortModelName = displayedModel.substringAfter('/').ifBlank { displayedModel }\n''',
    '''    val activeTextModel = state.currentChatTextModel ?: state.textModel\n    val shortModelName = activeTextModel.substringAfter('/').ifBlank { activeTextModel }\n''',
    "header stays on text model",
)

s = replace_once(
    s,
    '''                    onClick = { if (state.mode == ChatMode.TEXT) quickModelsOpen = true },\n''',
    '''                    onClick = { quickModelsOpen = true },\n''',
    "header model selector always active",
)

s = replace_once(
    s,
    '''                    if (state.mode == ChatMode.TEXT) {\n                        Spacer(Modifier.width(3.dp))\n                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Выбрать модель", modifier = Modifier.size(22.dp))\n                    }\n''',
    '''                    Spacer(Modifier.width(3.dp))\n                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Выбрать модель", modifier = Modifier.size(22.dp))\n''',
    "header model arrow always visible",
)

# Settings: image model gets its own collapsible card.
s = replace_once(
    s,
    '''    var modelsExpanded by remember { mutableStateOf(false) }\n    var reasoningExpanded by remember { mutableStateOf(false) }\n''',
    '''    var modelsExpanded by remember { mutableStateOf(false) }\n    var imageModelsExpanded by remember { mutableStateOf(false) }\n    var reasoningExpanded by remember { mutableStateOf(false) }\n''',
    "image settings expanded state",
)

s = replace_once(
    s,
    '''                    Spacer(Modifier.height(7.dp))\n                    FilledTonalButton(onClick = { modelPicker = ChatMode.IMAGE }, modifier = Modifier.fillMaxWidth()) {\n                        Icon(Icons.Outlined.Image, contentDescription = null)\n                        Spacer(Modifier.width(8.dp))\n                        Column(Modifier.weight(1f)) {\n                            Text("Генерация изображений", fontWeight = FontWeight.Medium)\n                            Text(state.imageModel, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)\n                        }\n                    }\n                }\n            }\n\n            item {\n                ReasoningSettingsCard(\n''',
    '''                }\n            }\n\n            item {\n                ExpandableSettingsCard(\n                    title = "Генерация изображений",\n                    subtitle = state.imageModel.substringAfterLast('/').ifBlank { state.imageModel },\n                    icon = Icons.Outlined.Image,\n                    expanded = imageModelsExpanded,\n                    onToggle = { imageModelsExpanded = !imageModelsExpanded }\n                ) {\n                    Text(\n                        "Модель используется только для «+ → Создать». Обычная модель чата не меняется.",\n                        style = MaterialTheme.typography.bodySmall,\n                        color = MaterialTheme.colorScheme.onSurfaceVariant\n                    )\n                    Spacer(Modifier.height(9.dp))\n                    FilledTonalButton(\n                        onClick = { modelPicker = ChatMode.IMAGE },\n                        modifier = Modifier.fillMaxWidth()\n                    ) {\n                        Icon(Icons.Outlined.Image, contentDescription = null)\n                        Spacer(Modifier.width(8.dp))\n                        Column(Modifier.weight(1f)) {\n                            Text("Выбрать модель", fontWeight = FontWeight.Medium)\n                            Text(\n                                state.imageModel,\n                                style = MaterialTheme.typography.bodySmall,\n                                maxLines = 1,\n                                overflow = TextOverflow.Ellipsis\n                            )\n                        }\n                    }\n                }\n            }\n\n            item {\n                ReasoningSettingsCard(\n''',
    "separate image model settings card",
)

write(path, s)


# -----------------------------------------------------------------------------
# Version + changelog for the next test release.
# -----------------------------------------------------------------------------
path = "app/build.gradle.kts"
s = read(path)
s = replace_once(s, "// Umnik v1.2.0-beta.1", "// Umnik v1.2.0-beta.2", "version comment")
s = replace_once(s, "versionCode = 30", "versionCode = 31", "version code")
s = replace_once(s, 'versionName = "1.2.0-beta.1"', 'versionName = "1.2.0-beta.2"', "version name")
write(path, s)

path = "CHANGELOG.md"
s = read(path)
section = '''## Unreleased\n\n## v1.2.0-beta.2 - 2026-09-11\n\n- Генерация изображений больше не переключает весь чат в отдельный режим: «+ → Создать» подготавливает один изолированный запрос в текущем диалоге.\n- Над полем ввода появляется подсказка «Генерация изображения» с выбранной моделью; после отправки Umnik автоматически возвращается к обычному текстовому вводу.\n- Запрос к модели изображений не получает историю чата, навыки, мастер-промпты, постоянные файлы и другие текстовые контексты — только текущий промпт и явно приложенные изображения-референсы.\n- Выбор модели генерации изображений вынесен в отдельный сворачиваемый раздел настроек.\n- Верхний селектор модели всегда остаётся селектором текстовой модели.\n\n'''
s = replace_once(s, "## Unreleased\n\n", section, "beta.2 changelog")
write(path, s)

print("Isolated image prompt beta.2 changes applied successfully")
