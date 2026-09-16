package com.ayuemin.ymnik

import android.content.Context
import com.ayuemin.ymnik.network.CompatibleApiClient
import com.ayuemin.ymnik.network.NvidiaImageClient
import com.ayuemin.ymnik.network.OpenRouterClient

/**
 * Network clients owned by one top-level request.
 *
 * Separate client instances mean cancelling one chat cannot cancel sockets that belong to
 * another chat. The same session can intentionally fan out several specialist calls inside
 * one orchestrator job; cancelling that orchestrator cancels all of its child calls.
 */
internal class RequestNetworkSession(
    context: Context,
    private val requestId: String
) {
    private val app = context.applicationContext

    val openRouter = OpenRouterClient(app) { label ->
        RequestExecutionManager.updatePhase(app, requestId, label)
    }
    val compatible = CompatibleApiClient(app)
    val nvidiaImage = NvidiaImageClient(app)

    fun cancel() {
        openRouter.cancelActiveRequest()
        compatible.cancelActiveRequest()
        nvidiaImage.cancelActiveRequest()
    }
}
