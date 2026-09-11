from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_all(path: Path, old: str, new: str, expected_min: int, label: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count < expected_min:
        raise RuntimeError(f"{label}: expected at least {expected_min} matches, found {count}")
    path.write_text(text.replace(old, new), encoding="utf-8")

models = Path("app/src/main/java/com/ayuemin/ymnik/model/Models.kt")
replace_once(
    models,
    '    val quickTextModels: List<String> = emptyList(),\n    val imageModel: String = "bytedance-seed/seedream-4.5",\n',
    '    val quickTextModels: List<String> = emptyList(),\n    val imageConnectionProfileId: String = "openrouter",\n    val imageModel: String = "bytedance-seed/seedream-4.5",\n',
    "UiState image connection"
)

compatible = Path("app/src/main/java/com/ayuemin/ymnik/network/CompatibleApiClient.kt")
replace_once(compatible, 'import android.net.Uri\n', 'import android.net.Uri\nimport android.util.Base64\n', "compatible Base64 import")
replace_once(compatible, 'import com.ayuemin.ymnik.model.ChatMessage\n', 'import com.ayuemin.ymnik.model.ChatMessage\nimport com.ayuemin.ymnik.model.GeneratedFile\n', "compatible GeneratedFile import")
replace_once(compatible, 'import java.io.File\n', 'import java.io.File\nimport java.util.UUID\n', "compatible UUID import")
replace_once(
    compatible,
    '    private fun userText(prompt: String, attachments: List<PendingAttachment>): String = buildString {\n',
    '''    suspend fun generateImage(\n        apiKey: String,\n        baseUrl: String,\n        model: String,\n        prompt: String\n    ): OpenRouterClient.Result = withContext(Dispatchers.IO) {\n        val payload = JsonObject().apply {\n            addProperty("model", model)\n            addProperty("prompt", prompt.ifBlank { "Создай изображение." })\n        }\n        val builder = Request.Builder()\n            .url(endpoint(baseUrl, "images/generations"))\n            .header("Content-Type", "application/json")\n            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))\n        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")\n        val call = http.newCall(builder.build())\n        activeCall = call\n        try {\n            call.execute().use { response ->\n                val body = response.body?.string().orEmpty()\n                if (!response.isSuccessful) error(apiError(response.code, body))\n                val root = gson.fromJson(body, JsonObject::class.java)\n                val data = root.getAsJsonArray("data") ?: root.getAsJsonArray("images")\n                    ?: error("Совместимый API не вернул изображение")\n                val files = data.mapIndexedNotNull { index, element ->\n                    val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapIndexedNotNull null\n                    val mime = item.get("media_type")?.takeIf { it.isJsonPrimitive }?.asString\n                        ?.takeIf { it.startsWith("image/") }\n                    val encoded = item.get("b64_json")?.takeIf { it.isJsonPrimitive }?.asString\n                        ?.takeIf { it.isNotBlank() }\n                    if (encoded != null) {\n                        saveGeneratedImageBytes(Base64.decode(encoded.substringAfter("base64,", encoded), Base64.DEFAULT), mime ?: "image/png", index)\n                    } else {\n                        val url = item.get("url")?.takeIf { it.isJsonPrimitive }?.asString\n                            ?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null\n                        downloadGeneratedImage(url, mime, index)\n                    }\n                }\n                if (files.isEmpty()) error("Совместимый API вернул ответ без данных изображения")\n                OpenRouterClient.Result("Изображение создано.", files)\n            }\n        } finally {\n            activeCall = null\n        }\n    }\n\n    private fun saveGeneratedImageBytes(bytes: ByteArray, mimeType: String, index: Int): GeneratedFile {\n        val extension = when (mimeType.lowercase()) {\n            "image/jpeg", "image/jpg" -> "jpg"\n            "image/webp" -> "webp"\n            else -> "png"\n        }\n        val dir = File(context.filesDir, "generated").apply { mkdirs() }\n        val name = "umnik_image_${System.currentTimeMillis()}_${index + 1}.$extension"\n        val file = File(dir, "${UUID.randomUUID()}_$name")\n        file.writeBytes(bytes)\n        return GeneratedFile(UUID.randomUUID().toString(), name, mimeType, file.absolutePath, file.length())\n    }\n\n    private fun downloadGeneratedImage(url: String, hintedMime: String?, index: Int): GeneratedFile? {\n        if (url.startsWith("data:image/")) {\n            val mime = url.substringAfter("data:").substringBefore(';').takeIf { it.startsWith("image/") } ?: hintedMime ?: "image/png"\n            val bytes = Base64.decode(url.substringAfter("base64,", ""), Base64.DEFAULT)\n            return saveGeneratedImageBytes(bytes, mime, index)\n        }\n        return runCatching {\n            http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->\n                if (!response.isSuccessful) return@use null\n                val bytes = response.body?.bytes() ?: return@use null\n                val mime = response.header("Content-Type")?.substringBefore(';')\n                    ?.takeIf { it.startsWith("image/") } ?: hintedMime ?: "image/png"\n                saveGeneratedImageBytes(bytes, mime, index)\n            }\n        }.getOrNull()\n    }\n\n    private fun userText(prompt: String, attachments: List<PendingAttachment>): String = buildString {\n''',
    "compatible image generation"
)

vm = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
replace_once(
    vm,
    '''    private val initialProfile = initialProfiles.firstOrNull { it.id == initialProfileId }\n        ?: defaultOpenRouterProfile()\n\n    private val _state = MutableStateFlow(\n''',
    '''    private val initialProfile = initialProfiles.firstOrNull { it.id == initialProfileId }\n        ?: defaultOpenRouterProfile()\n    private val initialImageProfileId = prefs.getString("image_connection_profile", "openrouter")\n        ?.takeIf { id -> initialProfiles.any { it.id == id } && id !in initialDisabledConnectionIds }\n        ?: initialProfiles.firstOrNull { it.id == "openrouter" && it.id !in initialDisabledConnectionIds }?.id\n        ?: initialProfiles.firstOrNull { it.id !in initialDisabledConnectionIds }?.id\n        ?: "openrouter"\n    private val initialImageProfile = initialProfiles.firstOrNull { it.id == initialImageProfileId }\n        ?: defaultOpenRouterProfile()\n\n    private val _state = MutableStateFlow(\n''',
    "initial image profile"
)
replace_once(
    vm,
    '''            quickTextModels = loadAllQuickTextModels(initialProfiles, initialDisabledConnectionIds),\n            imageModel = loadImageModelForProfile("openrouter"),\n''',
    '''            quickTextModels = loadAllQuickTextModels(initialProfiles, initialDisabledConnectionIds),\n            imageConnectionProfileId = initialImageProfileId,\n            imageModel = loadImageModelForProfile(initialImageProfile.id),\n''',
    "initial image state"
)
replace_all(vm, '            imageModel = loadImageModelForProfile("openrouter"),\n', '            imageModel = loadImageModelForProfile(_state.value.imageConnectionProfileId),\n', 2, "remove OpenRouter image resets")
replace_once(vm, '            availableImageModels = if (profileId == "openrouter") emptyList() else _state.value.availableImageModels,\n', '            availableImageModels = if (profileId == _state.value.imageConnectionProfileId) emptyList() else _state.value.availableImageModels,\n', "clear selected image connection models")
replace_once(vm, '        if (active && profileId !in _state.value.disabledConnectionIds && isProfileConfigured(updated)) refreshModelCapabilities()\n', '        if ((active || profileId == _state.value.imageConnectionProfileId) && profileId !in _state.value.disabledConnectionIds && isProfileConfigured(updated)) refreshModelCapabilities()\n', "refresh edited image connection")

replace_once(
    vm,
    '''        _state.value = _state.value.copy(\n            disabledConnectionIds = disabled,\n            quickTextModels = loadAllQuickTextModels(_state.value.connectionProfiles, disabled),\n            modelCatalogConnectionId = null,\n            modelCatalog = emptyList(),\n            status = if (enabled) "Подключение «${profile.name}» включено" else "Подключение «${profile.name}» выключено"\n        )\n\n        if (!enabled && _state.value.activeConnectionProfileId == profileId) {\n''',
    '''        _state.value = _state.value.copy(\n            disabledConnectionIds = disabled,\n            quickTextModels = loadAllQuickTextModels(_state.value.connectionProfiles, disabled),\n            modelCatalogConnectionId = null,\n            modelCatalog = emptyList(),\n            status = if (enabled) "Подключение «${profile.name}» включено" else "Подключение «${profile.name}» выключено"\n        )\n\n        if (!enabled && _state.value.imageConnectionProfileId == profileId) {\n            val fallbackImage = _state.value.connectionProfiles.firstOrNull {\n                it.id !in disabled && it.id != profileId && isProfileConfigured(it)\n            } ?: _state.value.connectionProfiles.firstOrNull { it.id !in disabled && it.id != profileId }\n            if (fallbackImage != null) {\n                prefs.edit().putString("image_connection_profile", fallbackImage.id).apply()\n                _state.value = _state.value.copy(\n                    imageConnectionProfileId = fallbackImage.id,\n                    imageModel = loadImageModelForProfile(fallbackImage.id),\n                    availableImageModels = emptyList()\n                )\n                if (isProfileConfigured(fallbackImage)) refreshModelCapabilities()\n            } else {\n                _state.value = _state.value.copy(availableImageModels = emptyList())\n            }\n        }\n\n        if (!enabled && _state.value.activeConnectionProfileId == profileId) {\n''',
    "image connection fallback on disable"
)
replace_once(
    vm,
    '''        } else if (enabled && profileId == "openrouter" && isProfileConfigured(profile)) {\n            refreshModelCapabilities()\n        }\n''',
    '''        } else if (enabled && (profileId == _state.value.activeConnectionProfileId || profileId == _state.value.imageConnectionProfileId) && isProfileConfigured(profile)) {\n            refreshModelCapabilities()\n        }\n''',
    "refresh enabled connection"
)

replace_once(
    vm,
    '''        val fallback = profiles.firstOrNull { it.id !in disabled && isProfileConfigured(it) }\n            ?: profiles.firstOrNull { it.id !in disabled }\n            ?: profiles.first()\n        val chats = _state.value.chats.map { chat ->\n''',
    '''        val fallback = profiles.firstOrNull { it.id !in disabled && isProfileConfigured(it) }\n            ?: profiles.firstOrNull { it.id !in disabled }\n            ?: profiles.first()\n        val nextImageProfileId = if (_state.value.imageConnectionProfileId == profileId) fallback.id else _state.value.imageConnectionProfileId\n        if (nextImageProfileId != _state.value.imageConnectionProfileId) {\n            prefs.edit().putString("image_connection_profile", nextImageProfileId).apply()\n        }\n        val chats = _state.value.chats.map { chat ->\n''',
    "image fallback on delete"
)
replace_once(
    vm,
    '''            quickTextModels = loadAllQuickTextModels(profiles, disabled),\n            modelCatalogConnectionId = null,\n            modelCatalog = emptyList(),\n            status = "Подключение «${profile.name}» удалено"\n        )\n        if (_state.value.activeConnectionProfileId == profileId) selectConnectionProfile(fallback.id)\n''',
    '''            quickTextModels = loadAllQuickTextModels(profiles, disabled),\n            imageConnectionProfileId = nextImageProfileId,\n            imageModel = loadImageModelForProfile(nextImageProfileId),\n            availableImageModels = if (nextImageProfileId != _state.value.imageConnectionProfileId) emptyList() else _state.value.availableImageModels,\n            modelCatalogConnectionId = null,\n            modelCatalog = emptyList(),\n            status = "Подключение «${profile.name}» удалено"\n        )\n        if (_state.value.activeConnectionProfileId == profileId) selectConnectionProfile(fallback.id)\n        else if (nextImageProfileId != profileId && isProfileConfigured(imageConnectionProfile())) refreshModelCapabilities()\n''',
    "deleted image connection state"
)

replace_once(
    vm,
    '''    fun toggleQuickTextModelForConnection(profileId: String, model: String) {\n''',
    '''    fun loadImageConnectionModels(profileId: String) {\n        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return\n        if (profile.id in _state.value.disabledConnectionIds) {\n            _state.value = _state.value.copy(status = "Сначала включите подключение «${profile.name}»")\n            return\n        }\n        if (!isProfileConfigured(profile)) {\n            _state.value = _state.value.copy(status = connectionSetupMessage(profile))\n            return\n        }\n        val key = secrets.getProfileApiKey(profile.id).orEmpty()\n        viewModelScope.launch {\n            _state.value = _state.value.copy(isLoading = true, busyLabel = "Загружаю модели изображений…", status = null)\n            runCatching {\n                if (profile.type == ProviderType.OPENROUTER) api.imageModels(key, profile.baseUrl)\n                else compatibleApi.models(key, profile.baseUrl)\n            }.onSuccess { infos ->\n                _state.value = _state.value.copy(\n                    modelCatalogConnectionId = profile.id,\n                    modelCatalog = infos,\n                    isLoading = false,\n                    busyLabel = null\n                )\n            }.onFailure {\n                _state.value = _state.value.copy(\n                    isLoading = false,\n                    busyLabel = null,\n                    status = it.message ?: "Не удалось загрузить модели изображений"\n                )\n            }\n        }\n    }\n\n    fun selectImageModel(profileId: String, model: String) {\n        val clean = model.trim()\n        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return\n        if (clean.isBlank() || profile.id in _state.value.disabledConnectionIds) return\n        if (!isProfileConfigured(profile)) {\n            _state.value = _state.value.copy(status = connectionSetupMessage(profile))\n            return\n        }\n        prefs.edit()\n            .putString("image_connection_profile", profile.id)\n            .putString(profilePrefKey("image_model", profile.id), clean)\n            .apply()\n        _state.value = _state.value.copy(\n            imageConnectionProfileId = profile.id,\n            imageModel = clean,\n            availableImageModels = if (_state.value.modelCatalogConnectionId == profile.id) _state.value.modelCatalog else emptyList(),\n            status = "${clean.substringAfterLast('/')} · ${profile.name}"\n        )\n    }\n\n    fun toggleQuickTextModelForConnection(profileId: String, model: String) {\n''',
    "image connection model functions"
)

replace_once(
    vm,
    '''        if (mode == ChatMode.IMAGE) {\n            val openRouter = openRouterProfile()\n            if (openRouter.id in _state.value.disabledConnectionIds || !isProfileConfigured(openRouter)) {\n                _state.value = _state.value.copy(status = "Для создания изображений включите и настройте подключение OpenRouter")\n                return\n            }\n        }\n''',
    '''        if (mode == ChatMode.IMAGE) {\n            val imageProfile = imageConnectionProfile()\n            if (imageProfile.id in _state.value.disabledConnectionIds || !isProfileConfigured(imageProfile)) {\n                _state.value = _state.value.copy(status = "Для создания изображений включите и настройте выбранное подключение")\n                return\n            }\n        }\n''',
    "setMode image profile"
)
replace_once(
    vm,
    '''            ChatMode.IMAGE -> {\n                prefs.edit().putString(profilePrefKey("image_model", "openrouter"), clean).apply()\n                _state.value = _state.value.copy(imageModel = clean)\n            }\n''',
    '''            ChatMode.IMAGE -> selectImageModel(_state.value.imageConnectionProfileId, clean)\n''',
    "selectModel image profile"
)

replace_once(
    vm,
    '''    fun refreshModels(mode: ChatMode) {\n        val profile = if (mode == ChatMode.IMAGE) openRouterProfile() else activeConnectionProfile()\n''',
    '''    fun refreshModels(mode: ChatMode) {\n        val profile = if (mode == ChatMode.IMAGE) imageConnectionProfile() else activeConnectionProfile()\n''',
    "refresh image connection"
)
replace_once(
    vm,
    '''                    ChatMode.IMAGE -> _state.value.copy(\n                        availableImageModels = infos,\n                        imageModel = loadImageModelForProfile("openrouter"),\n                        isLoading = false,\n                        busyLabel = null\n                    )\n''',
    '''                    ChatMode.IMAGE -> _state.value.copy(\n                        availableImageModels = infos,\n                        imageConnectionProfileId = profile.id,\n                        imageModel = loadImageModelForProfile(profile.id),\n                        isLoading = false,\n                        busyLabel = null\n                    )\n''',
    "refresh image state"
)

replace_once(
    vm,
    '''    private fun refreshModelCapabilities() {\n        val profile = activeConnectionProfile()\n        val openRouter = openRouterProfile()\n''',
    '''    private fun refreshModelCapabilities() {\n        val profile = activeConnectionProfile()\n        val imageProfile = imageConnectionProfile()\n''',
    "capabilities image profile variable"
)
replace_once(
    vm,
    '''            val imageInfos = if (openRouter.id !in _state.value.disabledConnectionIds && isProfileConfigured(openRouter)) {\n                val key = secrets.getProfileApiKey(openRouter.id).orEmpty()\n                runCatching { api.imageModels(key, openRouter.baseUrl) }.getOrNull()\n            } else emptyList()\n''',
    '''            val imageInfos = if (imageProfile.id !in _state.value.disabledConnectionIds && isProfileConfigured(imageProfile)) {\n                val key = secrets.getProfileApiKey(imageProfile.id).orEmpty()\n                runCatching {\n                    if (imageProfile.type == ProviderType.OPENROUTER) api.imageModels(key, imageProfile.baseUrl)\n                    else compatibleApi.models(key, imageProfile.baseUrl)\n                }.getOrNull()\n            } else emptyList()\n''',
    "capabilities image loading"
)
replace_once(
    vm,
    '''                availableImageModels = imageInfos ?: emptyList(),\n                imageModel = loadImageModelForProfile("openrouter"),\n                quickTextModels = loadAllQuickTextModels(next.connectionProfiles, next.disabledConnectionIds)\n''',
    '''                availableImageModels = imageInfos ?: emptyList(),\n                imageConnectionProfileId = imageProfile.id,\n                imageModel = loadImageModelForProfile(imageProfile.id),\n                quickTextModels = loadAllQuickTextModels(next.connectionProfiles, next.disabledConnectionIds)\n''',
    "capabilities selected image model"
)

replace_once(
    vm,
    '''    private fun imageAttachmentAllowed(attachment: PendingAttachment): Pair<Boolean, String?> {\n        if (!attachment.mimeType.startsWith("image/")) {\n''',
    '''    private fun imageAttachmentAllowed(attachment: PendingAttachment): Pair<Boolean, String?> {\n        if (imageConnectionProfile().type != ProviderType.OPENROUTER) {\n            return false to "Изображения-референсы для произвольного совместимого API пока не включены; текстовая генерация доступна через /images/generations"\n        }\n        if (!attachment.mimeType.startsWith("image/")) {\n''',
    "generic image refs guard"
)

replace_once(
    vm,
    '''    fun prepareImageGeneration(): Boolean {\n        if (_state.value.isLoading) return false\n        val openRouter = openRouterProfile()\n        if (openRouter.id in _state.value.disabledConnectionIds) {\n            _state.value = _state.value.copy(status = "Для генерации изображений включите подключение OpenRouter")\n            return false\n        }\n        if (!isProfileConfigured(openRouter)) {\n            _state.value = _state.value.copy(status = connectionSetupMessage(openRouter))\n            return false\n        }\n        if (_state.value.availableImageModels.isEmpty()) refreshModels(ChatMode.IMAGE)\n        return true\n    }\n\n    fun send(text: String) {\n        val profile = if (_state.value.mode == ChatMode.IMAGE) openRouterProfile() else activeConnectionProfile()\n''',
    '''    fun prepareImageGeneration(): Boolean {\n        if (_state.value.isLoading) return false\n        val profile = imageConnectionProfile()\n        if (profile.id in _state.value.disabledConnectionIds) {\n            _state.value = _state.value.copy(status = "Для генерации изображений включите выбранное подключение")\n            return false\n        }\n        if (!isProfileConfigured(profile)) {\n            _state.value = _state.value.copy(status = connectionSetupMessage(profile))\n            return false\n        }\n        if (_state.value.imageModel.isBlank()) {\n            _state.value = _state.value.copy(status = "Сначала выберите модель генерации изображений в настройках")\n            return false\n        }\n        if (_state.value.availableImageModels.isEmpty()) refreshModels(ChatMode.IMAGE)\n        return true\n    }\n\n    fun send(text: String) {\n        val profile = if (_state.value.mode == ChatMode.IMAGE) imageConnectionProfile() else activeConnectionProfile()\n''',
    "prepare image generation"
)
replace_once(
    vm,
    '''        if (_state.value.mode == ChatMode.IMAGE && profile.type != ProviderType.OPENROUTER) {\n            _state.value = _state.value.copy(status = "Генерация изображений сейчас доступна через профиль OpenRouter")\n            return\n        }\n\n''',
    '',
    "remove OpenRouter-only send guard"
)
replace_once(
    vm,
    '''                        api.generateImage(key, imageModel, listOf(projectPrefix, clean).filter { it.isNotBlank() }.joinToString("\\n\\n"), pending + projectImages, profile.baseUrl)\n''',
    '''                        val imagePrompt = listOf(projectPrefix, clean).filter { it.isNotBlank() }.joinToString("\\n\\n")\n                        if (profile.type == ProviderType.OPENROUTER) {\n                            api.generateImage(key, imageModel, imagePrompt, pending + projectImages, profile.baseUrl)\n                        } else {\n                            compatibleApi.generateImage(key, profile.baseUrl, imageModel, imagePrompt)\n                        }\n''',
    "legacy image generation transport"
)

replace_once(
    vm,
    '''    fun sendImagePrompt(text: String): Boolean {\n        if (_state.value.isLoading) return false\n        val profile = openRouterProfile()\n        if (profile.id in _state.value.disabledConnectionIds) {\n            _state.value = _state.value.copy(status = "Для генерации изображений включите подключение OpenRouter")\n''',
    '''    fun sendImagePrompt(text: String): Boolean {\n        if (_state.value.isLoading) return false\n        val profile = imageConnectionProfile()\n        if (profile.id in _state.value.disabledConnectionIds) {\n            _state.value = _state.value.copy(status = "Для генерации изображений включите выбранное подключение")\n''',
    "isolated image selected profile"
)
replace_once(
    vm,
    '''            val operation = runCatching {\n                api.generateImage(\n                    apiKey = key,\n                    model = imageModel,\n                    prompt = prompt,\n                    attachments = pending,\n                    baseUrl = profile.baseUrl\n                )\n            }\n''',
    '''            val operation = runCatching {\n                if (profile.type == ProviderType.OPENROUTER) {\n                    api.generateImage(\n                        apiKey = key,\n                        model = imageModel,\n                        prompt = prompt,\n                        attachments = pending,\n                        baseUrl = profile.baseUrl\n                    )\n                } else {\n                    compatibleApi.generateImage(\n                        apiKey = key,\n                        baseUrl = profile.baseUrl,\n                        model = imageModel,\n                        prompt = prompt\n                    )\n                }\n            }\n''',
    "isolated image transport"
)

replace_once(
    vm,
    '''    private fun playReadySound() {\n''',
    '''    fun previewAnswerSound() {\n        playReadySound()\n    }\n\n    private fun playReadySound() {\n''',
    "public sound preview"
)
replace_once(vm, '                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)\n', '                                .setUsage(AudioAttributes.USAGE_MEDIA)\n', "custom sound audio usage")
replace_once(vm, '                AudioManager.STREAM_NOTIFICATION,\n', '                AudioManager.STREAM_MUSIC,\n', "default sound audio stream")

replace_once(
    vm,
    '''    private fun activeConnectionProfile(): ConnectionProfile =\n        _state.value.connectionProfiles.firstOrNull { it.id == _state.value.activeConnectionProfileId }\n            ?: openRouterProfile()\n\n    private fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')\n''',
    '''    private fun activeConnectionProfile(): ConnectionProfile =\n        _state.value.connectionProfiles.firstOrNull { it.id == _state.value.activeConnectionProfileId }\n            ?: openRouterProfile()\n\n    private fun imageConnectionProfile(): ConnectionProfile =\n        _state.value.connectionProfiles.firstOrNull { it.id == _state.value.imageConnectionProfileId }\n            ?: openRouterProfile()\n\n    private fun normalizeBaseUrl(value: String): String = value.trim().trimEnd('/')\n''',
    "image connection helper"
)
replace_once(
    vm,
    '''    private fun loadImageModelForProfile(profileId: String): String =\n        prefs.getString(profilePrefKey("image_model", profileId), "bytedance-seed/seedream-4.5")\n            ?: "bytedance-seed/seedream-4.5"\n''',
    '''    private fun loadImageModelForProfile(profileId: String): String {\n        val fallback = if (profileId == "openrouter") "bytedance-seed/seedream-4.5" else ""\n        return prefs.getString(profilePrefKey("image_model", profileId), fallback) ?: fallback\n    }\n''',
    "profile-specific image default"
)

ui = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
replace_once(
    ui,
    '''    val openRouterProfile = activeProfile.type == ProviderType.OPENROUTER\n    val openRouterAvailable = "openrouter" !in state.disabledConnectionIds\n    val activeTextModel = state.currentChatTextModel ?: state.textModel\n''',
    '''    val openRouterProfile = activeProfile.type == ProviderType.OPENROUTER\n    val imageProfile = state.connectionProfiles.firstOrNull { it.id == state.imageConnectionProfileId }\n        ?: state.connectionProfiles.first()\n    val imageConnectionAvailable = imageProfile.id !in state.disabledConnectionIds\n    val activeTextModel = state.currentChatTextModel ?: state.textModel\n''',
    "chat selected image connection"
)
replace_once(
    ui,
    '''    val cameraAvailable = if (imagePromptMode) {\n        imageModelInfo?.accepts("image") != false\n    } else {\n''',
    '''    val cameraAvailable = if (imagePromptMode) {\n        imageProfile.type == ProviderType.OPENROUTER && imageModelInfo?.accepts("image") != false\n    } else {\n''',
    "camera generic image guard"
)
replace_once(
    ui,
    '''                                    "Опишите задачу. Модель: ${state.imageModel.substringAfterLast('/').ifBlank { state.imageModel }}",\n''',
    '''                                    "Опишите задачу. Модель: ${state.imageModel.substringAfterLast('/').ifBlank { state.imageModel }} · ${imageProfile.name}",\n''',
    "image prompt provider label"
)
replace_once(ui, '                        enabled = !state.isLoading && openRouterAvailable,\n', '                        enabled = !state.isLoading && imageConnectionAvailable,\n', "create tile image connection")

start = ui.read_text(encoding="utf-8")
fn_start = start.index('@Composable\nprivate fun ModelPickerDialog(')
fn_end = start.index('\n@Composable\nprivate fun EmptyChatCard', fn_start)
new_picker = '''@Composable\nprivate fun ModelPickerDialog(\n    mode: ChatMode,\n    state: UiState,\n    vm: ChatViewModel,\n    onDismiss: () -> Unit\n) {\n    var query by remember(mode) { mutableStateOf("") }\n    val enabledConnections = state.connectionProfiles.filter { it.id !in state.disabledConnectionIds }\n    var selectedImageConnectionId by remember(mode, enabledConnections.map { it.id }) {\n        mutableStateOf(\n            state.imageConnectionProfileId.takeIf { id -> enabledConnections.any { it.id == id } }\n                ?: enabledConnections.firstOrNull()?.id\n        )\n    }\n    val selectedImageConnection = enabledConnections.firstOrNull { it.id == selectedImageConnectionId }\n    val models = if (mode == ChatMode.TEXT) {\n        state.availableTextModels\n    } else {\n        if (state.modelCatalogConnectionId == selectedImageConnectionId) state.modelCatalog else emptyList()\n    }\n    val current = if (mode == ChatMode.TEXT) state.textModel else state.imageModel\n\n    LaunchedEffect(mode, selectedImageConnectionId) {\n        if (mode == ChatMode.TEXT) {\n            if (models.isEmpty()) vm.refreshModels(mode)\n        } else {\n            selectedImageConnectionId?.let(vm::loadImageConnectionModels)\n        }\n    }\n\n    val filtered = remember(models, query) {\n        models.filter { it.id.contains(query.trim(), ignoreCase = true) }.take(300)\n    }\n\n    FullScreenPanel(\n        title = if (mode == ChatMode.TEXT) "Текстовая модель" else "Модель изображений",\n        onBack = onDismiss\n    ) {\n        Text(\n            if (mode == ChatMode.TEXT) "Сейчас: $current" else "Сейчас: ${state.connectionProfiles.firstOrNull { it.id == state.imageConnectionProfileId }?.name ?: "Подключение"} · $current",\n            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),\n            style = MaterialTheme.typography.bodySmall,\n            color = MaterialTheme.colorScheme.onSurfaceVariant,\n            maxLines = 2,\n            overflow = TextOverflow.Ellipsis\n        )\n        if (mode == ChatMode.IMAGE) {\n            if (enabledConnections.isEmpty()) {\n                Text(\n                    "Нет включённых подключений.",\n                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),\n                    color = MaterialTheme.colorScheme.onSurfaceVariant\n                )\n            } else {\n                LazyRow(\n                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),\n                    horizontalArrangement = Arrangement.spacedBy(7.dp)\n                ) {\n                    items(enabledConnections, key = { it.id }) { connection ->\n                        FilterChip(\n                            selected = selectedImageConnectionId == connection.id,\n                            onClick = {\n                                selectedImageConnectionId = connection.id\n                                query = ""\n                            },\n                            label = { Text(connection.name, maxLines = 1) }\n                        )\n                    }\n                }\n                if (selectedImageConnection?.type == ProviderType.OPENAI_COMPATIBLE) {\n                    Text(\n                        "Для совместимого API показан общий список /models. Выберите модель, которая умеет генерацию изображений; запрос отправится на /images/generations.",\n                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),\n                        style = MaterialTheme.typography.bodySmall,\n                        color = MaterialTheme.colorScheme.onSurfaceVariant\n                    )\n                }\n            }\n        }\n        OutlinedTextField(\n            value = query,\n            onValueChange = { query = it },\n            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),\n            singleLine = true,\n            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },\n            placeholder = { Text("Поиск модели") }\n        )\n        Row(\n            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),\n            horizontalArrangement = Arrangement.End\n        ) {\n            TextButton(onClick = {\n                if (mode == ChatMode.TEXT) vm.refreshModels(mode)\n                else selectedImageConnectionId?.let(vm::loadImageConnectionModels)\n            }) {\n                Icon(Icons.Outlined.Refresh, contentDescription = null)\n                Spacer(Modifier.width(5.dp))\n                Text("Обновить")\n            }\n        }\n        if (filtered.isEmpty()) {\n            Text(\n                if (state.isLoading) "Загрузка списка…" else "Модели не найдены",\n                modifier = Modifier.padding(20.dp),\n                color = MaterialTheme.colorScheme.onSurfaceVariant\n            )\n        } else {\n            LazyColumn(\n                modifier = Modifier.weight(1f).fillMaxWidth(),\n                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)\n            ) {\n                items(filtered, key = { it.id }) { modelInfo ->\n                    TextButton(\n                        onClick = {\n                            if (mode == ChatMode.TEXT) vm.selectModel(mode, modelInfo.id)\n                            else selectedImageConnectionId?.let { vm.selectImageModel(it, modelInfo.id) }\n                            onDismiss()\n                        },\n                        modifier = Modifier.fillMaxWidth(),\n                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)\n                    ) {\n                        Text(\n                            modelInfo.id,\n                            modifier = Modifier.fillMaxWidth(),\n                            maxLines = 2,\n                            overflow = TextOverflow.Ellipsis\n                        )\n                    }\n                    HorizontalDivider()\n                }\n            }\n        }\n    }\n}\n'''
ui.write_text(start[:fn_start] + new_picker + start[fn_end:], encoding="utf-8")

replace_once(
    ui,
    '''                ExpandableSettingsCard(\n                    title = "Генерация изображений",\n                    subtitle = state.imageModel.substringAfterLast('/').ifBlank { state.imageModel },\n''',
    '''                val imageConnectionName = state.connectionProfiles.firstOrNull { it.id == state.imageConnectionProfileId }?.name ?: "Подключение"\n                ExpandableSettingsCard(\n                    title = "Генерация изображений",\n                    subtitle = "${state.imageModel.substringAfterLast('/').ifBlank { state.imageModel }} · $imageConnectionName",\n''',
    "image settings provider subtitle"
)
replace_once(
    ui,
    '''                        Slider(\n                            value = state.answerSoundVolume.toFloat(),\n                            onValueChange = { vm.setAnswerSoundVolume(it.toInt()) },\n                            valueRange = 0f..100f\n                        )\n''',
    '''                        Slider(\n                            value = state.answerSoundVolume.toFloat(),\n                            onValueChange = { vm.setAnswerSoundVolume(it.toInt()) },\n                            valueRange = 0f..100f\n                        )\n                        FilledTonalButton(\n                            onClick = vm::previewAnswerSound,\n                            modifier = Modifier.fillMaxWidth()\n                        ) {\n                            Icon(Icons.Outlined.VolumeUp, contentDescription = null)\n                            Spacer(Modifier.width(7.dp))\n                            Text("Проверить звук")\n                        }\n''',
    "sound preview button"
)

build = Path("app/build.gradle.kts")
replace_once(build, '// Umnik v1.2.0-beta.2\n', '// Umnik v1.2.0-beta.3\n', "version comment")
replace_once(build, '        versionCode = 31\n        versionName = "1.2.0-beta.2"\n', '        versionCode = 32\n        versionName = "1.2.0-beta.3"\n', "version beta3")

changelog = Path("CHANGELOG.md")
replace_once(
    changelog,
    '## Unreleased\n\n',
    '''## Unreleased\n\n## v1.2.0-beta.3 - 2026-09-11\n\n- Модель генерации изображений теперь можно выбирать из любого включённого подключения, а не только из OpenRouter.\n- Для OpenAI-совместимых подключений Umnik использует стандартный `/images/generations`; список моделей берётся из `/models`, поэтому пользователь сам выбирает подходящую image-модель.\n- Изображения-референсы пока оставлены только для OpenRouter, потому что формат image edit у совместимых API не унифицирован.\n- Исправлен звук готового ответа: основной и пользовательский сигнал теперь используют медиаканал устройства вместо канала уведомлений.\n- В настройках звука добавлена кнопка «Проверить звук».\n\n''',
    "changelog beta3"
)

print("beta.3 image connections and sound patch applied")
