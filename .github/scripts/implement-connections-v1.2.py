from pathlib import Path


def read(path: str) -> str:
    return Path(path).read_text()


def write(path: str, text: str) -> None:
    Path(path).write_text(text)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"missing pattern for {label}")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    i = text.find(start)
    if i < 0:
        raise SystemExit(f"missing start for {label}")
    j = text.find(end, i)
    if j < 0:
        raise SystemExit(f"missing end for {label}")
    return text[:i] + replacement.rstrip() + "\n\n" + text[j:]


# Models.kt
path = "app/src/main/java/com/ayuemin/ymnik/model/Models.kt"
s = read(path)
s = replace_once(
    s,
    "    val mode: ChatMode? = null,\n    val textModelOverride: String? = null,",
    "    val mode: ChatMode? = null,\n    val connectionProfileId: String? = null,\n    val textModelOverride: String? = null,",
    "chat connection binding",
)
s = replace_once(
    s,
    "    val activeConnectionProfileId: String = \"openrouter\",\n    val textModel: String = \"openrouter/auto\",",
    "    val activeConnectionProfileId: String = \"openrouter\",\n    val disabledConnectionIds: Set<String> = emptySet(),\n    val textModel: String = \"openrouter/auto\",",
    "disabled connections state",
)
s = replace_once(
    s,
    "    val availableTextModels: List<ModelInfo> = emptyList(),\n    val availableImageModels: List<ModelInfo> = emptyList(),",
    "    val availableTextModels: List<ModelInfo> = emptyList(),\n    val availableImageModels: List<ModelInfo> = emptyList(),\n    val modelCatalogConnectionId: String? = null,\n    val modelCatalog: List<ModelInfo> = emptyList(),",
    "connection catalog state",
)
write(path, s)


# ChatViewModel.kt
path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
s = read(path)
s = replace_once(
    s,
    "class ChatViewModel(private val context: Context) : ViewModel() {",
    "class ChatViewModel(private val context: Context) : ViewModel() {\n    private companion object { const val QUICK_MODEL_SEPARATOR = \"\\u001F\" }",
    "quick model separator",
)

initial_block = '''    private val initialProfiles = loadConnectionProfiles()
    private val initialDisabledConnectionIds = loadDisabledConnectionIds()
    private val initialChats = loadInitialChats()
    private val initialChatId = prefs.getString("current_chat_id", null)
        ?.takeIf { id -> initialChats.any { it.id == id } }
        ?: initialChats.first().id
    private val initialChat = initialChats.first { it.id == initialChatId }
    private val initialProfileId = (initialChat.connectionProfileId
        ?: prefs.getString("active_connection_profile", "openrouter"))
        ?.takeIf { id -> initialProfiles.any { it.id == id } && id !in initialDisabledConnectionIds }
        ?: initialProfiles.firstOrNull { it.id !in initialDisabledConnectionIds }?.id
        ?: "openrouter"
    private val initialProfile = initialProfiles.firstOrNull { it.id == initialProfileId }
        ?: defaultOpenRouterProfile()
'''
s = replace_between(
    s,
    "    private val initialProfiles = loadConnectionProfiles()\n",
    "    private val _state = MutableStateFlow(\n",
    initial_block,
    "initial connection state",
)
s = replace_once(
    s,
    "            mode = (initialChat.mode ?: runCatching {\n                ChatMode.valueOf(prefs.getString(\"chat_mode\", ChatMode.TEXT.name) ?: ChatMode.TEXT.name)\n            }.getOrDefault(ChatMode.TEXT)).let { if (initialProfile.type == ProviderType.OPENROUTER) it else ChatMode.TEXT },",
    "            mode = initialChat.mode ?: runCatching {\n                ChatMode.valueOf(prefs.getString(\"chat_mode\", ChatMode.TEXT.name) ?: ChatMode.TEXT.name)\n            }.getOrDefault(ChatMode.TEXT),",
    "initial mode",
)
s = replace_once(
    s,
    "            connectionProfiles = initialProfiles,\n            activeConnectionProfileId = initialProfileId,",
    "            connectionProfiles = initialProfiles,\n            activeConnectionProfileId = initialProfileId,\n            disabledConnectionIds = initialDisabledConnectionIds,",
    "ui disabled ids",
)
s = replace_once(
    s,
    "            currentChatTextModel = initialChat.textModelOverride,\n            quickTextModels = loadQuickTextModels(initialProfileId),\n            imageModel = loadImageModelForProfile(initialProfileId),",
    "            currentChatTextModel = initialChat.textModelOverride.takeIf { initialChat.connectionProfileId == null || initialChat.connectionProfileId == initialProfileId },\n            quickTextModels = loadAllQuickTextModels(initialProfiles, initialDisabledConnectionIds),\n            imageModel = loadImageModelForProfile(\"openrouter\"),",
    "global quick models init",
)
s = replace_once(
    s,
    "    init {\n        if (isProfileConfigured(initialProfile)) refreshModelCapabilities()\n    }",
    "    init {\n        if (initialProfile.id !in initialDisabledConnectionIds && isProfileConfigured(initialProfile)) refreshModelCapabilities()\n    }",
    "initial refresh",
)

