from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Network-wide diagnostic timing. The interceptor is a no-op unless the user
# explicitly enables diagnostics in Settings.
# ---------------------------------------------------------------------------
network_files = [
    ("app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt", "OpenRouter"),
    ("app/src/main/java/com/ayuemin/ymnik/network/NvidiaImageClient.kt", "NVIDIA image"),
    ("app/src/main/java/com/ayuemin/ymnik/network/CompatibleApiClient.kt", "Compatible text/image"),
]

for relative, label in network_files:
    path = ROOT / relative
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "import android.util.Base64\n" if "OpenRouterClient" in relative or "NvidiaImageClient" in relative or "CompatibleApiClient" in relative else "import android.content.Context\n",
        ("import android.util.Base64\n" if "OpenRouterClient" in relative or "NvidiaImageClient" in relative or "CompatibleApiClient" in relative else "import android.content.Context\n")
        + "import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor\n",
        f"{label} diagnostic import",
    )
    text = replace_once(
        text,
        "    private val http = OkHttpClient.Builder()\n        .connectTimeout(",
        f"    private val http = OkHttpClient.Builder()\n        .addInterceptor(DiagnosticHttpInterceptor(context, \"{label}\"))\n        .connectTimeout(",
        f"{label} HTTP interceptor",
    )
    path.write_text(text, encoding="utf-8")

# Provider registry has no Base64 import but does have Context.
registry_path = ROOT / "app/src/main/java/com/ayuemin/ymnik/network/ProviderRegistry.kt"
registry = registry_path.read_text(encoding="utf-8")
registry = replace_once(
    registry,
    "import android.content.Context\n",
    "import android.content.Context\nimport com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor\n",
    "registry diagnostic import",
)
registry = replace_once(
    registry,
    "    private val http = OkHttpClient.Builder()\n        .connectTimeout(12, TimeUnit.SECONDS)",
    "    private val http = OkHttpClient.Builder()\n        .addInterceptor(DiagnosticHttpInterceptor(context, \"Provider registry\"))\n        .connectTimeout(12, TimeUnit.SECONDS)",
    "registry HTTP interceptor",
)
registry_path.write_text(registry, encoding="utf-8")


# ---------------------------------------------------------------------------
# Compatible/NVIDIA text transport: explicit non-streaming, shorter sane max
# output, remove unanswered trailing user messages after a failed request, and
# preserve reasoning_content as a fallback. This also gives the logger useful
# request/result metadata without logging user text.
# ---------------------------------------------------------------------------
compatible_path = ROOT / "app/src/main/java/com/ayuemin/ymnik/network/CompatibleApiClient.kt"
compatible = compatible_path.read_text(encoding="utf-8")
compatible = replace_once(
    compatible,
    "import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor\n",
    "import com.ayuemin.ymnik.diagnostics.DiagnosticHttpInterceptor\nimport com.ayuemin.ymnik.diagnostics.DiagnosticLog\n",
    "compatible DiagnosticLog import",
)
compatible = replace_once(
    compatible,
    '''        history.takeLast(30).forEach { item -> messages.add(message(item.role, item.text)) }
        messages.add(message("user", userText(prompt, attachments)))

        val payload = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            addProperty("max_tokens", 6000)
        }
''',
    '''        // NVIDIA requires alternating user/assistant roles. After a timeout Umnik
        // keeps the unanswered user message in local history; do not send that stale
        // trailing user turn again when the user retries.
        history.takeLast(30)
            .dropLastWhile { it.role == "user" }
            .forEach { item -> messages.add(message(item.role, item.text)) }
        messages.add(message("user", userText(prompt, attachments)))

        val payload = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            addProperty("max_tokens", 4096)
            addProperty("stream", false)
        }
        val providerLabel = if (baseUrl.contains("nvidia.com", ignoreCase = true)) "NVIDIA" else "Compatible API"
        DiagnosticLog.record(
            context,
            "TEXT REQUEST",
            "$providerLabel start; model=$model; history=${history.size}; sentMessages=${messages.size()}; promptChars=${prompt.length}; attachments=${attachments.size}"
        )
''',
    "compatible request normalization",
)
compatible = replace_once(
    compatible,
    '''                val root = gson.fromJson(body, JsonObject::class.java)
                val content = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("message")?.get("content")
                val text = extractText(content)
                if (text.isBlank()) error("Совместимый API вернул пустой ответ")
                OpenRouterClient.Result(text, emptyList())
            }
        } finally {
            activeCall = null
        }
''',
    '''                val root = gson.fromJson(body, JsonObject::class.java)
                val messageObject = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                    ?.getAsJsonObject("message")
                val content = messageObject?.get("content")
                val reasoningContent = messageObject?.get("reasoning_content")
                val text = extractText(content).ifBlank { extractText(reasoningContent) }
                if (text.isBlank()) error("Совместимый API вернул пустой ответ")
                DiagnosticLog.record(
                    context,
                    "TEXT REQUEST",
                    "$providerLabel success; model=$model; responseChars=${text.length}; responseBytes=${body.length}"
                )
                OpenRouterClient.Result(text, emptyList())
            }
        } catch (t: Throwable) {
            DiagnosticLog.record(context, "TEXT REQUEST", "$providerLabel failed; model=$model", t)
            throw t
        } finally {
            activeCall = null
        }
''',
    "compatible response logging",
)
compatible_path.write_text(compatible, encoding="utf-8")


