from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 occurrence, got {count}")
    return text.replace(old, new, 1)


def sub_once(text, pattern, replacement, label, flags=re.S):
    result, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 regex match, got {count}")
    return result


# OpenRouterClient: phase updates belong to the isolated request session, not a global request.
path = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"
text = read(path)
text = text.replace("import com.ayuemin.ymnik.RequestExecutionManager\n", "")
text = replace_once(
    text,
    "class OpenRouterClient(private val context: Context) {",
    "class OpenRouterClient(\n    private val context: Context,\n    private val phaseCallback: (String) -> Unit = {}\n) {",
    "OpenRouterClient constructor"
)
text = replace_once(
    text,
    '''                RequestExecutionManager.updatePhase(\n                    context,\n                    if (recoveryAttempt == 0) "Запрос отправлен · модель отвечает…" else "Забираю восстановленный ответ…"\n                )''',
    '''                phaseCallback(\n                    if (recoveryAttempt == 0) "Запрос отправлен · модель отвечает…" else "Забираю восстановленный ответ…"\n                )''',
    "OpenRouter phase 1"
)
text = replace_once(
    text,
    '''                    RequestExecutionManager.updatePhase(\n                        context,\n                        if (cacheStatus.equals("HIT", ignoreCase = true))\n                            "Готовый ответ найден · загружаю…"\n                        else\n                            "Модель формирует ответ…"\n                    )''',
    '''                    phaseCallback(\n                        if (cacheStatus.equals("HIT", ignoreCase = true))\n                            "Готовый ответ найден · загружаю…"\n                        else\n                            "Модель формирует ответ…"\n                    )''',
    "OpenRouter phase 2"
)
text = replace_once(
    text,
    'RequestExecutionManager.updatePhase(context, "Связь прервалась · проверяю готовый ответ…")',
    'phaseCallback("Связь прервалась · проверяю готовый ответ…")',
    "OpenRouter recovery phase"
)
text = replace_once(
    text,
    'RequestExecutionManager.updatePhase(context, "Ответ готов · восстанавливаю соединение…")',
    'phaseCallback("Ответ готов · восстанавливаю соединение…")',
    "OpenRouter recovered phase"
)
write(path, text)


# ChatMemoryManager: let a foreground request provide its isolated OpenRouter client.
path = "app/src/main/java/com/ayuemin/ymnik/data/ChatMemoryManager.kt"
text = read(path)
text = replace_once(
    text,
    '''        query: String,\n        apiKey: String?,\n        baseUrl: String?\n    ): PreparedContext {''',
    '''        query: String,\n        apiKey: String?,\n        baseUrl: String?,\n        apiOverride: OpenRouterClient? = null\n    ): PreparedContext {''',
    "memory prepare signature"
)
text = replace_once(
    text,
    'sync(chat, fullHistory, settings, recentCount, apiKey, baseUrl)',
    'sync(chat, fullHistory, settings, recentCount, apiKey, baseUrl, apiOverride)',
    "memory prepare sync"
)
text = replace_once(
    text,
    'sync(chat, chat.messages, settings, recent, apiKey, baseUrl)',
    'sync(chat, chat.messages, settings, recent, apiKey, baseUrl, null)',
    "memory rebuild sync"
)
text = replace_once(
    text,
    '''        recentCount: Int,\n        apiKey: String,\n        baseUrl: String\n    ) {''',
    '''        recentCount: Int,\n        apiKey: String,\n        baseUrl: String,\n        apiOverride: OpenRouterClient?\n    ) {''',
    "memory sync signature"
)
text = replace_once(
    text,
    'summarize(settings, apiKey, baseUrl, source, previousState)',
    'summarize(settings, apiKey, baseUrl, source, previousState, apiOverride)',
    "memory summarize call"
)
text = replace_once(
    text,
    '''        baseUrl: String,\n        sourceText: String,\n        previousStateCard: String\n    ): Pair<String, String> {''',
    '''        baseUrl: String,\n        sourceText: String,\n        previousStateCard: String,\n        apiOverride: OpenRouterClient?\n    ): Pair<String, String> {''',
    "memory summarize signature"
)
text = replace_once(text, '        val result = api.chat(', '        val result = (apiOverride ?: api).chat(', "memory summary client")
write(path, text)