connection_functions = '''    fun selectConnectionProfile(profileId: String) {
        if (_state.value.isLoading) return
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Подключение «${profile.name}» выключено")
            return
        }
        prefs.edit().putString("active_connection_profile", profile.id).apply()
        val chats = _state.value.chats.map { chat ->
            if (chat.id == _state.value.currentChatId) chat.copy(
                connectionProfileId = profile.id,
                textModelOverride = null,
                mode = if (chat.mode == ChatMode.IMAGE && profile.type != ProviderType.OPENROUTER) ChatMode.TEXT else chat.mode,
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        chatsRepository.save(chats)
        _state.value = _state.value.copy(
            activeConnectionProfileId = profile.id,
            chats = chats,
            currentChatTextModel = null,
            textModel = loadTextModelForProfile(profile),
            quickTextModels = loadAllQuickTextModels(_state.value.connectionProfiles, _state.value.disabledConnectionIds),
            imageModel = loadImageModelForProfile("openrouter"),
            availableTextModels = emptyList(),
            mode = if (profile.type == ProviderType.OPENROUTER) _state.value.mode else ChatMode.TEXT,
            webSearchEnabled = if (profile.type == ProviderType.OPENROUTER) prefs.getBoolean("web_search", false) else false,
            reasoningEnabled = false,
            apiKeyConfigured = isProfileConfigured(profile),
            status = "Подключение «${profile.name}» выбрано для текущего чата"
        )
        if (isProfileConfigured(profile)) refreshModelCapabilities()
    }

    fun addCompatibleProfile(): String {
        val id = UUID.randomUUID().toString()
        val profile = ConnectionProfile(id, "Другой API", ProviderType.OPENAI_COMPATIBLE, "")
        val profiles = _state.value.connectionProfiles + profile
        saveConnectionProfiles(profiles)
        _state.value = _state.value.copy(
            connectionProfiles = profiles,
            quickTextModels = loadAllQuickTextModels(profiles, _state.value.disabledConnectionIds),
            status = "Подключение добавлено. Укажите адрес API."
        )
        return id
    }

    fun saveConnectionProfile(profileId: String, name: String, baseUrl: String, apiKey: String?) {
        val old = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        val cleanUrl = normalizeBaseUrl(baseUrl)
        if (cleanUrl.isBlank()) {
            _state.value = _state.value.copy(status = "Укажите адрес API")
            return
        }
        val updated = old.copy(
            name = if (old.type == ProviderType.OPENROUTER) "OpenRouter" else name.trim().ifBlank { "Другой API" },
            baseUrl = cleanUrl
        )
        val profiles = _state.value.connectionProfiles.map { if (it.id == profileId) updated else it }
        saveConnectionProfiles(profiles)
        if (!apiKey.isNullOrBlank()) secrets.saveProfileApiKey(profileId, apiKey)
        val active = _state.value.activeConnectionProfileId == profileId
        _state.value = _state.value.copy(
            connectionProfiles = profiles,
            apiKeyConfigured = if (active) isProfileConfigured(updated) else _state.value.apiKeyConfigured,
            availableTextModels = if (active) emptyList() else _state.value.availableTextModels,
            availableImageModels = if (profileId == "openrouter") emptyList() else _state.value.availableImageModels,
            modelCatalogConnectionId = null,
            modelCatalog = emptyList(),
            status = "Подключение сохранено"
        )
        if (active && profileId !in _state.value.disabledConnectionIds && isProfileConfigured(updated)) refreshModelCapabilities()
    }

    fun setConnectionEnabled(profileId: String, enabled: Boolean) {
        if (_state.value.isLoading) return
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        val disabled = _state.value.disabledConnectionIds.toMutableSet().apply {
            if (enabled) remove(profileId) else add(profileId)
        }.toSet()
        prefs.edit().putStringSet("disabled_connection_profiles", disabled).apply()
        _state.value = _state.value.copy(
            disabledConnectionIds = disabled,
            quickTextModels = loadAllQuickTextModels(_state.value.connectionProfiles, disabled),
            modelCatalogConnectionId = null,
            modelCatalog = emptyList(),
            status = if (enabled) "Подключение «${profile.name}» включено" else "Подключение «${profile.name}» выключено"
        )

        if (!enabled && _state.value.activeConnectionProfileId == profileId) {
            val fallback = _state.value.connectionProfiles.firstOrNull {
                it.id !in disabled && isProfileConfigured(it)
            } ?: _state.value.connectionProfiles.firstOrNull { it.id !in disabled }
            if (fallback != null) {
                selectConnectionProfile(fallback.id)
            } else {
                _state.value = _state.value.copy(
                    mode = ChatMode.TEXT,
                    availableTextModels = emptyList(),
                    availableImageModels = emptyList(),
                    reasoningEnabled = false,
                    webSearchEnabled = false,
                    status = "Все подключения выключены"
                )
            }
        } else if (enabled && profileId == "openrouter" && isProfileConfigured(profile)) {
            refreshModelCapabilities()
        }
    }

    fun deleteConnectionProfile(profileId: String) {
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.type == ProviderType.OPENROUTER) return
        secrets.deleteProfileApiKey(profileId)
        prefs.edit()
            .remove(profilePrefKey("text_model", profileId))
            .remove(profilePrefKey("image_model", profileId))
            .remove(profilePrefKey("quick_text_models_json", profileId))
            .apply()
        val profiles = _state.value.connectionProfiles.filterNot { it.id == profileId }
        val disabled = _state.value.disabledConnectionIds - profileId
        val fallback = profiles.firstOrNull { it.id !in disabled && isProfileConfigured(it) }
            ?: profiles.firstOrNull { it.id !in disabled }
            ?: profiles.first()
        val chats = _state.value.chats.map { chat ->
            if (chat.connectionProfileId == profileId) chat.copy(
                connectionProfileId = fallback.id,
                textModelOverride = null,
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        saveConnectionProfiles(profiles)
        prefs.edit().putStringSet("disabled_connection_profiles", disabled).apply()
        chatsRepository.save(chats)
        _state.value = _state.value.copy(
            connectionProfiles = profiles,
            disabledConnectionIds = disabled,
            chats = chats,
            quickTextModels = loadAllQuickTextModels(profiles, disabled),
            modelCatalogConnectionId = null,
            modelCatalog = emptyList(),
            status = "Подключение «${profile.name}» удалено"
        )
        if (_state.value.activeConnectionProfileId == profileId) selectConnectionProfile(fallback.id)
    }

    fun loadConnectionModels(profileId: String) {
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Сначала включите подключение «${profile.name}»")
            return
        }
        if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = connectionSetupMessage(profile))
            return
        }
        val key = secrets.getProfileApiKey(profile.id).orEmpty()
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, busyLabel = "Загружаю модели…", status = null)
            runCatching {
                if (profile.type == ProviderType.OPENROUTER) api.models(key, profile.baseUrl)
                else compatibleApi.models(key, profile.baseUrl)
            }.onSuccess { infos ->
                _state.value = _state.value.copy(
                    modelCatalogConnectionId = profile.id,
                    modelCatalog = infos,
                    isLoading = false,
                    busyLabel = null
                )
            }.onFailure {
                _state.value = _state.value.copy(
                    isLoading = false,
                    busyLabel = null,
                    status = it.message ?: "Не удалось загрузить модели подключения"
                )
            }
        }
    }

    fun toggleQuickTextModelForConnection(profileId: String, model: String) {
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
'''
s = replace_between(
    s,
    "    fun selectConnectionProfile(profileId: String) {\n",
    "    fun setMode(mode: ChatMode) {\n",
    connection_functions,
    "connection functions",
)

