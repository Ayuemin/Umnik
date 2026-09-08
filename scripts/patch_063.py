from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")

def replace_once(path, old, new):
    text = read(path)
    if old not in text:
        raise RuntimeError(f"Pattern not found in {path}: {old[:180]!r}")
    write(path, text.replace(old, new, 1))

# ---------------- Models ----------------
models_path = "app/src/main/java/com/ayuemin/ymnik/model/Models.kt"
replace_once(
    models_path,
    '''data class UserProfile(\n    val name: String = "",\n    val gender: String = "",\n    val age: String = "",\n    val occupation: String = "",\n    val note: String = ""\n) {\n    fun isEmpty(): Boolean = name.isBlank() && gender.isBlank() && age.isBlank() && occupation.isBlank() && note.isBlank()\n}\n\nenum class ReasoningEffort''',
    '''data class UserProfile(\n    val name: String = "",\n    val gender: String = "",\n    val age: String = "",\n    val occupation: String = "",\n    val note: String = ""\n) {\n    fun isEmpty(): Boolean = name.isBlank() && gender.isBlank() && age.isBlank() && occupation.isBlank() && note.isBlank()\n}\n\ndata class ModelInfo(\n    val id: String,\n    val inputModalities: Set<String> = setOf("text"),\n    val supportedParameters: Set<String> = emptySet(),\n    val reasoningEfforts: Set<String> = emptySet()\n) {\n    fun accepts(modality: String): Boolean = modality.lowercase() in inputModalities\n    val supportsReasoning: Boolean\n        get() = "reasoning" in supportedParameters || "reasoning_effort" in supportedParameters\n    val supportsReasoningEffort: Boolean\n        get() = "reasoning_effort" in supportedParameters\n    val supportsTools: Boolean\n        get() = "tools" in supportedParameters\n}\n\nenum class ReasoningEffort'''
)
replace_once(
    models_path,
    '''    val availableTextModels: List<String> = emptyList(),\n    val availableImageModels: List<String> = emptyList(),''',
    '''    val availableTextModels: List<ModelInfo> = emptyList(),\n    val availableImageModels: List<ModelInfo> = emptyList(),'''
)

# ---------------- OpenRouter client ----------------
client_path = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"
replace_once(
    client_path,
    '''import com.ayuemin.ymnik.model.GeneratedFile\nimport com.ayuemin.ymnik.model.PendingAttachment''',
    '''import com.ayuemin.ymnik.model.GeneratedFile\nimport com.ayuemin.ymnik.model.ModelInfo\nimport com.ayuemin.ymnik.model.PendingAttachment'''
)

client = read(client_path)
start = client.index('    suspend fun models(apiKey: String): List<String>')
end = client.index('    suspend fun chat(', start)
new_models_block = '''    suspend fun models(apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {\n        getModelInfos(apiKey, "https://openrouter.ai/api/v1/models")\n    }\n\n    suspend fun imageModels(apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {\n        getModelInfos(apiKey, "https://openrouter.ai/api/v1/images/models")\n    }\n\n    private fun getModelInfos(apiKey: String, url: String): List<ModelInfo> {\n        val request = Request.Builder()\n            .url(url)\n            .header("Authorization", "Bearer $apiKey")\n            .header("X-Title", "Umnik Android")\n            .get()\n            .build()\n        http.newCall(request).execute().use { response ->\n            val body = response.body?.string().orEmpty()\n            if (!response.isSuccessful) error(apiError(response.code, body))\n            val root = gson.fromJson(body, JsonObject::class.java)\n            return root.getAsJsonArray("data")\n                ?.mapNotNull { element ->\n                    val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null\n                    val id = item.get("id")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null\n                    val inputModalities = item.getAsJsonObject("architecture")\n                        ?.getAsJsonArray("input_modalities")\n                        ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString?.lowercase() }\n                        ?.toSet()\n                        .orEmpty()\n                        .ifEmpty { setOf("text") }\n                    val supportedParameters = when (val supported = item.get("supported_parameters")) {\n                        null -> emptySet()\n                        else -> when {\n                            supported.isJsonArray -> supported.asJsonArray\n                                .mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString?.lowercase() }\n                                .toSet()\n                            supported.isJsonObject -> supported.asJsonObject.keySet().map { it.lowercase() }.toSet()\n                            else -> emptySet()\n                        }\n                    }\n                    val reasoningEfforts = item.getAsJsonObject("reasoning")\n                        ?.getAsJsonArray("supported_efforts")\n                        ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString?.lowercase() }\n                        ?.toSet()\n                        .orEmpty()\n                    ModelInfo(id, inputModalities, supportedParameters, reasoningEfforts)\n                }\n                ?.distinctBy { it.id }\n                ?.sortedBy { it.id }\n                ?: emptyList()\n        }\n    }\n\n'''
client = client[:start] + new_models_block + client[end:]
write(client_path, client)

