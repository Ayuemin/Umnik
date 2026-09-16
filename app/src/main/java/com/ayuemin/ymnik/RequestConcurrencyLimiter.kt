package com.ayuemin.ymnik

import android.content.Context
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Optional user-configured gate for actual OpenRouter model calls. Zero means unlimited. */
internal object RequestConcurrencyLimiter {
    const val PREF_KEY = "concurrent_request_limit"

    private val lock = Any()
    private var configuredLimit: Int = -1
    private var semaphore: Semaphore? = null

    fun configuredLimit(context: Context): Int =
        context.getSharedPreferences("ymnik", Context.MODE_PRIVATE)
            .getInt(PREF_KEY, 0)
            .coerceAtLeast(0)

    suspend fun <T> withPermit(context: Context, block: suspend () -> T): T {
        val limit = configuredLimit(context)
        if (limit <= 0) return block()
        val gate = synchronized(lock) {
            if (configuredLimit != limit || semaphore == null) {
                configuredLimit = limit
                semaphore = Semaphore(limit)
            }
            semaphore!!
        }
        return gate.withPermit { block() }
    }
}
