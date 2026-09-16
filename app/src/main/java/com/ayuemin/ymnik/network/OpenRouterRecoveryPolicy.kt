package com.ayuemin.ymnik.network

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