replace_once(
    client_path,
    '''        webSearchEnabled: Boolean = false,\n        reasoningEnabled: Boolean = false,\n        reasoningEffort: String = "medium"\n    ): Result = withContext(Dispatchers.IO) {''',
    '''        webSearchEnabled: Boolean = false,\n        reasoningEnabled: Boolean = false,\n        reasoningEffort: String? = "medium",\n        toolsEnabled: Boolean = true\n    ): Result = withContext(Dispatchers.IO) {'''
)
replace_once(
    client_path,
    '''                addProperty("max_tokens", 6000)\n                add("tools", tools())''',
    '''                addProperty("max_tokens", 6000)\n                if (toolsEnabled) add("tools", tools())'''
)
replace_once(
    client_path,
    '''                if (reasoningEnabled) {\n                    add("reasoning", JsonObject().apply {\n                        addProperty("enabled", true)\n                        addProperty("effort", reasoningEffort)\n                        addProperty("exclude", true)\n                    })\n                }''',
    '''                if (reasoningEnabled) {\n                    add("reasoning", JsonObject().apply {\n                        addProperty("enabled", true)\n                        reasoningEffort?.takeIf { it.isNotBlank() }?.let { addProperty("effort", it) }\n                        addProperty("exclude", true)\n                    })\n                }'''
)

replace_once(
    client_path,
    '''                attachment.mimeType.startsWith("image/") -> parts.add(JsonObject().apply {\n                    addProperty("type", "image_url")\n                    add("image_url", JsonObject().apply {\n                        addProperty("url", "data:${attachment.mimeType};base64,$b64")\n                    })\n                })\n                attachment.mimeType.startsWith("text/") ||''',
    '''                attachment.mimeType.startsWith("image/") -> parts.add(JsonObject().apply {\n                    addProperty("type", "image_url")\n                    add("image_url", JsonObject().apply {\n                        addProperty("url", "data:${attachment.mimeType};base64,$b64")\n                    })\n                })\n                attachment.mimeType.startsWith("audio/") -> parts.add(JsonObject().apply {\n                    addProperty("type", "input_audio")\n                    add("input_audio", JsonObject().apply {\n                        addProperty("data", b64)\n                        addProperty("format", audioFormat(attachment))\n                    })\n                })\n                attachment.mimeType.startsWith("video/") -> parts.add(JsonObject().apply {\n                    addProperty("type", "video_url")\n                    add("video_url", JsonObject().apply {\n                        addProperty("url", "data:${attachment.mimeType};base64,$b64")\n                    })\n                })\n                attachment.mimeType.startsWith("text/") ||'''
)
replace_once(
    client_path,
    '''    private fun safeName(value: String): String = value\n        .substringAfterLast('/')''',
    '''    private fun audioFormat(attachment: PendingAttachment): String {\n        val ext = attachment.name.substringAfterLast('.', "").lowercase()\n        if (ext in setOf("wav", "mp3", "flac", "m4a", "ogg", "webm", "aac")) return ext\n        return when (attachment.mimeType.lowercase()) {\n            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"\n            "audio/mpeg", "audio/mp3" -> "mp3"\n            "audio/flac", "audio/x-flac" -> "flac"\n            "audio/mp4", "audio/x-m4a" -> "m4a"\n            "audio/ogg" -> "ogg"\n            "audio/webm" -> "webm"\n            "audio/aac" -> "aac"\n            else -> error("Формат аудио ${attachment.name} не поддерживается")\n        }\n    }\n\n    private fun safeName(value: String): String = value\n        .substringAfterLast('/')'''
)