# WorkManager must understand the manager now exposes a list of active requests.
path = "app/src/main/java/com/ayuemin/ymnik/OpenRouterBackgroundWorker.kt"
text = read(path)
text = replace_once(
    text,
    '''            if (RequestExecutionManager.hasActiveRequest() &&\n                stored.chatId != null &&\n                stored.chatId == RequestExecutionManager.snapshots.value.activeChatId\n            ) {''',
    '''            if (stored.chatId != null && RequestExecutionManager.hasActiveChat(stored.chatId)) {''',
    "background worker active chat"
)
write(path, text)


# ChatViewModel: isolate requests by chat and run all foreground network work through RequestNetworkSession.
path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
text = read(path)
text = text.replace("import com.ayuemin.ymnik.network.CompatibleApiClient\n", "")
text = text.replace("import com.ayuemin.ymnik.network.NvidiaImageClient\n", "")
text = text.replace("import kotlinx.coroutines.Job\n", "import kotlinx.coroutines.Job\nimport kotlinx.coroutines.async\nimport kotlinx.coroutines.awaitAll\nimport kotlinx.coroutines.coroutineScope\n")
text = replace_once(
    text,
    '''    private val api = OpenRouterClient(context)\n    private val chatMemoryManager = ChatMemoryManager(context, chatMemory, embeddingApi, api)\n    private val compatibleApi = CompatibleApiClient(context)\n    private val nvidiaImageApi = NvidiaImageClient(context)\n    private val providerRegistry = ProviderRegistry(context)\n    private val gson = Gson()\n    private val recoveredRequest = RequestExecutionManager.recoverInterrupted(context)\n    private var activeRequestJob: Job? = null\n    private var activeRequestPending: List<PendingAttachment> = emptyList()\n    private var projectStagesJob: Job? = null\n    private var requestGeneration: Long = 0L''',
    '''    private val api = OpenRouterClient(context)\n    private val chatMemoryManager = ChatMemoryManager(context, chatMemory, embeddingApi, api)\n    private val providerRegistry = ProviderRegistry(context)\n    private val gson = Gson()\n    private val recoveredRequest = RequestExecutionManager.recoverInterrupted(context)\n    private val activeRequestPending = mutableMapOf<String, List<PendingAttachment>>()\n    private val requestGenerations = mutableMapOf<String, Long>()''',
    "viewmodel request fields"
)
text = replace_once(
    text,
    '''    val state: StateFlow<UiState> = _state.asStateFlow()\n\n    private fun ensureProjectOrchestrators''',
    '''    val state: StateFlow<UiState> = _state.asStateFlow()\n\n    fun activeRequestChatId(): String? = RequestExecutionManager.snapshots.value.firstOrNull()?.chatId\n    fun activeRequestChatIds(): Set<String> = RequestExecutionManager.snapshots.value.mapTo(linkedSetOf()) { it.chatId }\n    fun activeRequestCount(): Int = RequestExecutionManager.activeCount()\n    fun isChatRequestActive(chatId: String): Boolean = RequestExecutionManager.hasActiveChat(chatId)\n    fun activeRequestLabel(chatId: String): String? = RequestExecutionManager.snapshotForChat(chatId)?.label\n    fun activeRequestStartedAt(chatId: String): Long? = RequestExecutionManager.snapshotForChat(chatId)?.startedAt\n\n    fun concurrentRequestLimit(): Int = RequestConcurrencyLimiter.configuredLimit(context)\n\n    fun setConcurrentRequestLimit(limit: Int) {\n        if (_state.value.isLoading || RequestExecutionManager.hasActiveRequest()) return\n        val clean = limit.coerceAtLeast(0)\n        prefs.edit().putInt(RequestConcurrencyLimiter.PREF_KEY, clean).apply()\n        _state.value = _state.value.copy(\n            status = if (clean == 0) "Одновременная работа: без ограничений" else "Одновременно запросов: не более $clean"\n        )\n    }\n\n    private fun nextRequestGeneration(chatId: String): Long {\n        val next = (requestGenerations[chatId] ?: 0L) + 1L\n        requestGenerations[chatId] = next\n        return next\n    }\n\n    private fun isCurrentRequestGeneration(chatId: String, generation: Long): Boolean =\n        requestGenerations[chatId] == generation\n\n    private fun invalidateRequestGeneration(chatId: String) {\n        requestGenerations[chatId] = (requestGenerations[chatId] ?: 0L) + 1L\n    }\n\n    private fun ensureProjectOrchestrators''',
    "viewmodel request helpers"
)
# Remove obsolete single-request helper later in the file.
text = text.replace('    fun activeRequestChatId(): String? = RequestExecutionManager.snapshots.value.activeChatId\n', '')

