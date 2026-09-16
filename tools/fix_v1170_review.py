from pathlib import Path


def replace_once(path: str, old: str, new: str):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_in_section(path: str, start: str, end: str, old: str, new: str):
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    a = text.index(start)
    b = text.index(end, a)
    section = text[a:b]
    if old not in section:
        raise SystemExit(f"section pattern not found in {path}: {old[:120]!r}")
    section = section.replace(old, new, 1)
    p.write_text(text[:a] + section + text[b:], encoding="utf-8")


manager = "app/src/main/java/com/ayuemin/ymnik/RequestExecutionManager.kt"
replace_once(
    manager,
    "    private val runtimes = linkedMapOf<String, Runtime>()\n    private val mutableSnapshots = MutableStateFlow<List<Snapshot>>(emptyList())",
    "    private val runtimes = linkedMapOf<String, Runtime>()\n    private val reservations = linkedMapOf<String, String>() // chatId -> owning requestId\n    private val mutableSnapshots = MutableStateFlow<List<Snapshot>>(emptyList())"
)
replace_once(
    manager,
    '''    fun hasActiveRequest(): Boolean = synchronized(lock) { runtimes.isNotEmpty() }\n\n    fun activeCount(): Int = synchronized(lock) { runtimes.size }\n\n    fun hasActiveChat(chatId: String): Boolean = synchronized(lock) {\n        runtimes.values.any { it.snapshot.chatId == chatId }\n    }\n\n    fun snapshotForChat(chatId: String): Snapshot? = synchronized(lock) {\n        runtimes.values.firstOrNull { it.snapshot.chatId == chatId }?.snapshot\n    }\n\n    fun snapshotForRequest(requestId: String): Snapshot? = synchronized(lock) {\n        runtimes[requestId]?.snapshot\n    }\n''',
    '''    fun hasActiveRequest(): Boolean = synchronized(lock) { runtimes.isNotEmpty() }\n\n    fun activeCount(): Int = synchronized(lock) { runtimes.size }\n\n    fun activeChatIds(): Set<String> = synchronized(lock) {\n        linkedSetOf<String>().apply {\n            runtimes.values.forEach { add(it.snapshot.chatId) }\n            addAll(reservations.keys)\n        }\n    }\n\n    fun hasActiveChat(chatId: String): Boolean = synchronized(lock) {\n        runtimes.values.any { it.snapshot.chatId == chatId } || chatId in reservations\n    }\n\n    fun snapshotForChat(chatId: String): Snapshot? = synchronized(lock) {\n        runtimes.values.firstOrNull { it.snapshot.chatId == chatId }?.snapshot\n            ?: reservations[chatId]?.let { owner -> runtimes[owner]?.snapshot }\n    }\n\n    fun snapshotForRequest(requestId: String): Snapshot? = synchronized(lock) {\n        runtimes[requestId]?.snapshot\n    }\n\n    fun reserveChat(requestId: String, chatId: String): Boolean = synchronized(lock) {\n        val owner = runtimes[requestId] ?: return@synchronized false\n        val directOwner = runtimes.values.firstOrNull { it.snapshot.chatId == chatId }\n        if (directOwner != null && directOwner.snapshot.requestId != requestId) return@synchronized false\n        val reservedBy = reservations[chatId]\n        if (reservedBy != null && reservedBy != requestId) return@synchronized false\n        if (owner.snapshot.chatId == chatId || reservedBy == requestId) return@synchronized true\n        reservations[chatId] = requestId\n        sequence += 1L\n        owner.snapshot = owner.snapshot.copy(sequence = sequence)\n        publishLocked()\n        true\n    }\n\n    fun releaseChat(requestId: String, chatId: String) {\n        synchronized(lock) {\n            if (reservations[chatId] != requestId) return\n            reservations.remove(chatId)\n            runtimes[requestId]?.let { owner ->\n                sequence += 1L\n                owner.snapshot = owner.snapshot.copy(sequence = sequence)\n            }\n            publishLocked()\n        }\n    }\n'''
)
replace_once(
    manager,
    '            check(runtimes.values.none { it.snapshot.chatId == chatId }) { "В этом чате запрос уже выполняется" }',
    '            check(runtimes.values.none { it.snapshot.chatId == chatId } && chatId !in reservations) { "В этом чате запрос уже выполняется" }'
)
replace_once(
    manager,
    '''                val remaining = synchronized(lock) {\n                    runtimes.remove(requestId)\n                    publishLocked()\n                    runtimes.size\n                }''',
    '''                val remaining = synchronized(lock) {\n                    runtimes.remove(requestId)\n                    reservations.entries.removeAll { it.value == requestId }\n                    publishLocked()\n                    runtimes.size\n                }'''
)
replace_once(
    manager,
    '''                synchronized(lock) {\n                    runtimes.remove(requestId)\n                    publishLocked()\n                }''',
    '''                synchronized(lock) {\n                    runtimes.remove(requestId)\n                    reservations.entries.removeAll { it.value == requestId }\n                    publishLocked()\n                }'''
)

