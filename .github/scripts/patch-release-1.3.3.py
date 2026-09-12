from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


# 1) Make the request stopwatch more compact while preserving a comfortable tap target.
ui_path = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
ui = ui_path.read_text(encoding="utf-8")
ui = replace_once(
    ui,
    '                                modifier = Modifier.size(if (state.requestActive) 58.dp else 48.dp)\n',
    '                                modifier = Modifier.size(if (state.requestActive) 50.dp else 48.dp)\n',
    "compact stopwatch tap container",
)
old_timer = '''@Composable
private fun WorkingStopTimer(seconds: Int) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(4.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = formatRequestDuration(seconds),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
    }
}
'''
new_timer = '''@Composable
private fun WorkingStopTimer(seconds: Int) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .border(
                width = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(4.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = formatRequestDuration(seconds),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
    }
}
'''
ui = replace_once(ui, old_timer, new_timer, "compact stopwatch visual")

# 2) Model picker: stage a choice, highlight it, then save explicitly.
old_pending_anchor = '''    var manualImageModel by remember(selectedImageConnectionId, current) { mutableStateOf(current) }

    LaunchedEffect(mode, selectedTextConnectionId, selectedImageConnectionId) {
'''
new_pending_anchor = '''    var manualImageModel by remember(selectedImageConnectionId, current) { mutableStateOf(current) }
    var pendingModel by remember(mode, selectedConnectionId, current) { mutableStateOf(current) }

    LaunchedEffect(mode, selectedTextConnectionId, selectedImageConnectionId) {
'''
ui = replace_once(ui, old_pending_anchor, new_pending_anchor, "pending model selection state")

ui = replace_once(
    ui,
    '                    "У каждого подключения своя модель по умолчанию. Выбор здесь не переключает текущий чат на другой сервис.",\n',
    '                    "Нажмите модель, затем «Сохранить выбор». Для текущего подключения модель сразу применяется к этому и новым чатам. Для другого подключения сохраняется его модель по умолчанию без переключения сервиса текущего чата.",\n',
    "text model picker explanation",
)

ui = replace_once(
    ui,
    '            ) { Text("Использовать эту модель") }\n',
    '            ) { Text("Сохранить модель") }\n',
    "custom image save label",
)

old_refresh_row = '''        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = {
                if (mode == ChatMode.TEXT) selectedTextConnectionId?.let(vm::loadConnectionModels)
                else selectedImageConnectionId?.let(vm::loadImageConnectionModels)
            }) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("Обновить")
            }
        }
'''
new_refresh_row = '''        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = {
                if (mode == ChatMode.TEXT) selectedTextConnectionId?.let(vm::loadConnectionModels)
                else selectedImageConnectionId?.let(vm::loadImageConnectionModels)
            }) {
                Icon(Icons.Outlined.Refresh, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("Обновить")
            }
        }
        if (!customImageConnection) {
            FilledTonalButton(
                onClick = {
                    val chosen = pendingModel.trim()
                    if (chosen.isNotBlank()) {
                        if (mode == ChatMode.TEXT) {
                            selectedTextConnectionId?.let { vm.selectDefaultTextModel(it, chosen) }
                        } else {
                            selectedImageConnectionId?.let { vm.selectImageModel(it, chosen) }
                        }
                        onDismiss()
                    }
                },
                enabled = pendingModel.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("Сохранить выбор")
            }
        }
'''
ui = replace_once(ui, old_refresh_row, new_refresh_row, "explicit model save button")

old_model_row = '''                items(filtered, key = { it.id }) { modelInfo ->
                    TextButton(
                        onClick = {
                            if (mode == ChatMode.TEXT) selectedTextConnectionId?.let { vm.selectDefaultTextModel(it, modelInfo.id) }
                            else selectedImageConnectionId?.let { vm.selectImageModel(it, modelInfo.id) }
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                    ) {
                        Text(
                            modelInfo.id,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    HorizontalDivider()
                }
'''
new_model_row = '''                items(filtered, key = { it.id }) { modelInfo ->
                    val selected = pendingModel == modelInfo.id
                    TextButton(
                        onClick = { pendingModel = modelInfo.id },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                    ) {
                        Text(
                            modelInfo.id,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (selected) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = "Выбрано",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    HorizontalDivider()
                }
'''
ui = replace_once(ui, old_model_row, new_model_row, "staged model list selection")
ui_path.write_text(ui, encoding="utf-8")