text = sub_once(
    text,
    r'''        viewModelScope\.launch \{\n            RequestExecutionManager\.snapshots\.collect \{ snapshot ->\n                if \(snapshot\.sequence == 0L\) return@collect\n                val chats = chatsRepository\.list\(\)\n                val active = snapshot\.activeChatId != null\n                _state\.value = _state\.value\.copy\(\n                    chats = chats,\n                    messages = chats\.firstOrNull \{ it\.id == _state\.value\.currentChatId \}\?\.messages\.orEmpty\(\),\n                    isLoading = active,\n                    requestActive = active,\n                    busyLabel = if \(active\) snapshot\.label \?: "Модель работает…" else null,\n                    status = if \(active\) null else snapshot\.lastError,\n                    storedFiles = storageRepository\.list\(\),\n                    storageStats = storageRepository\.stats\(\)\n                \)\n            \}\n        \}''',
    '''        viewModelScope.launch {\n            RequestExecutionManager.snapshots.collect { snapshots ->\n                val chats = chatsRepository.list()\n                val current = snapshots.firstOrNull { it.chatId == _state.value.currentChatId }\n                val latestError = snapshots.mapNotNull { it.lastError }.lastOrNull()\n                _state.value = _state.value.copy(\n                    chats = chats,\n                    messages = chats.firstOrNull { it.id == _state.value.currentChatId }?.messages.orEmpty(),\n                    requestActive = snapshots.isNotEmpty(),\n                    busyLabel = current?.label,\n                    status = latestError ?: _state.value.status,\n                    storedFiles = storageRepository.list(),\n                    storageStats = storageRepository.stats()\n                )\n            }\n        }''',
    "snapshot collector"
)

# The Orchestrator may create as many actions as the user actually requested. Independent actions can run in parallel.
text = text.replace('            - Максимум 8 линейных действий.\n', '            - Не ограничивай число действий искусственно: создай столько рабочих действий, сколько реально требуется поручением.\n            - Если действия независимы друг от друга, ставь passPreviousResult=false: Umnik сможет запустить разные чаты параллельно.\n')

# Per-chat generations instead of one global generation that invalidated unrelated chats.
text = text.replace('val requestId = ++requestGeneration', 'val requestId = nextRequestGeneration(chatId)')
text = text.replace('val generation = ++requestGeneration', 'val generation = nextRequestGeneration(chatId)')
text = text.replace('if (requestId != requestGeneration)', 'if (!isCurrentRequestGeneration(chatId, requestId))')
text = text.replace('if (requestId == requestGeneration)', 'if (isCurrentRequestGeneration(chatId, requestId))')
text = text.replace('if (generation != requestGeneration)', 'if (!isCurrentRequestGeneration(chatId, generation))')
text = text.replace('if (generation == requestGeneration)', 'if (isCurrentRequestGeneration(chatId, generation))')

# Request-owned temporary attachments are also per chat.
text = text.replace('activeRequestPending = pending', 'activeRequestPending[chatId] = pending')
text = text.replace('activeRequestPending = emptyList()', 'activeRequestPending.remove(chatId)')

# Do not use one global Job slot.
text = text.replace('activeRequestJob = launchRequest', 'launchRequest')
text = text.replace('projectStagesJob = launchRequest', 'launchRequest')
text = text.replace('                    activeRequestJob = null\n', '')
text = text.replace('                projectStagesJob = null\n', '')

