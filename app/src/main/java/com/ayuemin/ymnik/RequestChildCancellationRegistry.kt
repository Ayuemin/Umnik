package com.ayuemin.ymnik

/**
 * Owns cancellation hooks for asynchronous child work started by one top-level request.
 * Normal parent completion does not cancel children; explicit/system cancellation does.
 */
internal class RequestChildCancellationRegistry {
    private val lock = Any()
    private val cancellers = linkedMapOf<String, () -> Unit>()
    private var cancelled = false

    fun register(id: String, cancel: () -> Unit): Boolean {
        require(id.isNotBlank()) { "Child cancellation id must not be blank" }
        val cancelImmediately = synchronized(lock) {
            if (cancelled) {
                true
            } else {
                cancellers[id] = cancel
                false
            }
        }
        if (cancelImmediately) runCatching { cancel() }
        return !cancelImmediately
    }

    fun unregister(id: String) {
        synchronized(lock) { cancellers.remove(id) }
    }

    fun cancelAll() {
        val callbacks = synchronized(lock) {
            cancelled = true
            cancellers.values.toList().also { cancellers.clear() }
        }
        callbacks.forEach { callback -> runCatching { callback() } }
    }

    fun isCancelled(): Boolean = synchronized(lock) { cancelled }
}
