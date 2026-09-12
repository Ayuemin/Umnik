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


# ---------------------------------------------------------------------------
# 1. Chat lifecycle: do not accumulate blank chats + clear all history.
# ---------------------------------------------------------------------------
vm_path = ROOT / "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
vm = vm_path.read_text(encoding="utf-8")

new_create_chat = r'''    private fun isBareEmptyChat(chat: ChatSession): Boolean =
        chat.messages.isEmpty() &&
            chat.chatFiles.orEmpty().isEmpty() &&
            !chat.isFavorite &&
            chat.title == "Новый чат" &&
            chat.assignedRole.isNullOrBlank() &&
            chat.masterPrompt.isNullOrBlank() &&
            chat.textModelOverride.isNullOrBlank()

    fun createChat(projectId: String? = null): String {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return _state.value.currentChatId

        // A second tap on "New chat" while the current chat is still a pristine
        // empty placeholder should not create another persisted row. Reuse it.
        val current = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId }
        if (current != null && current.projectId == projectId && isBareEmptyChat(current)) {
            _state.value = _state.value.copy(
                pendingAttachments = emptyList(),
                status = null
            )
            return current.id
        }

        val chat = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "Новый чат",
            projectId = projectId,
            mode = ChatMode.TEXT,
            connectionProfileId = _state.value.activeConnectionProfileId
        )

        // Clean legacy duplicates created by older versions: only global blank
        // placeholders are disposable. Project membership is treated as a chat setting.
        val retained = _state.value.chats.filterNot { old ->
            old.id != _state.value.currentChatId && old.projectId == null && isBareEmptyChat(old)
        }
        val next = listOf(chat) + retained
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
            storageStats = storageRepository.stats(),
            status = null
        )
        return chat.id
    }'''

vm = replace_block(
    vm,
    "    fun createChat(projectId: String? = null): String {",
    "    fun branchFromMessage(messageId: String): String? {",
    new_create_chat,
    "createChat lifecycle",
)

clear_all = r'''    fun clearAllChats() {
        cleanupTempAttachments(_state.value.pendingAttachments)
        if (_state.value.isLoading) return

        _state.value.chats.forEach { chat ->
            chatFilesRepository.deleteChat(chat.id)
        }

        val chat = ChatSession(
            id = UUID.randomUUID().toString(),
            title = "Новый чат",
            mode = ChatMode.TEXT,
            connectionProfileId = _state.value.activeConnectionProfileId
        )
        val modelId = _state.value.textModel
        val info = _state.value.availableTextModels.firstOrNull { it.id == modelId }
        val effort = preferredReasoningEffort(modelId, info)
        val keepReasoning = reasoningStillValid(info, effort)

        chatsRepository.save(listOf(chat))
        prefs.edit()
            .putString("current_chat_id", chat.id)
            .putString("chat_mode", ChatMode.TEXT.name)
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", keepReasoning)
            .apply()

        _state.value = _state.value.copy(
            chats = listOf(chat),
            currentChatId = chat.id,
            messages = emptyList(),
            mode = ChatMode.TEXT,
            currentChatTextModel = null,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning,
            pendingAttachments = emptyList(),
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "История чатов очищена"
        )
    }
'''

vm = replace_once(
    vm,
    "    fun updateChatProfile(id: String, title: String, role: String, masterPrompt: String) {",
    clear_all + "\n    fun updateChatProfile(id: String, title: String, role: String, masterPrompt: String) {",
    "clear all chats function",
)

old_load_image = r'''    private fun loadImageModelForProfile(profileId: String): String {
        val profile = initialProfiles.firstOrNull { it.id == profileId }
            ?: runCatching { _state.value.connectionProfiles.firstOrNull { it.id == profileId } }.getOrNull()
        val fallback = when (profile?.type) {
            ProviderType.OPENROUTER -> "bytedance-seed/seedream-4.5"
            ProviderType.NVIDIA -> providerRegistry.imageModels(ProviderType.NVIDIA).firstOrNull()?.id.orEmpty()
            else -> ""
        }
        return prefs.getString(profilePrefKey("image_model", profileId), fallback) ?: fallback
    }'''

new_load_image = r'''    private fun loadImageModelForProfile(profileId: String): String {
        val profile = initialProfiles.firstOrNull { it.id == profileId }
            ?: runCatching { _state.value.connectionProfiles.firstOrNull { it.id == profileId } }.getOrNull()
        val registryModels = if (profile?.type == ProviderType.NVIDIA) {
            providerRegistry.imageModels(ProviderType.NVIDIA)
        } else emptyList()
        val fallback = when (profile?.type) {
            ProviderType.OPENROUTER -> "bytedance-seed/seedream-4.5"
            ProviderType.NVIDIA -> registryModels.firstOrNull()?.id.orEmpty()
            else -> ""
        }
        val key = profilePrefKey("image_model", profileId)
        val stored = prefs.getString(key, fallback)?.trim().orEmpty()

        // NVIDIA's hosted visual catalog can change independently of the text API.
        // If a previously selected image endpoint has been removed from Umnik's
        // verified registry, migrate to the first verified model instead of silently
        // keeping a model that can hang forever.
        if (profile?.type == ProviderType.NVIDIA && registryModels.isNotEmpty() && registryModels.none { it.id == stored }) {
            prefs.edit().putString(key, fallback).apply()
            return fallback
        }
        return stored.ifBlank { fallback }
    }'''