# Sending in another chat is allowed; only the same chat remains single-flight.
text = replace_once(
    text,
    '''        val mode = _state.value.mode\n        val chatId = _state.value.currentChatId\n        val currentChat = _state.value.chats.firstOrNull { it.id == chatId }''',
    '''        val mode = _state.value.mode\n        val chatId = _state.value.currentChatId\n        if (RequestExecutionManager.hasActiveChat(chatId)) {\n            _state.value = _state.value.copy(status = "В этом чате уже выполняется запрос")\n            return\n        }\n        val currentChat = _state.value.chats.firstOrNull { it.id == chatId }''',
    "send same-chat guard"
)

# Normal request state must not set the whole app into isLoading.
text = text.replace('            isLoading = true,\n            requestActive = true,', '            requestActive = true,')

# Orchestrator control: guard only its chat, and pass the isolated network session.
text = replace_once(
    text,
    '''    ) {\n        if (_state.value.isLoading || _state.value.requestActive) return\n        val chatId = _state.value.currentChatId\n        val orchestrator = _state.value.chats.firstOrNull { it.id == chatId && isOrchestratorChat(it.id) } ?: return''',
    '''    ) {\n        val chatId = _state.value.currentChatId\n        if (_state.value.isLoading || RequestExecutionManager.hasActiveChat(chatId)) return\n        val orchestrator = _state.value.chats.firstOrNull { it.id == chatId && isOrchestratorChat(it.id) } ?: return''',
    "orchestrator control guard"
)
text = text.replace('launchRequest(chatId, user.id, "◆ Оркестратор · ${project.name}") {\n            var failed', 'launchRequest(chatId, user.id, "◆ Оркестратор · ${project.name}") { network ->\n            var failed', 1)
text = text.replace('val plan = planOrchestratorControl(project, orchestrator, clean, before)', 'val plan = planOrchestratorControl(project, orchestrator, clean, before, network)', 1)

# Add network to Orchestrator helpers.
text = replace_once(
    text,
    '''        command: String,\n        history: List<ChatMessage>\n    ): OrchestratorControlPlan {''',
    '''        command: String,\n        history: List<ChatMessage>,\n        network: RequestNetworkSession\n    ): OrchestratorControlPlan {''',
    "orchestrator planner signature"
)
text = sub_once(
    text,
    r'''        val result = if \(profile\.type == ProviderType\.OPENROUTER\) \{\n            api\.chat\((.*?)\n            \)\n        \} else \{\n            compatibleApi\.chat\((.*?)\n            \)\n        \}''',
    '''        require(profile.type == ProviderType.OPENROUTER) { "Umnik использует только OpenRouter" }\n        val result = network.call { requestApi ->\n            requestApi.chat(\1\n            )\n        }''',
    "orchestrator planner OpenRouter only"
)
text = replace_once(
    text,
    '''        previous: String,\n        commandAttachments: List<PendingAttachment>\n    ): String {''',
    '''        previous: String,\n        commandAttachments: List<PendingAttachment>,\n        network: RequestNetworkSession\n    ): String {''',
    "control action network signature"
)
text = text.replace('extraAttachments = extra\n                )', 'extraAttachments = extra,\n                    network = network\n                )')
text = text.replace('runtimeOverride = runtime,\n                    extraAttachments = extra\n                )', 'runtimeOverride = runtime,\n                    extraAttachments = extra,\n                    network = network\n                )')

text = replace_once(
    text,
    '''        runtimeOverride: ProjectChatRuntimeProfile? = null,\n        extraAttachments: List<PendingAttachment> = emptyList()\n    ): String {''',
    '''        runtimeOverride: ProjectChatRuntimeProfile? = null,\n        extraAttachments: List<PendingAttachment> = emptyList(),\n        network: RequestNetworkSession\n    ): String {''',
    "execute chat network signature"
)
# There are two helpers with the same tail; replace the second occurrence for stage sequence.
needle = '''        runtimeOverride: ProjectChatRuntimeProfile? = null,\n        extraAttachments: List<PendingAttachment> = emptyList()\n    ): String {'''
if text.count(needle) != 1:
    raise RuntimeError(f"stage helper signature: expected remaining 1 occurrence, got {text.count(needle)}")
