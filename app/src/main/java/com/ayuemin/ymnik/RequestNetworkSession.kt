package com.ayuemin.ymnik

import android.content.Context
import com.ayuemin.ymnik.network.OpenRouterClient
import java.util.Collections

/**
 * OpenRouter network clients owned by one top-level request.
 *
 * A session may create several clients for an Orchestrator fan-out. That allows independent
 * specialists to work in parallel while cancellation stays scoped to this one top-level job.
 */
internal class RequestNetworkSession(
    context: Context,
    private val requestId: String
) {
    private val app = context.applicationContext
    private val clients = Collections.synchronizedSet(mutableSetOf<OpenRouterClient>())
    private val costLedger = RequestCostLedger()
    private val embeddingClient = com.ayuemin.ymnik.network.OpenRouterEmbeddingClient(app) { exact ->
        costLedger.record(RequestCostKind.EMBEDDINGS, exact)
    }

    private fun openRouter(
        chatId: String? = null,
        profileId: String? = null,
        recoverable: Boolean = false
    ): OpenRouterClient =
        OpenRouterClient(
            context = app,
            requestId = requestId,
            requestChatId = chatId ?: RequestExecutionManager.snapshotForRequest(requestId)?.chatId,
            requestProfileId = profileId,
            recoveryEnabled = recoverable,
            streamCallback = { text -> updatePartial(text) },
            phaseCallback = { label -> updatePhase(label) },
            costSink = costLedger::record
        )
            .also { clients += it }

    suspend fun <T> call(
        chatId: String? = null,
        profileId: String? = null,
        recoverable: Boolean = false,
        block: suspend (OpenRouterClient) -> T
    ): T = RequestConcurrencyLimiter.withPermit(app) {
        block(openRouter(chatId, profileId, recoverable))
    }

    fun embeddings(): com.ayuemin.ymnik.network.OpenRouterEmbeddingClient = embeddingClient

    fun costSnapshot(): com.ayuemin.ymnik.model.RequestCostBreakdown? = costLedger.snapshot()

    fun reserveChat(chatId: String): Boolean = RequestExecutionManager.reserveChat(requestId, chatId)

    fun releaseChat(chatId: String) = RequestExecutionManager.releaseChat(requestId, chatId)

    fun updatePhase(label: String) {
        RequestExecutionManager.updatePhase(app, requestId, label)
    }

    fun updatePartial(text: String) {
        RequestExecutionManager.updatePartial(requestId, text)
    }

    fun cancel() {
        val snapshot = synchronized(clients) { clients.toList() }
        snapshot.forEach { runCatching { it.cancelActiveRequest() } }
    }
}
