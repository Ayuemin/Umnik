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

# One shared definition of which OpenRouter cache statuses are safe to replay.
write('app/src/main/java/com/ayuemin/ymnik/network/OpenRouterRecoveryPolicy.kt', r'''package com.ayuemin.ymnik.network

internal fun isOpenRouterResponseCacheRecoverable(cacheStatus: String?): Boolean =
    cacheStatus.equals("MISS", ignoreCase = true) || cacheStatus.equals("HIT", ignoreCase = true)

internal fun shouldRecoverOpenRouterBodyFailure(
    locallyCancelled: Boolean,
    generationId: String?,
    cacheStatus: String?,
    recoveryAttempt: Int
): Boolean {
    if (locallyCancelled || recoveryAttempt >= 3 || generationId.isNullOrBlank()) return false
    return isOpenRouterResponseCacheRecoverable(cacheStatus)
}
''')
write('app/src/test/java/com/ayuemin/ymnik/network/OpenRouterRecoveryPolicyTest.kt', r'''package com.ayuemin.ymnik.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterRecoveryPolicyTest {
    @Test fun recoversOnlyConfirmedResponseCacheRequests() {
        assertTrue(isOpenRouterResponseCacheRecoverable("MISS"))
        assertTrue(isOpenRouterResponseCacheRecoverable("hit"))
        assertFalse(isOpenRouterResponseCacheRecoverable(null))
        assertFalse(isOpenRouterResponseCacheRecoverable("BYPASS"))
    }

    @Test fun recoversCachedGenerationAfterRemoteBodyFailure() {
        assertTrue(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "MISS", 0))
        assertTrue(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "HIT", 0))
        assertTrue(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "HIT", 2))
    }

    @Test fun neverRetriesLocalCancelUnknownGenerationUncachedOrPastBudget() {
        assertFalse(shouldRecoverOpenRouterBodyFailure(true, "gen-123", "MISS", 0))
        assertFalse(shouldRecoverOpenRouterBodyFailure(false, null, "MISS", 0))
        assertFalse(shouldRecoverOpenRouterBodyFailure(false, "gen-123", null, 0))
        assertFalse(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "BYPASS", 0))
        assertFalse(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "MISS", 3))
    }
}
''')

# Schedule a persisted watcher before the socket can disappear. Once response headers arrive,
# retain recovery state only when OpenRouter explicitly confirms response-cache participation.
path = 'app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt'
replace_once(path,
'''        val recoveryRecord = recoveryRecord(baseUrl, model, payloadJson)
        recoveryRecord?.let(recoveryStore::put)
        val request = Request.Builder()
''',
'''        val recoveryRecord = recoveryRecord(baseUrl, model, payloadJson)
        recoveryRecord?.let { record ->
            recoveryStore.put(record)
            // Persist the fallback before network I/O. If Android kills the process before
            // response headers arrive, WorkManager can still close the pending state safely.
            OpenRouterRecoveryWorker.schedule(context, record.requestId, initialDelaySeconds = 120L)
        }
        val request = Request.Builder()
''')
replace_once(path,
'''                    if (recoveryRecord != null && !generationId.isNullOrBlank()) {
                        recoveryStore.updateGeneration(recoveryRecord.requestId, generationId.orEmpty(), cacheStatus)
                        OpenRouterRecoveryWorker.schedule(context, recoveryRecord.requestId)
                    }
''',
'''                    if (recoveryRecord != null && !generationId.isNullOrBlank()) {
                        if (isOpenRouterResponseCacheRecoverable(cacheStatus)) {
                            recoveryStore.updateGeneration(recoveryRecord.requestId, generationId.orEmpty(), cacheStatus)
                            OpenRouterRecoveryWorker.schedule(context, recoveryRecord.requestId)
                        } else {
                            // Never persist an automatic paid replay unless OpenRouter explicitly
                            // confirms this exact request participates in response caching.
                            clearRecovery(recoveryRecord)
                        }
                    }
''')

# The process-death worker also enforces the cache-status gate before any replay.
path = 'app/src/main/java/com/ayuemin/ymnik/OpenRouterRecoveryWorker.kt'
replace_once(path,
'import com.ayuemin.ymnik.network.OpenRouterResponseParser\n',
'import com.ayuemin.ymnik.network.OpenRouterResponseParser\nimport com.ayuemin.ymnik.network.isOpenRouterResponseCacheRecoverable\n')
replace_once(path,
'''        val generationId = record.generationId
        if (generationId.isNullOrBlank()) {
''',
'''        val generationId = record.generationId
        if (generationId.isNullOrBlank()) {
''')
replace_once(path,
'''        val seenAt = record.generationSeenAt ?: record.updatedAt
        if (System.currentTimeMillis() - seenAt > CACHE_RECOVERY_MAX_AGE_MS) {
''',
'''        if (!isOpenRouterResponseCacheRecoverable(record.cacheStatus)) {
            failPending(record, "OpenRouter не подтвердил безопасный response cache. Запрос не отправлен повторно, чтобы исключить двойную оплату.")
            store.remove(requestId)
            return Result.success()
        }

        val seenAt = record.generationSeenAt ?: record.updatedAt
        if (System.currentTimeMillis() - seenAt > CACHE_RECOVERY_MAX_AGE_MS) {
''')

print('cache safety patch applied')