set_mode = '''    fun setMode(mode: ChatMode) {
        if (mode == ChatMode.IMAGE) {
            val openRouter = openRouterProfile()
            if (openRouter.id in _state.value.disabledConnectionIds || !isProfileConfigured(openRouter)) {
                _state.value = _state.value.copy(status = "Для создания изображений включите и настройте подключение OpenRouter")
                return
            }
        }
        prefs.edit().putString("chat_mode", mode.name).apply()
        val chats = _state.value.chats.map { chat ->
            if (chat.id == _state.value.currentChatId) chat.copy(mode = mode) else chat
        }
        chatsRepository.save(chats)
        _state.value = _state.value.copy(mode = mode, chats = chats)
        if (mode == ChatMode.IMAGE && _state.value.availableImageModels.isEmpty()) refreshModels(ChatMode.IMAGE)
    }
'''
s = replace_between(s, "    fun setMode(mode: ChatMode) {\n", "    fun selectModel(mode: ChatMode, model: String) {\n", set_mode, "setMode")
s = replace_once(
    s,
    "prefs.edit().putString(profilePrefKey(\"image_model\", _state.value.activeConnectionProfileId), clean).apply()",
    "prefs.edit().putString(profilePrefKey(\"image_model\", \"openrouter\"), clean).apply()",
    "image model belongs to openrouter",
)

quick_functions = '''    fun toggleQuickTextModel(model: String) {
        toggleQuickTextModelForConnection(_state.value.activeConnectionProfileId, model)
    }

    fun selectQuickTextModel(modelRef: String) {
        if (_state.value.isLoading) return
        val (profileId, modelId) = decodeQuickModelRef(modelRef, _state.value.activeConnectionProfileId)
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Подключение «${profile.name}» выключено")
            return
        }
        if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = connectionSetupMessage(profile))
            return
        }
        val clean = modelId.trim()
        if (clean.isBlank()) return
        val sameProfile = profile.id == _state.value.activeConnectionProfileId
        val info = if (sameProfile) _state.value.availableTextModels.firstOrNull { it.id == clean } else null
        val effort = preferredReasoningEffort(clean, info)
        val keepReasoning = sameProfile && reasoningStillValid(info, effort)
        val chats = _state.value.chats.map { chat ->
            if (chat.id == _state.value.currentChatId) chat.copy(
                connectionProfileId = profile.id,
                textModelOverride = clean,
                mode = ChatMode.TEXT,
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        chatsRepository.save(chats)
        prefs.edit()
            .putString("active_connection_profile", profile.id)
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", keepReasoning)
            .apply()
        _state.value = _state.value.copy(
            chats = chats,
            activeConnectionProfileId = profile.id,
            textModel = loadTextModelForProfile(profile),
            currentChatTextModel = clean,
            quickTextModels = loadAllQuickTextModels(_state.value.connectionProfiles, _state.value.disabledConnectionIds),
            mode = ChatMode.TEXT,
            availableTextModels = if (sameProfile) _state.value.availableTextModels else emptyList(),
            webSearchEnabled = if (profile.type == ProviderType.OPENROUTER) prefs.getBoolean("web_search", false) else false,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning,
            apiKeyConfigured = isProfileConfigured(profile),
            status = "${clean.substringAfterLast('/')} · ${profile.name}"
        )
        refreshModelCapabilities()
    }

    fun useDefaultTextModelForChat() {
        if (_state.value.isLoading) return
        val profile = activeConnectionProfile()
        val modelId = _state.value.textModel
        val info = _state.value.availableTextModels.firstOrNull { it.id == modelId }
        val effort = preferredReasoningEffort(modelId, info)
        val keepReasoning = reasoningStillValid(info, effort)
        val chats = _state.value.chats.map { chat ->
            if (chat.id == _state.value.currentChatId) chat.copy(
                connectionProfileId = profile.id,
                textModelOverride = null,
                mode = ChatMode.TEXT,
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
            mode = ChatMode.TEXT,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning
        )
    }
'''
s = replace_between(
    s,
    "    fun toggleQuickTextModel(model: String) {\n",
    "    fun setWebSearchEnabled(enabled: Boolean) {\n",
    quick_functions,
    "quick model functions",
)
s = replace_once(
    s,
    "        if (enabled && activeConnectionProfile().type != ProviderType.OPENROUTER) {\n            _state.value = _state.value.copy(status = \"Поиск в сети сейчас поддерживается профилем OpenRouter\")",
    "        if (enabled && (activeConnectionProfile().type != ProviderType.OPENROUTER || \"openrouter\" in _state.value.disabledConnectionIds)) {\n            _state.value = _state.value.copy(status = \"Поиск в сети сейчас поддерживается подключением OpenRouter\")",
    "web search wording",
)

