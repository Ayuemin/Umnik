from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    if old not in text:
        raise RuntimeError(f"Pattern not found in {path}: {old[:260]!r}")
    write(path, text.replace(old, new, 1))


# ---------------- Models ----------------
models = "app/src/main/java/com/ayuemin/ymnik/model/Models.kt"
replace_once(
    models,
    '''    val apiKeyConfigured: Boolean = false,\n    val isLoading: Boolean = false,\n    val busyLabel: String? = null,''',
    '''    val apiKeyConfigured: Boolean = false,\n    val isLoading: Boolean = false,\n    val requestActive: Boolean = false,\n    val busyLabel: String? = null,'''
)

# ---------------- ViewModel ----------------
vm = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
replace_once(
    vm,
    '''import kotlinx.coroutines.flow.MutableStateFlow\nimport kotlinx.coroutines.flow.StateFlow''',
    '''import kotlinx.coroutines.Job\nimport kotlinx.coroutines.flow.MutableStateFlow\nimport kotlinx.coroutines.flow.StateFlow'''
)
replace_once(
    vm,
    '''    private val api = OpenRouterClient(context)\n    private val gson = Gson()\n''',
    '''    private val api = OpenRouterClient(context)\n    private val gson = Gson()\n    private var activeRequestJob: Job? = null\n    private var activeRequestPending: List<PendingAttachment> = emptyList()\n    private var requestGeneration: Long = 0L\n'''
)
replace_once(
    vm,
    '''    fun send(text: String) {\n''',
    '''    fun stopGeneration() {\n        if (!_state.value.requestActive) return\n        requestGeneration += 1L\n        api.cancelActiveRequest()\n        activeRequestJob?.cancel()\n        activeRequestJob = null\n        cleanupTempAttachments(activeRequestPending)\n        activeRequestPending = emptyList()\n        _state.value = _state.value.copy(\n            isLoading = false,\n            requestActive = false,\n            busyLabel = null,\n            status = "Работа остановлена. Уточните запрос и отправьте снова."\n        )\n    }\n\n    fun send(text: String) {\n'''
)
replace_once(
    vm,
    '''            isLoading = true,\n            busyLabel = if (_state.value.mode == ChatMode.IMAGE) "Генерирую изображение…" else "Модель думает…",''',
    '''            isLoading = true,\n            requestActive = true,\n            busyLabel = if (_state.value.mode == ChatMode.IMAGE) "Генерирую изображение…" else "Модель думает…",'''
)
replace_once(
    vm,
    '''        val reasoningEnabled = _state.value.reasoningEnabled\n        val reasoningEffort = _state.value.reasoningEffort\n\n        viewModelScope.launch {\n''',
    '''        val reasoningEnabled = _state.value.reasoningEnabled\n        val reasoningEffort = _state.value.reasoningEffort\n        val requestId = ++requestGeneration\n        activeRequestPending = pending\n\n        activeRequestJob = viewModelScope.launch {\n'''
)
replace_once(
    vm,
    '''            operation.onSuccess { result ->\n                val assistant = ChatMessage(\n                    id = UUID.randomUUID().toString(),\n                    role = "assistant",\n                    text = result.text.ifBlank {\n                        if (result.files.isNotEmpty()) "Готово." else "Пустой ответ модели."\n                    },\n                    generatedFiles = result.files\n                )\n                val messages = _state.value.messages + assistant\n                val chats = replaceChatMessages(_state.value.chats, chatId, messages, null)\n                chatsRepository.save(chats)\n                _state.value = _state.value.copy(\n                    messages = messages,\n                    chats = chats,\n                    isLoading = false,\n                    busyLabel = null,\n                    storedFiles = storageRepository.list(),\n                    storageStats = storageRepository.stats()\n                )\n                playReadySound()\n            }.onFailure {\n                _state.value = _state.value.copy(\n                    isLoading = false,\n                    busyLabel = null,\n                    status = it.message ?: "Ошибка запроса"\n                )\n            }\n            cleanupTempAttachments(pending)\n        }\n''',
    '''            if (requestId != requestGeneration) {\n                cleanupTempAttachments(pending)\n                return@launch\n            }\n\n            operation.onSuccess { result ->\n                val assistant = ChatMessage(\n                    id = UUID.randomUUID().toString(),\n                    role = "assistant",\n                    text = result.text.ifBlank {\n                        if (result.files.isNotEmpty()) "Готово." else "Пустой ответ модели."\n                    },\n                    generatedFiles = result.files\n                )\n                val messages = _state.value.messages + assistant\n                val chats = replaceChatMessages(_state.value.chats, chatId, messages, null)\n                chatsRepository.save(chats)\n                _state.value = _state.value.copy(\n                    messages = messages,\n                    chats = chats,\n                    isLoading = false,\n                    requestActive = false,\n                    busyLabel = null,\n                    storedFiles = storageRepository.list(),\n                    storageStats = storageRepository.stats()\n                )\n                playReadySound()\n            }.onFailure {\n                _state.value = _state.value.copy(\n                    isLoading = false,\n                    requestActive = false,\n                    busyLabel = null,\n                    status = it.message ?: "Ошибка запроса"\n                )\n            }\n            cleanupTempAttachments(pending)\n            if (requestId == requestGeneration) {\n                activeRequestJob = null\n                activeRequestPending = emptyList()\n            }\n        }\n'''
)
replace_once(
    vm,
    '''    fun exportMessage(message: ChatMessage): GeneratedFile {\n''',
    '''    override fun onCleared() {\n        api.cancelActiveRequest()\n        activeRequestJob?.cancel()\n        super.onCleared()\n    }\n\n    fun exportMessage(message: ChatMessage): GeneratedFile {\n'''
)

