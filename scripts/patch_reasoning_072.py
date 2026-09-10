from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VM = ROOT / "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
text = VM.read_text(encoding="utf-8")

def replace_between(source: str, start: str, end: str, replacement: str) -> str:
    a = source.index(start)
    b = source.index(end, a)
    return source[:a] + replacement.rstrip() + "\n\n" + source[b:]

needle = '''            reasoningEffort = runCatching {
                ReasoningEffort.valueOf(prefs.getString("reasoning_effort", ReasoningEffort.MEDIUM.name) ?: ReasoningEffort.MEDIUM.name)
            }.getOrDefault(ReasoningEffort.MEDIUM),
            userProfile = UserProfile('''
replacement = '''            reasoningEffort = runCatching {
                ReasoningEffort.valueOf(prefs.getString("reasoning_effort", ReasoningEffort.MEDIUM.name) ?: ReasoningEffort.MEDIUM.name)
            }.getOrDefault(ReasoningEffort.MEDIUM),
            reasoningEffortsByModel = loadReasoningEffortsByModel(),
            userProfile = UserProfile('''
if needle not in text:
    raise SystemExit("initial reasoning state marker not found")
text = text.replace(needle, replacement, 1)

text = replace_between(text, "    fun selectModel(mode: ChatMode, model: String) {", "    fun toggleQuickTextModel(model: String) {", r'''    fun selectModel(mode: ChatMode, model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
        when (mode) {
            ChatMode.TEXT -> {
                val effectiveId = _state.value.currentChatTextModel ?: clean
                val info = _state.value.availableTextModels.firstOrNull { it.id == effectiveId }
                val effort = preferredReasoningEffort(effectiveId, info)
                val keepReasoning = reasoningStillValid(info, effort)
                prefs.edit()
                    .putString("text_model", clean)
                    .putString("reasoning_effort", effort.name)
                    .putBoolean("reasoning_enabled", keepReasoning)
                    .apply()
                _state.value = _state.value.copy(
                    textModel = clean,
                    reasoningEffort = effort,
                    reasoningEnabled = keepReasoning
                )
            }
            ChatMode.IMAGE -> {
                prefs.edit().putString("image_model", clean).apply()
                _state.value = _state.value.copy(imageModel = clean)
            }
        }
    }''')

text = replace_between(text, "    fun selectQuickTextModel(model: String) {", "    fun useDefaultTextModelForChat() {", r'''    fun selectQuickTextModel(model: String) {
        if (_state.value.isLoading) return
        val clean = model.trim()
        if (clean.isBlank()) return
        val info = _state.value.availableTextModels.firstOrNull { it.id == clean }
        val effort = preferredReasoningEffort(clean, info)
        val keepReasoning = reasoningStillValid(info, effort)
        val chats = _state.value.chats.map { chat ->
            if (chat.id == _state.value.currentChatId) chat.copy(
                textModelOverride = clean,
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        chatsRepository.save(chats)
        prefs.edit()
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", keepReasoning)
            .apply()
        _state.value = _state.value.copy(
            chats = chats,
            currentChatTextModel = clean,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning
        )
    }''')

text = replace_between(text, "    fun useDefaultTextModelForChat() {", "    fun setWebSearchEnabled(enabled: Boolean) {", r'''    fun useDefaultTextModelForChat() {
        if (_state.value.isLoading) return
        val modelId = _state.value.textModel
        val info = _state.value.availableTextModels.firstOrNull { it.id == modelId }
        val effort = preferredReasoningEffort(modelId, info)
        val keepReasoning = reasoningStillValid(info, effort)
        val chats = _state.value.chats.map { chat ->
            if (chat.id == _state.value.currentChatId) chat.copy(
                textModelOverride = null,
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        chatsRepository.save(chats)
        prefs.edit()
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", keepReasoning)
            .apply()
        _state.value = _state.value.copy(
            chats = chats,
            currentChatTextModel = null,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning
        )
    }''')

