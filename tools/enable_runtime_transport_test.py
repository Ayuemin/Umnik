from pathlib import Path

path = Path("app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt")
text = path.read_text()

needle = """    private val recoveryStore by lazy { OpenRouterRecoveryStore(context.applicationContext) }\n    private val activeCallLock = Any()\n"""
replacement = """    private val recoveryStore by lazy { OpenRouterRecoveryStore(context.applicationContext) }\n    private val runtimeTransport: RuntimeTransportClient? by lazy {\n        val id = requestId?.takeIf { it.isNotBlank() } ?: return@lazy null\n        val profileId = requestProfileId?.takeIf { it.isNotBlank() } ?: return@lazy null\n        if (!recoveryEnabled) null else RuntimeTransportClient(\n            context = context,\n            requestId = id,\n            profileId = profileId,\n            streamCallback = streamCallback,\n            phaseCallback = phaseCallback\n        )\n    }\n    private val activeCallLock = Any()\n"""
if needle not in text:
    raise SystemExit("runtime field insertion point not found")
text = text.replace(needle, replacement, 1)

needle = """        synchronized(activeCallLock) { activeCall?.cancel() }\n        http.dispatcher.cancelAll()\n"""
replacement = """        runtimeTransport?.cancel()\n        synchronized(activeCallLock) { activeCall?.cancel() }\n        http.dispatcher.cancelAll()\n"""
if needle not in text:
    raise SystemExit("runtime cancellation insertion point not found")
text = text.replace(needle, replacement, 1)

needle = """        recoveryRecord?.let { record ->\n            recoveryStore.put(record)\n            // Persist the fallback before network I/O. If Android kills the process before\n            // response headers arrive, WorkManager can still close the pending state safely.\n            OpenRouterRecoveryWorker.schedule(context, record.requestId, initialDelaySeconds = 120L)\n        }\n        val request = Request.Builder()\n"""
replacement = """        recoveryRecord?.let { record ->\n            recoveryStore.put(record)\n            // Persist the fallback before network I/O. If Android kills the process before\n            // response headers arrive, WorkManager can still close the pending state safely.\n            OpenRouterRecoveryWorker.schedule(context, record.requestId, initialDelaySeconds = 120L)\n        }\n\n        val runtime = runtimeTransport\n        if (runtime != null && recoveryRecord != null) {\n            val completion = runtime.complete(\n                baseUrl = baseUrl,\n                payloadJson = payloadJson,\n                allowEmpty = allowEmpty,\n                recoveryRecord = recoveryRecord\n            )\n            clearRecovery(recoveryRecord)\n            DiagnosticLog.record(\n                context,\n                \"COMPLETION\",\n                \"OpenRouter runtime id=${completion.id}; provider=${completion.provider}; finish=${completion.finishReason}; completionTokens=${completion.completionTokens}\"\n            )\n            return completion\n        }\n\n        val request = Request.Builder()\n"""
if needle not in text:
    raise SystemExit("runtime request insertion point not found")
text = text.replace(needle, replacement, 1)

path.write_text(text)
