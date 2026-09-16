package com.ayuemin.ymnik.network

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
