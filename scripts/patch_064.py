from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    if old not in text:
        raise RuntimeError(f"Pattern not found in {path}: {old[:240]!r}")
    write(path, text.replace(old, new, 1))


models = "app/src/main/java/com/ayuemin/ymnik/model/Models.kt"
replace_once(
    models,
    '''data class PendingAttachment(\n    val uri: String,\n    val name: String,\n    val mimeType: String,\n    val size: Long,\n    val localPath: String? = null\n)\n\ndata class GeneratedFile(''',
    '''data class PendingAttachment(\n    val uri: String,\n    val name: String,\n    val mimeType: String,\n    val size: Long,\n    val localPath: String? = null\n)\n\ndata class ChatFile(\n    val id: String,\n    val name: String,\n    val mimeType: String,\n    val localPath: String,\n    val size: Long,\n    val addedAt: Long = System.currentTimeMillis()\n)\n\ndata class GeneratedFile('''
)
replace_once(
    models,
    '''    val mode: ChatMode? = null,\n    val textModelOverride: String? = null,\n    val isFavorite: Boolean = false,''',
    '''    val mode: ChatMode? = null,\n    val textModelOverride: String? = null,\n    val chatFiles: List<ChatFile>? = null,\n    val isFavorite: Boolean = false,'''
)

write(
    "app/src/main/java/com/ayuemin/ymnik/data/ChatFileRepository.kt",
    '''package com.ayuemin.ymnik.data\n\nimport android.content.Context\nimport android.net.Uri\nimport com.ayuemin.ymnik.model.ChatFile\nimport com.ayuemin.ymnik.model.PendingAttachment\nimport java.io.File\nimport java.util.UUID\n\nclass ChatFileRepository(private val context: Context) {\n    private val root = File(context.filesDir, "chat_files").apply { mkdirs() }\n\n    fun importFile(chatId: String, attachment: PendingAttachment): ChatFile {\n        val dir = File(root, safeSegment(chatId)).apply { mkdirs() }\n        val id = UUID.randomUUID().toString()\n        val target = File(dir, "${id}_${safeName(attachment.name)}")\n        runCatching {\n            val input = attachment.localPath\n                ?.takeIf { it.isNotBlank() }\n                ?.let { File(it).inputStream() }\n                ?: context.contentResolver.openInputStream(Uri.parse(attachment.uri))\n                ?: error("Не удалось открыть ${attachment.name}")\n            input.use { source -> target.outputStream().use { output -> source.copyTo(output) } }\n        }.onFailure {\n            target.delete()\n            throw it\n        }\n        return ChatFile(\n            id = id,\n            name = attachment.name,\n            mimeType = attachment.mimeType,\n            localPath = target.absolutePath,\n            size = target.length()\n        )\n    }\n\n    fun delete(file: ChatFile): Boolean = runCatching {\n        val target = File(file.localPath).canonicalFile\n        val canonicalRoot = root.canonicalFile\n        if (!target.path.startsWith(canonicalRoot.path + File.separator)) return false\n        target.delete()\n    }.getOrDefault(false)\n\n    fun deleteChat(chatId: String) {\n        File(root, safeSegment(chatId)).deleteRecursively()\n    }\n\n    private fun safeSegment(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)\n\n    private fun safeName(value: String): String = value\n        .substringAfterLast('/')\n        .substringAfterLast('\\\\')\n        .replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_")\n        .take(120)\n        .ifBlank { "file" }\n}\n'''
)