# ---------------------------------------------------------------------------
# ViewModel-facing log controls + request metadata and friendly timeout text.
# ---------------------------------------------------------------------------
vm_path = ROOT / "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
vm = vm_path.read_text(encoding="utf-8")
vm = replace_once(
    vm,
    "import com.ayuemin.ymnik.data.StorageRepository\n",
    "import com.ayuemin.ymnik.data.StorageRepository\nimport com.ayuemin.ymnik.diagnostics.DiagnosticLog\n",
    "ViewModel DiagnosticLog import",
)
vm = replace_once(
    vm,
    '''    init {
        if (initialProfile.id !in initialDisabledConnectionIds && isProfileConfigured(initialProfile)) refreshModelCapabilities()
''',
    '''    init {
        DiagnosticLog.record(
            context,
            "APP",
            "ChatViewModel initialized; activeProfile=${initialProfile.name}; activeModel=${loadTextModelForProfile(initialProfile)}"
        )
        if (initialProfile.id !in initialDisabledConnectionIds && isProfileConfigured(initialProfile)) refreshModelCapabilities()
''',
    "ViewModel startup log",
)

log_methods = '''    fun isDiagnosticLoggingEnabled(): Boolean = DiagnosticLog.isEnabled(context)

    fun setDiagnosticLoggingEnabled(enabled: Boolean) {
        DiagnosticLog.setEnabled(context, enabled)
        _state.value = _state.value.copy(
            status = if (enabled)
                "Запись диагностических логов включена"
            else
                "Запись диагностических логов выключена"
        )
    }

    fun diagnosticLogSize(): Long = DiagnosticLog.size(context)

    fun diagnosticLogFile(): GeneratedFile? {
        val file = DiagnosticLog.file(context) ?: return null
        return GeneratedFile(
            id = "diagnostic-log",
            name = "umnik-diagnostic.log",
            mimeType = "text/plain",
            localPath = file.absolutePath,
            size = file.length()
        )
    }

    fun clearDiagnosticLog() {
        DiagnosticLog.clear(context)
        _state.value = _state.value.copy(status = "Диагностический лог очищен")
    }

'''
vm = replace_once(
    vm,
    "    fun saveApiKey(apiKey: String?) {",
    log_methods + "    fun saveApiKey(apiKey: String?) {",
    "ViewModel diagnostic methods",
)