# ---------------- ViewModel ----------------
vm_path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
replace_once(
    vm_path,
    '''import com.ayuemin.ymnik.model.GeneratedFile\nimport com.ayuemin.ymnik.model.PendingAttachment''',
    '''import com.ayuemin.ymnik.model.GeneratedFile\nimport com.ayuemin.ymnik.model.ModelInfo\nimport com.ayuemin.ymnik.model.PendingAttachment'''
)
replace_once(
    vm_path,
    '''    val state: StateFlow<UiState> = _state.asStateFlow()\n\n    fun saveApiKey(apiKey: String?) {''',
    '''    val state: StateFlow<UiState> = _state.asStateFlow()\n\n    init {\n        if (!secrets.getApiKey().isNullOrBlank()) refreshModelCapabilities()\n    }\n\n    fun saveApiKey(apiKey: String?) {'''
)
replace_once(
    vm_path,
    '''        _state.value = _state.value.copy(\n            apiKeyConfigured = !secrets.getApiKey().isNullOrBlank(),\n            status = "Настройки сохранены"\n        )\n    }''',
    '''        _state.value = _state.value.copy(\n            apiKeyConfigured = !secrets.getApiKey().isNullOrBlank(),\n            status = "Настройки сохранены"\n        )\n        if (_state.value.apiKeyConfigured) refreshModelCapabilities()\n    }'''
)
replace_once(
    vm_path,
    '''            ChatMode.TEXT -> {\n                prefs.edit().putString("text_model", clean).apply()\n                _state.value = _state.value.copy(textModel = clean)\n            }''',
    '''            ChatMode.TEXT -> {\n                val info = _state.value.availableTextModels.firstOrNull { it.id == clean }\n                val keepReasoning = _state.value.reasoningEnabled && info?.supportsReasoning == true\n                prefs.edit()\n                    .putString("text_model", clean)\n                    .putBoolean("reasoning_enabled", keepReasoning)\n                    .apply()\n                _state.value = _state.value.copy(textModel = clean, reasoningEnabled = keepReasoning)\n            }'''
)
replace_once(
    vm_path,
    '''    fun setReasoningEnabled(enabled: Boolean) {\n        prefs.edit().putBoolean("reasoning_enabled", enabled).apply()\n        _state.value = _state.value.copy(reasoningEnabled = enabled)\n    }''',
    '''    fun setReasoningEnabled(enabled: Boolean) {\n        if (enabled) {\n            val info = currentTextModelInfo()\n            if (info?.supportsReasoning != true) {\n                _state.value = _state.value.copy(status = "Выбранная модель не поддерживает размышление")\n                return\n            }\n            if (info.reasoningEfforts.isNotEmpty() && _state.value.reasoningEffort.apiValue !in info.reasoningEfforts) {\n                _state.value = _state.value.copy(status = "Выбранная сила размышления не поддерживается этой моделью")\n                return\n            }\n        }\n        prefs.edit().putBoolean("reasoning_enabled", enabled).apply()\n        _state.value = _state.value.copy(reasoningEnabled = enabled)\n    }'''
)

