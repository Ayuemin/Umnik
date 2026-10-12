package com.ayuemin.ymnik.agenttools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/** Approval is obtained ONLY from an explicit, on-device button press. */
internal object GitHubApprovalGate {
    data class Pending(
        val id: String,
        val chatId: String,
        val operation: String,
        val target: String,
        val explanation: String
    )

    private val mutex = Mutex()
    private val pendingMutable = MutableStateFlow<Pending?>(null)
    val pending = pendingMutable.asStateFlow()
    private var response: CompletableDeferred<Boolean>? = null

    suspend fun ask(chatId: String, operation: String, target: String, explanation: String): Boolean =
        mutex.withLock {
            val request = Pending(
                id = UUID.randomUUID().toString(),
                chatId = chatId,
                operation = operation.take(40),
                target = target.take(160),
                explanation = explanation.take(500)
            )
            val answer = CompletableDeferred<Boolean>()
            response = answer
            pendingMutable.value = request
            try {
                withTimeout(120_000L) { answer.await() }
            } catch (_: TimeoutCancellationException) {
                false
            } finally {
                if (pendingMutable.value?.id == request.id) {
                    pendingMutable.value = null
                    response = null
                }
            }
        }

    fun answer(id: String, approved: Boolean) {
        val current = pendingMutable.value ?: return
        if (current.id != id) return
        response?.complete(approved)
    }
}
