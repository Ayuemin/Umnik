from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)

# ---------------- Models.kt ----------------
models_path = Path("app/src/main/java/com/ayuemin/ymnik/model/Models.kt")
models = models_path.read_text()
models = replace_once(
    models,
    '''data class ModelInfo(\n    val id: String,\n    val inputModalities: Set<String> = setOf("text"),\n    val supportedParameters: Set<String> = emptySet(),\n    val reasoningEfforts: Set<String> = emptySet()\n) {\n    fun accepts(modality: String): Boolean = modality.lowercase() in inputModalities\n''',
    '''data class ModelInfo(\n    val id: String,\n    val inputModalities: Set<String> = setOf("text"),\n    val supportedParameters: Set<String> = emptySet(),\n    val reasoningEfforts: Set<String> = emptySet(),\n    val parameterOptions: Map<String, List<String>> = emptyMap()\n) {\n    fun accepts(modality: String): Boolean = modality.lowercase() in inputModalities\n    fun parameterValues(parameter: String): List<String> = parameterOptions[parameter.lowercase()].orEmpty()\n''',
    "ModelInfo parameter options",
)
models = replace_once(
    models,
    '''    val imageConnectionProfileId: String = "openrouter",\n    val imageModel: String = "bytedance-seed/seedream-4.5",\n    val webSearchEnabled: Boolean = false,\n''',
    '''    val imageConnectionProfileId: String = "openrouter",\n    val imageModel: String = "bytedance-seed/seedream-4.5",\n    val imageAspectRatio: String? = null,\n    val imageResolution: String? = null,\n    val webSearchEnabled: Boolean = false,\n''',
    "UiState image params",
)
models_path.write_text(models)

# ---------------- OpenRouterClient.kt ----------------
client_path = Path("app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt")
client = client_path.read_text()
client = replace_once(
    client,
    '''                    val reasoningEfforts = item.getAsJsonObject("reasoning")\n                        ?.getAsJsonArray("supported_efforts")\n                        ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString?.lowercase() }\n                        ?.toSet()\n                        .orEmpty()\n                    ModelInfo(id, inputModalities, supportedParameters, reasoningEfforts)\n''',
    '''                    val reasoningEfforts = item.getAsJsonObject("reasoning")\n                        ?.getAsJsonArray("supported_efforts")\n                        ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString?.lowercase() }\n                        ?.toSet()\n                        .orEmpty()\n                    val parameterOptions = item.get("supported_parameters")\n                        ?.takeIf { it.isJsonObject }\n                        ?.asJsonObject\n                        ?.entrySet()\n                        ?.mapNotNull { (name, descriptor) ->\n                            val values = descriptor.takeIf { it.isJsonObject }\n                                ?.asJsonObject\n                                ?.get("values")\n                                ?.takeIf { it.isJsonArray }\n                                ?.asJsonArray\n                                ?.mapNotNull { value ->\n                                    value.takeIf { it.isJsonPrimitive }\n                                        ?.asString\n                                        ?.takeIf { it.isNotBlank() }\n                                }\n                                .orEmpty()\n                                .distinct()\n                            if (values.isEmpty()) null else name.lowercase() to values\n                        }\n                        ?.toMap()\n                        .orEmpty()\n                    ModelInfo(\n                        id = id,\n                        inputModalities = inputModalities,\n                        supportedParameters = supportedParameters,\n                        reasoningEfforts = reasoningEfforts,\n                        parameterOptions = parameterOptions\n                    )\n''',
    "OpenRouter image capability options",
)
client = replace_once(
    client,
    '''    suspend fun generateImage(\n        apiKey: String,\n        model: String,\n        prompt: String,\n        attachments: List<PendingAttachment>,\n        baseUrl: String = DEFAULT_BASE_URL\n    ): Result = withContext(Dispatchers.IO) {\n        val payload = JsonObject().apply {\n            addProperty("model", model)\n            addProperty("prompt", prompt.ifBlank { "Создай вариант приложенного изображения." })\n\n            val references = JsonArray()\n''',
    '''    suspend fun generateImage(\n        apiKey: String,\n        model: String,\n        prompt: String,\n        attachments: List<PendingAttachment>,\n        baseUrl: String = DEFAULT_BASE_URL,\n        aspectRatio: String? = null,\n        resolution: String? = null\n    ): Result = withContext(Dispatchers.IO) {\n        val payload = JsonObject().apply {\n            addProperty("model", model)\n            addProperty("prompt", prompt.ifBlank { "Создай вариант приложенного изображения." })\n            aspectRatio?.takeIf { it.isNotBlank() }?.let { addProperty("aspect_ratio", it) }\n            resolution?.takeIf { it.isNotBlank() }?.let { addProperty("resolution", it) }\n\n            val references = JsonArray()\n''',
    "OpenRouter image request params",
)
client_path.write_text(client)

