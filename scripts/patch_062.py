from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel, text):
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def replace_once(rel, old, new):
    text = read(rel)
    if old not in text:
        raise RuntimeError(f"Pattern not found in {rel}: {old[:160]!r}")
    write(rel, text.replace(old, new, 1))


# ---------- Models ----------
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/model/Models.kt",
    '''enum class ChatMode {\n    TEXT,\n    IMAGE\n}\n\n''',
    '''enum class ChatMode {\n    TEXT,\n    IMAGE\n}\n\nenum class UserProfileScope {\n    OFF,\n    PROJECTS,\n    EVERYWHERE\n}\n\ndata class UserProfile(\n    val name: String = "",\n    val gender: String = "",\n    val age: String = "",\n    val occupation: String = "",\n    val note: String = ""\n) {\n    fun isEmpty(): Boolean = name.isBlank() && gender.isBlank() && age.isBlank() && occupation.isBlank() && note.isBlank()\n}\n\n'''
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/model/Models.kt",
    '''    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,\n    val apiKeyConfigured: Boolean = false,''',
    '''    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,\n    val userProfile: UserProfile = UserProfile(),\n    val userProfileScope: UserProfileScope = UserProfileScope.OFF,\n    val apiKeyConfigured: Boolean = false,'''
)

# ---------- ViewModel ----------
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''import com.ayuemin.ymnik.model.UiState\n''',
    '''import com.ayuemin.ymnik.model.UiState\nimport com.ayuemin.ymnik.model.UserProfile\nimport com.ayuemin.ymnik.model.UserProfileScope\n'''
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''            reasoningEffort = runCatching {\n                ReasoningEffort.valueOf(prefs.getString("reasoning_effort", ReasoningEffort.MEDIUM.name) ?: ReasoningEffort.MEDIUM.name)\n            }.getOrDefault(ReasoningEffort.MEDIUM),\n            apiKeyConfigured = !secrets.getApiKey().isNullOrBlank(),''',
    '''            reasoningEffort = runCatching {\n                ReasoningEffort.valueOf(prefs.getString("reasoning_effort", ReasoningEffort.MEDIUM.name) ?: ReasoningEffort.MEDIUM.name)\n            }.getOrDefault(ReasoningEffort.MEDIUM),\n            userProfile = UserProfile(\n                name = prefs.getString("profile_name", "").orEmpty(),\n                gender = prefs.getString("profile_gender", "").orEmpty(),\n                age = prefs.getString("profile_age", "").orEmpty(),\n                occupation = prefs.getString("profile_occupation", "").orEmpty(),\n                note = prefs.getString("profile_note", "").orEmpty()\n            ),\n            userProfileScope = runCatching {\n                UserProfileScope.valueOf(prefs.getString("profile_scope", UserProfileScope.OFF.name) ?: UserProfileScope.OFF.name)\n            }.getOrDefault(UserProfileScope.OFF),\n            apiKeyConfigured = !secrets.getApiKey().isNullOrBlank(),'''
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''    fun setReasoningEffort(effort: ReasoningEffort) {\n        prefs.edit().putString("reasoning_effort", effort.name).apply()\n        _state.value = _state.value.copy(reasoningEffort = effort)\n    }\n\n''',
    '''    fun setReasoningEffort(effort: ReasoningEffort) {\n        prefs.edit().putString("reasoning_effort", effort.name).apply()\n        _state.value = _state.value.copy(reasoningEffort = effort)\n    }\n\n    fun saveUserProfile(name: String, gender: String, age: String, occupation: String, note: String) {\n        val profile = UserProfile(\n            name = name.trim(),\n            gender = gender.trim(),\n            age = age.trim(),\n            occupation = occupation.trim(),\n            note = note.trim().take(240)\n        )\n        prefs.edit()\n            .putString("profile_name", profile.name)\n            .putString("profile_gender", profile.gender)\n            .putString("profile_age", profile.age)\n            .putString("profile_occupation", profile.occupation)\n            .putString("profile_note", profile.note)\n            .apply()\n        _state.value = _state.value.copy(userProfile = profile, status = "Профиль пользователя сохранён")\n    }\n\n    fun setUserProfileScope(scope: UserProfileScope) {\n        prefs.edit().putString("profile_scope", scope.name).apply()\n        _state.value = _state.value.copy(userProfileScope = scope)\n    }\n\n'''
)
# Avoid temporary camera photos surviving when chat context changes.
for signature in [
    '''    fun createChat(projectId: String? = null): String {\n        if (_state.value.isLoading) return _state.value.currentChatId\n''',
    '''    fun switchChat(id: String) {\n        if (_state.value.isLoading) return\n''',
    '''    fun deleteChat(id: String) {\n        if (_state.value.isLoading) return\n''',
    '''    fun clearChat() {\n        if (_state.value.isLoading) return\n'''
]:
    replacement = signature.replace("\n        if (_state.value.isLoading)", "\n        cleanupTempAttachments(_state.value.pendingAttachments)\n        if (_state.value.isLoading)")
    replace_once("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt", signature, replacement)

replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''    fun addAttachment(uri: Uri) {\n        runCatching { api.attachmentFromUri(uri) }\n            .onSuccess { attachment ->\n                if (attachment.size > 25L * 1024 * 1024) {\n                    _state.value = _state.value.copy(status = "Ограничение Umnik сейчас 25 МБ на один файл")\n                } else {\n                    _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)\n                }\n            }\n            .onFailure { _state.value = _state.value.copy(status = it.message) }\n    }\n\n    fun removeAttachment(uri: String) {\n        _state.value = _state.value.copy(\n            pendingAttachments = _state.value.pendingAttachments.filterNot { it.uri == uri }\n        )\n    }''',
    '''    fun addAttachment(uri: Uri) {\n        runCatching { api.attachmentFromUri(uri) }\n            .onSuccess { attachment ->\n                if (attachment.size > 25L * 1024 * 1024) {\n                    _state.value = _state.value.copy(status = "Ограничение Umnik сейчас 25 МБ на один файл")\n                } else {\n                    _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)\n                }\n            }\n            .onFailure { _state.value = _state.value.copy(status = it.message) }\n    }\n\n    fun addCameraAttachment(uri: Uri, localPath: String) {\n        runCatching { api.attachmentFromUri(uri).copy(localPath = localPath) }\n            .onSuccess { attachment ->\n                if (attachment.size > 25L * 1024 * 1024) {\n                    File(localPath).delete()\n                    _state.value = _state.value.copy(status = "Фото превышает ограничение 25 МБ")\n                } else {\n                    _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)\n                }\n            }\n            .onFailure {\n                File(localPath).delete()\n                _state.value = _state.value.copy(status = it.message ?: "Не удалось добавить фото")\n            }\n    }\n\n    fun removeAttachment(uri: String) {\n        val removed = _state.value.pendingAttachments.firstOrNull { it.uri == uri }\n        cleanupTempAttachments(listOfNotNull(removed))\n        _state.value = _state.value.copy(\n            pendingAttachments = _state.value.pendingAttachments.filterNot { it.uri == uri }\n        )\n    }'''
)
# Clean camera temporary file after request has consumed it.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''            operation.onSuccess { result ->''',
    '''            operation.onSuccess { result ->'''
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''            }.onFailure {\n                _state.value = _state.value.copy(\n                    isLoading = false,\n                    busyLabel = null,\n                    status = it.message ?: "Ошибка запроса"\n                )\n            }\n        }\n    }\n\n    fun exportMessage''',
    '''            }.onFailure {\n                _state.value = _state.value.copy(\n                    isLoading = false,\n                    busyLabel = null,\n                    status = it.message ?: "Ошибка запроса"\n                )\n            }\n            cleanupTempAttachments(pending)\n        }\n    }\n\n    fun exportMessage'''
)
# Inject optional short user profile into text system context.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''    private fun buildSystemPrompt(skillText: String, project: Project?, chat: ChatSession?): String = buildString {\n        appendLine("Ты работаешь внутри Android-приложения «Umnik». Отвечай на языке пользователя, если он не попросил иначе.")\n        if (project != null) {''',
    '''    private fun buildSystemPrompt(skillText: String, project: Project?, chat: ChatSession?): String = buildString {\n        appendLine("Ты работаешь внутри Android-приложения «Umnik». Отвечай на языке пользователя, если он не попросил иначе.")\n        val profile = _state.value.userProfile\n        val useProfile = !profile.isEmpty() && when (_state.value.userProfileScope) {\n            UserProfileScope.OFF -> false\n            UserProfileScope.PROJECTS -> project != null\n            UserProfileScope.EVERYWHERE -> true\n        }\n        if (useProfile) {\n            appendLine("\\n===== КРАТКО О ПОЛЬЗОВАТЕЛЕ =====")\n            if (profile.name.isNotBlank()) appendLine("Имя: ${profile.name}")\n            if (profile.gender.isNotBlank()) appendLine("Пол: ${profile.gender}")\n            if (profile.age.isNotBlank()) appendLine("Возраст: ${profile.age}")\n            if (profile.occupation.isNotBlank()) appendLine("Род занятий: ${profile.occupation}")\n            if (profile.note.isNotBlank()) appendLine("Предпочтение в общении: ${profile.note}")\n            appendLine("Используй эти сведения только когда они полезны. Не пересказывай профиль пользователю без необходимости. Явный запрос и инструкции проекта важнее этого краткого профиля.")\n            appendLine("===== КОНЕЦ ПРОФИЛЯ =====")\n        }\n        if (project != null) {'''
)
# Temp helper before ready sound.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt",
    '''    private fun playReadySound() {''',
    '''    private fun cleanupTempAttachments(items: List<PendingAttachment>) {\n        val cameraRoot = File(context.cacheDir, "camera")\n        items.mapNotNull { it.localPath }.forEach { path ->\n            runCatching {\n                val file = File(path).canonicalFile\n                val root = cameraRoot.canonicalFile\n                if (file.path.startsWith(root.path + File.separator)) file.delete()\n            }\n        }\n    }\n\n    private fun playReadySound() {'''
)