create_chat = '''    fun createChat(projectId: String? = null): String {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return _state.value.currentChatId
        val chat = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "Новый чат",
            projectId = projectId,
            mode = _state.value.mode,
            connectionProfileId = _state.value.activeConnectionProfileId
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
    }
'''
s = replace_between(s, "    fun createChat(projectId: String? = null): String {\n", "    fun branchFromMessage(messageId: String): String? {\n", create_chat, "createChat")
s = replace_once(
    s,
    "            mode = source.mode ?: _state.value.mode,\n            textModelOverride = source.textModelOverride,",
    "            mode = source.mode ?: _state.value.mode,\n            connectionProfileId = source.connectionProfileId ?: _state.value.activeConnectionProfileId,\n            textModelOverride = source.textModelOverride,",
    "branch connection",
)

switch_chat = '''    fun switchChat(id: String) {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return
        val original = _state.value.chats.firstOrNull { it.id == id } ?: return
        val requestedProfileId = original.connectionProfileId
            ?: prefs.getString("active_connection_profile", "openrouter")
            ?: "openrouter"
        val profile = _state.value.connectionProfiles.firstOrNull {
            it.id == requestedProfileId && it.id !in _state.value.disabledConnectionIds
        } ?: _state.value.connectionProfiles.firstOrNull {
            it.id !in _state.value.disabledConnectionIds && isProfileConfigured(it)
        } ?: _state.value.connectionProfiles.firstOrNull { it.id !in _state.value.disabledConnectionIds }
            ?: openRouterProfile()
        val migrated = profile.id != requestedProfileId || original.connectionProfileId == null
        val chat = if (migrated) original.copy(
            connectionProfileId = profile.id,
            textModelOverride = if (profile.id == requestedProfileId) original.textModelOverride else null,
            updatedAt = System.currentTimeMillis()
        ) else original
        val chats = if (migrated) _state.value.chats.map { if (it.id == id) chat else it } else _state.value.chats
        if (migrated) chatsRepository.save(chats)
        val openRouter = openRouterProfile()
        val canImage = openRouter.id !in _state.value.disabledConnectionIds && isProfileConfigured(openRouter)
        val nextMode = if ((chat.mode ?: ChatMode.TEXT) == ChatMode.IMAGE && !canImage) ChatMode.TEXT else (chat.mode ?: ChatMode.TEXT)
        val defaultModel = loadTextModelForProfile(profile)
        val modelId = chat.textModelOverride ?: defaultModel
        val info = if (profile.id == _state.value.activeConnectionProfileId) {
            _state.value.availableTextModels.firstOrNull { it.id == modelId }
        } else null
        val effort = preferredReasoningEffort(modelId, info)
        prefs.edit()
            .putString("current_chat_id", id)
            .putString("active_connection_profile", profile.id)
            .putString("chat_mode", nextMode.name)
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", false)
            .apply()
        _state.value = _state.value.copy(
            chats = chats,
            currentChatId = id,
            messages = chat.messages,
            mode = nextMode,
            activeConnectionProfileId = profile.id,
            textModel = defaultModel,
            currentChatTextModel = chat.textModelOverride,
            quickTextModels = loadAllQuickTextModels(_state.value.connectionProfiles, _state.value.disabledConnectionIds),
            imageModel = loadImageModelForProfile("openrouter"),
            availableTextModels = emptyList(),
            reasoningEffort = effort,
            reasoningEnabled = false,
            webSearchEnabled = if (profile.type == ProviderType.OPENROUTER) prefs.getBoolean("web_search", false) else false,
            apiKeyConfigured = isProfileConfigured(profile),
            pendingAttachments = emptyList()
        )
        if (profile.id !in _state.value.disabledConnectionIds && isProfileConfigured(profile)) refreshModelCapabilities()
    }
'''
s = replace_between(s, "    fun switchChat(id: String) {\n", "    fun deleteChat(id: String) {\n", switch_chat, "switchChat")

delete_chat = '''    fun deleteChat(id: String) {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return
        if (_state.value.chats.none { it.id == id }) return

        chatFilesRepository.deleteChat(id)
        var remaining = _state.value.chats.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            remaining = listOf(
                ChatSession(
                    UUID.randomUUID().toString(),
                    "Новый чат",
                    mode = ChatMode.TEXT,
                    connectionProfileId = _state.value.activeConnectionProfileId
                )
            )
        }
        val selected = if (_state.value.currentChatId == id) remaining.first() else
            remaining.firstOrNull { it.id == _state.value.currentChatId } ?: remaining.first()
        chatsRepository.save(remaining)
        _state.value = _state.value.copy(chats = remaining, pendingAttachments = emptyList())
        switchChat(selected.id)
        _state.value = _state.value.copy(
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Диалог удалён"
        )
    }
'''
s = replace_between(s, "    fun deleteChat(id: String) {\n", "    fun updateChatProfile(id: String, title: String, role: String, masterPrompt: String) {\n", delete_chat, "deleteChat")

