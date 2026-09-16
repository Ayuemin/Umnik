from pathlib import Path

ROOT = Path('.')

def read(path): return (ROOT / path).read_text(encoding='utf-8')
def write(path, text): (ROOT / path).write_text(text, encoding='utf-8')
def replace_once(path, old, new):
    text = read(path)
    n = text.count(old)
    if n != 1:
        raise SystemExit(f'{path}: expected one match, found {n}: {old[:180]!r}')
    write(path, text.replace(old, new, 1))

# Persist the actual payload after OpenRouterRequestEnhancer has applied provider/privacy/tools/RAG changes.
path = 'app/src/main/java/com/ayuemin/ymnik/diagnostics/DiagnosticLog.kt'
replace_once(path,
'import okhttp3.Interceptor\nimport okhttp3.Response',
'import okhttp3.Interceptor\nimport okhttp3.Request\nimport okhttp3.Response')
replace_once(path,
'''class DiagnosticHttpInterceptor(
    private val context: Context,
    private val source: String,
    private val requestId: String? = null,
    private val requestChatId: String? = null
) : Interceptor {''',
'''class DiagnosticHttpInterceptor(
    private val context: Context,
    private val source: String,
    private val requestId: String? = null,
    private val requestChatId: String? = null,
    private val onPreparedOpenRouterRequest: ((Request) -> Unit)? = null
) : Interceptor {''')
replace_once(path,
'''            val enhanced = openRouterEnhancer.enhance(request)
            enhanced.response?.let { return it }
            request = enhanced.request ?: request
        }

        if (!DiagnosticLog.isEnabled(context)) return chain.proceed(request)
''',
'''            val enhanced = openRouterEnhancer.enhance(request)
            enhanced.response?.let { return it }
            request = enhanced.request ?: request
            onPreparedOpenRouterRequest?.invoke(request)
        }

        if (!DiagnosticLog.isEnabled(context)) return chain.proceed(request)
''')

path = 'app/src/main/java/com/ayuemin/ymnik/network/OpenRouterRecoveryStore.kt'
replace_once(path,
'''    fun updateGeneration(requestId: String, generationId: String, cacheStatus: String?) = synchronized(lock) {
        val current = get(requestId) ?: return@synchronized
        val now = System.currentTimeMillis()
        writeAtomic(
            current.copy(
                generationId = generationId,
                cacheStatus = cacheStatus,
                generationSeenAt = current.generationSeenAt ?: now,
                updatedAt = now
            )
        )
    }

    fun remove(requestId: String) = synchronized(lock) {''',
'''    fun updateGeneration(requestId: String, generationId: String, cacheStatus: String?) = synchronized(lock) {
        val current = get(requestId) ?: return@synchronized
        val now = System.currentTimeMillis()
        writeAtomic(
            current.copy(
                generationId = generationId,
                cacheStatus = cacheStatus,
                generationSeenAt = current.generationSeenAt ?: now,
                updatedAt = now
            )
        )
    }

    fun updatePayload(requestId: String, payloadJson: String) = synchronized(lock) {
        val current = get(requestId) ?: return@synchronized
        writeAtomic(current.copy(payloadJson = payloadJson, updatedAt = System.currentTimeMillis()))
    }

    fun remove(requestId: String) = synchronized(lock) {''')

path = 'app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt'
replace_once(path,
'import okhttp3.RequestBody.Companion.toRequestBody\nimport java.io.File',
'import okhttp3.RequestBody.Companion.toRequestBody\nimport okio.Buffer\nimport java.io.File')
replace_once(path,
'''    private val http = OkHttpClient.Builder()
        .addInterceptor(DiagnosticHttpInterceptor(context, "OpenRouter", requestId, requestChatId))
''',
'''    private val http = OkHttpClient.Builder()
        .addInterceptor(
            DiagnosticHttpInterceptor(context, "OpenRouter", requestId, requestChatId) { prepared ->
                captureFinalRecoveryPayload(prepared)
            }
        )
''')
replace_once(path,
'''    private fun executeActive(request: Request): okhttp3.Response {
''',
'''    private fun captureFinalRecoveryPayload(request: Request) {
        if (!recoveryEnabled || request.method != "POST" || !request.url.encodedPath.endsWith("/chat/completions")) return
        val id = requestId?.takeIf { it.isNotBlank() } ?: return
        if (recoveryStore.get(id) == null) return
        val body = request.body ?: return
        val payload = runCatching {
            val buffer = Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        recoveryStore.updatePayload(id, payload)
        DiagnosticLog.record(context, "REQUEST_RECOVERY", "Persisted final enhanced payload request=${id.take(8)} bytes=${payload.toByteArray().size}")
    }

    private fun executeActive(request: Request): okhttp3.Response {
''')

# A replay must never be accepted unless OpenRouter explicitly says HIT, and must stay comfortably inside TTL.
path = 'app/src/main/java/com/ayuemin/ymnik/OpenRouterRecoveryWorker.kt'
replace_once(path,
'''        val generationId = record.generationId ?: throw IOException("Missing generation id")
        val deadline = SystemClock.elapsedRealtime() + RECOVERY_WINDOW_MS
''',
'''        val generationId = record.generationId ?: throw IOException("Missing generation id")
        val seenAt = record.generationSeenAt ?: record.updatedAt
        val deadline = SystemClock.elapsedRealtime() + RECOVERY_WINDOW_MS
''')
replace_once(path,
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
''',
'''        // Otherwise use the exact response-cache replay. Keep a safety margin inside the
        // requested 300 s TTL and re-check cancellation immediately before any POST.
        if (System.currentTimeMillis() - seenAt > CACHE_REPLAY_MAX_AGE_MS) {
            throw IOException("Response cache replay window expired")
        }
        if (OpenRouterRecoveryStore(applicationContext).get(record.requestId) == null) {
            throw IOException("Recovery cancelled")
        }
        delay(900L)
        if (System.currentTimeMillis() - seenAt > CACHE_REPLAY_MAX_AGE_MS) {
            throw IOException("Response cache replay window expired")
        }
        if (OpenRouterRecoveryStore(applicationContext).get(record.requestId) == null) {
            throw IOException("Recovery cancelled")
        }
        val request = Request.Builder()
''')
replace_once(path,
'''            val cache = response.header("X-OpenRouter-Cache-Status")
            if (!cache.equals("HIT", ignoreCase = true)) {
                DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Expected cache HIT but got ${cache ?: "unknown"}; request=${record.requestId.take(8)}")
            }
            OpenRouterResponseParser.parse(body, allowEmpty = false)
''',
'''            val cache = response.header("X-OpenRouter-Cache-Status")
            if (!cache.equals("HIT", ignoreCase = true)) {
                DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Rejected replay because cache was ${cache ?: "unknown"}; request=${record.requestId.take(8)}")
                throw IOException("OpenRouter response cache replay was not a HIT")
            }
            OpenRouterResponseParser.parse(body, allowEmpty = false)
''')
replace_once(path,
'''        private const val CACHE_RECOVERY_MAX_AGE_MS = 240_000L
        private const val RECOVERY_WINDOW_MS = 90_000L
''',
'''        private const val CACHE_RECOVERY_MAX_AGE_MS = 180_000L
        private const val CACHE_REPLAY_MAX_AGE_MS = 240_000L
        private const val RECOVERY_WINDOW_MS = 60_000L
''')

print('v1.17.1 review fixes applied')