text = text.replace(needle, '''        runtimeOverride: ProjectChatRuntimeProfile? = null,\n        extraAttachments: List<PendingAttachment> = emptyList(),\n        network: RequestNetworkSession\n    ): String {''', 1)

# OpenRouter-only calls in the two Orchestrator worker helpers.
text = sub_once(
    text,
    r'''        val execPrefs = context\.getSharedPreferences\("request_execution", Context\.MODE_PRIVATE\)\n        execPrefs\.edit\(\)\.putString\("target_chat_id", chat\.id\)\.commit\(\)\n        val result = try \{\n            if \(profile\.type == ProviderType\.OPENROUTER\) \{\n                api\.chat\((.*?)\n                \)\n            \} else \{\n                compatibleApi\.chat\((.*?)\n                \)\n            \}\n        \} finally \{\n            execPrefs\.edit\(\)\.remove\("target_chat_id"\)\.commit\(\)\n        \}''',
    '''        require(profile.type == ProviderType.OPENROUTER) { "Umnik использует только OpenRouter" }\n        val result = network.call { requestApi ->\n            requestApi.chat(\1\n            )\n        }''',
    "orchestrator chat OpenRouter only"
)
text = sub_once(
    text,
    r'''            val execPrefs = context\.getSharedPreferences\("request_execution", Context\.MODE_PRIVATE\)\n            execPrefs\.edit\(\)\.putString\("target_chat_id", chat\.id\)\.commit\(\)\n            val response = try \{\n                if \(profile\.type == ProviderType\.OPENROUTER\) api\.chat\((.*?)\n                \) else compatibleApi\.chat\((.*?)\n                \)\n            \} finally \{ execPrefs\.edit\(\)\.remove\("target_chat_id"\)\.commit\(\) \}''',
    '''            require(profile.type == ProviderType.OPENROUTER) { "Umnik использует только OpenRouter" }\n            val response = network.call { requestApi ->\n                requestApi.chat(\1\n                )\n            }''',
    "orchestrator stages OpenRouter only"
)

# Pass network into control actions and fixed Orchestrator helpers.
text = text.replace('executeOrchestratorControlAction(project, orchestrator, action, previous, pending)', 'executeOrchestratorControlAction(project, orchestrator, action, previous, pending, network)')
text = text.replace('executeOrchestratorChatTask(project, target, step.prompt, previous, step.passPreviousResult)', 'executeOrchestratorChatTask(project, target, step.prompt, previous, step.passPreviousResult, network = network)')
text = text.replace('executeOrchestratorStageSequence(project, target, target.stages.orEmpty(), step.prompt, previous, step.passPreviousResult)', 'executeOrchestratorStageSequence(project, target, target.stages.orEmpty(), step.prompt, previous, step.passPreviousResult, network = network)')
text = text.replace('executeOrchestratorStageSequence(project, orchestrator, project.stages.orEmpty(), step.prompt, previous, step.passPreviousResult)', 'executeOrchestratorStageSequence(project, orchestrator, project.stages.orEmpty(), step.prompt, previous, step.passPreviousResult, network = network)')

# Fixed Orchestrator may run independent specialist chats in parallel.
text = replace_once(
    text,
    '''        launchRequest(orchestratorId, launch.id, "Оркестратор · ${project.name}") {\n            var previous = ""''',
    '''        launchRequest(orchestratorId, launch.id, "Оркестратор · ${project.name}") { network ->\n            var previous = ""''',
    "fixed orchestrator network lambda"
)

