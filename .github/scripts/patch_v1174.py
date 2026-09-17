from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# OpenRouterClient: restore normal OkHttp protocol negotiation and enable SSE for ordinary chat calls.
path = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"
replace_once(path, "import okhttp3.Protocol\n", "")
replace_once(
    path,
    "    private val recoveryEnabled: Boolean = false,\n    private val phaseCallback: (String) -> Unit = {}\n",
    "    private val recoveryEnabled: Boolean = false,\n    private val streamCallback: (String) -> Unit = {},\n    private val phaseCallback: (String) -> Unit = {}\n",
)
replace_once(
    path,
    "        // Direct Umnik requests do not multiplex on one client. HTTP/1.1 avoids the\n"
    "        // Android background HTTP/2 socket abort reproduced on real devices.\n"
    "        .protocols(listOf(Protocol.HTTP_1_1))\n",
    "        // Keep OkHttp defaults: negotiate HTTP/2 when available and fall back to HTTP/1.1.\n"
    "        // No active ping is configured; SSE traffic itself keeps long responses active.\n",
)
replace_once(
    path,
    "            val completion = requestCompletion(apiKey, baseUrl, payload, allowEmpty = created.isNotEmpty())\n",
    "            streamCallback(\"\")\n"
    "            val completion = requestCompletion(apiKey, baseUrl, payload, allowEmpty = created.isNotEmpty())\n",
)
replace_once(
    path,
    "        val payloadJson = gson.toJson(payload)\n",
    "        val requestPayload = payload.deepCopy().apply {\n"
    "            addProperty(\"stream\", true)\n"
    "            add(\"stream_options\", JsonObject().apply { addProperty(\"include_usage\", true) })\n"
    "        }\n"
    "        val payloadJson = gson.toJson(requestPayload)\n",
)
old_response = '''                    phaseCallback(
                        if (cacheStatus.equals("HIT", ignoreCase = true))
                            "Готовый ответ найден · загружаю…"
                        else
                            "Модель формирует ответ…"
                    )
                    // Do not clear recovery state until the whole response body has arrived.
                    val body = response.body?.string().orEmpty()
                    clearRecovery(recoveryRecord)
                    if (!response.isSuccessful) error(apiError(response.code, body))
                    val completion = OpenRouterResponseParser.parse(body, allowEmpty)
'''
new_response = '''                    if (!response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        clearRecovery(recoveryRecord)
                        error(apiError(response.code, body))
                    }
                    phaseCallback(
                        if (cacheStatus.equals("HIT", ignoreCase = true))
                            "Готовый ответ найден · загружаю…"
                        else
                            "Модель формирует ответ…"
                    )
                    // Do not clear recovery state until the whole SSE/body has arrived.
                    val responseBody = response.body ?: error("OpenRouter вернул ответ без тела")
                    val completion = if (
                        response.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)
                    ) {
                        var streamAnnounced = false
                        OpenRouterStreamParser.parse(responseBody.source(), allowEmpty) { partial ->
                            if (!streamAnnounced && partial.isNotBlank()) {
                                streamAnnounced = true
                                phaseCallback("Получаю ответ…")
                            }
                            streamCallback(partial)
                        }.also { parsed ->
                            val finalText = extractText(parsed.message.get("content"))
                            if (finalText.isNotBlank()) streamCallback(finalText)
                        }
                    } else {
                        // Defensive compatibility path for an endpoint that ignores stream=true.
                        OpenRouterResponseParser.parse(responseBody.string(), allowEmpty)
                    }
                    clearRecovery(recoveryRecord)
'''
replace_once(path, old_response, new_response)
replace_once(
    path,
    "                    // completion after a mobile HTTP/2 interruption instead of blindly paying twice.\n",
    "                    // completion after a mobile transport interruption instead of blindly paying twice.\n",
)

# RequestNetworkSession: route stream previews to the process-wide request state.
path = "app/src/main/java/com/ayuemin/ymnik/RequestNetworkSession.kt"
replace_once(
    path,
    "            requestProfileId = profileId,\n            recoveryEnabled = recoverable\n        ) { label -> updatePhase(label) }\n",
    "            requestProfileId = profileId,\n            recoveryEnabled = recoverable,\n            streamCallback = { text -> updatePartial(text) },\n            phaseCallback = { label -> updatePhase(label) }\n        )\n",
)
replace_once(
    path,
    "    fun updatePhase(label: String) {\n        RequestExecutionManager.updatePhase(app, requestId, label)\n    }\n\n",
    "    fun updatePhase(label: String) {\n        RequestExecutionManager.updatePhase(app, requestId, label)\n    }\n\n"
    "    fun updatePartial(text: String) {\n        RequestExecutionManager.updatePartial(requestId, text)\n    }\n\n",
)