text = replace_between(text, "    fun setReasoningEnabled(enabled: Boolean) {", "    fun saveUserProfile(name: String, gender: String, age: String, occupation: String, note: String) {", r'''    fun setReasoningEnabled(enabled: Boolean) {
        if (enabled) {
            val info = currentTextModelInfo()
            val effort = preferredReasoningEffort(currentTextModelId(), info)
            if (info?.supportsReasoning != true) {
                _state.value = _state.value.copy(status = "Выбранная модель не поддерживает размышление")
                return
            }
            if (info.supportsReasoningEffort && info.reasoningEfforts.isNotEmpty() && effort.apiValue !in info.reasoningEfforts) {
                _state.value = _state.value.copy(status = "Выбранная сила размышления не поддерживается этой моделью")
                return
            }
            prefs.edit().putString("reasoning_effort", effort.name).apply()
            _state.value = _state.value.copy(reasoningEffort = effort)
        }
        prefs.edit().putBoolean("reasoning_enabled", enabled).apply()
        _state.value = _state.value.copy(reasoningEnabled = enabled)
    }

    fun setReasoningEffort(effort: ReasoningEffort) {
        setReasoningEffortForModel(currentTextModelId(), effort)
    }

    fun setReasoningEffortForModel(modelId: String, effort: ReasoningEffort) {
        val clean = modelId.trim()
        if (clean.isBlank()) return
        val info = _state.value.availableTextModels.firstOrNull { it.id == clean }
            ?: _state.value.availableImageModels.firstOrNull { it.id == clean }

        if (info?.supportsReasoningEffort == true && info.reasoningEfforts.isNotEmpty() && effort.apiValue !in info.reasoningEfforts) {
            _state.value = _state.value.copy(status = "${reasoningEffortName(effort)} не поддерживается моделью ${clean.substringAfter('/')}")
            return
        }

        val nextMap = _state.value.reasoningEffortsByModel + (clean to effort)
        prefs.edit().putString("reasoning_efforts_by_model_json", gson.toJson(nextMap)).apply()

        if (clean == currentTextModelId()) {
            val keepReasoning = reasoningStillValid(info, effort)
            prefs.edit()
                .putString("reasoning_effort", effort.name)
                .putBoolean("reasoning_enabled", keepReasoning)
                .apply()
            _state.value = _state.value.copy(
                reasoningEffortsByModel = nextMap,
                reasoningEffort = effort,
                reasoningEnabled = keepReasoning
            )
        } else {
            _state.value = _state.value.copy(reasoningEffortsByModel = nextMap)
        }
    }
''')

text = replace_between(text, "    fun createChat(projectId: String? = null): String {", "    fun switchChat(id: String) {", r'''    fun createChat(projectId: String? = null): String {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return _state.value.currentChatId
        val chat = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "Новый чат",
            projectId = projectId,
            mode = _state.value.mode
        )
        val next = listOf(chat) + _state.value.chats
        val modelId = _state.value.textModel
        val info = _state.value.availableTextModels.firstOrNull { it.id == modelId }
        val effort = preferredReasoningEffort(modelId, info)
        val keepReasoning = reasoningStillValid(info, effort)
        chatsRepository.save(next)
        prefs.edit()
            .putString("current_chat_id", chat.id)
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", keepReasoning)
            .apply()
        _state.value = _state.value.copy(
            chats = next,
            currentChatId = chat.id,
            messages = emptyList(),
            currentChatTextModel = null,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning,
            pendingAttachments = emptyList(),
            storageStats = storageRepository.stats()
        )
        return chat.id
    }''')

text = replace_between(text, "    fun switchChat(id: String) {", "    fun deleteChat(id: String) {", r'''    fun switchChat(id: String) {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return
        val chat = _state.value.chats.firstOrNull { it.id == id } ?: return
        val nextMode = chat.mode ?: _state.value.mode
        val modelId = chat.textModelOverride ?: _state.value.textModel
        val info = _state.value.availableTextModels.firstOrNull { it.id == modelId }
        val effort = preferredReasoningEffort(modelId, info)
        val keepReasoning = reasoningStillValid(info, effort)
        prefs.edit()
            .putString("current_chat_id", id)
            .putString("chat_mode", nextMode.name)
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", keepReasoning)
            .apply()
        _state.value = _state.value.copy(
            currentChatId = id,
            messages = chat.messages,
            mode = nextMode,
            currentChatTextModel = chat.textModelOverride,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning,
            pendingAttachments = emptyList()
        )
    }''')

text = replace_between(text, "    fun refreshModels(mode: ChatMode) {", "    private fun refreshModelCapabilities() {", r'''    fun refreshModels(mode: ChatMode) {
        val key = secrets.getApiKey()
        if (key.isNullOrBlank()) {
            _state.value = _state.value.copy(status = "Сначала сохраните API-ключ OpenRouter")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, busyLabel = "Загружаю модели…", status = null)
            runCatching {
                when (mode) {
                    ChatMode.TEXT -> api.models(key)
                    ChatMode.IMAGE -> api.imageModels(key)
                }
            }.onSuccess { infos ->
                _state.value = when (mode) {
                    ChatMode.TEXT -> {
                        val effectiveId = _state.value.currentChatTextModel ?: _state.value.textModel
                        val current = infos.firstOrNull { it.id == effectiveId }
                        val effort = preferredReasoningEffort(effectiveId, current)
                        val keepReasoning = reasoningStillValid(current, effort)
                        prefs.edit()
                            .putString("reasoning_effort", effort.name)
                            .putBoolean("reasoning_enabled", keepReasoning)
                            .apply()
                        _state.value.copy(
                            availableTextModels = infos,
                            reasoningEffort = effort,
                            reasoningEnabled = keepReasoning,
                            isLoading = false,
                            busyLabel = null
                        )
                    }
                    ChatMode.IMAGE -> _state.value.copy(
                        availableImageModels = infos,
                        isLoading = false,
                        busyLabel = null
                    )
                }
            }.onFailure {
                _state.value = _state.value.copy(
                    isLoading = false,
                    busyLabel = null,
                    status = it.message ?: "Не удалось загрузить модели"
                )
            }
        }
    }''')