# ---------- UI ----------
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''import androidx.compose.material.icons.outlined.ChatBubbleOutline\n''',
    '''import androidx.compose.material.icons.outlined.CameraAlt\nimport androidx.compose.material.icons.outlined.ChatBubbleOutline\n'''
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''import androidx.compose.ui.unit.dp\n''',
    '''import androidx.compose.ui.unit.dp\nimport androidx.core.content.FileProvider\n'''
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''import com.ayuemin.ymnik.model.UiState\n''',
    '''import com.ayuemin.ymnik.model.UiState\nimport com.ayuemin.ymnik.model.UserProfileScope\n'''
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''import java.text.SimpleDateFormat\n''',
    '''import java.io.File\nimport java.text.SimpleDateFormat\n'''
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''    var projectsOpen by remember { mutableStateOf(false) }\n    val listState = rememberLazyListState()\n\n    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->\n        uris.forEach(vm::addAttachment)\n    }''',
    '''    var projectsOpen by remember { mutableStateOf(false) }\n    var cameraTarget by remember { mutableStateOf<CameraTarget?>(null) }\n    val listState = rememberLazyListState()\n    val context = LocalContext.current\n\n    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->\n        uris.forEach(vm::addAttachment)\n    }\n    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->\n        cameraTarget?.let { target ->\n            if (ok) vm.addCameraAttachment(target.uri, target.file.absolutePath) else target.file.delete()\n        }\n        cameraTarget = null\n    }'''
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''                    onExportText = {\n                        val file = vm.exportMessage(message)\n                        fileToSave = file\n                        save.launch(file.name)\n                    }\n                )''',
    '''                    onExportText = {\n                        val file = vm.exportMessage(message)\n                        fileToSave = file\n                        save.launch(file.name)\n                    },\n                    onRetry = if (message.role == "user" && message.text.isNotBlank() && message.attachmentNames.isEmpty()) {\n                        { vm.send(message.text) }\n                    } else null\n                )'''
)
# Add direct camera button next to attachment.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''                IconButton(\n                    onClick = {\n                        val types = if (state.mode == ChatMode.IMAGE) arrayOf("image/*") else arrayOf("*/*")\n                        attach.launch(types)\n                    },\n                    enabled = !state.isLoading,\n                    modifier = Modifier.size(42.dp)\n                ) {\n                    Icon(Icons.Outlined.AttachFile, contentDescription = "Прикрепить файл")\n                }\n\n                if (state.mode == ChatMode.TEXT) {''',
    '''                IconButton(\n                    onClick = {\n                        val types = if (state.mode == ChatMode.IMAGE) arrayOf("image/*") else arrayOf("*/*")\n                        attach.launch(types)\n                    },\n                    enabled = !state.isLoading,\n                    modifier = Modifier.size(42.dp)\n                ) {\n                    Icon(Icons.Outlined.AttachFile, contentDescription = "Прикрепить файл")\n                }\n\n                IconButton(\n                    onClick = {\n                        runCatching { createCameraTarget(context) }\n                            .onSuccess { target ->\n                                cameraTarget = target\n                                camera.launch(target.uri)\n                            }\n                            .onFailure { Toast.makeText(context, it.message ?: "Не удалось открыть камеру", Toast.LENGTH_SHORT).show() }\n                    },\n                    enabled = !state.isLoading,\n                    modifier = Modifier.size(42.dp)\n                ) {\n                    Icon(Icons.Outlined.CameraAlt, contentDescription = "Сделать фото")\n                }\n\n                if (state.mode == ChatMode.TEXT) {'''
)
# User message controls and retry.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''    onSaveGenerated: (GeneratedFile) -> Unit,\n    onExportText: () -> Unit\n) {''',
    '''    onSaveGenerated: (GeneratedFile) -> Unit,\n    onExportText: () -> Unit,\n    onRetry: (() -> Unit)?\n) {'''
)
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''        if (!user && (message.text.isNotBlank() || message.generatedFiles.isNotEmpty())) {\n            Row(''',
    '''        if (user && message.text.isNotBlank()) {\n            Row(\n                modifier = Modifier.padding(end = 4.dp, top = 2.dp),\n                verticalAlignment = Alignment.CenterVertically\n            ) {\n                IconButton(onClick = { copyText(context, message.text) }) {\n                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Копировать своё сообщение")\n                }\n                if (onRetry != null) {\n                    IconButton(onClick = onRetry) {\n                        Icon(Icons.Outlined.Refresh, contentDescription = "Спросить ещё раз")\n                    }\n                }\n            }\n        }\n\n        if (!user && (message.text.isNotBlank() || message.generatedFiles.isNotEmpty())) {\n            Row('''
)
# Settings profile state.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''    var modelPicker by remember { mutableStateOf<ChatMode?>(null) }\n    val themes = ThemeChoice.entries\n    val reasoningEfforts = ReasoningEffort.entries\n''',
    '''    var modelPicker by remember { mutableStateOf<ChatMode?>(null) }\n    var profileName by remember(state.userProfile.name) { mutableStateOf(state.userProfile.name) }\n    var profileGender by remember(state.userProfile.gender) { mutableStateOf(state.userProfile.gender) }\n    var profileAge by remember(state.userProfile.age) { mutableStateOf(state.userProfile.age) }\n    var profileOccupation by remember(state.userProfile.occupation) { mutableStateOf(state.userProfile.occupation) }\n    var profileNote by remember(state.userProfile.note) { mutableStateOf(state.userProfile.note) }\n    val themes = ThemeChoice.entries\n    val reasoningEfforts = ReasoningEffort.entries\n    val profileScopes = UserProfileScope.entries\n'''
)
# Insert compact optional profile card before storage card.
ui_path = ROOT / "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
ui = ui_path.read_text(encoding="utf-8")
needle = '                            Text("Хранилище Umnik", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)'
pos = ui.find(needle)
if pos < 0:
    raise RuntimeError("Storage card marker not found")
insert_pos = ui.rfind('        item {', 0, pos)
profile_card = r'''        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Коротко обо мне", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Необязательно. Передаётся модели только в выбранной области и только если поля заполнены.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(profileName, { profileName = it }, Modifier.fillMaxWidth(), label = { Text("Имя") }, singleLine = true)
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedTextField(profileGender, { profileGender = it }, Modifier.weight(1f), label = { Text("Пол") }, singleLine = true)
                        OutlinedTextField(profileAge, { profileAge = it }, Modifier.weight(1f), label = { Text("Возраст") }, singleLine = true)
                    }
                    Spacer(Modifier.height(7.dp))
                    OutlinedTextField(profileOccupation, { profileOccupation = it }, Modifier.fillMaxWidth(), label = { Text("Род занятий") }, singleLine = true)
                    Spacer(Modifier.height(7.dp))
                    OutlinedTextField(
                        profileNote,
                        { profileNote = it.take(240) },
                        Modifier.fillMaxWidth(),
                        label = { Text("Короткая установка") },
                        placeholder = { Text("Например: без заискивания, отвечай прямо") },
                        minLines = 2,
                        maxLines = 3
                    )
                    Spacer(Modifier.height(9.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(profileScopes) { scope ->
                            FilterChip(
                                selected = state.userProfileScope == scope,
                                onClick = { vm.setUserProfileScope(scope) },
                                label = { Text(profileScopeLabel(scope)) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { vm.saveUserProfile(profileName, profileGender, profileAge, profileOccupation, profileNote) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Сохранить профиль")
                    }
                }
            }
        }

'''
ui = ui[:insert_pos] + profile_card + ui[insert_pos:]
ui_path.write_text(ui, encoding="utf-8")

# Add camera helper + profile scope labels before themeLabel.
replace_once(
    "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt",
    '''private fun themeLabel(choice: ThemeChoice): String = when (choice) {''',
    '''private data class CameraTarget(val uri: Uri, val file: File)\n\nprivate fun createCameraTarget(context: Context): CameraTarget {\n    val dir = File(context.cacheDir, "camera").apply { mkdirs() }\n    val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L\n    dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }\n    val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")\n    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)\n    return CameraTarget(uri, file)\n}\n\nprivate fun profileScopeLabel(scope: UserProfileScope): String = when (scope) {\n    UserProfileScope.OFF -> "Выкл"\n    UserProfileScope.PROJECTS -> "Только проекты"\n    UserProfileScope.EVERYWHERE -> "Везде"\n}\n\nprivate fun themeLabel(choice: ThemeChoice): String = when (choice) {'''
)

# ---------- Manifest + FileProvider ----------
replace_once(
    "app/src/main/AndroidManifest.xml",
    '''        <activity\n            android:name="com.ayuemin.ymnik.MainActivity"''',
    '''        <provider\n            android:name="androidx.core.content.FileProvider"\n            android:authorities="${applicationId}.fileprovider"\n            android:exported="false"\n            android:grantUriPermissions="true">\n            <meta-data\n                android:name="android.support.FILE_PROVIDER_PATHS"\n                android:resource="@xml/file_paths" />\n        </provider>\n\n        <activity\n            android:name="com.ayuemin.ymnik.MainActivity"'''
)
write(
    "app/src/main/res/xml/file_paths.xml",
    '''<?xml version="1.0" encoding="utf-8"?>\n<paths xmlns:android="http://schemas.android.com/apk/res/android">\n    <cache-path name="camera" path="camera/" />\n</paths>\n'''
)

# ---------- Version/dependency ----------
replace_once(
    "app/build.gradle.kts",
    '''        versionCode = 9\n        versionName = "0.6.0"''',
    '''        versionCode = 10\n        versionName = "0.6.2"'''
)
# Explicit FileProvider dependency if not already present.
gradle = read("app/build.gradle.kts")
if 'androidx.core:core-ktx' not in gradle:
    gradle = gradle.replace(
        '    implementation("androidx.activity:activity-compose:1.10.1")\n',
        '    implementation("androidx.activity:activity-compose:1.10.1")\n    implementation("androidx.core:core-ktx:1.16.0")\n',
        1
    )
    write("app/build.gradle.kts", gradle)

# ---------- Release workflow ----------
workflow = read(".github/workflows/android-release.yml")
workflow = workflow.replace('VERSION: "0.6.1"', 'VERSION: "0.6.2"')
workflow = workflow.replace('VERSION: "0.6.0"', 'VERSION: "0.6.2"')
start = workflow.find('          gh release create "v${VERSION}"')
if start < 0:
    raise RuntimeError("release command not found")
notes_start = workflow.find('--notes "', start)
if notes_start < 0:
    raise RuntimeError("release notes not found")
prefix = workflow[:notes_start]
workflow = prefix + '''--notes "Umnik v0.6.2.\n\n          Главное:\n          - в строке ввода появилась отдельная кнопка камеры: можно сделать полноценное фото и сразу приложить его к запросу\n          - фото с камеры хранится временно и удаляется после отправки или удаления вложения\n          - под сообщениями пользователя появилась кнопка копирования\n          - для текстовых сообщений пользователя появилась кнопка «Спросить ещё раз»\n          - в настройках добавлен необязательный короткий профиль пользователя: имя, пол, возраст, род занятий и короткая установка по стилю общения\n          - профиль можно полностью выключить, использовать только в проектах или во всех текстовых чатах\n          - явный запрос и мастер-инструкции проекта имеют приоритет над профилем\n          - проекты, навыки, веб-поиск, reasoning, TTS, генерация изображений и локальное хранилище сохранены"\n'''
write(".github/workflows/android-release.yml", workflow)
