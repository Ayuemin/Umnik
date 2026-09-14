package com.ayuemin.ymnik

import android.content.Context
import android.os.PowerManager
import com.ayuemin.ymnik.data.BatchJobRepository
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

    private const val WAKE_LOCK_TIMEOUT_MS = 15L * 60L * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableSnapshots = MutableStateFlow(Snapshot())
    val snapshots: StateFlow<Snapshot> = mutableSnapshots
    private var job: Job? = null
    private var cancelCall: (() -> Unit)? = null
    private var stoppingService = false
    private var wakeLock: PowerManager.WakeLock? = null

    fun hasActiveRequest(): Boolean = job?.isActive == true

    fun recoverInterrupted(context: Context): String? {
        if (hasActiveRequest()) return null
        val prefs = context.getSharedPreferences("request_execution", Context.MODE_PRIVATE)
        val chatId = prefs.getString("chat_id", null) ?: return null
        val messageId = prefs.getString("message_id", null)
        val savedBatch = if (messageId == null) null else runCatching {
            BatchJobRepository(context).list().firstOrNull {
                it.chatId == chatId && it.userMessageId == messageId
            }
        }.getOrNull()
        if (savedBatch != null) {
            prefs.edit().clear().commit()
            OpenRouterBackgroundWorker.schedule(context, replace = true)
            return "Batch-запрос продолжает выполняться на OpenRouter. Umnik заберёт результат автоматически."
        }

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
        check(!hasActiveRequest()) { "Другой запрос ещё выполняется" }
        val app = context.applicationContext
        RequestKeepAliveService.start(app, label)
        val prefs = app.getSharedPreferences("request_execution", Context.MODE_PRIVATE)
        if (!prefs.edit().putString("chat_id", chatId).putString("message_id", messageId)
                .putLong("started_at", System.currentTimeMillis()).commit()
        ) {
            RequestKeepAliveService.stop(app)
            error("Не удалось сохранить состояние запроса")
        }
        acquireWakeLock(app)
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
                runCatching {
                    ChatRepository(app).updateMessage(chatId, messageId) {
                        if (it.deliveryState == "pending") it.copy(deliveryState = "failed") else it
                    }
                }
                prefs.edit().clear().commit()
                cancelCall = null
                job = null
                releaseWakeLock(app)
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

    /**
     * Destroying the foreground Service must not itself cancel an in-flight paid request
     * while this process and its network job are still alive. A sticky Service can be
     * recreated independently of the Activity.
     */
    fun serviceStoppedUnexpectedly(context: Context) {
        if (!stoppingService && hasActiveRequest()) {
            DiagnosticLog.record(
                context.applicationContext,
                "REQUEST",
                "Foreground service destroyed while request is active; network job kept alive"
            )
        }
    }

    private fun acquireWakeLock(app: Context) {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val power = app.getSystemService(PowerManager::class.java)
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${app.packageName}:active_request").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.onSuccess { lock ->
            wakeLock = lock
            DiagnosticLog.record(app, "REQUEST", "Partial wake lock acquired for active request")
        }.onFailure { error ->
            DiagnosticLog.record(app, "REQUEST", "Could not acquire wake lock", error)
        }
    }

    private fun releaseWakeLock(app: Context) {
        val lock = wakeLock
        wakeLock = null
        if (lock?.isHeld == true) {
            runCatching { lock.release() }
                .onFailure { DiagnosticLog.record(app, "REQUEST", "Could not release wake lock", it) }
        }
    }
}
