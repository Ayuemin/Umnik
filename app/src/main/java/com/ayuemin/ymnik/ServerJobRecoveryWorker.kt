package com.ayuemin.ymnik

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ayuemin.ymnik.data.ChatRepository
import com.ayuemin.ymnik.data.ServerConnectionStore
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.network.OpenRouterResponseParser
import com.ayuemin.ymnik.network.ServerJobRecoveryRecord
import com.ayuemin.ymnik.network.ServerJobRecoveryStore
import com.ayuemin.ymnik.network.UmnikServerClient
import com.ayuemin.ymnik.network.UmnikServerHttpException
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class ServerJobRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val requestId = inputData.getString(KEY_REQUEST_ID)?.takeIf { it.isNotBlank() } ?: return Result.success()
        val recovery = ServerJobRecoveryStore(applicationContext)
        val record = recovery.get(requestId) ?: return Result.success()

        if (RequestExecutionManager.snapshotForRequest(requestId) != null) return Result.retry()

        val serverToken = ServerConnectionStore(applicationContext).token()
            ?: return terminalFailure(
                record,
                recovery,
                "Токен личного сервера отсутствует. Автоматическое восстановление остановлено."
            )
        val payload = runCatching { Gson().fromJson(record.payloadJson, JsonObject::class.java) }.getOrNull()
            ?: return terminalFailure(record, recovery, "Не удалось прочитать сохранённый серверный запрос.")

        return runCatching {
            val client = UmnikServerClient()
            client.createChatJob(
                baseUrl = record.serverBaseUrl,
                token = serverToken,
                clientRequestId = record.clientRequestId,
                payload = payload
            )
        }.fold(
            onSuccess = { job ->
                when (job.status) {
                    "queued", "running" -> Result.retry()
                    "failed" -> terminalFailure(
                        record,
                        recovery,
                        job.error?.takeIf { it.isNotBlank() } ?: "Серверная задача завершилась ошибкой."
                    )
                    "completed" -> deliver(record, job.response, recovery)
                    else -> Result.retry()
                }
            },
            onFailure = { error ->
                if (error is UmnikServerHttpException && error.statusCode in setOf(401, 403)) {
                    return@fold terminalFailure(
                        record,
                        recovery,
                        "Личный сервер отклонил сохранённый токен. Проверьте настройки подключения."
                    )
                }
                DiagnosticLog.record(
                    applicationContext,
                    "SERVER_RECOVERY",
                    "Server job recovery retry request=${requestId.take(8)}",
                    error
                )
                Result.retry()
            }
        )
    }

    private fun deliver(
        record: ServerJobRecoveryRecord,
        rawResponse: JsonObject?,
        recovery: ServerJobRecoveryStore
    ): Result {
        if (recovery.get(record.requestId) == null) return Result.success()
        val response = rawResponse ?: return terminalFailure(record, recovery, "Сервер завершил задачу без ответа.")
        val completion = runCatching {
            OpenRouterResponseParser.parse(Gson().toJson(response), allowEmpty = false)
        }.getOrElse {
            return terminalFailure(record, recovery, "Не удалось разобрать восстановленный ответ сервера.")
        }
        val toolCalls = completion.message.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray
        if (toolCalls != null && toolCalls.size() > 0) {
            return terminalFailure(
                record,
                recovery,
                "Ответ восстановлен, но требует локального инструмента. Повторите запрос вручную."
            )
        }
        val text = contentText(completion.message.get("content"))
        if (text.isBlank()) return Result.retry()
        if (recovery.get(record.requestId) == null) return Result.success()

        val marker = "async:server:${record.clientRequestId}"
        val assistant = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = "assistant",
            text = text,
            modelId = completion.model.ifBlank { record.modelId },
            providerName = completion.provider.takeIf { it.isNotBlank() } ?: "OpenRouter",
            costUsd = completion.costUsd,
            inputTokens = completion.promptTokens,
            outputTokens = completion.completionTokens,
            deliveryState = marker
        )
        val delivered = runCatching {
            var inserted = false
            ChatRepository(applicationContext).updateChat(record.chatId) { chat ->
                if (chat.messages.any { it.role == "assistant" && it.deliveryState == marker }) return@updateChat chat
                val userIndex = chat.messages.indexOfFirst { it.id == record.messageId && it.role == "user" }
                if (userIndex < 0) return@updateChat chat
                val messages = chat.messages.toMutableList()
                messages[userIndex] = messages[userIndex].copy(deliveryState = null)
                messages.add(userIndex + 1, assistant)
                inserted = true
                chat.copy(messages = messages, updatedAt = System.currentTimeMillis())
            }
            inserted
        }.getOrElse {
            DiagnosticLog.record(applicationContext, "SERVER_RECOVERY", "Could not deliver recovered server answer", it)
            return Result.retry()
        }
        recovery.remove(record.requestId)
        if (delivered) AsyncJobEvents.notifyChanged()
        DiagnosticLog.record(
            applicationContext,
            "SERVER_RECOVERY",
            "Recovered server job delivered request=${record.requestId.take(8)} chat=${record.chatId.take(8)} inserted=$delivered"
        )
        return Result.success()
    }

    private fun terminalFailure(
        record: ServerJobRecoveryRecord,
        recovery: ServerJobRecoveryStore,
        message: String
    ): Result {
        runCatching {
            ChatRepository(applicationContext).updateMessage(record.chatId, record.messageId) {
                if (it.deliveryState == "pending") it.copy(deliveryState = "failed") else it
            }
        }
        recovery.remove(record.requestId)
        DiagnosticLog.record(
            applicationContext,
            "SERVER_RECOVERY",
            "Server recovery stopped request=${record.requestId.take(8)} reason=$message"
        )
        return Result.success()
    }

    private fun contentText(element: com.google.gson.JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        if (element.isJsonPrimitive) return element.asString
        if (!element.isJsonArray) return ""
        return element.asJsonArray.mapNotNull { item ->
            when {
                item.isJsonPrimitive -> item.asString
                item.isJsonObject -> item.asJsonObject.get("text")?.takeUnless { it.isJsonNull }?.asString
                else -> null
            }
        }.joinToString("")
    }

    companion object {
        private const val KEY_REQUEST_ID = "request_id"
        private const val WORK_PREFIX = "umnik-server-recovery-"

        fun schedule(context: Context, requestId: String, initialDelaySeconds: Long = 20L, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<ServerJobRecoveryWorker>()
                .setInputData(workDataOf(KEY_REQUEST_ID to requestId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(initialDelaySeconds.coerceAtLeast(0L), TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_PREFIX + requestId,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context, requestId: String) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_PREFIX + requestId)
        }
    }
}