text = replace_between(text, "    private fun refreshModelCapabilities() {", "    private fun currentTextModelId(): String", r'''    private fun refreshModelCapabilities() {
        val key = secrets.getApiKey() ?: return
        viewModelScope.launch {
            val textInfos = runCatching { api.models(key) }.getOrNull()
            val imageInfos = runCatching { api.imageModels(key) }.getOrNull()
            var next = _state.value
            if (textInfos != null) {
                val effectiveId = next.currentChatTextModel ?: next.textModel
                val current = textInfos.firstOrNull { it.id == effectiveId }
                val effort = preferredReasoningEffort(effectiveId, current)
                val keepReasoning = reasoningStillValid(current, effort)
                prefs.edit()
                    .putString("reasoning_effort", effort.name)
                    .putBoolean("reasoning_enabled", keepReasoning)
                    .apply()
                next = next.copy(
                    availableTextModels = textInfos,
                    reasoningEffort = effort,
                    reasoningEnabled = keepReasoning
                )
            }
            if (imageInfos != null) next = next.copy(availableImageModels = imageInfos)
            _state.value = next
        }
    }
''')

old_helpers = '''    private fun reasoningStillValid(info: ModelInfo?): Boolean =
        _state.value.reasoningEnabled && info?.supportsReasoning == true &&
            (info.reasoningEfforts.isEmpty() || _state.value.reasoningEffort.apiValue in info.reasoningEfforts)

    private fun currentImageModelInfo(): ModelInfo? ='''
new_helpers = '''    private fun reasoningStillValid(info: ModelInfo?, effort: ReasoningEffort = _state.value.reasoningEffort): Boolean =
        _state.value.reasoningEnabled && info?.supportsReasoning == true &&
            (!info.supportsReasoningEffort || info.reasoningEfforts.isEmpty() || effort.apiValue in info.reasoningEfforts)

    private fun preferredReasoningEffort(modelId: String, info: ModelInfo?): ReasoningEffort {
        val configured = _state.value.reasoningEffortsByModel[modelId] ?: _state.value.reasoningEffort
        if (info?.supportsReasoningEffort != true || info.reasoningEfforts.isEmpty()) return configured
        if (configured.apiValue in info.reasoningEfforts) return configured
        val fallbackOrder = listOf(
            ReasoningEffort.MEDIUM,
            ReasoningEffort.LOW,
            ReasoningEffort.HIGH,
            ReasoningEffort.MINIMAL,
            ReasoningEffort.XHIGH
        )
        return fallbackOrder.firstOrNull { it.apiValue in info.reasoningEfforts } ?: configured
    }

    private fun reasoningEffortName(effort: ReasoningEffort): String = when (effort) {
        ReasoningEffort.MINIMAL -> "Минимальная сила"
        ReasoningEffort.LOW -> "Низкая сила"
        ReasoningEffort.MEDIUM -> "Средняя сила"
        ReasoningEffort.HIGH -> "Высокая сила"
        ReasoningEffort.XHIGH -> "Максимальная сила"
    }

    private fun currentImageModelInfo(): ModelInfo? ='''
if old_helpers not in text:
    raise SystemExit("reasoning helper marker not found")
text = text.replace(old_helpers, new_helpers, 1)

load_marker = '''    private fun loadInitialChats(): List<ChatSession> {'''
load_func = r'''    private fun loadReasoningEffortsByModel(): Map<String, ReasoningEffort> = runCatching {
        val type = object : TypeToken<Map<String, ReasoningEffort>>() {}.type
        gson.fromJson<Map<String, ReasoningEffort>>(
            prefs.getString("reasoning_efforts_by_model_json", "{}") ?: "{}",
            type
        ).orEmpty()
    }.getOrDefault(emptyMap())

'''
if load_marker not in text:
    raise SystemExit("loadInitialChats marker not found")
text = text.replace(load_marker, load_func + load_marker, 1)

VM.write_text(text, encoding="utf-8")
print("Per-model reasoning patch applied")