refresh_block = '''    fun refreshModels(mode: ChatMode) {
        val profile = if (mode == ChatMode.IMAGE) openRouterProfile() else activeConnectionProfile()
        if (profile.id in _state.value.disabledConnectionIds) {
            _state.value = _state.value.copy(status = "Подключение «${profile.name}» выключено")
            return
        }
        if (!isProfileConfigured(profile)) {
            _state.value = _state.value.copy(status = connectionSetupMessage(profile))
            return
        }
        val key = secrets.getProfileApiKey(profile.id).orEmpty()
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, busyLabel = "Загружаю модели…", status = null)
            runCatching {
                when {
                    profile.type == ProviderType.OPENROUTER && mode == ChatMode.TEXT -> api.models(key, profile.baseUrl)
                    profile.type == ProviderType.OPENROUTER && mode == ChatMode.IMAGE -> api.imageModels(key, profile.baseUrl)
                    else -> compatibleApi.models(key, profile.baseUrl)
                }
            }.onSuccess { infos ->
                _state.value = when (mode) {
                    ChatMode.TEXT -> {
                        var selectedModel = _state.value.textModel
                        if (profile.type == ProviderType.OPENAI_COMPATIBLE && infos.none { it.id == selectedModel }) {
                            selectedModel = infos.firstOrNull()?.id.orEmpty()
                            if (selectedModel.isNotBlank()) {
                                prefs.edit().putString(profilePrefKey("text_model", profile.id), selectedModel).apply()
                            }
                        }
                        val effectiveId = _state.value.currentChatTextModel?.takeIf { id -> infos.any { it.id == id } } ?: selectedModel
                        val current = infos.firstOrNull { it.id == effectiveId }
                        val effort = preferredReasoningEffort(effectiveId, current)
                        val keepReasoning = reasoningStillValid(current, effort)
                        prefs.edit()
                            .putString("reasoning_effort", effort.name)
                            .putBoolean("reasoning_enabled", keepReasoning)
                            .apply()
                        _state.value.copy(
                            textModel = selectedModel,
                            currentChatTextModel = _state.value.currentChatTextModel?.takeIf { id -> infos.any { it.id == id } },
                            availableTextModels = infos,
                            reasoningEffort = effort,
                            reasoningEnabled = keepReasoning,
                            isLoading = false,
                            busyLabel = null
                        )
                    }
                    ChatMode.IMAGE -> _state.value.copy(
                        availableImageModels = infos,
                        imageModel = loadImageModelForProfile("openrouter"),
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
    }

    private fun refreshModelCapabilities() {
        val profile = activeConnectionProfile()
        val openRouter = openRouterProfile()
        viewModelScope.launch {
            val textInfos = if (profile.id !in _state.value.disabledConnectionIds && isProfileConfigured(profile)) {
                val key = secrets.getProfileApiKey(profile.id).orEmpty()
                runCatching {
                    if (profile.type == ProviderType.OPENROUTER) api.models(key, profile.baseUrl)
                    else compatibleApi.models(key, profile.baseUrl)
                }.getOrNull()
            } else null
            val imageInfos = if (openRouter.id !in _state.value.disabledConnectionIds && isProfileConfigured(openRouter)) {
                val key = secrets.getProfileApiKey(openRouter.id).orEmpty()
                runCatching { api.imageModels(key, openRouter.baseUrl) }.getOrNull()
            } else emptyList()
            var next = _state.value
            if (textInfos != null) {
                var selectedModel = loadTextModelForProfile(profile)
                if (profile.type == ProviderType.OPENAI_COMPATIBLE && textInfos.none { it.id == selectedModel }) {
                    selectedModel = textInfos.firstOrNull()?.id.orEmpty()
                    if (selectedModel.isNotBlank()) prefs.edit().putString(profilePrefKey("text_model", profile.id), selectedModel).apply()
                }
                val effectiveId = next.currentChatTextModel?.takeIf { id -> textInfos.any { it.id == id } } ?: selectedModel
                val current = textInfos.firstOrNull { it.id == effectiveId }
                val effort = preferredReasoningEffort(effectiveId, current)
                val keepReasoning = reasoningStillValid(current, effort)
                prefs.edit()
                    .putString("reasoning_effort", effort.name)
                    .putBoolean("reasoning_enabled", keepReasoning)
                    .apply()
                next = next.copy(
                    textModel = selectedModel,
                    currentChatTextModel = next.currentChatTextModel?.takeIf { id -> textInfos.any { it.id == id } },
                    availableTextModels = textInfos,
                    reasoningEffort = effort,
                    reasoningEnabled = keepReasoning
                )
            } else {
                next = next.copy(availableTextModels = emptyList(), reasoningEnabled = false)
            }
            next = next.copy(
                availableImageModels = imageInfos ?: emptyList(),
                imageModel = loadImageModelForProfile("openrouter"),
                quickTextModels = loadAllQuickTextModels(next.connectionProfiles, next.disabledConnectionIds)
            )
            _state.value = next
        }
    }
'''
s = replace_between(s, "    fun refreshModels(mode: ChatMode) {\n", "    private fun currentTextModelId(): String =", refresh_block, "refresh functions")

s = replace_once(
    s,
    "    fun send(text: String) {\n        val profile = activeConnectionProfile()\n        if (!isProfileConfigured(profile)) {",
    "    fun send(text: String) {\n        val profile = if (_state.value.mode == ChatMode.IMAGE) openRouterProfile() else activeConnectionProfile()\n        if (profile.id in _state.value.disabledConnectionIds) {\n            _state.value = _state.value.copy(status = \"Подключение «${profile.name}» выключено\")\n            return\n        }\n        if (!isProfileConfigured(profile)) {",
    "send provider resolution",
)