# Replace refreshModels function block.
vm = read(vm_path)
start = vm.index('    fun refreshModels(mode: ChatMode) {')
end = vm.index('    fun addAttachment(uri: Uri) {', start)
new_refresh = '''    fun refreshModels(mode: ChatMode) {\n        val key = secrets.getApiKey()\n        if (key.isNullOrBlank()) {\n            _state.value = _state.value.copy(status = "Сначала сохраните API-ключ OpenRouter")\n            return\n        }\n        viewModelScope.launch {\n            _state.value = _state.value.copy(isLoading = true, busyLabel = "Загружаю модели…", status = null)\n            runCatching {\n                when (mode) {\n                    ChatMode.TEXT -> api.models(key)\n                    ChatMode.IMAGE -> api.imageModels(key)\n                }\n            }.onSuccess { infos ->\n                _state.value = when (mode) {\n                    ChatMode.TEXT -> {\n                        val current = infos.firstOrNull { it.id == _state.value.textModel }\n                        val keepReasoning = _state.value.reasoningEnabled && current?.supportsReasoning == true &&\n                            (current.reasoningEfforts.isEmpty() || _state.value.reasoningEffort.apiValue in current.reasoningEfforts)\n                        if (!keepReasoning && _state.value.reasoningEnabled) {\n                            prefs.edit().putBoolean("reasoning_enabled", false).apply()\n                        }\n                        _state.value.copy(\n                            availableTextModels = infos,\n                            reasoningEnabled = keepReasoning,\n                            isLoading = false,\n                            busyLabel = null\n                        )\n                    }\n                    ChatMode.IMAGE -> _state.value.copy(\n                        availableImageModels = infos,\n                        isLoading = false,\n                        busyLabel = null\n                    )\n                }\n            }.onFailure {\n                _state.value = _state.value.copy(\n                    isLoading = false,\n                    busyLabel = null,\n                    status = it.message ?: "Не удалось загрузить модели"\n                )\n            }\n        }\n    }\n\n    private fun refreshModelCapabilities() {\n        val key = secrets.getApiKey() ?: return\n        viewModelScope.launch {\n            val textInfos = runCatching { api.models(key) }.getOrNull()\n            val imageInfos = runCatching { api.imageModels(key) }.getOrNull()\n            var next = _state.value\n            if (textInfos != null) {\n                val current = textInfos.firstOrNull { it.id == next.textModel }\n                val keepReasoning = next.reasoningEnabled && current?.supportsReasoning == true &&\n                    (current.reasoningEfforts.isEmpty() || next.reasoningEffort.apiValue in current.reasoningEfforts)\n                if (!keepReasoning && next.reasoningEnabled) prefs.edit().putBoolean("reasoning_enabled", false).apply()\n                next = next.copy(availableTextModels = textInfos, reasoningEnabled = keepReasoning)\n            }\n            if (imageInfos != null) next = next.copy(availableImageModels = imageInfos)\n            _state.value = next\n        }\n    }\n\n    private fun currentTextModelInfo(): ModelInfo? =\n        _state.value.availableTextModels.firstOrNull { it.id == _state.value.textModel }\n\n    private fun currentImageModelInfo(): ModelInfo? =\n        _state.value.availableImageModels.firstOrNull { it.id == _state.value.imageModel }\n\n    private fun attachmentAllowed(attachment: PendingAttachment): Pair<Boolean, String?> {\n        if (_state.value.mode == ChatMode.IMAGE) {\n            if (!attachment.mimeType.startsWith("image/")) return false to "В режиме изображений можно добавлять только изображения-референсы"\n            val info = currentImageModelInfo()\n            return if (info?.accepts("image") == true) true to null\n            else false to "Выбранная модель изображений не принимает изображения-референсы"\n        }\n\n        val info = currentTextModelInfo()\n        val mime = attachment.mimeType.lowercase()\n        val name = attachment.name.lowercase()\n        val textLike = mime.startsWith("text/") || name.endsWith(".md") || name.endsWith(".json") ||\n            name.endsWith(".csv") || name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".xml")\n        if (textLike) return true to null\n        if (mime == "application/pdf" || name.endsWith(".pdf")) return true to null\n        if (mime.startsWith("image/")) return if (info?.accepts("image") == true) true to null else false to "Выбранная модель не принимает изображения"\n        if (mime.startsWith("audio/")) return if (info?.accepts("audio") == true) true to null else false to "Выбранная модель не принимает аудио"\n        if (mime.startsWith("video/")) return if (info?.accepts("video") == true) true to null else false to "Выбранная модель не принимает видео"\n        return if (info?.accepts("file") == true) true to null else false to "Выбранная модель не принимает этот тип файла"\n    }\n\n'''
vm = vm[:start] + new_refresh + vm[end:]
write(vm_path, vm)

replace_once(
    vm_path,
    '''    fun addAttachment(uri: Uri) {\n        runCatching { api.attachmentFromUri(uri) }\n            .onSuccess { attachment ->\n                if (attachment.size > 25L * 1024 * 1024) {\n                    _state.value = _state.value.copy(status = "Ограничение Umnik сейчас 25 МБ на один файл")\n                } else {\n                    _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)\n                }\n            }\n            .onFailure { _state.value = _state.value.copy(status = it.message) }\n    }''',
    '''    fun addAttachment(uri: Uri) {\n        runCatching { api.attachmentFromUri(uri) }\n            .onSuccess { attachment ->\n                if (attachment.size > 25L * 1024 * 1024) {\n                    _state.value = _state.value.copy(status = "Ограничение Umnik сейчас 25 МБ на один файл")\n                } else {\n                    val (allowed, reason) = attachmentAllowed(attachment)\n                    if (!allowed) _state.value = _state.value.copy(status = reason)\n                    else _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)\n                }\n            }\n            .onFailure { _state.value = _state.value.copy(status = it.message) }\n    }'''
)
replace_once(
    vm_path,
    '''            .onSuccess { attachment ->\n                if (attachment.size > 25L * 1024 * 1024) {\n                    File(localPath).delete()\n                    _state.value = _state.value.copy(status = "Фото превышает ограничение 25 МБ")\n                } else {\n                    _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)\n                }\n            }''',
    '''            .onSuccess { attachment ->\n                if (attachment.size > 25L * 1024 * 1024) {\n                    File(localPath).delete()\n                    _state.value = _state.value.copy(status = "Фото превышает ограничение 25 МБ")\n                } else {\n                    val (allowed, reason) = attachmentAllowed(attachment)\n                    if (!allowed) {\n                        File(localPath).delete()\n                        _state.value = _state.value.copy(status = reason)\n                    } else {\n                        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)\n                    }\n                }\n            }'''
)