vm = replace_once(
    vm,
    '''        val reasoningEffort = _state.value.reasoningEffort
        val requestId = ++requestGeneration
        activeRequestPending = pending

        activeRequestJob = viewModelScope.launch {
''',
    '''        val reasoningEffort = _state.value.reasoningEffort
        val requestId = ++requestGeneration
        activeRequestPending = pending
        DiagnosticLog.record(
            context,
            "REQUEST",
            "start id=$requestId; provider=${profile.name}; mode=$mode; model=${if (mode == ChatMode.TEXT) textModel else imageModel}; history=${before.size}; pending=${pending.size}; persistent=${persistentChatFiles.size}; promptChars=${clean.length}"
        )

        activeRequestJob = viewModelScope.launch {
''',
    "request start logging",
)
vm = replace_once(
    vm,
    '''            operation.onSuccess { result ->
                val assistant = ChatMessage(
''',
    '''            operation.onSuccess { result ->
                DiagnosticLog.record(
                    context,
                    "REQUEST",
                    "success id=$requestId; provider=${profile.name}; model=${if (mode == ChatMode.TEXT) textModel else imageModel}; responseChars=${result.text.length}; files=${result.files.size}"
                )
                val assistant = ChatMessage(
''',
    "request success logging",
)
vm = replace_once(
    vm,
    '''            }.onFailure {
                _state.value = _state.value.copy(
                    isLoading = false,
                    requestActive = false,
                    busyLabel = null,
                    status = it.message ?: "Ошибка запроса"
                )
            }
''',
    '''            }.onFailure {
                DiagnosticLog.record(
                    context,
                    "REQUEST",
                    "failed id=$requestId; provider=${profile.name}; model=${if (mode == ChatMode.TEXT) textModel else imageModel}",
                    it
                )
                val friendlyError = if (it is java.net.SocketTimeoutException) {
                    "Сервис не ответил вовремя (тайм-аут). При необходимости включите «Диагностика и логи» и повторите запрос."
                } else {
                    it.message ?: "Ошибка запроса"
                }
                _state.value = _state.value.copy(
                    isLoading = false,
                    requestActive = false,
                    busyLabel = null,
                    status = friendlyError
                )
            }
''',
    "request failure logging",
)
vm_path.write_text(vm, encoding="utf-8")


# ---------------------------------------------------------------------------
# Settings UI: opt-in diagnostics at the very bottom, just above app version.
# ---------------------------------------------------------------------------
ui_path = ROOT / "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
ui = ui_path.read_text(encoding="utf-8")
ui = replace_once(
    ui,
    '''    var connectionsExpanded by remember { mutableStateOf(false) }
    var editingProfileId by remember { mutableStateOf("openrouter") }
''',
    '''    var connectionsExpanded by remember { mutableStateOf(false) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    var diagnosticLoggingEnabled by remember { mutableStateOf(vm.isDiagnosticLoggingEnabled()) }
    var diagnosticLogBytes by remember { mutableStateOf(vm.diagnosticLogSize()) }
    var diagnosticFileToSave by remember { mutableStateOf<GeneratedFile?>(null) }
    var editingProfileId by remember { mutableStateOf("openrouter") }
''',
    "settings diagnostics state",
)
ui = replace_once(
    ui,
    '''    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importAnswerSound)
    }

    Column(Modifier.fillMaxSize()) {
''',
    '''    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importAnswerSound)
    }
    val diagnosticSave = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        val file = diagnosticFileToSave
        if (uri != null && file != null) vm.saveGeneratedFile(file, uri)
        diagnosticFileToSave = null
        diagnosticLogBytes = vm.diagnosticLogSize()
    }

    Column(Modifier.fillMaxSize()) {
''',
    "diagnostic save launcher",
)

