package com.ayuemin.ymnik

import android.content.Context
import com.ayuemin.ymnik.data.ChatRepository
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Process-wide owner of the network job. The Activity can be recreated without cancelling it. */
internal object RequestExecutionManager {
    data class Snapshot(val activeChatId: String? = null, val sequence: Long = 0L, val lastError: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableSnapshots = MutableStateFlow(Snapshot())
    val snapshots: StateFlow<Snapshot> = mutableSnapshots
    private var job: Job? = null
    private var cancelCall: (() -> Unit)? = null
    private var stoppingService = false

    fun recoverInterrupted(context: Context): String? {
        if (job?.isActive == true) return null
        val prefs = context.getSharedPreferences("request_execution", Context.MODE_PRIVATE)
        val chatId = prefs.getString("chat_id", null) ?: return null
        val messageId = prefs.getString("message_id", null)
        val completed = if (messageId == null) false else runCatching {
            val messages = ChatRepository(context).list().firstOrNull { it.id == chatId }?.messages.orEmpty()
            val index = messages.indexOfFirst { it.id == messageId }
            index >= 0 && messages.drop(index + 1).firstOrNull { it.role == "assistant" || it.role == "user" }
                ?.role == "assistant"
        }.getOrDefault(false)
        if (messageId != null && !completed) runCatching {
            ChatRepository(context).updateMessage(chatId, messageId) {
                if (it.deliveryState == "pending") it.copy(deliveryState = "failed") else it
            }
        }
        prefs.edit().clear().commit()
        return if (completed) null else
            "Предыдущий запрос был прерван системой. Он не отправлен повторно во избежание повторной оплаты; при необходимости повторите его вручную."
    }

    fun start(
        context: Context,
        chatId: String,
        messageId: String,
        label: String,
        cancelNetworkCall: () -> Unit,
        execute: suspend () -> Unit
    ): Job {
        check(job?.isActive != true) { "Другой запрос ещё выполняется" }
        val app = context.applicationContext
        // The user initiates the service while the Activity is visible. Do not silently
        // continue without foreground protection if Android refuses to start it.
        RequestKeepAliveService.start(app, label)
        val prefs = app.getSharedPreferences("request_execution", Context.MODE_PRIVATE)
        if (!prefs.edit().putString("chat_id", chatId).putString("message_id", messageId)
                .putLong("started_at", System.currentTimeMillis()).commit()
        ) {
            RequestKeepAliveService.stop(app)
            error("Не удалось сохранить состояние запроса")
        }
        cancelCall = cancelNetworkCall
        stoppingService = false
        mutableSnapshots.value = Snapshot(chatId, mutableSnapshots.value.sequence + 1L)
        return scope.launch {
            try {
                execute()
            } catch (error: Throwable) {
                DiagnosticLog.record(app, "REQUEST", "Background execution failed", error)
                mutableSnapshots.value = mutableSnapshots.value.copy(lastError = error.message ?: "Запрос прерван")
            } finally {
                // If the worker was killed/cancelled before saving a result, leave the
                // original user message visible but do not send a duplicate paid POST.
                runCatching {
                    ChatRepository(app).updateMessage(chatId, messageId) {
                        if (it.deliveryState == "pending") it.copy(deliveryState = "failed") else it
                    }
                }
                prefs.edit().clear().commit()
                cancelCall = null
                job = null
                stoppingService = true
                RequestKeepAliveService.stop(app)
                mutableSnapshots.value = Snapshot(null, mutableSnapshots.value.sequence + 1L, mutableSnapshots.value.lastError)
            }
        }.also { job = it }
    }

    fun fail(message: String) {
        mutableSnapshots.value = mutableSnapshots.value.copy(lastError = message)
    }

    fun cancel() {
        cancelCall?.invoke()
        job?.cancel()
    }

    fun serviceStoppedUnexpectedly() {
        if (!stoppingService && job?.isActive == true) {
            fail("Фоновая служба остановлена системой. Запрос прерван; его можно повторить вручную.")
            cancel()
        }
    }
}