# Direct project/chat stages use their own request network session.
text = replace_once(
    text,
    'if (_state.value.isLoading || _state.value.requestActive || projectStagesJob != null) return null',
    'if (_state.value.isLoading) return null',
    "stage sequence global guard"
)
text = replace_once(
    text,
    '''        val activeChat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId }\n        val chatId = if (activeChat?.projectId == project.id) activeChat.id else createChat(project.id)\n        val currentChat = _state.value.chats.firstOrNull { it.id == chatId } ?: return null''',
    '''        val activeChat = _state.value.chats.firstOrNull { it.id == _state.value.currentChatId }\n        val chatId = if (activeChat?.projectId == project.id) activeChat.id else createChat(project.id)\n        if (RequestExecutionManager.hasActiveChat(chatId)) {\n            _state.value = _state.value.copy(status = "В этом чате уже выполняется запрос")\n            return null\n        }\n        val currentChat = _state.value.chats.firstOrNull { it.id == chatId } ?: return null''',
    "stage sequence same-chat guard"
)
text = replace_once(
    text,
    'launchRequest(chatId, user.id, "Этапы $sequenceName · ${project.name}") {\n            val results',
    'launchRequest(chatId, user.id, "Этапы $sequenceName · ${project.name}") { network ->\n            val results',
    "stage sequence network lambda"
)
text = text.replace('RequestExecutionManager.updatePhase(context, phaseLabel)', 'network.updatePhase(phaseLabel)')
# Only the direct stage block uses this exact api.chat shape now.
text = replace_once(
    text,
    '''                        api.chat(\n                            apiKey,\n                            modelId,\n                            baseHistory,\n                            prompt,\n                            attachments,\n                            systemPrompt,\n                            currentState.webSearchEnabled,\n                            actualReasoning,\n                            effort,\n                            modelInfo?.supportsTools == true,\n                            effectiveTextBaseUrl(profile),\n                            requestModelInfo\n                        )''',
    '''                        network.call { requestApi ->\n                            requestApi.chat(\n                                apiKey,\n                                modelId,\n                                baseHistory,\n                                prompt,\n                                attachments,\n                                systemPrompt,\n                                currentState.webSearchEnabled,\n                                actualReasoning,\n                                effort,\n                                modelInfo?.supportsTools == true,\n                                effectiveTextBaseUrl(profile),\n                                requestModelInfo\n                            )\n                        }''',
    "direct stages request client"
)

# Ordinary text chat: keep memory summary and completion inside the request-owned OpenRouter client.
text = sub_once(
    text,
    r'''                        val preparedContext = chatMemoryManager\.prepare\((.*?)\n                        \)\n                        if \(profile\.type == ProviderType\.OPENROUTER\) \{\n                            api\.chat\((.*?)\n                            \)\n                        \} else \{\n                            compatibleApi\.chat\((.*?)\n                            \)\n                        \}''',
    '''                        require(profile.type == ProviderType.OPENROUTER) { "Umnik использует только OpenRouter" }\n                        network.call { requestApi ->\n                            val preparedContext = chatMemoryManager.prepare(\1,\n                                apiOverride = requestApi\n                            )\n                            requestApi.chat(\2\n                            )\n                        }''',
    "ordinary text request client"
)

# launchRequest lambdas in ordinary send/image must receive network.
# The first remaining generic launch is ordinary send.
text = text.replace('launchRequest(chatId, user.id, "${profile.name} · ${if (mode == ChatMode.TEXT) textModel else imageModel}") {', 'launchRequest(chatId, user.id, "${profile.name} · ${if (mode == ChatMode.TEXT) textModel else imageModel}") { network ->', 1)
# image-only send has its own launch call.
text = text.replace('launchRequest(chatId, user.id, "${profile.name} · $imageModel") {', 'launchRequest(chatId, user.id, "${profile.name} · $imageModel") { network ->', 1)

