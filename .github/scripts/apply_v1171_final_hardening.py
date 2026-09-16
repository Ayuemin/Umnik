from pathlib import Path

ROOT = Path('.')
def read(path): return (ROOT / path).read_text(encoding='utf-8')
def write(path, text):
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')
def replace_once(path, old, new):
    text = read(path)
    n = text.count(old)
    if n != 1:
        raise SystemExit(f'{path}: expected one match, found {n}: {old[:160]!r}')
    write(path, text.replace(old, new, 1))

# Recovery records keep only a one-way fingerprint of the OpenRouter key.
path = 'app/src/main/java/com/ayuemin/ymnik/network/OpenRouterRecoveryStore.kt'
replace_once(path,
'''    val connectionProfileId: String,
    val baseUrl: String,
''',
'''    val connectionProfileId: String,
    val apiKeyFingerprint: String,
    val baseUrl: String,
''')

# Shared cache/key safety helpers.
write('app/src/main/java/com/ayuemin/ymnik/network/OpenRouterRecoveryPolicy.kt', r'''package com.ayuemin.ymnik.network

import java.security.MessageDigest

internal fun isOpenRouterResponseCacheRecoverable(cacheStatus: String?): Boolean =
    cacheStatus.equals("MISS", ignoreCase = true) || cacheStatus.equals("HIT", ignoreCase = true)

internal fun openRouterApiKeyFingerprint(apiKey: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterRecoveryPolicyTest {
    @Test fun recoversOnlyConfirmedResponseCacheRequests() {
        assertTrue(isOpenRouterResponseCacheRecoverable("MISS"))
        assertTrue(isOpenRouterResponseCacheRecoverable("hit"))
        assertFalse(isOpenRouterResponseCacheRecoverable(null))
        assertFalse(isOpenRouterResponseCacheRecoverable("BYPASS"))
    }

    @Test fun apiKeyFingerprintIsStableButDoesNotStoreTheKey() {
        val key = "sk-or-test-secret-123"
        val first = openRouterApiKeyFingerprint(key)
        assertEquals(first, openRouterApiKeyFingerprint(key))
        assertNotEquals(first, openRouterApiKeyFingerprint("sk-or-other"))
        assertFalse(first.contains(key))
        assertEquals(64, first.length)
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

# Persist the fingerprint of the same key that created the original cache entry.
path = 'app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt'
replace_once(path,
'        val recoveryRecord = recoveryRecord(baseUrl, model, payloadJson)\n',
'        val recoveryRecord = recoveryRecord(apiKey, baseUrl, model, payloadJson)\n')
replace_once(path,
'    private fun recoveryRecord(baseUrl: String, model: String, payloadJson: String): OpenRouterRecoveryRecord? {\n',
'    private fun recoveryRecord(apiKey: String, baseUrl: String, model: String, payloadJson: String): OpenRouterRecoveryRecord? {\n')
replace_once(path,
'''            messageId = snapshot.messageId,
            connectionProfileId = profileId,
            baseUrl = baseUrl,
''',
'''            messageId = snapshot.messageId,
            connectionProfileId = profileId,
            apiKeyFingerprint = openRouterApiKeyFingerprint(apiKey),
            baseUrl = baseUrl,
''')

# Worker verifies key identity and first tries OpenRouter's read-only generation/content endpoint.
path = 'app/src/main/java/com/ayuemin/ymnik/OpenRouterRecoveryWorker.kt'
replace_once(path,
'import com.ayuemin.ymnik.network.isOpenRouterResponseCacheRecoverable\n',
'import com.ayuemin.ymnik.network.isOpenRouterResponseCacheRecoverable\nimport com.ayuemin.ymnik.network.openRouterApiKeyFingerprint\n')
replace_once(path,
'''        val apiKey = SecretStore(applicationContext).getProfileApiKey(record.connectionProfileId)
        if (apiKey.isNullOrBlank()) return Result.retry()

        return runCatching { recover(record, apiKey) }
''',
'''        val apiKey = SecretStore(applicationContext).getProfileApiKey(record.connectionProfileId)
        if (apiKey.isNullOrBlank()) return Result.retry()
        if (openRouterApiKeyFingerprint(apiKey) != record.apiKeyFingerprint) {
            failPending(record, "API-ключ OpenRouter изменился после отправки запроса. Автоматический повтор отменён, чтобы исключить двойную оплату.")
            store.remove(requestId)
            return Result.success()
        }

        return runCatching { recover(record, apiKey) }
''')
replace_once(path,
'''        if (!ready) throw IOException("Generation is not complete yet")

        // Give OpenRouter a brief moment to make the just-completed response cache-visible.
        delay(900L)
        val request = Request.Builder()
''',
'''        if (!ready) throw IOException("Generation is not complete yet")

        // If the account has opted into OpenRouter input/output logging, this read-only endpoint
        // can return the completed text directly. It costs nothing and avoids any replay at all.
        storedGenerationCompletion(client, gson, record, apiKey, generationId)?.let { return@withContext it }

        // Otherwise use the exact response-cache replay. The caller already verified that the
        // original response explicitly reported HIT/MISS and that the API-key fingerprint matches.
        delay(900L)
        val request = Request.Builder()
''')
replace_once(path,
'''    private fun networkAvailable(): Boolean {
''',
'''    private fun storedGenerationCompletion(
        client: OkHttpClient,
        gson: Gson,
        record: OpenRouterRecoveryRecord,
        apiKey: String,
        generationId: String
    ): OpenRouterResponseParser.Completion? {
        val request = Request.Builder()
            .url(endpoint(record.baseUrl, "generation/content") + "?id=" + Uri.encode(generationId))
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Umnik Android")
            .get()
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return@use null
                if (!response.isSuccessful) return@use null
                val root = gson.fromJson(response.body?.string().orEmpty(), JsonObject::class.java)
                val completion = root?.getAsJsonObject("data")
                    ?.getAsJsonObject("output")
                    ?.get("completion")
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?: return@use null
                DiagnosticLog.record(applicationContext, "REQUEST_RECOVERY", "Recovered directly from generation/content request=${record.requestId.take(8)}")
                OpenRouterResponseParser.Completion(
                    message = JsonObject().apply {
                        addProperty("role", "assistant")
                        addProperty("content", completion)
                    },
                    id = generationId,
                    provider = "",
                    model = record.modelId,
                    finishReason = "stop",
                    nativeFinishReason = "",
                    promptTokens = null,
                    completionTokens = null,
                    totalTokens = null,
                    reasoningTokens = null,
                    costUsd = null
                )
            }
        }.getOrNull()
    }

    private fun networkAvailable(): Boolean {
''')

# Refresh the foreground notification immediately after the user grants Android 13+ permission.
path = 'app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt'
replace_once(path,
'import com.ayuemin.ymnik.ChatViewModel\n',
'import com.ayuemin.ymnik.ChatViewModel\nimport com.ayuemin.ymnik.RequestKeepAliveService\n')
replace_once(path,
'    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }\n',
'''    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) RequestKeepAliveService.update(context.applicationContext)
    }
''')

# Use a proper monochrome notification small icon instead of the full launcher artwork.
write('app/src/main/res/drawable/ic_notification_umnik.xml', r'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M12,14c1.66,0 3,-1.34 3,-3V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21H8v2h8v-2h-3v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z" />
</vector>
''')
replace_once('app/src/main/java/com/ayuemin/ymnik/RequestKeepAliveService.kt',
'            .setSmallIcon(R.drawable.ic_umnik)\n',
'            .setSmallIcon(R.drawable.ic_notification_umnik)\n')

print('final background recovery hardening applied')
