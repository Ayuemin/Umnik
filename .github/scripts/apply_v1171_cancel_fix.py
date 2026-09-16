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

# RequestExecutionManager owns the cancellation semantics for a top-level request.
path = 'app/src/main/java/com/ayuemin/ymnik/RequestExecutionManager.kt'
replace_once(path,
'''    private data class Runtime(
        var snapshot: Snapshot,
        val job: Job,
        val cancelNetworkCall: () -> Unit
    )
''',
'''    private data class Runtime(
        var snapshot: Snapshot,
        val job: Job,
        val cancelNetworkCall: () -> Unit,
        val appContext: Context
    )
''')
replace_once(path,
'''                runtimes[requestId] = Runtime(snapshot, job, cancelNetworkCall)
''',
'''                runtimes[requestId] = Runtime(snapshot, job, cancelNetworkCall, app)
''')
replace_once(path,
'''    fun cancel(requestId: String) {
        val runtime = synchronized(lock) { runtimes[requestId] } ?: return
        runCatching { runtime.cancelNetworkCall.invoke() }
        runtime.job.cancel()
    }
''',
'''    fun cancel(requestId: String) {
        val runtime = synchronized(lock) { runtimes[requestId] } ?: return
        // Manual Stop is final: no WorkManager recovery may resurrect this answer later.
        OpenRouterRecoveryStore(runtime.appContext).remove(requestId)
        OpenRouterRecoveryWorker.cancel(runtime.appContext, requestId)
        runCatching { runtime.cancelNetworkCall.invoke() }
        runtime.job.cancel()
        DiagnosticLog.record(runtime.appContext, "REQUEST_RECOVERY", "Manual cancellation cleared recovery request=${requestId.take(8)}")
    }
''')

# A worker that was already running must observe a manual cancellation before replay/delivery.
path = 'app/src/main/java/com/ayuemin/ymnik/OpenRouterRecoveryWorker.kt'
replace_once(path,
'''        // Otherwise use the exact response-cache replay. The caller already verified that the
        // original response explicitly reported HIT/MISS and that the API-key fingerprint matches.
        delay(900L)
        val request = Request.Builder()
''',
'''        // Otherwise use the exact response-cache replay. The caller already verified that the
        // original response explicitly reported HIT/MISS and that the API-key fingerprint matches.
        // Re-check the durable recovery record immediately before any POST so a manual Stop that
        // happened while polling cannot trigger a late replay.
        if (OpenRouterRecoveryStore(applicationContext).get(record.requestId) == null) {
            throw IOException("Recovery cancelled")
        }
        delay(900L)
        if (OpenRouterRecoveryStore(applicationContext).get(record.requestId) == null) {
            throw IOException("Recovery cancelled")
        }
        val request = Request.Builder()
''')
replace_once(path,
'''                onSuccess = { completion ->
                    val toolCalls = completion.message.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray
''',
'''                onSuccess = { completion ->
                    if (store.get(requestId) == null) {
                        DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Recovered result discarded after manual cancellation request=${requestId.take(8)}")
                        return@fold Result.success()
                    }
                    val toolCalls = completion.message.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray
''')
replace_once(path,
'''                onFailure = { error ->
                    DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Background recovery attempt failed request=${requestId.take(8)}", error)
                    Result.retry()
                }
''',
'''                onFailure = { error ->
                    if (store.get(requestId) == null) {
                        DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Background recovery stopped after manual cancellation request=${requestId.take(8)}")
                        return@fold Result.success()
                    }
                    DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Background recovery attempt failed request=${requestId.take(8)}", error)
                    Result.retry()
                }
''')

print('v1.17.1 cancellation safety fix applied')