# ---------------- ChatViewModel.kt ----------------
vm_path = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
vm = vm_path.read_text()
vm = replace_once(
    vm,
    '''    private val initialImageProfile = initialProfiles.firstOrNull { it.id == initialImageProfileId }\n        ?: defaultOpenRouterProfile()\n\n    private val _state = MutableStateFlow(\n''',
    '''    private val initialImageProfile = initialProfiles.firstOrNull { it.id == initialImageProfileId }\n        ?: defaultOpenRouterProfile()\n    private val initialImageModel = loadImageModelForProfile(initialImageProfile.id)\n    private val initialImageAspectRatio = loadImageParameter("aspect_ratio", initialImageProfile.id, initialImageModel)\n    private val initialImageResolution = loadImageParameter("resolution", initialImageProfile.id, initialImageModel)\n\n    private val _state = MutableStateFlow(\n''',
    "initial image parameters",
)
vm = replace_once(
    vm,
    '''            imageConnectionProfileId = initialImageProfileId,\n            imageModel = loadImageModelForProfile(initialImageProfile.id),\n            webSearchEnabled = prefs.getBoolean("web_search", false),\n''',
    '''            imageConnectionProfileId = initialImageProfileId,\n            imageModel = initialImageModel,\n            imageAspectRatio = initialImageAspectRatio,\n            imageResolution = initialImageResolution,\n            webSearchEnabled = prefs.getBoolean("web_search", false),\n''',
    "initial UiState image parameters",
)
vm = replace_once(
    vm,
    '''                _state.value = _state.value.copy(\n                    imageConnectionProfileId = fallbackImage.id,\n                    imageModel = loadImageModelForProfile(fallbackImage.id),\n                    availableImageModels = emptyList()\n                )\n''',
    '''                val fallbackImageModel = loadImageModelForProfile(fallbackImage.id)\n                _state.value = _state.value.copy(\n                    imageConnectionProfileId = fallbackImage.id,\n                    imageModel = fallbackImageModel,\n                    imageAspectRatio = loadImageParameter("aspect_ratio", fallbackImage.id, fallbackImageModel),\n                    imageResolution = loadImageParameter("resolution", fallbackImage.id, fallbackImageModel),\n                    availableImageModels = emptyList()\n                )\n''',
    "fallback image connection parameters",
)
vm = replace_once(
    vm,
    '''        _state.value = _state.value.copy(\n            connectionProfiles = profiles,\n            disabledConnectionIds = disabled,\n            chats = chats,\n            quickTextModels = loadAllQuickTextModels(profiles, disabled),\n            imageConnectionProfileId = nextImageProfileId,\n            imageModel = loadImageModelForProfile(nextImageProfileId),\n            availableImageModels = if (nextImageProfileId != _state.value.imageConnectionProfileId) emptyList() else _state.value.availableImageModels,\n''',
    '''        val nextImageModel = loadImageModelForProfile(nextImageProfileId)\n        _state.value = _state.value.copy(\n            connectionProfiles = profiles,\n            disabledConnectionIds = disabled,\n            chats = chats,\n            quickTextModels = loadAllQuickTextModels(profiles, disabled),\n            imageConnectionProfileId = nextImageProfileId,\n            imageModel = nextImageModel,\n            imageAspectRatio = loadImageParameter("aspect_ratio", nextImageProfileId, nextImageModel),\n            imageResolution = loadImageParameter("resolution", nextImageProfileId, nextImageModel),\n            availableImageModels = if (nextImageProfileId != _state.value.imageConnectionProfileId) emptyList() else _state.value.availableImageModels,\n''',
    "deleted connection image params",
)
vm = replace_once(
    vm,
    '''        _state.value = _state.value.copy(\n            imageConnectionProfileId = profile.id,\n            imageModel = clean,\n            availableImageModels = if (_state.value.modelCatalogConnectionId == profile.id) _state.value.modelCatalog else emptyList(),\n            status = "${clean.substringAfterLast('/')} · ${profile.name}"\n        )\n    }\n\n    fun toggleQuickTextModelForConnection(profileId: String, model: String) {\n''',
    '''        val available = if (_state.value.modelCatalogConnectionId == profile.id) _state.value.modelCatalog else emptyList()\n        val info = available.firstOrNull { it.id == clean }\n        _state.value = _state.value.copy(\n            imageConnectionProfileId = profile.id,\n            imageModel = clean,\n            imageAspectRatio = validatedImageParameter("aspect_ratio", profile.id, clean, info),\n            imageResolution = validatedImageParameter("resolution", profile.id, clean, info),\n            availableImageModels = available,\n            status = "${clean.substringAfterLast('/')} · ${profile.name}"\n        )\n    }\n\n    fun setImageAspectRatio(value: String?) {\n        setImageParameter("aspect_ratio", value)\n    }\n\n    fun setImageResolution(value: String?) {\n        setImageParameter("resolution", value)\n    }\n\n    private fun setImageParameter(parameter: String, value: String?) {\n        val profile = imageConnectionProfile()\n        val model = _state.value.imageModel\n        if (model.isBlank()) return\n        val clean = value?.trim()?.takeIf { it.isNotBlank() }\n        if (clean != null) {\n            val allowed = currentImageModelInfo()?.parameterValues(parameter).orEmpty()\n            if (allowed.isEmpty() || clean !in allowed) {\n                _state.value = _state.value.copy(status = "Выбранная модель не поддерживает параметр $clean")\n                return\n            }\n        }\n        val key = imageParameterPrefKey(parameter, profile.id, model)\n        prefs.edit().apply {\n            if (clean == null) remove(key) else putString(key, clean)\n        }.apply()\n        _state.value = when (parameter) {\n            "aspect_ratio" -> _state.value.copy(imageAspectRatio = clean)\n            "resolution" -> _state.value.copy(imageResolution = clean)\n            else -> _state.value\n        }\n    }\n\n    fun toggleQuickTextModelForConnection(profileId: String, model: String) {\n''',
    "select image model and parameter setters",
)
vm = replace_once(
    vm,
    '''                    ChatMode.IMAGE -> _state.value.copy(\n                        availableImageModels = infos,\n                        imageConnectionProfileId = profile.id,\n                        imageModel = loadImageModelForProfile(profile.id),\n                        isLoading = false,\n                        busyLabel = null\n                    )\n''',
    '''                    ChatMode.IMAGE -> {\n                        val selectedModel = loadImageModelForProfile(profile.id)\n                        val info = infos.firstOrNull { it.id == selectedModel }\n                        _state.value.copy(\n                            availableImageModels = infos,\n                            imageConnectionProfileId = profile.id,\n                            imageModel = selectedModel,\n                            imageAspectRatio = validatedImageParameter("aspect_ratio", profile.id, selectedModel, info),\n                            imageResolution = validatedImageParameter("resolution", profile.id, selectedModel, info),\n                            isLoading = false,\n                            busyLabel = null\n                        )\n                    }\n''',
    "refresh image models params",
)
vm = replace_once(
    vm,
    '''            next = next.copy(\n                availableImageModels = imageInfos ?: emptyList(),\n                imageConnectionProfileId = imageProfile.id,\n                imageModel = loadImageModelForProfile(imageProfile.id),\n                quickTextModels = loadAllQuickTextModels(next.connectionProfiles, next.disabledConnectionIds)\n            )\n''',
    '''            val selectedImageModel = loadImageModelForProfile(imageProfile.id)\n            val selectedImageInfo = imageInfos?.firstOrNull { it.id == selectedImageModel }\n            next = next.copy(\n                availableImageModels = imageInfos ?: emptyList(),\n                imageConnectionProfileId = imageProfile.id,\n                imageModel = selectedImageModel,\n                imageAspectRatio = validatedImageParameter("aspect_ratio", imageProfile.id, selectedImageModel, selectedImageInfo),\n                imageResolution = validatedImageParameter("resolution", imageProfile.id, selectedImageModel, selectedImageInfo),\n                quickTextModels = loadAllQuickTextModels(next.connectionProfiles, next.disabledConnectionIds)\n            )\n''',
    "refresh capabilities image params",
)
vm = replace_once(
    vm,
    '''        val imageModel = _state.value.imageModel\n        val webSearchEnabled = _state.value.webSearchEnabled\n''',
    '''        val imageModel = _state.value.imageModel\n        val imageAspectRatio = _state.value.imageAspectRatio\n        val imageResolution = _state.value.imageResolution\n        val webSearchEnabled = _state.value.webSearchEnabled\n''',
    "capture image parameters",
)
vm = replace_once(
    vm,
    '''                        if (profile.type == ProviderType.OPENROUTER) {\n                            api.generateImage(key, imageModel, imagePrompt, pending + projectImages, profile.baseUrl)\n                        } else {\n''',
    '''                        if (profile.type == ProviderType.OPENROUTER) {\n                            api.generateImage(\n                                key,\n                                imageModel,\n                                imagePrompt,\n                                pending + projectImages,\n                                profile.baseUrl,\n                                imageAspectRatio,\n                                imageResolution\n                            )\n                        } else {\n''',
    "legacy image mode parameters",
)
vm = replace_once(
    vm,
    '''        val imageModel = _state.value.imageModel\n        val requestId = ++requestGeneration\n''',
    '''        val imageModel = _state.value.imageModel\n        val imageAspectRatio = _state.value.imageAspectRatio\n        val imageResolution = _state.value.imageResolution\n        val requestId = ++requestGeneration\n''',
    "image prompt captured parameters",
)
vm = replace_once(
    vm,
    '''                        prompt = prompt,\n                        attachments = pending,\n                        baseUrl = profile.baseUrl\n                    )\n''',
    '''                        prompt = prompt,\n                        attachments = pending,\n                        baseUrl = profile.baseUrl,\n                        aspectRatio = imageAspectRatio,\n                        resolution = imageResolution\n                    )\n''',
    "image prompt request parameters",
)
vm = replace_once(
    vm,
    '''    private fun loadImageModelForProfile(profileId: String): String {\n        val fallback = if (profileId == "openrouter") "bytedance-seed/seedream-4.5" else ""\n        return prefs.getString(profilePrefKey("image_model", profileId), fallback) ?: fallback\n    }\n\n    private fun loadReasoningEffortsByModel(): Map<String, ReasoningEffort> = runCatching {\n''',
    '''    private fun loadImageModelForProfile(profileId: String): String {\n        val fallback = if (profileId == "openrouter") "bytedance-seed/seedream-4.5" else ""\n        return prefs.getString(profilePrefKey("image_model", profileId), fallback) ?: fallback\n    }\n\n    private fun imageParameterPrefKey(parameter: String, profileId: String, modelId: String): String =\n        "image_parameter_${parameter}_${profileId}_${modelId}"\n\n    private fun loadImageParameter(parameter: String, profileId: String, modelId: String): String? =\n        prefs.getString(imageParameterPrefKey(parameter, profileId, modelId), null)\n            ?.trim()\n            ?.takeIf { it.isNotBlank() }\n\n    private fun validatedImageParameter(\n        parameter: String,\n        profileId: String,\n        modelId: String,\n        info: ModelInfo?\n    ): String? {\n        val stored = loadImageParameter(parameter, profileId, modelId) ?: return null\n        if (info == null) return stored\n        return stored.takeIf { it in info.parameterValues(parameter) }\n    }\n\n    private fun loadReasoningEffortsByModel(): Map<String, ReasoningEffort> = runCatching {\n''',
    "image parameter preference helpers",
)
vm_path.write_text(vm)