# RequestExecutionManager: keep a throttled in-memory partial answer. It is intentionally not persisted.
path = "app/src/main/java/com/ayuemin/ymnik/RequestExecutionManager.kt"
replace_once(
    path,
    "        val lastError: String? = null,\n        val label: String = \"Модель работает…\",\n        val startedAt: Long = System.currentTimeMillis()\n",
    "        val lastError: String? = null,\n        val label: String = \"Модель работает…\",\n        val partialText: String = \"\",\n        val startedAt: Long = System.currentTimeMillis()\n",
)
replace_once(
    path,
    "        val cancelNetworkCall: () -> Unit,\n        val appContext: Context\n",
    "        val cancelNetworkCall: () -> Unit,\n        val appContext: Context,\n        var lastPartialUpdateAt: Long = 0L\n",
)
replace_once(
    path,
    "    private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L\n",
    "    private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L\n"
    "    private const val PARTIAL_UPDATE_MIN_INTERVAL_MS = 120L\n"
    "    private const val PARTIAL_UPDATE_MIN_CHARS = 96\n"
    "    private const val MAX_PARTIAL_PREVIEW_CHARS = 120_000\n",
)
phase_method = '''    fun updatePhase(context: Context, requestId: String, label: String) {
        val clean = label.trim().take(160).ifBlank { "Модель работает…" }
        val changed = synchronized(lock) {
            val runtime = runtimes[requestId] ?: return@synchronized false
            sequence += 1L
            runtime.snapshot = runtime.snapshot.copy(sequence = sequence, label = clean)
            publishLocked()
            true
        }
        if (changed) RequestKeepAliveService.update(context.applicationContext)
    }

'''
replace_once(
    path,
    phase_method,
    phase_method + '''    fun updatePartial(requestId: String, text: String) {
        val clean = text.take(MAX_PARTIAL_PREVIEW_CHARS)
        synchronized(lock) {
            val runtime = runtimes[requestId] ?: return
            if (runtime.snapshot.partialText == clean) return
            val now = System.currentTimeMillis()
            val force = clean.isBlank()
            val enoughTime = now - runtime.lastPartialUpdateAt >= PARTIAL_UPDATE_MIN_INTERVAL_MS
            val enoughText = kotlin.math.abs(clean.length - runtime.snapshot.partialText.length) >= PARTIAL_UPDATE_MIN_CHARS
            if (!force && !enoughTime && !enoughText) return
            runtime.lastPartialUpdateAt = now
            runtime.snapshot = runtime.snapshot.copy(partialText = clean)
            // Do not update the foreground notification for every token; the StateFlow is enough for Compose.
            publishLocked()
        }
    }

''',
)

# ChatViewModel: streaming snapshot updates should not reread the full chat repository every ~120 ms.
path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
replace_once(
    path,
    "        viewModelScope.launch {\n            RequestExecutionManager.snapshots.collect { snapshots ->\n                val chats = chatsRepository.list()\n",
    "        viewModelScope.launch {\n            var previousRequestIds = emptySet<String>()\n            RequestExecutionManager.snapshots.collect { snapshots ->\n                val requestIds = snapshots.mapTo(linkedSetOf()) { it.requestId }\n                val topologyChanged = requestIds != previousRequestIds\n                val chats = if (topologyChanged) chatsRepository.list() else _state.value.chats\n                previousRequestIds = requestIds\n",
)

# Chat UI: render the in-memory stream as a temporary assistant response.
path = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
replace_once(path, "import com.ayuemin.ymnik.ChatViewModel\n", "import com.ayuemin.ymnik.ChatViewModel\nimport com.ayuemin.ymnik.RequestExecutionManager\n")
replace_once(
    path,
    "    val requestActiveHere = vm.isChatRequestActive(state.currentChatId)\n",
    "    val requestActiveHere = vm.isChatRequestActive(state.currentChatId)\n"
    "    val requestSnapshots by RequestExecutionManager.snapshots.collectAsState()\n"
    "    val streamingText = requestSnapshots.firstOrNull { it.chatId == state.currentChatId }?.partialText.orEmpty()\n",
)
replace_once(
    path,
    "            item(key = \"chat-end\") { Spacer(Modifier.height(1.dp)) }\n",
    "            if (requestActiveHere && streamingText.isNotBlank()) {\n"
    "                item(key = \"streaming-${state.currentChatId}\") {\n"
    "                    StreamingAssistantMessage(streamingText)\n"
    "                }\n"
    "            }\n"
    "            item(key = \"chat-end\") { Spacer(Modifier.height(1.dp)) }\n",
)
message_card_anchor = '''@Composable
private fun MessageCard(
'''
streaming_card = '''@Composable
private fun StreamingAssistantMessage(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        MessageBody(text, MaterialTheme.colorScheme.onSurface, null)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Ответ поступает…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessageCard(
'''
replace_once(path, message_card_anchor, streaming_card)

