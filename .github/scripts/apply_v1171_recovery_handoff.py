from pathlib import Path

ROOT = Path('.')

def read(path): return (ROOT / path).read_text(encoding='utf-8')
def write(path, text): (ROOT / path).write_text(text, encoding='utf-8')
def replace_once(path, old, new):
    text = read(path)
    n = text.count(old)
    if n != 1:
        raise SystemExit(f'{path}: expected one match, found {n}: {old[:140]!r}')
    write(path, text.replace(old, new, 1))
def replace_first_of_two(path, old, new):
    text = read(path)
    n = text.count(old)
    if n != 2:
        raise SystemExit(f'{path}: expected two matches, found {n}: {old[:140]!r}')
    write(path, text.replace(old, new, 1))

# Keep a recoverable server generation alive when the foreground coroutine gives up.
path = 'app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt'
replace_once(path,
'''                if (!recover) {
                    clearRecovery(recoveryRecord)
                    throw error
                }
''',
'''                if (!recover) {
                    if (!locallyCancelled && recoveryRecord != null && !generationId.isNullOrBlank()) {
                        phaseCallback("Связь нестабильна · продолжу восстановление в фоне…")
                        OpenRouterRecoveryWorker.schedule(context, recoveryRecord.requestId, initialDelaySeconds = 0L)
                        throw error
                    }
                    clearRecovery(recoveryRecord)
                    throw error
                }
''')
replace_once(path,
'''                if (!awaitNetworkAvailable(deadline)) {
                    clearRecovery(recoveryRecord)
                    throw error
                }
''',
'''                if (!awaitNetworkAvailable(deadline)) {
                    if (recoveryRecord != null && !generationId.isNullOrBlank()) {
                        phaseCallback("Сеть недоступна · продолжу восстановление в фоне…")
                        OpenRouterRecoveryWorker.schedule(context, recoveryRecord.requestId, initialDelaySeconds = 0L)
                        throw error
                    }
                    clearRecovery(recoveryRecord)
                    throw error
                }
''')
replace_once(path,
'''                if (!ready) {
                    DiagnosticLog.record(context, "REQUEST_RECOVERY", "generation not completed in recovery window; id=${generationId ?: "none"}")
                    clearRecovery(recoveryRecord)
                    throw error
                }
''',
'''                if (!ready) {
                    DiagnosticLog.record(context, "REQUEST_RECOVERY", "generation still pending after live recovery window; handing off id=${generationId ?: "none"}")
                    if (recoveryRecord != null && !generationId.isNullOrBlank()) {
                        phaseCallback("Ответ ещё формируется · продолжу восстановление в фоне…")
                        OpenRouterRecoveryWorker.schedule(context, recoveryRecord.requestId, initialDelaySeconds = 0L)
                        throw error
                    }
                    clearRecovery(recoveryRecord)
                    throw error
                }
''')

# A live request that was handed off must leave the origin message pending.
path = 'app/src/main/java/com/ayuemin/ymnik/RequestExecutionManager.kt'
replace_once(path,
'''            } finally {
                runCatching {
                    ChatRepository(app).updateMessage(chatId, messageId) {
                        if (it.deliveryState == "pending") it.copy(deliveryState = "failed") else it
                    }
                }
                prefs.edit().remove(ACTIVE_PREFIX + requestId).commit()
''',
'''            } finally {
                val recoveryPending = OpenRouterRecoveryStore(app).get(requestId) != null
                if (recoveryPending) {
                    OpenRouterRecoveryWorker.schedule(app, requestId, initialDelaySeconds = 0L)
                    DiagnosticLog.record(app, "REQUEST_RECOVERY", "Foreground request handed to WorkManager request=${requestId.take(8)} chat=${chatId.take(8)}")
                } else {
                    runCatching {
                        ChatRepository(app).updateMessage(chatId, messageId) {
                            if (it.deliveryState == "pending") it.copy(deliveryState = "failed") else it
                        }
                    }
                }
                prefs.edit().remove(ACTIVE_PREFIX + requestId).commit()
''')

# The ordinary text-chat error handler is the first of two structurally identical handlers;
# the second one belongs to image generation and intentionally keeps its old behavior.
path = 'app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt'
replace_once(path,
'import com.ayuemin.ymnik.network.OpenRouterEmbeddingClient\nimport com.ayuemin.ymnik.network.ProviderRegistry',
'import com.ayuemin.ymnik.network.OpenRouterEmbeddingClient\nimport com.ayuemin.ymnik.network.OpenRouterRecoveryStore\nimport com.ayuemin.ymnik.network.ProviderRegistry')
replace_first_of_two(path,
'''                RequestExecutionManager.snapshotForChat(chatId)?.requestId?.let { activeRequestId ->
                    RequestExecutionManager.fail(activeRequestId, friendlyError)
                }
                val failedChats = chatsRepository.finishRequest(chatId, user.id, null)
                _state.value = _state.value.copy(
                    messages = failedChats.firstOrNull { it.id == _state.value.currentChatId }?.messages.orEmpty(),
                    chats = failedChats,
                    status = friendlyError
                )
''',
'''                val activeRequestId = RequestExecutionManager.snapshotForChat(chatId)?.requestId
                val recoveryPending = activeRequestId?.let { OpenRouterRecoveryStore(context).get(it) != null } == true
                if (recoveryPending) {
                    activeRequestId?.let { RequestExecutionManager.updatePhase(context, it, "Восстанавливаю ответ в фоне…") }
                    val currentChats = chatsRepository.list()
                    _state.value = _state.value.copy(
                        messages = currentChats.firstOrNull { it.id == _state.value.currentChatId }?.messages.orEmpty(),
                        chats = currentChats,
                        status = "Соединение прервалось. Ответ уже принят OpenRouter и восстанавливается в фоне."
                    )
                } else {
                    activeRequestId?.let { RequestExecutionManager.fail(it, friendlyError) }
                    val failedChats = chatsRepository.finishRequest(chatId, user.id, null)
                    _state.value = _state.value.copy(
                        messages = failedChats.firstOrNull { it.id == _state.value.currentChatId }?.messages.orEmpty(),
                        chats = failedChats,
                        status = friendlyError
                    )
                }
''')

# If a cached response requires local tool continuation, do not retry forever in a worker.
path = 'app/src/main/java/com/ayuemin/ymnik/OpenRouterRecoveryWorker.kt'
replace_once(path,
'''                    if (toolCalls != null && toolCalls.size() > 0) {
                        DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Recovered completion requires tool continuation; request=${requestId.take(8)}")
                        return@fold Result.retry()
                    }
''',
'''                    if (toolCalls != null && toolCalls.size() > 0) {
                        DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Recovered completion requires local tool continuation; request=${requestId.take(8)}")
                        failPending(record, "Ответ модели восстановлен, но он требует продолжения локального инструмента. Повторите запрос вручную.")
                        store.remove(requestId)
                        return@fold Result.success()
                    }
''')

print('recovery handoff patch applied')