# ---------------- YmnikApp.kt ----------------
ui_path = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
ui = ui_path.read_text()
ui = replace_once(
    ui,
    '''private fun quickModelId(ref: String): String =\n    if (QUICK_MODEL_SEPARATOR in ref) ref.substringAfter(QUICK_MODEL_SEPARATOR) else ref\n\n@Composable\nfun YmnikApp(viewModel: ChatViewModel) {\n''',
    '''private fun quickModelId(ref: String): String =\n    if (QUICK_MODEL_SEPARATOR in ref) ref.substringAfter(QUICK_MODEL_SEPARATOR) else ref\n\nprivate fun imageParameterSummary(state: UiState): String =\n    listOfNotNull(state.imageAspectRatio, state.imageResolution)\n        .ifEmpty { listOf("Авто") }\n        .joinToString(" · ")\n\n@Composable\nfun YmnikApp(viewModel: ChatViewModel) {\n''',
    "image parameter summary helper",
)
ui = replace_once(
    ui,
    '''                                    "Опишите задачу. Модель: ${state.imageModel.substringAfterLast('/').ifBlank { state.imageModel }} · ${imageProfile.name}",\n''',
    '''                                    "Опишите задачу · ${state.imageModel.substringAfterLast('/').ifBlank { state.imageModel }} · ${imageParameterSummary(state)}",\n''',
    "image generation banner summary",
)
ui = replace_once(
    ui,
    '''    var imageModelsExpanded by remember { mutableStateOf(false) }\n    var reasoningExpanded by remember { mutableStateOf(false) }\n''',
    '''    var imageModelsExpanded by remember { mutableStateOf(false) }\n    var imageParametersOpen by remember { mutableStateOf(false) }\n    var reasoningExpanded by remember { mutableStateOf(false) }\n''',
    "image parameter dialog state",
)
ui = replace_once(
    ui,
    '''                    FilledTonalButton(\n                        onClick = { modelPicker = ChatMode.IMAGE },\n                        modifier = Modifier.fillMaxWidth()\n                    ) {\n                        Icon(Icons.Outlined.Image, contentDescription = null)\n                        Spacer(Modifier.width(8.dp))\n                        Column(Modifier.weight(1f)) {\n                            Text("Выбрать модель", fontWeight = FontWeight.Medium)\n                            Text(\n                                state.imageModel,\n                                style = MaterialTheme.typography.bodySmall,\n                                maxLines = 1,\n                                overflow = TextOverflow.Ellipsis\n                            )\n                        }\n                    }\n                }\n            }\n''',
    '''                    FilledTonalButton(\n                        onClick = { modelPicker = ChatMode.IMAGE },\n                        modifier = Modifier.fillMaxWidth()\n                    ) {\n                        Icon(Icons.Outlined.Image, contentDescription = null)\n                        Spacer(Modifier.width(8.dp))\n                        Column(Modifier.weight(1f)) {\n                            Text("Выбрать модель", fontWeight = FontWeight.Medium)\n                            Text(\n                                state.imageModel,\n                                style = MaterialTheme.typography.bodySmall,\n                                maxLines = 1,\n                                overflow = TextOverflow.Ellipsis\n                            )\n                        }\n                    }\n                    Spacer(Modifier.height(7.dp))\n                    FilledTonalButton(\n                        onClick = { imageParametersOpen = true },\n                        modifier = Modifier.fillMaxWidth()\n                    ) {\n                        Icon(Icons.Outlined.Settings, contentDescription = null)\n                        Spacer(Modifier.width(8.dp))\n                        Column(Modifier.weight(1f)) {\n                            Text("Параметры изображения", fontWeight = FontWeight.Medium)\n                            Text(\n                                imageParameterSummary(state),\n                                style = MaterialTheme.typography.bodySmall,\n                                maxLines = 1,\n                                overflow = TextOverflow.Ellipsis\n                            )\n                        }\n                    }\n                }\n            }\n''',
    "image parameters settings button",
)
ui = replace_once(
    ui,
    '''    if (quickModelsSettingsOpen) {\n        QuickModelsSettingsDialog(state = state, vm = vm, onDismiss = { quickModelsSettingsOpen = false })\n    }\n}\n\n@Composable\nprivate fun ExpandableSettingsCard(\n''',
    '''    if (quickModelsSettingsOpen) {\n        QuickModelsSettingsDialog(state = state, vm = vm, onDismiss = { quickModelsSettingsOpen = false })\n    }\n    if (imageParametersOpen) {\n        ImageParametersDialog(state = state, vm = vm, onDismiss = { imageParametersOpen = false })\n    }\n}\n\n@Composable\nprivate fun ImageParametersDialog(\n    state: UiState,\n    vm: ChatViewModel,\n    onDismiss: () -> Unit\n) {\n    val info = state.availableImageModels.firstOrNull { it.id == state.imageModel }\n    val aspectRatios = info?.parameterValues("aspect_ratio").orEmpty()\n    val resolutions = info?.parameterValues("resolution").orEmpty()\n    val imageProfile = state.connectionProfiles.firstOrNull { it.id == state.imageConnectionProfileId }\n\n    LaunchedEffect(state.imageConnectionProfileId, state.imageModel) {\n        if (state.imageModel.isNotBlank() && info == null && !state.isLoading) {\n            vm.refreshModels(ChatMode.IMAGE)\n        }\n    }\n\n    AlertDialog(\n        onDismissRequest = onDismiss,\n        title = { Text("Параметры изображения") },\n        text = {\n            Column {\n                Text(\n                    "${state.imageModel.substringAfterLast('/').ifBlank { state.imageModel }} · ${imageProfile?.name ?: "Подключение"}",\n                    style = MaterialTheme.typography.bodySmall,\n                    color = MaterialTheme.colorScheme.onSurfaceVariant\n                )\n                Spacer(Modifier.height(14.dp))\n                Text("Соотношение сторон", fontWeight = FontWeight.SemiBold)\n                Spacer(Modifier.height(6.dp))\n                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {\n                    items(listOf("Авто") + aspectRatios) { option ->\n                        val auto = option == "Авто"\n                        FilterChip(\n                            selected = if (auto) state.imageAspectRatio == null else state.imageAspectRatio == option,\n                            onClick = { vm.setImageAspectRatio(if (auto) null else option) },\n                            label = { Text(option) }\n                        )\n                    }\n                }\n                Spacer(Modifier.height(14.dp))\n                Text("Разрешение", fontWeight = FontWeight.SemiBold)\n                Spacer(Modifier.height(6.dp))\n                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {\n                    items(listOf("Авто") + resolutions) { option ->\n                        val auto = option == "Авто"\n                        FilterChip(\n                            selected = if (auto) state.imageResolution == null else state.imageResolution == option,\n                            onClick = { vm.setImageResolution(if (auto) null else option) },\n                            label = { Text(option) }\n                        )\n                    }\n                }\n                if (aspectRatios.isEmpty() && resolutions.isEmpty()) {\n                    Spacer(Modifier.height(10.dp))\n                    Text(\n                        if (imageProfile?.type == ProviderType.OPENROUTER)\n                            "Модель не сообщила доступные параметры размера. Umnik оставит режим «Авто»."\n                        else\n                            "Совместимый API не сообщает Umnik единый список параметров размера. Для него используется режим «Авто».",\n                        style = MaterialTheme.typography.bodySmall,\n                        color = MaterialTheme.colorScheme.onSurfaceVariant\n                    )\n                }\n            }\n        },\n        confirmButton = {\n            TextButton(onClick = onDismiss) { Text("Готово") }\n        },\n        dismissButton = {\n            if (state.imageAspectRatio != null || state.imageResolution != null) {\n                TextButton(onClick = {\n                    vm.setImageAspectRatio(null)\n                    vm.setImageResolution(null)\n                }) { Text("Сбросить") }\n            }\n        }\n    )\n}\n\n@Composable\nprivate fun ExpandableSettingsCard(\n''',
    "image parameters dialog",
)
ui_path.write_text(ui)