# Recovery worker: use OkHttp's normal HTTP/2 -> HTTP/1.1 negotiation and parse cached SSE replay.
path = "app/src/main/java/com/ayuemin/ymnik/OpenRouterRecoveryWorker.kt"
replace_once(path, "import okhttp3.Protocol\n", "")
replace_once(path, "import com.ayuemin.ymnik.network.OpenRouterResponseParser\n", "import com.ayuemin.ymnik.network.OpenRouterResponseParser\nimport com.ayuemin.ymnik.network.OpenRouterStreamParser\n")
replace_once(path, "            .protocols(listOf(Protocol.HTTP_1_1))\n", "")
old_replay = '''        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("OpenRouter ${response.code}")
            val cache = response.header("X-OpenRouter-Cache-Status")
            if (!cache.equals("HIT", ignoreCase = true)) {
                DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Rejected replay because cache was ${cache ?: "unknown"}; request=${record.requestId.take(8)}")
                throw IOException("OpenRouter response cache replay was not a HIT")
            }
            OpenRouterResponseParser.parse(body, allowEmpty = false)
        }
'''
new_replay = '''        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                response.body?.close()
                throw IOException("OpenRouter ${response.code}")
            }
            val cache = response.header("X-OpenRouter-Cache-Status")
            if (!cache.equals("HIT", ignoreCase = true)) {
                response.body?.close()
                DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Rejected replay because cache was ${cache ?: "unknown"}; request=${record.requestId.take(8)}")
                throw IOException("OpenRouter response cache replay was not a HIT")
            }
            val body = response.body ?: throw IOException("OpenRouter cache replay returned no body")
            if (response.header("Content-Type").orEmpty().contains("text/event-stream", ignoreCase = true)) {
                OpenRouterStreamParser.parse(body.source(), allowEmpty = false)
            } else {
                OpenRouterResponseParser.parse(body.string(), allowEmpty = false)
            }
        }
'''
replace_once(path, old_replay, new_replay)

# Version and changelog.
path = "app/build.gradle.kts"
replace_once(path, "// Umnik v1.17.3\n", "// Umnik v1.17.4\n")
replace_once(path, "        versionCode = 126\n        versionName = \"1.17.3\"\n", "        versionCode = 127\n        versionName = \"1.17.4\"\n")

path = "CHANGELOG.md"
p = Path(path)
text = p.read_text(encoding="utf-8")
entry = '''## v1.17.4

- Обычные OpenRouter-чаты переведены на SSE streaming (`stream: true`): текст приходит частями по мере генерации вместо одного длинного молчащего HTTP-ответа.
- Частичный ответ показывается прямо в чате как временный ответ ассистента; обновления UI ограничены по частоте и не записываются на диск на каждый токен.
- Потоковый parser собирает не только текст, но и `tool_calls` по индексам, поэтому локальные инструменты продолжают работать после полного завершения stream.
- Для streaming включён `stream_options.include_usage`, чтобы сохранить статистику токенов/стоимости там, где OpenRouter её возвращает.
- Возвращено стандартное согласование OkHttp: HTTP/2 используется при доступности с автоматическим fallback на HTTP/1.1. Принудительный HTTP/1.1 из v1.17.3 убран, активный ping не возвращён.
- Безопасный recovery сохранён: cached replay умеет разбирать SSE и по-прежнему принимается только при подтверждённом `X-OpenRouter-Cache-Status: HIT`.
- Оптимизировано обновление состояния: потоковые фрагменты не заставляют ViewModel перечитывать весь репозиторий чатов на каждое обновление.
- Версия: 1.17.4 / versionCode 127.

'''
if not text.startswith("## v1.17.3"):
    raise SystemExit("CHANGELOG.md: unexpected header")
p.write_text(entry + text, encoding="utf-8")

print("v1.17.4 production patch applied")
