package com.ayuemin.ymnik.network

import android.content.Context
import com.ayuemin.ymnik.OpenRouterRecoveryWorker
import com.ayuemin.ymnik.RuntimeOpenRouterService
import com.ayuemin.ymnik.RuntimeTransportRecord
import com.ayuemin.ymnik.RuntimeTransportStore
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.UUID

internal class RuntimeTransportClient(
    context: Context,
    private val requestId: String,
    private val profileId: String,
    private val streamCallback: (String) -> Unit,
    private val phaseCallback: (String) -> Unit
) {
    private val app = context.applicationContext
    private val gson = Gson()
    private val store = RuntimeTransportStore(app)
    private val recoveryStore = OpenRouterRecoveryStore(app)
    @Volatile private var activeTransportId: String? = null

    suspend fun complete(
        baseUrl: String,
        payloadJson: String,
        allowEmpty: Boolean,
        recoveryRecord: OpenRouterRecoveryRecord?
    ): OpenRouterResponseParser.Completion {
        val transportId = "${requestId}-${UUID.randomUUID()}"
        activeTransportId = transportId
        store.put(
            RuntimeTransportRecord(
                transportId = transportId,
                requestId = requestId,
                profileId = profileId,
                baseUrl = baseUrl,
                payloadJson = payloadJson,
                allowEmpty = allowEmpty
            )
        )
        var lastPartial = ""
        var persistedGeneration: String? = null
        phaseCallback("Передаю запрос в отдельный runtime…")
        DiagnosticLog.record(app, "RUNTIME_TRANSPORT", "dispatch id=${transportId.take(8)} request=${requestId.take(8)}")
        try {
            RuntimeOpenRouterService.start(app, transportId)
            val deadline = System.currentTimeMillis() + MAX_WAIT_MS
            while (System.currentTimeMillis() < deadline) {
                val state = store.get(transportId) ?: throw IOException("Runtime transport mailbox disappeared")
                val generationId = state.generationId?.takeIf { it.isNotBlank() }
                if (generationId != null && generationId != persistedGeneration && recoveryRecord != null) {
                    recoveryStore.updateGeneration(recoveryRecord.requestId, generationId, state.cacheStatus)
                    OpenRouterRecoveryWorker.schedule(app, recoveryRecord.requestId, replaceExisting = true)
                    persistedGeneration = generationId
                }
                when (state.phase) {
                    "queued" -> phaseCallback("Запускаю отдельный runtime…")
                    "running", "headers" -> phaseCallback("Runtime · модель отвечает…")
                    "streaming" -> {
                        phaseCallback("Runtime · получаю ответ…")
                        if (state.partialText.isNotBlank() && state.partialText != lastPartial) {
                            lastPartial = state.partialText
                            streamCallback(lastPartial)
                        }
                    }
                    "completed" -> {
                        val json = state.completionJson ?: throw IOException("Runtime завершился без данных ответа")
                        val completion = gson.fromJson(json, OpenRouterResponseParser.Completion::class.java)
                            ?: throw IOException("Runtime вернул пустой результат")
                        store.remove(transportId)
                        activeTransportId = null
                        return completion
                    }
                    "cancelled" -> throw CancellationException(state.error ?: "Runtime transport cancelled")
                    "failed" -> throw IOException(state.error ?: "Runtime transport failed")
                }
                delay(POLL_INTERVAL_MS)
            }
            throw IOException("Runtime transport timed out")
        } catch (error: CancellationException) {
            RuntimeOpenRouterService.cancel(app, transportId)
            store.remove(transportId)
            activeTransportId = null
            throw error
        } catch (error: Throwable) {
            activeTransportId = null
            throw error
        }
    }

    fun cancel() {
        val id = activeTransportId ?: return
        RuntimeOpenRouterService.cancel(app, id)
        store.remove(id)
        activeTransportId = null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 100L
        private const val MAX_WAIT_MS = 10L * 60L * 1000L
    }
}