# Request-specific image client. Umnik now uses OpenRouter only.
text = replace_once(
    text,
    '''                        generateImageForProfile(\n                            profile = profile,\n                            apiKey = key,\n                            model = imageModel,\n                            prompt = imagePrompt,\n                            attachments = pending + projectImages,\n                            aspectRatio = imageAspectRatio,\n                            resolution = imageResolution\n                        )''',
    '''                        network.call { requestApi ->\n                            generateImageForProfile(\n                                profile = profile,\n                                apiKey = key,\n                                model = imageModel,\n                                prompt = imagePrompt,\n                                attachments = pending + projectImages,\n                                aspectRatio = imageAspectRatio,\n                                resolution = imageResolution,\n                                requestApi = requestApi\n                            )\n                        }''',
    "inline image request client"
)
text = replace_once(
    text,
    '''                generateImageForProfile(\n                    profile = profile,\n                    apiKey = key,\n                    model = imageModel,\n                    prompt = prompt,\n                    attachments = pending,\n                    aspectRatio = imageAspectRatio,\n                    resolution = imageResolution\n                )''',
    '''                network.call { requestApi ->\n                    generateImageForProfile(\n                        profile = profile,\n                        apiKey = key,\n                        model = imageModel,\n                        prompt = prompt,\n                        attachments = pending,\n                        aspectRatio = imageAspectRatio,\n                        resolution = imageResolution,\n                        requestApi = requestApi\n                    )\n                }''',
    "image send request client"
)
text = replace_once(
    text,
    '''        attachments: List<PendingAttachment>,\n        aspectRatio: String?,\n        resolution: String?\n    ): OpenRouterClient.Result {\n        if (profile.type == ProviderType.OPENROUTER) {\n            return generateOpenRouterImageWithResolutionFallback(''',
    '''        attachments: List<PendingAttachment>,\n        aspectRatio: String?,\n        resolution: String?,\n        requestApi: OpenRouterClient = api\n    ): OpenRouterClient.Result {\n        require(profile.type == ProviderType.OPENROUTER) { "Umnik использует только OpenRouter" }\n        return generateOpenRouterImageWithResolutionFallback(''',
    "image helper OpenRouter only start"
)
# Replace the old non-OpenRouter tail of generateImageForProfile.
text = sub_once(
    text,
    r'''                resolution = resolution\n            \)\n        \}\n        return when \(resolvedImageProtocol\(profile\)\) \{.*?\n        \}\n    \}\n\n    private suspend fun generateOpenRouterImageWithResolutionFallback\(''',
    '''                resolution = resolution,\n                requestApi = requestApi\n            )\n    }\n\n    private suspend fun generateOpenRouterImageWithResolutionFallback(''',
    "image helper OpenRouter only tail"
)
text = replace_once(
    text,
    '''        baseUrl: String,\n        aspectRatio: String?,\n        resolution: String?\n    ): OpenRouterClient.Result {''',
    '''        baseUrl: String,\n        aspectRatio: String?,\n        resolution: String?,\n        requestApi: OpenRouterClient = api\n    ): OpenRouterClient.Result {''',
    "image fallback signature"
)
# Both attempts in the fallback must use the request-owned client.
text = text.replace('return api.generateImage(', 'return requestApi.generateImage(', 1)
text = text.replace('val result = api.generateImage(', 'val result = requestApi.generateImage(', 1)

# Same-chat guard for the dedicated image-send entry point.
text = replace_once(
    text,
    '''        val prompt = clean.ifBlank { "Создай вариант приложенного изображения" }\n        val chatId = _state.value.currentChatId\n        val before = _state.value.messages''',
    '''        val prompt = clean.ifBlank { "Создай вариант приложенного изображения" }\n        val chatId = _state.value.currentChatId\n        if (RequestExecutionManager.hasActiveChat(chatId)) {\n            _state.value = _state.value.copy(status = "В этом чате уже выполняется запрос")\n            return false\n        }\n        val before = _state.value.messages''',
    "image same-chat guard"
)

# Voice capture is blocked only by a request in this chat, not by another chat.
text = replace_once(
    text,
    '''        if (_state.value.isLoading || _state.value.requestActive) {\n            File(localPath).delete()\n            return false\n        }''',
    '''        if (_state.value.isLoading || RequestExecutionManager.hasActiveChat(_state.value.currentChatId)) {\n            File(localPath).delete()\n            return false\n        }''',
    "voice same-chat guard"
)