session = "app/src/main/java/com/ayuemin/ymnik/RequestNetworkSession.kt"
replace_once(
    session,
    '''    private fun openRouter(): OpenRouterClient = OpenRouterClient(app, requestId) { label -> updatePhase(label) }\n        .also { clients += it }\n\n    suspend fun <T> call(block: suspend (OpenRouterClient) -> T): T =\n        RequestConcurrencyLimiter.withPermit(app) { block(openRouter()) }\n''',
    '''    private fun openRouter(chatId: String? = null): OpenRouterClient =\n        OpenRouterClient(\n            context = app,\n            requestId = requestId,\n            requestChatId = chatId ?: RequestExecutionManager.snapshotForRequest(requestId)?.chatId\n        ) { label -> updatePhase(label) }\n            .also { clients += it }\n\n    suspend fun <T> call(chatId: String? = null, block: suspend (OpenRouterClient) -> T): T =\n        RequestConcurrencyLimiter.withPermit(app) { block(openRouter(chatId)) }\n\n    fun reserveChat(chatId: String): Boolean = RequestExecutionManager.reserveChat(requestId, chatId)\n\n    fun releaseChat(chatId: String) = RequestExecutionManager.releaseChat(requestId, chatId)\n'''
)

diag = "app/src/main/java/com/ayuemin/ymnik/diagnostics/DiagnosticLog.kt"
replace_once(
    diag,
    '''class DiagnosticHttpInterceptor(\n    private val context: Context,\n    private val source: String\n) : Interceptor {\n    private val openRouterEnhancer by lazy { OpenRouterRequestEnhancer(context.applicationContext) }''',
    '''class DiagnosticHttpInterceptor(\n    private val context: Context,\n    private val source: String,\n    private val requestId: String? = null,\n    private val requestChatId: String? = null\n) : Interceptor {\n    private val openRouterEnhancer by lazy {\n        OpenRouterRequestEnhancer(context.applicationContext, requestId, requestChatId)\n    }'''
)

client = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"
replace_once(
    client,
    '''class OpenRouterClient(\n    private val context: Context,\n    private val requestId: String? = null,\n    private val phaseCallback: (String) -> Unit = {}\n) {''',
    '''class OpenRouterClient(\n    private val context: Context,\n    private val requestId: String? = null,\n    private val requestChatId: String? = null,\n    private val phaseCallback: (String) -> Unit = {}\n) {'''
)
replace_once(
    client,
    '.addInterceptor(DiagnosticHttpInterceptor(context, "OpenRouter"))',
    '.addInterceptor(DiagnosticHttpInterceptor(context, "OpenRouter", requestId, requestChatId))'
)
replace_once(
    client,
    '    private val chatBatchRunner = OpenRouterChatBatchRunner(context, requestId)',
    '    private val chatBatchRunner = OpenRouterChatBatchRunner(context, requestId, requestChatId)'
)

runner = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterChatBatchRunner.kt"
replace_once(
    runner,
    '''internal class OpenRouterChatBatchRunner(\n    private val context: Context,\n    private val requestId: String? = null\n) {''',
    '''internal class OpenRouterChatBatchRunner(\n    private val context: Context,\n    private val requestId: String? = null,\n    private val requestChatId: String? = null\n) {'''
)
replace_once(
    runner,
    '''        val originChatId = originSnapshot.chatId\n        val originChat = chats.list().firstOrNull { it.id == originChatId }\n        val originMessageId = originSnapshot.messageId''',
    '''        val originChatId = requestChatId ?: originSnapshot.chatId\n        val originChat = chats.list().firstOrNull { it.id == originChatId }\n        val originMessageId = if (originChatId == originSnapshot.chatId) {\n            originSnapshot.messageId\n        } else {\n            originChat?.messages?.lastOrNull { it.role == "user" }?.id\n        }'''
)