helper_block = '''    private fun loadQuickTextModels(profileId: String): List<String> = runCatching {
        val type = object : TypeToken<List<String>>() {}.type
        gson.fromJson<List<String>>(prefs.getString(profilePrefKey("quick_text_models_json", profileId), "[]") ?: "[]", type)
            .orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(10)
    }.getOrDefault(emptyList())

    private fun quickModelRef(profileId: String, modelId: String): String =
        profileId + QUICK_MODEL_SEPARATOR + modelId

    private fun decodeQuickModelRef(ref: String, fallbackProfileId: String): Pair<String, String> {
        val index = ref.indexOf(QUICK_MODEL_SEPARATOR)
        return if (index > 0) {
            ref.substring(0, index) to ref.substring(index + QUICK_MODEL_SEPARATOR.length)
        } else {
            fallbackProfileId to ref
        }
    }

    private fun loadAllQuickTextModels(
        profiles: List<ConnectionProfile>,
        disabled: Set<String>
    ): List<String> = profiles
        .asSequence()
        .filter { it.id !in disabled }
        .flatMap { profile -> loadQuickTextModels(profile.id).asSequence().map { quickModelRef(profile.id, it) } }
        .distinct()
        .take(10)
        .toList()

    private fun totalStoredQuickModels(profiles: List<ConnectionProfile>): Int =
        profiles.sumOf { loadQuickTextModels(it.id).size }

    private fun loadDisabledConnectionIds(): Set<String> =
        prefs.getStringSet("disabled_connection_profiles", emptySet())?.toSet() ?: emptySet()

    private fun defaultOpenRouterProfile() = ConnectionProfile(
        id = "openrouter",
        name = "OpenRouter",
        type = ProviderType.OPENROUTER,
        baseUrl = OpenRouterClient.DEFAULT_BASE_URL
    )

    private fun loadConnectionProfiles(): List<ConnectionProfile> = runCatching {
        val type = object : TypeToken<List<ConnectionProfile>>() {}.type
        val stored = gson.fromJson<List<ConnectionProfile>>(
            prefs.getString("connection_profiles_json", "[]") ?: "[]",
            type
        ).orEmpty().filter { it.id.isNotBlank() }
        val openRouter = stored.firstOrNull { it.id == "openrouter" }?.copy(
            name = "OpenRouter",
            type = ProviderType.OPENROUTER,
            baseUrl = normalizeBaseUrl(stored.first { it.id == "openrouter" }.baseUrl).ifBlank { OpenRouterClient.DEFAULT_BASE_URL }
        ) ?: defaultOpenRouterProfile()
        listOf(openRouter) + stored.filterNot { it.id == "openrouter" }
    }.getOrElse { listOf(defaultOpenRouterProfile()) }

    private fun saveConnectionProfiles(profiles: List<ConnectionProfile>) {
        prefs.edit().putString("connection_profiles_json", gson.toJson(profiles)).apply()
    }

    private fun openRouterProfile(): ConnectionProfile =
        _state.value.connectionProfiles.firstOrNull { it.id == "openrouter" } ?: defaultOpenRouterProfile()

    private fun activeConnectionProfile(): ConnectionProfile =
        _state.value.connectionProfiles.firstOrNull { it.id == _state.value.activeConnectionProfileId }
            ?: openRouterProfile()

    private fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')

    private fun isProfileConfigured(profile: ConnectionProfile): Boolean = when (profile.type) {
        ProviderType.OPENROUTER -> profile.baseUrl.isNotBlank() && !secrets.getProfileApiKey(profile.id).isNullOrBlank()
        ProviderType.OPENAI_COMPATIBLE -> profile.baseUrl.isNotBlank()
    }

    private fun connectionSetupMessage(profile: ConnectionProfile): String = when (profile.type) {
        ProviderType.OPENROUTER -> "Откройте «Подключения» и сохраните адрес и API-ключ OpenRouter"
        ProviderType.OPENAI_COMPATIBLE -> "Откройте «Подключения» и укажите адрес совместимого API"
    }

    private fun profilePrefKey(base: String, profileId: String): String =
        if (profileId == "openrouter") base else "${base}_profile_$profileId"

    private fun loadTextModelForProfile(profile: ConnectionProfile): String {
        val fallback = if (profile.type == ProviderType.OPENROUTER) "openrouter/auto" else ""
        return prefs.getString(profilePrefKey("text_model", profile.id), fallback) ?: fallback
    }

    private fun loadImageModelForProfile(profileId: String): String =
        prefs.getString(profilePrefKey("image_model", profileId), "bytedance-seed/seedream-4.5")
            ?: "bytedance-seed/seedream-4.5"
'''
s = replace_between(
    s,
    "    private fun loadQuickTextModels(profileId: String): List<String> = runCatching {\n",
    "    private fun loadReasoningEffortsByModel(): Map<String, ReasoningEffort> = runCatching {\n",
    helper_block,
    "connection helpers",
)
write(path, s)