vm = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
replace_once(
    vm,
    '''import com.ayuemin.ymnik.data.ChatRepository\nimport com.ayuemin.ymnik.data.ProjectRepository''',
    '''import com.ayuemin.ymnik.data.ChatFileRepository\nimport com.ayuemin.ymnik.data.ChatRepository\nimport com.ayuemin.ymnik.data.ProjectRepository'''
)
replace_once(
    vm,
    '''import com.ayuemin.ymnik.model.ChatMessage\nimport com.ayuemin.ymnik.model.ChatMode''',
    '''import com.ayuemin.ymnik.model.ChatFile\nimport com.ayuemin.ymnik.model.ChatMessage\nimport com.ayuemin.ymnik.model.ChatMode'''
)
replace_once(
    vm,
    '''    private val skills = SkillRepository(context)\n    private val chatsRepository = ChatRepository(context)\n    private val projectsRepository = ProjectRepository(context)''',
    '''    private val skills = SkillRepository(context)\n    private val chatsRepository = ChatRepository(context)\n    private val chatFilesRepository = ChatFileRepository(context)\n    private val projectsRepository = ProjectRepository(context)'''
)
replace_once(
    vm,
    '''    fun deleteChat(id: String) {\n        cleanupTempAttachments(_state.value.pendingAttachments)\n        if (_state.value.isLoading) return\n        if (_state.value.chats.none { it.id == id }) return\n\n        var remaining = _state.value.chats.filterNot { it.id == id }''',
    '''    fun deleteChat(id: String) {\n        cleanupTempAttachments(_state.value.pendingAttachments)\n        if (_state.value.isLoading) return\n        if (_state.value.chats.none { it.id == id }) return\n\n        chatFilesRepository.deleteChat(id)\n        var remaining = _state.value.chats.filterNot { it.id == id }'''
)

old_add = '''    fun addAttachment(uri: Uri) {\n        runCatching { api.attachmentFromUri(uri) }\n            .onSuccess { attachment ->\n                if (attachment.size > 25L * 1024 * 1024) {\n                    _state.value = _state.value.copy(status = "Ограничение Umnik сейчас 25 МБ на один файл")\n                } else {\n                    val (allowed, reason) = attachmentAllowed(attachment)\n                    if (!allowed) _state.value = _state.value.copy(status = reason)\n                    else _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)\n                }\n            }\n            .onFailure { _state.value = _state.value.copy(status = it.message) }\n    }\n'''
new_add = '''    private fun shouldPersistInChat(attachment: PendingAttachment): Boolean {\n        if (_state.value.mode != ChatMode.TEXT) return false\n        val mime = attachment.mimeType.lowercase()\n        val name = attachment.name.lowercase()\n        val textLike = mime.startsWith("text/") || name.endsWith(".md") || name.endsWith(".json") ||\n            name.endsWith(".csv") || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".xml")\n        return textLike || mime == "application/pdf" || name.endsWith(".pdf")\n    }\n\n    private fun chatFileAsAttachment(file: ChatFile): PendingAttachment = PendingAttachment(\n        uri = "chat://${file.id}",\n        name = file.name,\n        mimeType = file.mimeType,\n        size = file.size,\n        localPath = file.localPath\n    )\n\n    private fun persistChatAttachment(attachment: PendingAttachment) {\n        val chatId = _state.value.currentChatId\n        val current = _state.value.chats.firstOrNull { it.id == chatId } ?: return\n        val duplicate = current.chatFiles.orEmpty().any {\n            it.name.equals(attachment.name, ignoreCase = true) &&\n                (attachment.size <= 0L || it.size == attachment.size)\n        }\n        if (duplicate) {\n            _state.value = _state.value.copy(status = "Файл «${attachment.name}» уже есть в этом чате")\n            return\n        }\n        runCatching { chatFilesRepository.importFile(chatId, attachment) }\n            .onSuccess { file ->\n                val chats = _state.value.chats.map { chat ->\n                    if (chat.id == chatId) chat.copy(\n                        chatFiles = chat.chatFiles.orEmpty() + file,\n                        updatedAt = System.currentTimeMillis()\n                    ) else chat\n                }\n                chatsRepository.save(chats)\n                _state.value = _state.value.copy(\n                    chats = chats,\n                    storedFiles = storageRepository.list(),\n                    storageStats = storageRepository.stats(),\n                    status = "Файл «${file.name}» закреплён за этим чатом"\n                )\n            }\n            .onFailure { _state.value = _state.value.copy(status = it.message ?: "Не удалось сохранить файл чата") }\n    }\n\n    fun removeChatFile(fileId: String) {\n        if (_state.value.isLoading) return\n        val chatId = _state.value.currentChatId\n        val current = _state.value.chats.firstOrNull { it.id == chatId } ?: return\n        val file = current.chatFiles.orEmpty().firstOrNull { it.id == fileId } ?: return\n        chatFilesRepository.delete(file)\n        val chats = _state.value.chats.map { chat ->\n            if (chat.id == chatId) chat.copy(\n                chatFiles = chat.chatFiles.orEmpty().filterNot { it.id == fileId },\n                updatedAt = System.currentTimeMillis()\n            ) else chat\n        }\n        chatsRepository.save(chats)\n        _state.value = _state.value.copy(\n            chats = chats,\n            storedFiles = storageRepository.list(),\n            storageStats = storageRepository.stats(),\n            status = "Файл «${file.name}» убран из контекста чата"\n        )\n    }\n\n    fun addAttachment(uri: Uri) {\n        runCatching { api.attachmentFromUri(uri) }\n            .onSuccess { attachment ->\n                if (attachment.size > 25L * 1024 * 1024) {\n                    _state.value = _state.value.copy(status = "Ограничение Umnik сейчас 25 МБ на один файл")\n                } else {\n                    val (allowed, reason) = attachmentAllowed(attachment)\n                    if (!allowed) {\n                        _state.value = _state.value.copy(status = reason)\n                    } else if (shouldPersistInChat(attachment)) {\n                        persistChatAttachment(attachment)\n                    } else {\n                        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)\n                    }\n                }\n            }\n            .onFailure { _state.value = _state.value.copy(status = it.message) }\n    }\n'''
replace_once(vm, old_add, new_add)

