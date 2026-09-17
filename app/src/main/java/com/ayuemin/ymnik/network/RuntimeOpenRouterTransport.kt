package com.ayuemin.ymnik.network

import android.content.Context
import com.ayuemin.ymnik.RuntimeOpenRouterService
import com.ayuemin.ymnik.RuntimeTransportRecord
import com.ayuemin.ymnik.RuntimeTransportStore
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.UUID

internal class RuntimeTransportStartException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal class RuntimeTransportFailure(
    message: String,
    val generationId: String?,
    val cacheStatus: String?
) : IOException(message)

/** Main-process facade for the isolated :runtime OpenRouter transport. */
internal class RuntimeOpenRouterTransport(
    context: Context,
    private val requestId: String
) {
    private val app = context.applicationContext
    private val gson = Gson()
    private val store = RuntimeTransportStore(app)

    suspend fun execute(
        apiKey: String,
        baseUrl: String,
        payloadJson: String,
        allowEmpty: Boolean,
        onPhase: (String) -> Unit = {},
        onPartial: (String) -> Unit = {},
        onGeneration: (String, String?) -> Unit = {}
    ): OpenRouterResponseParser.Completion {
        val transportId = "$requestId-${UUID.randomUUID()}"
        store.put(
            RuntimeTransportRecord(
                transportId = transportId,
                requestId = requestId,
                payloadJson = payloadJson,
                allowEmpty = allowEmpty
            )
        )

        try {
            onPhase("Запускаю отдельный runtime…")
            try {
                RuntimeOpenRouterService.start(app, transportId, apiKey, baseUrl)
            } catch (error: Throwable) {
                store.remove(transportId)
                throw RuntimeTransportStartException("Не удалось запустить отдельный runtime", error)
            }

            DiagnosticLog.record(
                app,
                "RUNTIME_TRANSPORT",
                "handoff id=${transportId.take(8)} request=${requestId.take(8)} bytes=${payloadJson.toByteArray().size}"
            )

            val deadline = System.currentTimeMillis() + TRANSPORT_TIMEOUT_MS
            var lastPartial = ""
            var lastGeneration: String? = null
            var accepted = false

            while (System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                val record = store.get(transportId)
                    ?: throw RuntimeTransportFailure("Runtime transport state disappeared", lastGeneration, null)

                if (!record.generationId.isNullOrBlank() && record.generationId != lastGeneration) {
                    lastGeneration = record.generationId
                    accepted = true
                    onGeneration(record.generationId, record.cacheStatus)
                }

                if (record.partialText != lastPartial) {
                    lastPartial = record.partialText
                    if (lastPartial.isNotBlank()) onPartial(lastPartial)
                }

                when (record.phase) {
                    "queued" -> onPhase("Передаю запрос в runtime…")
                    "running" -> onPhase("Запрос отправлен из отдельного runtime…")
                    "headers" -> {
                        accepted = true
                        onPhase(
                            if (record.cacheStatus.equals("HIT", ignoreCase = true))
                                "Готовый ответ найден · runtime загружает…"
                            else
                                "Модель отвечает через отдельный runtime…"
                        )
                    }
                    "streaming" -> onPhase("Получаю ответ через отдельный runtime…")
                    "completed" -> {
                        val json = record.completionJson
                            ?: throw RuntimeTransportFailure("Runtime завершился без результата", record.generationId, record.cacheStatus)
                        return gson.fromJson(json, OpenRouterResponseParser.Completion::class.java)
                            ?: throw RuntimeTransportFailure("Не удалось прочитать результат runtime", record.generationId, record.cacheStatus)
                    }
                    "cancelled" -> throw CancellationException(record.error ?: "Запрос отменён")
                    "failed" -> throw RuntimeTransportFailure(
                        record.error ?: "Отдельный runtime завершил запрос с ошибкой",
                        record.generationId,
                        record.cacheStatus
                    )
                }
                delay(POLL_INTERVAL_MS)
            }

            // Once the isolated service was started we cannot prove that the POST was not accepted.
            // Never silently fall back to a second paid POST after this point.
            throw RuntimeTransportFailure(
                if (accepted) "Отдельный runtime не завершил принятый запрос вовремя" else "Отдельный runtime не подтвердил запрос вовремя",
                lastGeneration,
                null
            )
        } catch (cancelled: CancellationException) {
            RuntimeOpenRouterService.cancel(app, transportId)
            throw cancelled
        } finally {
            // On real main-process death this block does not run, so the runtime mailbox survives
            // and can be consumed by the next recovery phase. Normal completed/failed calls clean up.
            val terminal = store.get(transportId)?.phase in setOf("completed", "failed", "cancelled")
            if (terminal) store.remove(transportId)
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 120L
        private const val TRANSPORT_TIMEOUT_MS = 11L * 60L * 1000L
    }
}