# ---------------- OpenRouter cancellation ----------------
net = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"
replace_once(
    net,
    '''import okhttp3.MediaType.Companion.toMediaType\nimport okhttp3.OkHttpClient''',
    '''import okhttp3.Call\nimport okhttp3.MediaType.Companion.toMediaType\nimport okhttp3.OkHttpClient'''
)
replace_once(
    net,
    '''    private val http = OkHttpClient.Builder()\n        .connectTimeout(30, TimeUnit.SECONDS)\n        .readTimeout(240, TimeUnit.SECONDS)\n        .writeTimeout(240, TimeUnit.SECONDS)\n        .build()\n\n    data class Result''',
    '''    private val http = OkHttpClient.Builder()\n        .connectTimeout(30, TimeUnit.SECONDS)\n        .readTimeout(240, TimeUnit.SECONDS)\n        .writeTimeout(240, TimeUnit.SECONDS)\n        .build()\n    private val activeCallLock = Any()\n    @Volatile private var activeCall: Call? = null\n\n    data class Result'''
)
replace_once(
    net,
    '''    suspend fun models(apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {''',
    '''    fun cancelActiveRequest() {\n        synchronized(activeCallLock) { activeCall?.cancel() }\n    }\n\n    private fun executeActive(request: Request): okhttp3.Response {\n        val call = http.newCall(request)\n        synchronized(activeCallLock) { activeCall = call }\n        return try {\n            call.execute()\n        } finally {\n            synchronized(activeCallLock) {\n                if (activeCall === call) activeCall = null\n            }\n        }\n    }\n\n    suspend fun models(apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {'''
)
replace_once(
    net,
    '''        http.newCall(request).execute().use { response ->\n            val body = response.body?.string().orEmpty()\n            if (!response.isSuccessful) error(apiError(response.code, body))\n            val root = gson.fromJson(body, JsonObject::class.java)\n            val data = root.getAsJsonArray("data") ?: error("OpenRouter не вернул изображение")''',
    '''        executeActive(request).use { response ->\n            val body = response.body?.string().orEmpty()\n            if (!response.isSuccessful) error(apiError(response.code, body))\n            val root = gson.fromJson(body, JsonObject::class.java)\n            val data = root.getAsJsonArray("data") ?: error("OpenRouter не вернул изображение")'''
)
replace_once(
    net,
    '''        http.newCall(request).execute().use { response ->\n            val body = response.body?.string().orEmpty()\n            if (!response.isSuccessful) error(apiError(response.code, body))\n            val root = gson.fromJson(body, JsonObject::class.java)\n            return root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject''',
    '''        executeActive(request).use { response ->\n            val body = response.body?.string().orEmpty()\n            if (!response.isSuccessful) error(apiError(response.code, body))\n            val root = gson.fromJson(body, JsonObject::class.java)\n            return root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject'''
)

# ---------------- UI ----------------
ui = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
replace_once(
    ui,
    '''import androidx.compose.material.icons.outlined.Storage\nimport androidx.compose.material.icons.outlined.SwapHoriz''',
    '''import androidx.compose.material.icons.outlined.Stop\nimport androidx.compose.material.icons.outlined.Storage\nimport androidx.compose.material.icons.outlined.SwapHoriz'''
)
replace_once(
    ui,
    '''                IconButton(\n                    onClick = {\n                        vm.send(text)\n                        text = ""\n                    },\n                    enabled = !state.isLoading && (text.isNotBlank() || state.pendingAttachments.isNotEmpty()),\n                    modifier = Modifier.size(42.dp)\n                ) {\n                    Icon(Icons.Outlined.Send, contentDescription = "Отправить")\n                }''',
    '''                IconButton(\n                    onClick = {\n                        if (state.requestActive) {\n                            vm.stopGeneration()\n                        } else {\n                            vm.send(text)\n                            text = ""\n                        }\n                    },\n                    enabled = state.requestActive || (!state.isLoading && (\n                        text.isNotBlank() || state.pendingAttachments.isNotEmpty() || currentChatFiles.isNotEmpty()\n                    )),\n                    modifier = Modifier.size(42.dp)\n                ) {\n                    Icon(\n                        if (state.requestActive) Icons.Outlined.Stop else Icons.Outlined.Send,\n                        contentDescription = if (state.requestActive) "Остановить работу модели" else "Отправить"\n                    )\n                }'''
)
replace_once(
    ui,
    '''                if (state.isLoading) {\n                    CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp)\n                }\n\n''',
    ''''''
)

# ---------------- Version ----------------
gradle = "app/build.gradle.kts"
replace_once(gradle, "// Umnik v0.6.5 chat-scoped persistent documents", "// Umnik v0.6.6 stoppable model requests")
replace_once(gradle, 'versionCode = 14', 'versionCode = 15')
replace_once(gradle, 'versionName = "0.6.5"', 'versionName = "0.6.6"')

print("Umnik 0.6.6 stoppable-request patch applied")