# 3) Persist text default synchronously and apply it to the current chat when it belongs
# to the same connection. This removes stale per-chat overrides that previously masked
# the newly selected default.
vm_path = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
vm = vm_path.read_text(encoding="utf-8")
old_text_select = '''    fun selectDefaultTextModel(profileId: String, model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id in _state.value.disabledConnectionIds) return
        if (!isProfileConfigured(profile)) return
        prefs.edit().putString(profilePrefKey("text_model", profile.id), clean).apply()
        if (profile.id == _state.value.activeConnectionProfileId) {
            val effectiveId = _state.value.currentChatTextModel ?: clean
            val info = _state.value.availableTextModels.firstOrNull { it.id == effectiveId }
            val effort = preferredReasoningEffort(effectiveId, info)
            val keepReasoning = reasoningStillValid(info, effort)
            prefs.edit().putString("reasoning_effort", effort.name).putBoolean("reasoning_enabled", keepReasoning).apply()
            _state.value = _state.value.copy(textModel=clean, reasoningEffort=effort, reasoningEnabled=keepReasoning, status="Модель по умолчанию · ${profile.name}: ${clean.substringAfterLast('/')}")
        } else {
            _state.value = _state.value.copy(status="Модель по умолчанию · ${profile.name}: ${clean.substringAfterLast('/')}")
        }
    }
'''
new_text_select = '''    fun selectDefaultTextModel(profileId: String, model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id in _state.value.disabledConnectionIds) return
        if (!isProfileConfigured(profile)) return
        val saved = prefs.edit().putString(profilePrefKey("text_model", profile.id), clean).commit()
        if (!saved) {
            _state.value = _state.value.copy(status = "Не удалось сохранить выбор модели")
            return
        }
        if (profile.id == _state.value.activeConnectionProfileId) {
            val chats = _state.value.chats.map { chat ->
                if (chat.id == _state.value.currentChatId) chat.copy(
                    connectionProfileId = profile.id,
                    textModelOverride = null,
                    mode = ChatMode.TEXT,
                    updatedAt = System.currentTimeMillis()
                ) else chat
            }
            chatsRepository.save(chats)
            val info = _state.value.availableTextModels.firstOrNull { it.id == clean }
                ?: _state.value.modelCatalog
                    .takeIf { _state.value.modelCatalogConnectionId == profile.id }
                    ?.firstOrNull { it.id == clean }
            val effort = preferredReasoningEffort(clean, info)
            val keepReasoning = reasoningStillValid(info, effort)
            prefs.edit()
                .putString("reasoning_effort", effort.name)
                .putBoolean("reasoning_enabled", keepReasoning)
                .apply()
            _state.value = _state.value.copy(
                chats = chats,
                textModel = clean,
                currentChatTextModel = null,
                mode = ChatMode.TEXT,
                reasoningEffort = effort,
                reasoningEnabled = keepReasoning,
                status = "Сохранено · ${profile.name}: ${clean.substringAfterLast('/')} · текущий и новые чаты"
            )
        } else {
            _state.value = _state.value.copy(
                status = "Сохранено · ${profile.name}: ${clean.substringAfterLast('/')} · текущий чат не переключён"
            )
        }
    }
'''
vm = replace_once(vm, old_text_select, new_text_select, "persist and apply text model selection")

old_image_prefs = '''        prefs.edit()
            .putString("image_connection_profile", profile.id)
            .putString(profilePrefKey("image_model", profile.id), clean)
            .apply()
'''
new_image_prefs = '''        val saved = prefs.edit()
            .putString("image_connection_profile", profile.id)
            .putString(profilePrefKey("image_model", profile.id), clean)
            .commit()
        if (!saved) {
            _state.value = _state.value.copy(status = "Не удалось сохранить выбор модели изображений")
            return
        }
'''
vm = replace_once(vm, old_image_prefs, new_image_prefs, "synchronous image model save")
vm_path.write_text(vm, encoding="utf-8")

# 4) Version and changelog.
build_path = Path("app/build.gradle.kts")
build = build_path.read_text(encoding="utf-8")
build = replace_once(build, "// Umnik v1.3.2", "// Umnik v1.3.3", "version comment")
build = replace_once(build, "        versionCode = 44", "        versionCode = 45", "version code")
build = replace_once(build, '        versionName = "1.3.2"', '        versionName = "1.3.3"', "version name")
build_path.write_text(build, encoding="utf-8")

changelog_path = Path("CHANGELOG.md")
changelog = changelog_path.read_text(encoding="utf-8")
marker = "## Unreleased\n\n"
section = '''## v1.3.3 - 2026-09-12

- Квадратный секундомер запроса уменьшен с сохранением удобной области нажатия; цифры уменьшены пропорционально.
- Выбор текстовой и image-модели в настройках теперь двухэтапный: сначала модель отмечается галочкой, затем пользователь явно нажимает «Сохранить выбор».
- Исправлена ситуация, когда новая текстовая модель по умолчанию сохранялась, но текущий чат продолжал использовать старую модель из-за сохранённого override: для текущего подключения старый override теперь снимается.
- При сохранении текстовой модели текущего подключения она сразу применяется к текущему чату и становится моделью по умолчанию для новых чатов.
- При выборе модели другого подключения Umnik сохраняет её только для этого подключения и явно сообщает, что текущий чат не переключался на другой сервис.
- Сохранение выбранной текстовой и image-модели переведено на синхронную запись настроек, чтобы исключить визуальное подтверждение до фактической фиксации выбора.

'''
if section.strip() in changelog:
    raise RuntimeError("v1.3.3 changelog section already exists")
changelog = replace_once(changelog, marker, marker + section, "changelog insertion")
changelog_path.write_text(changelog, encoding="utf-8")

# Static regression checks before Gradle compilation.
ui_check = ui_path.read_text(encoding="utf-8")
vm_check = vm_path.read_text(encoding="utf-8")
build_check = build_path.read_text(encoding="utf-8")
required_ui = [
    ".size(if (state.requestActive) 50.dp else 48.dp)",
    ".size(44.dp)",
    'Text("Сохранить выбор")',
    "pendingModel == modelInfo.id",
]
for fragment in required_ui:
    if fragment not in ui_check:
        raise RuntimeError(f"UI regression check failed: missing {fragment}")
required_vm = [
    'textModelOverride = null',
    'putString(profilePrefKey("text_model", profile.id), clean).commit()',
    'currentChatTextModel = null',
    'Не удалось сохранить выбор модели изображений',
]
for fragment in required_vm:
    if fragment not in vm_check:
        raise RuntimeError(f"model persistence check failed: missing {fragment}")
if 'versionName = "1.3.3"' not in build_check or "versionCode = 45" not in build_check:
    raise RuntimeError("version bump check failed")

print("Umnik v1.3.3 patch applied: compact stopwatch + explicit, persistent model selection")