enhancer = "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterRequestEnhancer.kt"
replace_once(
    enhancer,
    'import com.ayuemin.ymnik.OpenRouterBackgroundWorker\n',
    'import com.ayuemin.ymnik.OpenRouterBackgroundWorker\nimport com.ayuemin.ymnik.RequestExecutionManager\n'
)
replace_once(
    enhancer,
    'internal class OpenRouterRequestEnhancer(private val context: Context) {',
    '''internal class OpenRouterRequestEnhancer(\n    private val context: Context,\n    private val requestId: String? = null,\n    private val requestChatId: String? = null\n) {'''
)
replace_once(
    enhancer,
    '    data class Result(val request: Request? = null, val response: Response? = null)\n\n    fun enhance(request: Request): Result {',
    '''    data class Result(val request: Request? = null, val response: Response? = null)\n\n    private fun activeSnapshot() = requestId?.let(RequestExecutionManager::snapshotForRequest)\n\n    private fun effectiveChatId(): String? {\n        requestChatId?.takeIf { it.isNotBlank() }?.let { return it }\n        activeSnapshot()?.chatId?.let { return it }\n        val execution = context.getSharedPreferences("request_execution", Context.MODE_PRIVATE)\n        return execution.getString("target_chat_id", null) ?: execution.getString("chat_id", null)\n    }\n\n    fun enhance(request: Request): Result {'''
)
replace_once(
    enhancer,
    '''        val execution = context.getSharedPreferences("request_execution", Context.MODE_PRIVATE)\n        val requestChatId = execution.getString("target_chat_id", null) ?: execution.getString("chat_id", null)\n        val serverTools = requestChatId?.let { ProjectAutomationRepository(context).profile(it)?.tools } ?: prefs.tools()''',
    '''        val activeChatId = effectiveChatId()\n        val serverTools = activeChatId?.let { ProjectAutomationRepository(context).profile(it)?.tools } ?: prefs.tools()'''
)
replace_once(
    enhancer,
    '''        val execution = context.getSharedPreferences("request_execution", Context.MODE_PRIVATE)\n        val chatId = execution.getString("chat_id", null)\n        val messageId = execution.getString("message_id", null)''',
    '''        val snapshot = activeSnapshot()\n        val execution = context.getSharedPreferences("request_execution", Context.MODE_PRIVATE)\n        val chatId = effectiveChatId()\n        val messageId = snapshot?.messageId?.takeIf { chatId == snapshot.chatId }\n            ?: execution.getString("message_id", null)'''
)

vm = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
replace_once(
    vm,
    '    fun activeRequestChatIds(): Set<String> = RequestExecutionManager.snapshots.value.mapTo(linkedSetOf()) { it.chatId }',
    '    fun activeRequestChatIds(): Set<String> = RequestExecutionManager.activeChatIds()'
)

# Reserve a worker chat for the full single-action lifetime and pass its identity to OpenRouter.
replace_once(
    vm,
    '''        val all = chatsRepository.list()\n        val chat = all.firstOrNull { it.id == requested.id } ?: requested\n        if (!isOrchestratorChat(chat.id) && RequestExecutionManager.hasActiveChat(chat.id)) {\n            error("В чате «${chat.title}» уже выполняется другой запрос")\n        }\n        val runtime = runtimeOverride ?: projectAutomation.profile(chat.id) ?: defaultRuntimeProfile(chat)''',
    '''        val all = chatsRepository.list()\n        val chat = all.firstOrNull { it.id == requested.id } ?: requested\n        val reservationRequired = !isOrchestratorChat(chat.id)\n        if (reservationRequired && !network.reserveChat(chat.id)) {\n            error("В чате «${chat.title}» уже выполняется другой запрос")\n        }\n        try {\n        val runtime = runtimeOverride ?: projectAutomation.profile(chat.id) ?: defaultRuntimeProfile(chat)'''
)
replace_in_section(
    vm,
    "    private suspend fun executeOrchestratorChatTask(",
    "    private suspend fun executeOrchestratorStageSequence(",
    "        val result = network.call { requestApi ->",
    "        val result = network.call(chat.id) { requestApi ->"
)
replace_once(
    vm,
    '''        publishChats(updated)\n        return text\n    }\n\n    private suspend fun executeOrchestratorStageSequence(''',
    '''        publishChats(updated)\n        return text\n        } finally {\n            if (reservationRequired) network.releaseChat(chat.id)\n        }\n    }\n\n    private suspend fun executeOrchestratorStageSequence('''
)