# Pass only capabilities the selected model actually supports.
replace_once(
    vm_path,
    '''                        api.chat(\n                            key,\n                            textModel,\n                            before,\n                            clean,\n                            pending + projectFiles,\n                            buildSystemPrompt(skillText, currentProject, currentChat),\n                            webSearchEnabled,\n                            reasoningEnabled,\n                            reasoningEffort.apiValue\n                        )''',
    '''                        val modelInfo = _state.value.availableTextModels.firstOrNull { it.id == textModel }\n                        val actualReasoning = reasoningEnabled && modelInfo?.supportsReasoning == true\n                        val effort = if (actualReasoning && modelInfo?.supportsReasoningEffort == true) reasoningEffort.apiValue else null\n                        api.chat(\n                            key,\n                            textModel,\n                            before,\n                            clean,\n                            pending + projectFiles,\n                            buildSystemPrompt(skillText, currentProject, currentChat, modelInfo?.supportsTools == true),\n                            webSearchEnabled,\n                            actualReasoning,\n                            effort,\n                            modelInfo?.supportsTools == true\n                        )'''
)
replace_once(
    vm_path,
    '''    private fun buildSystemPrompt(skillText: String, project: Project?, chat: ChatSession?): String = buildString {''',
    '''    private fun buildSystemPrompt(skillText: String, project: Project?, chat: ChatSession?, toolsEnabled: Boolean): String = buildString {'''
)
replace_once(
    vm_path,
    '''        appendLine("У тебя есть локальный инструмент create_file. Если пользователь просит результат файлом или материал получается слишком длинным для удобного чтения в чате, используй create_file.")''',
    '''        if (toolsEnabled) appendLine("У тебя есть локальный инструмент create_file. Если пользователь просит результат файлом или материал получается слишком длинным для удобного чтения в чате, используй create_file.")'''
)

# ---------------- UI ----------------
ui_path = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
# Camera availability and reasoning availability are calculated from selected model metadata.
replace_once(
    ui_path,
    '''    val listState = rememberLazyListState()\n    val context = LocalContext.current\n''',
    '''    val listState = rememberLazyListState()\n    val context = LocalContext.current\n    val textModelInfo = state.availableTextModels.firstOrNull { it.id == state.textModel }\n    val imageModelInfo = state.availableImageModels.firstOrNull { it.id == state.imageModel }\n    val cameraAvailable = when (state.mode) {\n        ChatMode.TEXT -> textModelInfo?.accepts("image") == true\n        ChatMode.IMAGE -> imageModelInfo?.accepts("image") == true\n    }\n    val reasoningAvailable = state.mode == ChatMode.TEXT && textModelInfo?.supportsReasoning == true &&\n        (textModelInfo.reasoningEfforts.isEmpty() || state.reasoningEffort.apiValue in textModelInfo.reasoningEfforts)\n'''
)
replace_once(
    ui_path,
    '''                    enabled = !state.isLoading,\n                    modifier = Modifier.size(42.dp)\n                ) {\n                    Icon(Icons.Outlined.CameraAlt, contentDescription = "Сделать фото")''',
    '''                    enabled = !state.isLoading && cameraAvailable,\n                    modifier = Modifier.size(42.dp)\n                ) {\n                    Icon(Icons.Outlined.CameraAlt, contentDescription = "Сделать фото")'''
)
replace_once(
    ui_path,
    '''                    ComposerToggleIcon(\n                        selected = state.reasoningEnabled,\n                        icon = Icons.Outlined.Psychology,\n                        description = if (state.reasoningEnabled) "Размышление включено" else "Включить размышление",\n                        onClick = { vm.setReasoningEnabled(!state.reasoningEnabled) }\n                    )''',
    '''                    ComposerToggleIcon(\n                        selected = state.reasoningEnabled,\n                        icon = Icons.Outlined.Psychology,\n                        description = if (state.reasoningEnabled) "Размышление включено" else "Включить размышление",\n                        enabled = reasoningAvailable,\n                        onClick = { vm.setReasoningEnabled(!state.reasoningEnabled) }\n                    )'''
)
replace_once(
    ui_path,
    '''private fun ComposerToggleIcon(\n    selected: Boolean,\n    icon: ImageVector,\n    description: String,\n    onClick: () -> Unit\n) {''',
    '''private fun ComposerToggleIcon(\n    selected: Boolean,\n    icon: ImageVector,\n    description: String,\n    enabled: Boolean = true,\n    onClick: () -> Unit\n) {'''
)
replace_once(
    ui_path,
    '''        FilledTonalIconButton(\n            onClick = onClick,\n            modifier = Modifier.size(38.dp),''',
    '''        FilledTonalIconButton(\n            onClick = onClick,\n            enabled = enabled,\n            modifier = Modifier.size(38.dp),'''
)
replace_once(
    ui_path,
    '''        IconButton(\n            onClick = onClick,\n            modifier = Modifier.size(38.dp)\n        ) {\n            Icon(icon, contentDescription = description, modifier = Modifier.size(19.dp))\n        }\n    }\n}\n\n@Composable\nprivate fun ChatsDialog''',
    '''        IconButton(\n            onClick = onClick,\n            enabled = enabled,\n            modifier = Modifier.size(38.dp)\n        ) {\n            Icon(icon, contentDescription = description, modifier = Modifier.size(19.dp))\n        }\n    }\n}\n\n@Composable\nprivate fun ChatsDialog'''
)