# YmnikApp.kt
path = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
s = read(path)
ui_helpers = '''
private const val QUICK_MODEL_SEPARATOR = "\\u001F"
private fun quickModelRef(connectionId: String, modelId: String): String = connectionId + QUICK_MODEL_SEPARATOR + modelId
private fun quickModelConnectionId(ref: String, fallback: String = "openrouter"): String =
    if (QUICK_MODEL_SEPARATOR in ref) ref.substringBefore(QUICK_MODEL_SEPARATOR) else fallback
private fun quickModelId(ref: String): String =
    if (QUICK_MODEL_SEPARATOR in ref) ref.substringAfter(QUICK_MODEL_SEPARATOR) else ref
'''
s = replace_once(s, "\n@Composable\nfun YmnikApp", ui_helpers + "\n@Composable\nfun YmnikApp", "ui quick ref helpers")
s = replace_once(
    s,
    "    val openRouterProfile = activeProfile.type == ProviderType.OPENROUTER\n",
    "    val openRouterProfile = activeProfile.type == ProviderType.OPENROUTER\n    val openRouterAvailable = \"openrouter\" !in state.disabledConnectionIds\n",
    "openrouter availability",
)
s = replace_once(
    s,
    "enabled = !state.isLoading && (state.mode == ChatMode.IMAGE || openRouterProfile),",
    "enabled = !state.isLoading && (state.mode == ChatMode.IMAGE || openRouterAvailable),",
    "image action enabled",
)
s = s.replace("Недоступно для этого профиля", "Недоступно для этого подключения")

chat_header = '''@Composable
private fun ChatHeader(
    state: UiState,
    vm: ChatViewModel,
    onChats: () -> Unit,
    onProjects: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onClear: () -> Unit
) {
    var quickModelsOpen by remember { mutableStateOf(false) }
    var hubOpen by remember { mutableStateOf(false) }
    val activeTextModel = state.currentChatTextModel ?: state.textModel
    val displayedModel = if (state.mode == ChatMode.TEXT) activeTextModel else state.imageModel
    val shortModelName = displayedModel.substringAfter('/').ifBlank { displayedModel }
    val currentRef = quickModelRef(state.activeConnectionProfileId, activeTextModel)
    val defaultRef = quickModelRef(state.activeConnectionProfileId, state.textModel)
    val quickCandidates = (listOf(currentRef, defaultRef) + state.quickTextModels)
        .filter { quickModelId(it).isNotBlank() }
        .distinct()

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f)) {
                TextButton(
                    onClick = { if (state.mode == ChatMode.TEXT) quickModelsOpen = true },
                    enabled = !state.isLoading,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        shortModelName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.mode == ChatMode.TEXT) {
                        Spacer(Modifier.width(3.dp))
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Выбрать модель", modifier = Modifier.size(22.dp))
                    }
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

            IconButton(onClick = onNewChat, enabled = !state.isLoading, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Outlined.AddComment, contentDescription = "Новый чат", modifier = Modifier.size(24.dp))
            }

            Box {
                FilledTonalButton(
                    onClick = { hubOpen = true },
                    enabled = !state.isLoading,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    val currentProjectId = state.chats.firstOrNull { it.id == state.currentChatId }?.projectId
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = if (currentProjectId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Меню")
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
                            onClick = { hubOpen = false; onClear() }
                        )
                    }
                }
            }
        }
    }
}
'''
s = replace_between(s, "@Composable\nprivate fun ChatHeader(\n", "@Composable\nprivate fun ComposerActionTile(\n", chat_header, "ChatHeader")

s = replace_once(
    s,
    "    var connectionsExpanded by remember(state.apiKeyConfigured) { mutableStateOf(!state.apiKeyConfigured) }\n    var editingProfileId by remember(state.activeConnectionProfileId) { mutableStateOf(state.activeConnectionProfileId) }",
    "    var connectionsExpanded by remember { mutableStateOf(false) }\n    var editingProfileId by remember { mutableStateOf(\"openrouter\") }",
    "settings connection state",
)

connection_card = '''            item {
                val enabledCount = state.connectionProfiles.count { it.id !in state.disabledConnectionIds }
                ExpandableSettingsCard(
                    title = "Подключения",
                    subtitle = "Включено: $enabledCount из ${state.connectionProfiles.size}",
                    icon = Icons.Outlined.Language,
                    expanded = connectionsExpanded,
                    onToggle = { connectionsExpanded = !connectionsExpanded }
                ) {
                    Text(
                        "Включённые подключения доступны Umnik. Быстрая модель сама выбирает нужное подключение, поэтому отдельно переключать сервис перед запросом не нужно.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(9.dp))
                    state.connectionProfiles.forEach { profile ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    editingProfileId = profile.id
                                    connectionName = profile.name
                                    connectionUrl = profile.baseUrl
                                    connectionKey = ""
                                },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 7.dp)
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        profile.name,
                                        fontWeight = if (editingProfileId == profile.id) FontWeight.Bold else FontWeight.Medium
                                    )
                                    Text(
                                        if (profile.type == ProviderType.OPENROUTER) "OpenRouter" else "OpenAI-совместимое",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                            Switch(
                                checked = profile.id !in state.disabledConnectionIds,
                                onCheckedChange = { vm.setConnectionEnabled(profile.id, it) }
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                    }
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            val id = vm.addCompatibleProfile()
                            editingProfileId = id
                            connectionName = "Другой API"
                            connectionUrl = ""
                            connectionKey = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(7.dp))
                        Text("Добавить подключение")
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (editingProfile.type == ProviderType.OPENROUTER) "OpenRouter" else "Настройка подключения",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (editingProfile.type != ProviderType.OPENROUTER) {
                        Spacer(Modifier.height(7.dp))
                        OutlinedTextField(
                            value = connectionName,
                            onValueChange = { connectionName = it.take(60) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Название") },
                            singleLine = true
                        )
                    }
                    Spacer(Modifier.height(7.dp))
                    OutlinedTextField(
                        value = connectionUrl,
                        onValueChange = { connectionUrl = it.trim().take(240) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Адрес API") },
                        placeholder = {
                            Text(if (editingProfile.type == ProviderType.OPENROUTER) "https://openrouter.ai/api/v1" else "https://example.com/v1")
                        },
                        singleLine = true
                    )
                    Text(
                        if (editingProfile.type == ProviderType.OPENROUTER)
                            "Адрес можно изменить на случай изменения API у провайдера. Обычно оставьте значение по умолчанию."
                        else
                            "Umnik использует стандартные /models и /chat/completions. Для удалённого сервера используйте HTTPS.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                    Spacer(Modifier.height(7.dp))
                    OutlinedTextField(
                        value = connectionKey,
                        onValueChange = { connectionKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API-ключ") },
                        placeholder = { Text("Оставьте пустым, чтобы не менять сохранённый ключ") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            vm.saveConnectionProfile(
                                editingProfile.id,
                                connectionName,
                                connectionUrl,
                                connectionKey.takeIf { it.isNotBlank() }
                            )
                            connectionKey = ""
                        },
                        enabled = connectionUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сохранить подключение") }
                    if (editingProfile.type != ProviderType.OPENROUTER) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                vm.deleteConnectionProfile(editingProfile.id)
                                editingProfileId = "openrouter"
                                val openRouter = state.connectionProfiles.first { it.id == "openrouter" }
                                connectionName = openRouter.name
                                connectionUrl = openRouter.baseUrl
                                connectionKey = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Удалить подключение")
                        }
                    }
                    Text(
                        "Ключи хранятся локально и шифруются через Android Keystore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
'''
s = replace_between(
    s,
    '            item {\n                ExpandableSettingsCard(\n                    title = "Профили подключения",',
    '            item {\n                Column(\n                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),',
    connection_card,
    "connections settings card",
)