vm = replace_once(vm, old_load_image, new_load_image, "NVIDIA image model migration")
vm_path.write_text(vm, encoding="utf-8")


# ---------------------------------------------------------------------------
# 2. Chat history UI: clear all chats with explicit confirmation.
# ---------------------------------------------------------------------------
project_ui_path = ROOT / "app/src/main/java/com/ayuemin/ymnik/ui/ProjectDialogs.kt"
project_ui = project_ui_path.read_text(encoding="utf-8")
project_ui = replace_once(
    project_ui,
    "import androidx.compose.material.icons.outlined.DeleteOutline\n",
    "import androidx.compose.material.icons.outlined.DeleteForever\nimport androidx.compose.material.icons.outlined.DeleteOutline\n",
    "DeleteForever import",
)

new_chats_hub = r'''@Composable
fun ChatsHubDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {
    var editorId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<ChatSession?>(null) }
    var clearAllConfirm by remember { mutableStateOf(false) }
    val chats = state.chats.sortedWith(compareByDescending<ChatSession> { it.isFavorite }.thenByDescending { it.updatedAt })
    val favorites = chats.filter { it.isFavorite }
    val others = chats.filterNot { it.isFavorite }

    FullScreenPanel(title = "История чатов", onBack = onDismiss) {
        FilledTonalButton(
            onClick = {
                vm.createChat()
                onDismiss()
            },
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Новый чат")
        }

        TextButton(
            onClick = { clearAllConfirm = true },
            enabled = !state.isLoading && chats.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        ) {
            Icon(Icons.Outlined.DeleteForever, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Очистить все чаты")
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
        ) {
            if (favorites.isNotEmpty()) {
                item { SectionTitle("Избранные") }
                items(favorites, key = { it.id }) { chat ->
                    ChatHubRow(chat, state, vm, onDismiss, { editorId = chat.id }, { deleteTarget = chat })
                }
            }
            if (others.isNotEmpty()) {
                item { SectionTitle(if (favorites.isEmpty()) "Все чаты" else "Остальные") }
                items(others, key = { it.id }) { chat ->
                    ChatHubRow(chat, state, vm, onDismiss, { editorId = chat.id }, { deleteTarget = chat })
                }
            }
        }
    }

    state.chats.firstOrNull { it.id == editorId }?.let { chat ->
        ChatProfileDialog(chat, vm) { editorId = null }
    }

    deleteTarget?.let { chat ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить диалог?") },
            text = { Text("«${chat.title}» будет удалён. Его сгенерированные файлы останутся в хранилище Umnik.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteChat(chat.id)
                    deleteTarget = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } }
        )
    }

    if (clearAllConfirm) {
        AlertDialog(
            onDismissRequest = { clearAllConfirm = false },
            title = { Text("Очистить всю историю чатов?") },
            text = {
                Text(
                    "Будут удалены все ${state.chats.size} чатов и их файлы контекста. Сгенерированные изображения и экспорт в хранилище Umnik останутся."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAllChats()
                    clearAllConfirm = false
                    onDismiss()
                }) { Text("Очистить все") }
            },
            dismissButton = {
                TextButton(onClick = { clearAllConfirm = false }) { Text("Отмена") }
            }
        )
    }
}'''

project_ui = replace_block(
    project_ui,
    "@Composable\nfun ChatsHubDialog(state: UiState, vm: ChatViewModel, onDismiss: () -> Unit) {",
    "@Composable\nprivate fun ChatHubRow(",
    new_chats_hub,
    "ChatsHubDialog",
)
project_ui_path.write_text(project_ui, encoding="utf-8")


# ---------------------------------------------------------------------------
# 3. Swipe anywhere in the chat. Observe at Final pass so nested horizontal
#    components (Markdown tables etc.) keep priority and prevent menu opening.
# ---------------------------------------------------------------------------
ui_path = ROOT / "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
ui = ui_path.read_text(encoding="utf-8")
ui = replace_once(
    ui,
    "import androidx.compose.foundation.gestures.detectHorizontalDragGestures\n",
    "import androidx.compose.foundation.gestures.awaitEachGesture\nimport androidx.compose.foundation.gestures.awaitFirstDown\n",
    "swipe gesture imports",
)
ui = replace_once(
    ui,
    "import androidx.compose.ui.input.pointer.pointerInput\n",
    "import androidx.compose.ui.input.pointer.PointerEventPass\nimport androidx.compose.ui.input.pointer.pointerInput\n",
    "pointer pass import",
)

