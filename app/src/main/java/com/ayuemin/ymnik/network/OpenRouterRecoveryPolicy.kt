package com.ayuemin.ymnik.network

internal fun shouldRecoverOpenRouterBodyFailure(
    locallyCancelled: Boolean,
    generationId: String?,
    cacheStatus: String?,
    recoveryAttempt: Int
): Boolean {
    if (locallyCancelled || recoveryAttempt > 0 || generationId.isNullOrBlank()) return false
    return cacheStatus.equals("MISS", ignoreCase = true) || cacheStatus.equals("HIT", ignoreCase = true)
}
