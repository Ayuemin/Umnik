package com.ayuemin.ymnik.network

import java.security.MessageDigest

internal fun openRouterApiKeyFingerprint(apiKey: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun shouldRecoverOpenRouterBodyFailure(
    locallyCancelled: Boolean,
    generationId: String?,
    recoveryAttempt: Int = 0
): Boolean {
    if (locallyCancelled || recoveryAttempt >= 3 || generationId.isNullOrBlank()) return false
    // Recovery is read-only. A generation id is enough to poll its state and try
    // /generation/content; response-cache participation must never authorize a replay POST.
    return true
}