replace_once(
    vm,
    '''        val clean = text.trim()\n        val pending = _state.value.pendingAttachments\n        if (clean.isBlank() && pending.isEmpty()) return\n        if (_state.value.isLoading) return\n\n        val invalidPending = pending.firstOrNull { !attachmentAllowed(it).first }\n        if (invalidPending != null) {\n            _state.value = _state.value.copy(status = attachmentAllowed(invalidPending).second ?: "Вложение не поддерживается выбранной моделью")\n            return\n        }\n\n        val chatId = _state.value.currentChatId\n        val currentChat = _state.value.chats.firstOrNull { it.id == chatId }\n        val currentProject = currentChat?.projectId?.let { id -> _state.value.projects.firstOrNull { it.id == id } }''',
    '''        val clean = text.trim()\n        val pending = _state.value.pendingAttachments\n        if (_state.value.isLoading) return\n\n        val mode = _state.value.mode\n        val chatId = _state.value.currentChatId\n        val currentChat = _state.value.chats.firstOrNull { it.id == chatId }\n        val persistentChatFiles = if (mode == ChatMode.TEXT) {\n            currentChat?.chatFiles.orEmpty().map(::chatFileAsAttachment)\n        } else {\n            emptyList()\n        }\n        if (clean.isBlank() && pending.isEmpty() && persistentChatFiles.isEmpty()) return\n\n        val invalidPending = pending.firstOrNull { !attachmentAllowed(it).first }\n        if (invalidPending != null) {\n            _state.value = _state.value.copy(status = attachmentAllowed(invalidPending).second ?: "Вложение не поддерживается выбранной моделью")\n            return\n        }\n\n        val currentProject = currentChat?.projectId?.let { id -> _state.value.projects.firstOrNull { it.id == id } }'''
)
replace_once(
    vm,
    '''            text = clean.ifBlank {\n                if (_state.value.mode == ChatMode.IMAGE) "Создай вариант приложенного изображения" else "[Вложения]"\n            },\n            attachmentNames = pending.map { it.name }\n        )\n        val nextMessages = before + user\n        val title = if (before.isEmpty()) makeChatTitle(clean, pending.map { it.name }) else null''',
    '''            text = clean.ifBlank {\n                when {\n                    mode == ChatMode.IMAGE -> "Создай вариант приложенного изображения"\n                    persistentChatFiles.isNotEmpty() -> "[Файлы чата]"\n                    else -> "[Вложения]"\n                }\n            },\n            attachmentNames = pending.map { it.name }\n        )\n        val nextMessages = before + user\n        val titleAttachments = pending.map { it.name } + currentChat?.chatFiles.orEmpty().map { it.name }\n        val title = if (before.isEmpty()) makeChatTitle(clean, titleAttachments) else null'''
)
replace_once(vm, '''        val mode = _state.value.mode\n        val textModel = currentTextModelId()''', '''        val textModel = currentTextModelId()''')
replace_once(
    vm,
    '''                            pending + projectFiles,\n                            buildSystemPrompt(skillText, currentProject, currentChat, modelInfo?.supportsTools == true),''',
    '''                            (pending + persistentChatFiles.filter { attachmentAllowed(it).first } + projectFiles)\n                                .distinctBy { it.localPath ?: it.uri },\n                            buildSystemPrompt(skillText, currentProject, currentChat, modelInfo?.supportsTools == true),'''
)

