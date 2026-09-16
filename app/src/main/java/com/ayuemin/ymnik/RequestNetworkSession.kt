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

    fun openRouter(): OpenRouterClient = OpenRouterClient(app) { label ->
        RequestExecutionManager.updatePhase(app, requestId, label)
    }.also { clients += it }

    fun cancel() {
        val snapshot = synchronized(clients) { clients.toList() }
        snapshot.forEach { runCatching { it.cancelActiveRequest() } }
    }
}
