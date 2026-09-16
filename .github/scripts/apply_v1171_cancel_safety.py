from pathlib import Path

ROOT = Path('.')
def read(path): return (ROOT / path).read_text(encoding='utf-8')
def write(path, text): (ROOT / path).write_text(text, encoding='utf-8')
def replace_once(path, old, new):
    text = read(path)
    n = text.count(old)
    if n != 1:
        raise SystemExit(f'{path}: expected one match, found {n}: {old[:160]!r}')
    write(path, text.replace(old, new, 1))

# Manual Stop is authoritative: clear persisted recovery and cancel its worker before sockets.
path = 'app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt'
replace_once(path,
'''    fun cancelActiveRequest() {
        chatBatchRunner.stopTracking()
        synchronized(activeCallLock) { activeCall?.cancel() }
        http.dispatcher.cancelAll()
    }
''',
'''    fun cancelActiveRequest() {
        chatBatchRunner.stopTracking()
        requestId?.takeIf { it.isNotBlank() }?.let { id ->
            recoveryStore.remove(id)
            OpenRouterRecoveryWorker.cancel(context, id)
        }
        synchronized(activeCallLock) { activeCall?.cancel() }
        http.dispatcher.cancelAll()
    }
''')

# A worker can already be running when the user presses Stop. Re-check the recovery record
# immediately before writing the assistant response so a cancelled request cannot resurrect.
path = 'app/src/main/java/com/ayuemin/ymnik/OpenRouterRecoveryWorker.kt'
replace_once(path,
'''                    val assistant = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = "assistant",
                        text = text,
                        modelId = completion.model.ifBlank { record.modelId },
                        providerName = completion.provider.takeIf { it.isNotBlank() } ?: "OpenRouter",
                        costUsd = completion.costUsd,
                        inputTokens = completion.promptTokens,
                        outputTokens = completion.completionTokens
                    )
                    ChatRepository(applicationContext).finishRequest(record.chatId, record.messageId, assistant)
''',
'''                    if (store.get(requestId) == null) {
                        DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Recovery result discarded after manual cancel/completion; request=${requestId.take(8)}")
                        return@fold Result.success()
                    }
                    val assistant = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = "assistant",
                        text = text,
                        modelId = completion.model.ifBlank { record.modelId },
                        providerName = completion.provider.takeIf { it.isNotBlank() } ?: "OpenRouter",
                        costUsd = completion.costUsd,
                        inputTokens = completion.promptTokens,
                        outputTokens = completion.completionTokens
                    )
                    ChatRepository(applicationContext).finishRequest(record.chatId, record.messageId, assistant)
''')

print('manual cancellation safety applied')