replace_once(
    vm,
    '''    fun clearChat() {\n        cleanupTempAttachments(_state.value.pendingAttachments)\n        if (_state.value.isLoading) return\n        val chats = replaceChatMessages(_state.value.chats, _state.value.currentChatId, emptyList(), "Новый чат")\n        chatsRepository.save(chats)\n        _state.value = _state.value.copy(\n            messages = emptyList(),\n            chats = chats,\n            pendingAttachments = emptyList(),\n            storedFiles = storageRepository.list(),\n            storageStats = storageRepository.stats(),\n            status = "Чат очищен"\n        )\n    }\n''',
    '''    fun clearChat() {\n        cleanupTempAttachments(_state.value.pendingAttachments)\n        if (_state.value.isLoading) return\n        val chatId = _state.value.currentChatId\n        chatFilesRepository.deleteChat(chatId)\n        val now = System.currentTimeMillis()\n        val chats = _state.value.chats.map { chat ->\n            if (chat.id == chatId) chat.copy(\n                title = "Новый чат",\n                messages = emptyList(),\n                chatFiles = emptyList(),\n                updatedAt = now\n            ) else chat\n        }\n        chatsRepository.save(chats)\n        _state.value = _state.value.copy(\n            messages = emptyList(),\n            chats = chats,\n            pendingAttachments = emptyList(),\n            storedFiles = storageRepository.list(),\n            storageStats = storageRepository.stats(),\n            status = "Чат очищен вместе с его временными файлами"\n        )\n    }\n'''
)
replace_once(
    vm,
    '''        if (chat != null && (!chat.assignedRole.isNullOrBlank() || !chat.masterPrompt.isNullOrBlank())) {\n            appendLine("\\n===== НАСТРОЙКИ ЭТОГО ДИАЛОГА =====")\n            chat.assignedRole?.takeIf { it.isNotBlank() }?.let { appendLine("Роль диалога: $it") }\n            chat.masterPrompt?.takeIf { it.isNotBlank() }?.let {\n                appendLine("Мастер-инструкция диалога:")\n                appendLine(it)\n            }\n            appendLine("===== КОНЕЦ НАСТРОЕК ДИАЛОГА =====")\n        }''',
    '''        if (chat != null && (!chat.assignedRole.isNullOrBlank() || !chat.masterPrompt.isNullOrBlank())) {\n            appendLine("\\n===== НАСТРОЙКИ ЭТОГО ДИАЛОГА =====")\n            chat.assignedRole?.takeIf { it.isNotBlank() }?.let { appendLine("Роль диалога: $it") }\n            chat.masterPrompt?.takeIf { it.isNotBlank() }?.let {\n                appendLine("Мастер-инструкция диалога:")\n                appendLine(it)\n            }\n            appendLine("===== КОНЕЦ НАСТРОЕК ДИАЛОГА =====")\n        }\n        if (!chat?.chatFiles.isNullOrEmpty()) {\n            appendLine("Файлы этого диалога автоматически приложены к текущему запросу. Используй их как постоянный рабочий контекст этого чата.")\n        }'''
)