s = replace_once(
    s,
    "    val modelIds = (listOf(currentId, state.textModel) + state.quickTextModels)\n        .filter { it.isNotBlank() }\n        .distinct()",
    "    val activeQuickModels = state.quickTextModels\n        .filter { quickModelConnectionId(it, state.activeConnectionProfileId) == state.activeConnectionProfileId }\n        .map(::quickModelId)\n    val modelIds = (listOf(currentId, state.textModel) + activeQuickModels)\n        .filter { it.isNotBlank() }\n        .distinct()",
    "reasoning active connection quick models",
)

quick_dialog = '''@Composable
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
            "Закрепите до 10 моделей из разных подключений. В чате Umnik сам выберет нужное подключение при нажатии на модель.",
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
'''
s = replace_between(
    s,
    "@Composable\nprivate fun QuickModelsSettingsDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {\n",
    "@Composable\nprivate fun StorageDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {\n",
    quick_dialog,
    "QuickModelsSettingsDialog",
)
write(path, s)


# build version
path = "app/build.gradle.kts"
s = read(path)
s = s.replace("// Umnik v1.1.1", "// Umnik v1.2.0-beta.1", 1)
s = s.replace("versionCode = 29", "versionCode = 30", 1)
s = s.replace('versionName = "1.1.1"', 'versionName = "1.2.0-beta.1"', 1)
write(path, s)


# changelog
path = "CHANGELOG.md"
s = read(path)
marker = "## Unreleased\n"
section = '''## v1.2.0-beta.1 - 2026-09-11

- «Профили подключения» переименованы в понятные «Подключения».
- Подключения можно добавлять, удалять, включать и временно выключать независимо друг от друга; OpenRouter можно выключить, но нельзя удалить.
- Быстрые модели теперь могут одновременно относиться к разным включённым подключениям. В чате достаточно выбрать модель — Umnik сам использует связанное с ней подключение.
- Диалог запоминает подключение вместе с выбранной моделью, поэтому переключение между чатами не отправляет модель в неправильный API.
- OpenRouter сохраняет полный набор возможностей Umnik, включая фото, reasoning, web search и генерацию изображений; адрес API OpenRouter остаётся редактируемым.

'''
if "## v1.2.0-beta.1" not in s:
    if marker not in s:
        raise SystemExit("missing Unreleased changelog marker")
    s = s.replace(marker, marker + "\n" + section, 1)
write(path, s)


# release workflow: prerelease versions must never replace stable latest
path = ".github/workflows/android-release.yml"
s = read(path)
old = '''          gh release create "$RELEASE_TAG" \\
            "dist/Umnik-v${VERSION}.apk" \\
            "dist/Umnik-v${VERSION}.apk.sha256" \\
            "dist/Umnik-signing-certificate-sha256.txt" \\
            --verify-tag \\
            --title "Umnik v${VERSION}" \\
            --notes-file dist/release-notes.md \\
            --latest
'''
new = '''          RELEASE_KIND=(--latest)
          RELEASE_TITLE="Umnik v${VERSION}"
          if [[ "$VERSION" == *-* ]]; then
            RELEASE_KIND=(--prerelease)
            RELEASE_TITLE="Umnik v${VERSION} (тестовая)"
          fi

          gh release create "$RELEASE_TAG" \\
            "dist/Umnik-v${VERSION}.apk" \\
            "dist/Umnik-v${VERSION}.apk.sha256" \\
            "dist/Umnik-signing-certificate-sha256.txt" \\
            --verify-tag \\
            --title "$RELEASE_TITLE" \\
            --notes-file dist/release-notes.md \\
            "${RELEASE_KIND[@]}"
'''
s = replace_once(s, old, new, "prerelease release flags")
write(path, s)

print("v1.2 connection patch applied")