# ---------------- Version + changelog ----------------
build_path = Path("app/build.gradle.kts")
build = build_path.read_text()
build = replace_once(build, "// Umnik v1.2.0-beta.5\n", "// Umnik v1.2.0-beta.6\n", "version comment")
build = replace_once(build, "versionCode = 34", "versionCode = 35", "version code")
build = replace_once(build, 'versionName = "1.2.0-beta.5"', 'versionName = "1.2.0-beta.6"', "version name")
build_path.write_text(build)

changelog_path = Path("CHANGELOG.md")
changelog = changelog_path.read_text()
insert = '''## v1.2.0-beta.6 - 2026-09-11\n\n- В сворачиваемом разделе «Генерация изображений» добавлена кнопка «Параметры изображения».\n- Для моделей OpenRouter Umnik читает допустимые `aspect_ratio` и `resolution` из `/images/models` и показывает только поддерживаемые моделью варианты.\n- Соотношение сторон и разрешение передаются в Image API отдельными параметрами, поэтому, например, `16:9` теперь задаётся не только текстом промпта.\n- Параметры запоминаются отдельно для каждой модели и подключения; режим «Авто» ничего лишнего в API не отправляет.\n- В плашке «Генерация изображения» перед отправкой видны текущие параметры, например `Seedream 4.5 · 16:9 · 2K`.\n- Для произвольных OpenAI-совместимых подключений параметры остаются в режиме «Авто», пока сервер не предоставляет единый совместимый способ сообщить поддерживаемые размеры.\n\n'''
changelog = replace_once(changelog, "## Unreleased\n\n", "## Unreleased\n\n" + insert, "changelog beta6")
changelog_path.write_text(changelog)