storage = "app/src/main/java/com/ayuemin/ymnik/data/StorageRepository.kt"
replace_once(
    storage,
    '''    private val projectsRoot = File(context.filesDir, "projects").apply { mkdirs() }\n    private val chatsFile = File(File(context.filesDir, "chats"), "chats.json")''',
    '''    private val projectsRoot = File(context.filesDir, "projects").apply { mkdirs() }\n    private val chatFilesRoot = File(context.filesDir, "chat_files").apply { mkdirs() }\n    private val chatsFile = File(File(context.filesDir, "chats"), "chats.json")'''
)
replace_once(
    storage,
    '''        collect(skillsRoot, "Навыки", false, items, skipName = "skills.json")\n        collect(projectsRoot, "Проекты", false, items, skipName = "projects.json")''',
    '''        collect(skillsRoot, "Навыки", false, items, skipName = "skills.json")\n        collect(projectsRoot, "Проекты", false, items, skipName = "projects.json")\n        collect(chatFilesRoot, "Файлы чатов", false, items)'''
)
replace_once(
    storage,
    '''        projectBytes = sizeOf(projectsRoot),\n        chatBytes = if (chatsFile.exists()) chatsFile.length() else 0L''',
    '''        projectBytes = sizeOf(projectsRoot),\n        chatBytes = (if (chatsFile.exists()) chatsFile.length() else 0L) + sizeOf(chatFilesRoot)'''
)

ui = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
replace_once(
    ui,
    '''    val reasoningAvailable = state.mode == ChatMode.TEXT && textModelInfo?.supportsReasoning == true &&\n        (textModelInfo.reasoningEfforts.isEmpty() || state.reasoningEffort.apiValue in textModelInfo.reasoningEfforts)\n\n    val attach = rememberLauncherForActivityResult''',
    '''    val reasoningAvailable = state.mode == ChatMode.TEXT && textModelInfo?.supportsReasoning == true &&\n        (textModelInfo.reasoningEfforts.isEmpty() || state.reasoningEffort.apiValue in textModelInfo.reasoningEfforts)\n    val currentChatFiles = state.chats.firstOrNull { it.id == state.currentChatId }?.chatFiles.orEmpty()\n\n    val attach = rememberLauncherForActivityResult'''
)
replace_once(
    ui,
    '''        if (state.pendingAttachments.isNotEmpty()) {\n            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {\n                LazyRow(\n                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),\n                    horizontalArrangement = Arrangement.spacedBy(6.dp)\n                ) {\n                    items(state.pendingAttachments, key = { it.uri }) { attachment ->\n                        AssistChip(\n                            onClick = { vm.removeAttachment(attachment.uri) },\n                            label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },\n                            leadingIcon = {\n                                Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))\n                            },\n                            trailingIcon = {\n                                Icon(Icons.Outlined.Close, contentDescription = "Убрать", modifier = Modifier.size(18.dp))\n                            }\n                        )\n                    }\n                }\n            }\n        }\n''',
    '''        if (currentChatFiles.isNotEmpty() || state.pendingAttachments.isNotEmpty()) {\n            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {\n                LazyRow(\n                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),\n                    horizontalArrangement = Arrangement.spacedBy(6.dp)\n                ) {\n                    items(currentChatFiles, key = { "chat-${it.id}" }) { file ->\n                        AssistChip(\n                            onClick = { vm.removeChatFile(file.id) },\n                            label = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },\n                            leadingIcon = {\n                                Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(18.dp))\n                            },\n                            trailingIcon = {\n                                Icon(Icons.Outlined.Close, contentDescription = "Убрать файл из контекста чата", modifier = Modifier.size(18.dp))\n                            }\n                        )\n                    }\n                    items(state.pendingAttachments, key = { "pending-${it.uri}" }) { attachment ->\n                        AssistChip(\n                            onClick = { vm.removeAttachment(attachment.uri) },\n                            label = { Text(attachment.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },\n                            leadingIcon = {\n                                Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))\n                            },\n                            trailingIcon = {\n                                Icon(Icons.Outlined.Close, contentDescription = "Убрать", modifier = Modifier.size(18.dp))\n                            }\n                        )\n                    }\n                }\n            }\n        }\n'''
)

gradle = "app/build.gradle.kts"
replace_once(gradle, "// Umnik v0.6.4 quick per-chat model switching", "// Umnik v0.6.5 chat-scoped persistent documents")
replace_once(gradle, 'versionCode = 13', 'versionCode = 14')
replace_once(gradle, 'versionName = "0.6.4"', 'versionName = "0.6.5"')

print("Umnik 0.6.5 chat-scoped files patch applied")