diagnostic_card = '''            item {
                ExpandableSettingsCard(
                    title = "Диагностика и логи",
                    subtitle = if (diagnosticLoggingEnabled)
                        "Запись включена · ${humanSize(diagnosticLogBytes)}"
                    else
                        "Выключено · включайте только при поиске ошибки",
                    icon = Icons.Outlined.Description,
                    expanded = diagnosticsExpanded,
                    onToggle = {
                        diagnosticsExpanded = !diagnosticsExpanded
                        diagnosticLoggingEnabled = vm.isDiagnosticLoggingEnabled()
                        diagnosticLogBytes = vm.diagnosticLogSize()
                    }
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Запись логов", fontWeight = FontWeight.Medium)
                            Text(
                                "Включите, повторите действия с ошибкой и затем отправьте лог.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = diagnosticLoggingEnabled,
                            onCheckedChange = { enabled ->
                                vm.setDiagnosticLoggingEnabled(enabled)
                                diagnosticLoggingEnabled = enabled
                                diagnosticLogBytes = vm.diagnosticLogSize()
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Когда запись выключена, она практически не влияет на работу приложения. В лог не пишутся тексты сообщений, содержимое файлов и API-ключи: сохраняются технические события, модель, адрес сервиса без параметров, HTTP-код, время запроса и текст ошибки.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                val file = vm.diagnosticLogFile()
                                if (file != null) {
                                    shareGeneratedFile(context, file)
                                    diagnosticLogBytes = vm.diagnosticLogSize()
                                }
                            },
                            enabled = diagnosticLogBytes > 0L,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Поделиться", maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = {
                                val file = vm.diagnosticLogFile()
                                if (file != null) {
                                    diagnosticFileToSave = file
                                    diagnosticSave.launch(file.name)
                                }
                            },
                            enabled = diagnosticLogBytes > 0L,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Сохранить", maxLines = 1)
                        }
                    }
                    TextButton(
                        onClick = {
                            vm.clearDiagnosticLog()
                            diagnosticLogBytes = 0L
                        },
                        enabled = diagnosticLogBytes > 0L,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Очистить лог")
                    }
                }
            }

'''
ui = replace_once(
    ui,
    '''            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Umnik", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
''',
    diagnostic_card + '''            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Umnik", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
''',
    "diagnostic settings card",
)
ui_path.write_text(ui, encoding="utf-8")


# FileProvider must be allowed to share the internal diagnostic file.
paths_path = ROOT / "app/src/main/res/xml/file_paths.xml"
paths = paths_path.read_text(encoding="utf-8")
paths = replace_once(
    paths,
    '    <files-path name="exports" path="exports/" />\n',
    '    <files-path name="exports" path="exports/" />\n    <files-path name="diagnostics" path="diagnostics/" />\n',
    "FileProvider diagnostics path",
)
paths_path.write_text(paths, encoding="utf-8")


# ---------------------------------------------------------------------------
# Version + changelog.
# ---------------------------------------------------------------------------
build_path = ROOT / "app/build.gradle.kts"
build = build_path.read_text(encoding="utf-8")
build = replace_once(build, "// Umnik v1.3.6", "// Umnik v1.3.7", "build version comment")
build = replace_once(build, "versionCode = 48", "versionCode = 49", "version code")
build = replace_once(build, 'versionName = "1.3.6"', 'versionName = "1.3.7"', "version name")
build_path.write_text(build, encoding="utf-8")

changelog_path = ROOT / "CHANGELOG.md"
changelog = changelog_path.read_text(encoding="utf-8")
entry = '''## v1.3.7 - 2026-09-12

- Внизу настроек добавлен раздел «Диагностика и логи». Запись выключена по умолчанию и включается только вручную на время воспроизведения ошибки.
- Лог можно сразу «Поделиться», сохранить отдельным `.log`-файлом или очистить. Размер автоматически ограничивается примерно 1 МБ.
- В диагностический лог не записываются тексты сообщений, содержимое файлов и API-ключи. Фиксируются технические события, выбранный провайдер и модель, безопасный URL без query-параметров, HTTP-код, длительность и ошибки.
- Сетевой лог охватывает OpenRouter, NVIDIA text/image, произвольные OpenAI-compatible подключения и обновление реестра провайдеров.
- Для NVIDIA/совместимого текстового API запрос теперь явно нестриминговый (`stream=false`) и ограничен 4096 выходными токенами; это соответствует официальному примеру NVIDIA для GPT-OSS.
- После тайм-аута повторный запрос больше не отправляет подряд несколько неотвеченных сообщений пользователя: хвост без ответа отбрасывается перед запросом к совместимому API, что соблюдает требование NVIDIA к чередованию ролей.
- Если `content` неожиданно пуст, Umnik умеет взять текст из `reasoning_content`; тайм-аут теперь показывается понятным сообщением с подсказкой включить диагностику.

'''
changelog = replace_once(
    changelog,
    "## Unreleased\n\n",
    "## Unreleased\n\n" + entry,
    "changelog v1.3.7",
)
changelog_path.write_text(changelog, encoding="utf-8")

print("Applied Umnik v1.3.7 changes")