# Model picker now works with ModelInfo while remaining visually just a list of names.
replace_once(
    ui_path,
    '''    val filtered = remember(models, query) {\n        models.filter { it.contains(query.trim(), ignoreCase = true) }.take(250)\n    }''',
    '''    val filtered = remember(models, query) {\n        models.filter { it.id.contains(query.trim(), ignoreCase = true) }.take(250)\n    }'''
)
replace_once(
    ui_path,
    '''                        items(filtered) { id ->\n                            TextButton(\n                                onClick = {\n                                    vm.selectModel(mode, id)\n                                    onDismiss()\n                                },\n                                modifier = Modifier.fillMaxWidth()\n                            ) {\n                                Text(id, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)\n                            }''',
    '''                        items(filtered, key = { it.id }) { modelInfo ->\n                            TextButton(\n                                onClick = {\n                                    vm.selectModel(mode, modelInfo.id)\n                                    onDismiss()\n                                },\n                                modifier = Modifier.fillMaxWidth()\n                            ) {\n                                Text(modelInfo.id, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)\n                            }'''
)

# Reasoning effort choices are disabled when selected model explicitly does not support them.
replace_once(
    ui_path,
    '''    val profileScopes = UserProfileScope.entries\n\n    LazyColumn(''',
    '''    val profileScopes = UserProfileScope.entries\n    val selectedTextModelInfo = state.availableTextModels.firstOrNull { it.id == state.textModel }\n\n    LazyColumn('''
)
replace_once(
    ui_path,
    '''                            FilterChip(\n                                selected = state.reasoningEffort == effort,\n                                onClick = { vm.setReasoningEffort(effort) },\n                                label = { Text(reasoningEffortLabel(effort)) }\n                            )''',
    '''                            FilterChip(\n                                selected = state.reasoningEffort == effort,\n                                onClick = { vm.setReasoningEffort(effort) },\n                                enabled = selectedTextModelInfo?.supportsReasoning == true &&\n                                    (!selectedTextModelInfo.supportsReasoningEffort || selectedTextModelInfo.reasoningEfforts.isEmpty() || effort.apiValue in selectedTextModelInfo.reasoningEfforts),\n                                label = { Text(reasoningEffortLabel(effort)) }\n                            )'''
)

# ---------------- Version ----------------
gradle_path = "app/build.gradle.kts"
gradle = read(gradle_path)
gradle = gradle.replace("// Umnik v0.6.2", "// Umnik v0.6.3", 1)
gradle = gradle.replace('        versionCode = 11\n        versionName = "0.6.2"', '        versionCode = 12\n        versionName = "0.6.3"', 1)
if 'versionName = "0.6.3"' not in gradle:
    raise RuntimeError("Version update failed")
write(gradle_path, gradle)

print("Umnik 0.6.3 patch applied")