# Replace stopGeneration and launchRequest wholesale; Stop now affects only the currently open chat.
text = sub_once(
    text,
    r'''    fun stopGeneration\(\) \{.*?\n    \}\n\n    fun retryFailedMessage''',
    '''    fun stopGeneration() {\n        val chatId = _state.value.currentChatId\n        val snapshot = RequestExecutionManager.snapshotForChat(chatId) ?: return\n        invalidateRequestGeneration(chatId)\n        RequestExecutionManager.fail(snapshot.requestId, "Работа остановлена. При необходимости повторите запрос вручную.")\n        RequestExecutionManager.cancel(snapshot.requestId)\n        val restore = activeRequestPending.remove(chatId).orEmpty()\n        _state.value = _state.value.copy(\n            pendingAttachments = restore,\n            busyLabel = null,\n            status = "Работа в этом чате остановлена. Уточните запрос и отправьте снова."\n        )\n    }\n\n    fun retryFailedMessage''',
    "stopGeneration per-chat"
)
text = sub_once(
    text,
    r'''    private fun launchRequest\(\n        chatId: String,\n        messageId: String,\n        label: String,\n        execute: suspend \(\) -> Unit\n    \): Job\? = runCatching \{.*?\n        null\n    \}\n''',
    '''    private fun launchRequest(\n        chatId: String,\n        messageId: String,\n        label: String,\n        execute: suspend (RequestNetworkSession) -> Unit\n    ): Job? {\n        val requestId = UUID.randomUUID().toString()\n        val network = RequestNetworkSession(context, requestId)\n        return runCatching {\n            RequestExecutionManager.start(\n                context = context,\n                requestId = requestId,\n                chatId = chatId,\n                messageId = messageId,\n                label = label,\n                cancelNetworkCall = network::cancel,\n                execute = { execute(network) }\n            )\n        }.getOrElse { error ->\n            activeRequestPending.remove(chatId)\n            val chats = chatsRepository.finishRequest(chatId, messageId, null)\n            _state.value = _state.value.copy(\n                chats = chats,\n                messages = chats.firstOrNull { it.id == _state.value.currentChatId }?.messages.orEmpty(),\n                requestActive = RequestExecutionManager.hasActiveRequest(),\n                busyLabel = RequestExecutionManager.snapshotForChat(_state.value.currentChatId)?.label,\n                status = "Не удалось запустить фоновую работу: ${error.message ?: "ошибка Android"}"\n            )\n            null\n        }\n    }\n''',
    "launchRequest isolated"
)

# Orchestrator/fixed stages must not globally clear request state when one job completes.
text = text.replace('                    isLoading = false,\n                    requestActive = false,\n                    busyLabel = null,\n', '')
text = text.replace('                isLoading = false,\n                requestActive = false,\n                busyLabel = null,\n', '')
text = text.replace('                        isLoading = false,\n                        requestActive = false,\n                        busyLabel = null,\n', '')

# runOrchestrator guard after its chat is known.
text = replace_once(
    text,
    '''    fun runOrchestrator(projectId: String): String? {\n        if (_state.value.isLoading || _state.value.requestActive || projectStagesJob != null) return null\n        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return null\n        val orchestratorId = projectAutomation.orchestratorChatId(projectId) ?: return null''',
    '''    fun runOrchestrator(projectId: String): String? {\n        if (_state.value.isLoading) return null\n        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return null\n        val orchestratorId = projectAutomation.orchestratorChatId(projectId) ?: return null\n        if (RequestExecutionManager.hasActiveChat(orchestratorId)) return null''',
    "runOrchestrator guard"
)

# Remove legacy target_chat_id bookkeeping; request identity is explicit now.
text = re.sub(r'\s*context\.getSharedPreferences\("request_execution", Context\.MODE_PRIVATE\)\.edit\(\)\.remove\("target_chat_id"\)\.apply\(\)\n', '\n', text)

# If any global generation variable references survived, fail loudly.
if re.search(r'\brequestGeneration\b', text):
    raise RuntimeError("requestGeneration global reference survived")
if 'projectStagesJob' in text or 'activeRequestJob' in text:
    raise RuntimeError("global request Job slot survived")

write(path, text)

print("multi-request core patch applied")