# Reserve stage-sequence worker chat until every stage has finished.
replace_once(
    vm,
    '''        var all = chatsRepository.list()\n        val first = all.firstOrNull { it.id == requested.id } ?: requested\n        if (!isOrchestratorChat(first.id) && RequestExecutionManager.hasActiveChat(first.id)) {\n            error("В чате «${first.title}» уже выполняется другой запрос")\n        }\n        val launch = ChatMessage''',
    '''        var all = chatsRepository.list()\n        val first = all.firstOrNull { it.id == requested.id } ?: requested\n        val reservationRequired = !isOrchestratorChat(first.id)\n        if (reservationRequired && !network.reserveChat(first.id)) {\n            error("В чате «${first.title}» уже выполняется другой запрос")\n        }\n        try {\n        val launch = ChatMessage'''
)
replace_in_section(
    vm,
    "    private suspend fun executeOrchestratorStageSequence(",
    "    private fun orchestratorAttachments(",
    "            val response = network.call { requestApi ->",
    "            val response = network.call(chat.id) { requestApi ->"
)
replace_once(
    vm,
    '''        return results.lastOrNull()?.second ?: "Этапы выполнены."\n    }\n\n    private fun orchestratorAttachments(''',
    '''        return results.lastOrNull()?.second ?: "Этапы выполнены."\n        } finally {\n            if (reservationRequired) network.releaseChat(first.id)\n        }\n    }\n\n    private fun orchestratorAttachments('''
)

# Deletion safety: do not orphan paid/in-flight calls.
replace_once(
    vm,
    '''    fun deleteChat(id: String) {\n        cleanupTempAttachments(_state.value.pendingAttachments)\n        if (_state.value.isLoading) return\n        val deletingChat = _state.value.chats.firstOrNull { it.id == id } ?: return''',
    '''    fun deleteChat(id: String) {\n        if (_state.value.isLoading) return\n        if (RequestExecutionManager.hasActiveChat(id)) {\n            _state.value = _state.value.copy(status = "Нельзя удалить чат, пока в нём выполняется работа")\n            return\n        }\n        cleanupTempAttachments(_state.value.pendingAttachments)\n        val deletingChat = _state.value.chats.firstOrNull { it.id == id } ?: return'''
)
replace_once(
    vm,
    '''    fun clearAllChats() {\n        cleanupTempAttachments(_state.value.pendingAttachments)\n        if (_state.value.isLoading) return\n\n        val protectedOrchestrators = _state.value.chats.filter { isOrchestratorChat(it.id) }''',
    '''    fun clearAllChats() {\n        if (_state.value.isLoading) return\n        val active = _state.value.chats.firstOrNull { RequestExecutionManager.hasActiveChat(it.id) }\n        if (active != null) {\n            _state.value = _state.value.copy(status = "Нельзя очистить чаты: «${active.title}» сейчас выполняет работу")\n            return\n        }\n        cleanupTempAttachments(_state.value.pendingAttachments)\n\n        val protectedOrchestrators = _state.value.chats.filter { isOrchestratorChat(it.id) }'''
)
replace_once(
    vm,
    '''    fun deleteProject(projectId: String) {\n        if (_state.value.isLoading) return\n        projectsRepository.deleteProjectFiles(projectId)''',
    '''    fun deleteProject(projectId: String) {\n        if (_state.value.isLoading) return\n        val active = _state.value.chats.firstOrNull {\n            it.projectId == projectId && RequestExecutionManager.hasActiveChat(it.id)\n        }\n        if (active != null) {\n            _state.value = _state.value.copy(status = "Нельзя удалить проект: «${active.title}» сейчас выполняет работу")\n            return\n        }\n        projectsRepository.deleteProjectFiles(projectId)'''
)

print("Applied v1.17.0 review safety fixes")