old_swipe = r'''    val edgeSwipeWidthPx = with(LocalDensity.current) { 36.dp.toPx() }
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
    ) {'''

new_swipe = r'''    val menuSwipeTriggerPx = with(LocalDensity.current) { 76.dp.toPx() }

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
                            menuSwipeSignal += 1
                            opened = true
                        }

                        if (!change.pressed) break
                        if (horizontalDistance <= -menuSwipeTriggerPx) break
                        if (kotlin.math.abs(verticalDistance) > menuSwipeTriggerPx * 1.35f) break
                    }
                }
            }
    ) {'''

ui = replace_once(ui, old_swipe, new_swipe, "chat swipe gesture")
ui = replace_once(
    ui,
    'ProviderType.NVIDIA -> "Показаны только генераторы изображений NVIDIA NIM из обновляемого реестра Umnik."',
    'ProviderType.NVIDIA -> "Показаны только проверенные генераторы NVIDIA NIM. Модели с нестабильным hosted endpoint временно скрываются реестром Umnik."',
    "NVIDIA picker hint",
)
ui_path.write_text(ui, encoding="utf-8")


# ---------------------------------------------------------------------------
# 4. NVIDIA catalog: the hosted FLUX.1-schnell endpoint is currently unstable
#    for the cloud trial. Remove it from the verified catalog, while leaving the
#    client implementation intact so it can be re-enabled later by a registry update.
# ---------------------------------------------------------------------------
registry_path = ROOT / "app/src/main/java/com/ayuemin/ymnik/network/ProviderRegistry.kt"
registry = registry_path.read_text(encoding="utf-8")
registry = replace_once(registry, "val version: Int = 2,", "val version: Int = 3,", "registry document version")
registry = replace_once(registry, "version = 2,", "version = 3,", "fallback registry version")
registry = replace_once(registry, "private const val MIN_REGISTRY_VERSION = 2", "private const val MIN_REGISTRY_VERSION = 3", "minimum registry version")
registry = replace_once(
    registry,
    '''                    ImageModelDefinition(\n                        "black-forest-labs/flux.1-schnell",\n                        parameterOptions = mapOf("aspect_ratio" to COMMON_RATIOS)\n                    ),\n''',
    "",
    "remove unstable NVIDIA schnell fallback",
)
registry_path.write_text(registry, encoding="utf-8")

remote_registry_path = ROOT / "docs/provider-registry.json"
remote = json.loads(remote_registry_path.read_text(encoding="utf-8"))
remote["version"] = 3
models = remote["providers"]["nvidia"].get("imageModels", [])
remote["providers"]["nvidia"]["imageModels"] = [
    item for item in models if item.get("id") != "black-forest-labs/flux.1-schnell"
]
if not remote["providers"]["nvidia"]["imageModels"]:
    raise RuntimeError("NVIDIA registry became empty")
remote_registry_path.write_text(json.dumps(remote, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


# ---------------------------------------------------------------------------
# 5. Version + changelog.
# ---------------------------------------------------------------------------
build_path = ROOT / "app/build.gradle.kts"
build = build_path.read_text(encoding="utf-8")
build = replace_once(build, "// Umnik v1.3.5", "// Umnik v1.3.6", "version banner")
build = replace_once(build, "versionCode = 47", "versionCode = 48", "version code")
build = replace_once(build, 'versionName = "1.3.5"', 'versionName = "1.3.6"', "version name")
build_path.write_text(build, encoding="utf-8")

changelog_path = ROOT / "CHANGELOG.md"
changelog = changelog_path.read_text(encoding="utf-8")
section = '''## v1.3.6 - 2026-09-12

- Повторное нажатие «Новый чат» больше не создаёт цепочку пустых диалогов: чистый пустой чат переиспользуется, а старые глобальные пустышки из предыдущих версий постепенно убираются при создании нового чата.
- В «Истории чатов» добавлена кнопка «Очистить все чаты» с обязательным подтверждением. Сгенерированные файлы и экспорт в хранилище Umnik при этом не удаляются.
- Свайп слева направо для открытия меню теперь работает по всей области чата, а не только от края экрана. Горизонтально прокручиваемые вложения, включая Markdown-таблицы, получают приоритет и не должны открывать меню вместо прокрутки.
- NVIDIA FLUX.1-schnell временно убран из проверенного каталога Umnik: hosted endpoint NVIDIA сейчас нестабилен и может зависать без ответа. Уже сохранённый выбор автоматически переводится на первый доступный проверенный NVIDIA image-модель.
- Реестр провайдеров поднят до версии 3, чтобы старый кэш с FLUX.1-schnell сбросился сразу после обновления приложения.

'''
changelog = replace_once(changelog, "## Unreleased\n\n", "## Unreleased\n\n" + section, "changelog insertion")
changelog_path.write_text(changelog, encoding="utf-8")

print("Umnik v1.3.6 patch applied")
