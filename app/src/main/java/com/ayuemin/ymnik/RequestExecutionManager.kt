package com.ayuemin.ymnik

import android.content.Context
import android.os.PowerManager
import com.ayuemin.ymnik.data.BatchJobRepository
import com.ayuemin.ymnik.data.ChatRepository
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide owner of foreground model work.
 *
 * Requests are isolated by chat: different chats may work concurrently, while a
 * single chat may own at most one unfinished request. The Activity/ViewModel may
 * be recreated without cancelling in-process work.
 */
internal object RequestExecutionManager {
    data class RequestState(
        val chatId: String,
        val messageId: String,
        val label: String,
        val startedAt: Long,
        val lastError: String? = null
    )

    data class Snapshot(
        val activeRequests: Map<String, RequestState> = emptyMap(),
        val sequence: Long = 0L,
        val lastError: String? = null
    ) {
        // Compatibility helpers for code that has not yet switched to per-chat state.
        val activeChatId: String? get() = activeRequests.keys.firstOrNull()
        val label: String? get() = activeRequests.values.firstOrNull()?.label
        val startedAt: Long? get() = activeRequests.values.firstOrNull()?.startedAt
    }

    private data class PersistedRequest(
        val chatId: String,
        val messageId: String,
        val startedAt: Long
    )

    private const val WAKE_LOCK_TIMEOUT_MS = 15L * 60L * 1000L
    private const val PREFS_NAME = "request_execution"
    private const val KEY_ACTIVE_REQUESTS = "active_requests_v2"
    private const val FIELD_SEPARATOR = "\u001F"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private val mutableSnapshots = MutableStateFlow(Snapshot())
    val snapshots: StateFlow<Snapshot> = mutableSnapshots

    private val states = linkedMapOf<String, RequestState>()
    private val jobs = mutableMapOf<String, Job>()
    private val cancelCalls = mutableMapOf<String, () -> Unit>()
    private var stoppingService = false
    private var wakeLock: PowerManager.WakeLock? = null

    fun hasActiveRequest(chatId: String? = null): Boolean = synchronized(lock) {
        if (chatId == null) states.isNotEmpty() else chatId in states
    }

    fun activeCount(): Int = synchronized(lock) { states.size }

    fun activeChatIds(): Set<String> = synchronized(lock) { states.keys.toSet() }

    fun state(chatId: String): RequestState? = synchronized(lock) { states[chatId] }

    fun notificationLabel(): String = synchronized(lock) {
        when (states.size) {
            0 -> "Модель работает…"
            1 -> states.values.first().label
            else -> "${states.size} активных запросов · модели работают…"
        }
    }

    fun recoverInterrupted(context: Context): String? {
        if (hasActiveRequest()) return null
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val persisted = linkedMapOf<String, PersistedRequest>()

        prefs.getStringSet(KEY_ACTIVE_REQUESTS, emptySet()).orEmpty().forEach { encoded ->
            decode(encoded)?.let { persisted[it.chatId] = it }
        }
        // v1.16.x compatibility: migrate the old single-request record if present.
        prefs.getString("chat_id", null)?.let { chatId ->
            val messageId = prefs.getString("message_id", null).orEmpty()
            if (messageId.isNotBlank()) {
                persisted.putIfAbsent(
                    chatId,
                    PersistedRequest(chatId, messageId, prefs.getLong("started_at", 0L))
                )
            }
        }
        if (persisted.isEmpty()) return null

        val repository = ChatRepository(app)
        val batches = runCatching { BatchJobRepository(app).list() }.getOrDefault(emptyList())
        var interrupted = 0
        var batchCount = 0
        persisted.values.forEach { request ->
            val savedBatch = batches.firstOrNull {
                it.chatId == request.chatId && it.userMessageId == request.messageId
            }
            if (savedBatch != null) {
                batchCount += 1
                return@forEach
            }

            val completed = runCatching {
                val messages = repository.list().firstOrNull { it.id == request.chatId }?.messages.orEmpty()
                val index = messages.indexOfFirst { it.id == request.messageId }
                index >= 0 && messages.drop(index + 1)
                    .firstOrNull { it.role == "assistant" || it.role == "user" }
                    ?.role == "assistant"
            }.getOrDefault(false)
            if (!completed) {
                interrupted += 1
                runCatching {
                    repository.updateMessage(request.chatId, request.messageId) {
                        if (it.deliveryState == "pending") it.copy(deliveryState = "failed") else it
                    }
                }
            }
        }
        prefs.edit().clear().commit()
        if (batchCount > 0) OpenRouterBackgroundWorker.schedule(app, replace = true)

        return when {
            interrupted > 0 && batchCount > 0 ->
                "После перезапуска прервано запросов: $interrupted. Batch-запросов продолжают выполняться: $batchCount."
            interrupted == 1 ->
                "Предыдущий запрос был прерван системой. Он не отправлен повторно во избежание повторной оплаты; при необходимости повторите его вручную."
            interrupted > 1 ->
                "Система прервала $interrupted активных запросов. Umnik не отправил их повторно во избежание повторной оплаты."
            batchCount > 0 ->
                "Batch-запросы продолжают выполняться на OpenRouter. Umnik заберёт результаты автоматически."
            else -> null
        }
    }

    fun start(
        context: Context,
        chatId: String,
        messageId: String,
        label: String,
        maxParallelRequests: Int = 0,
        cancelNetworkCall: () -> Unit,
        execute: suspend () -> Unit
    ): Job {
        val app = context.applicationContext
        val cleanLabel = label.trim().take(160).ifBlank { "Модель работает…" }
        val startedAt = System.currentTimeMillis()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                execute()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                DiagnosticLog.record(app, "REQUEST", "Background execution failed chat=${chatId.take(8)}", error)
                fail(chatId, error.message ?: "Запрос прерван")
            } finally {
                finish(app, chatId, messageId)
            }
        }

        synchronized(lock) {
            check(chatId !in states) { "В этом чате уже выполняется запрос" }
            val limit = maxParallelRequests.coerceAtLeast(0)
            check(limit == 0 || states.size < limit) {
                "Достигнут заданный лимит одновременных запросов: $limit"
            }
            states[chatId] = RequestState(chatId, messageId, cleanLabel, startedAt)
            jobs[chatId] = job
            cancelCalls[chatId] = cancelNetworkCall
            stoppingService = false
            persistLocked(app)
            publishLocked()
        }

        try {
            acquireWakeLock(app)
            RequestKeepAliveService.start(app, notificationLabel())
            DiagnosticLog.record(
                app,
                "REQUEST",
                "registered chat=${chatId.take(8)}; active=${activeCount()}; limit=${maxParallelRequests.coerceAtLeast(0)}"
            )
            job.start()
            return job
        } catch (error: Throwable) {
            synchronized(lock) {
                states.remove(chatId)
                jobs.remove(chatId)
                cancelCalls.remove(chatId)
                persistLocked(app)
                publishLocked(error.message)
            }
            if (!hasActiveRequest()) releaseWakeLock(app)
            throw error
        }
    }

    /**
     * Tracks an Orchestrator worker in a target chat. Unlike [start], this waits
     * for a configured capacity slot and for the target chat to become free.
     * `exemptChatId` is normally the parent Orchestrator chat, so a limit of 1
     * still lets that parent hand work to one specialist instead of deadlocking.
     */
    suspend fun <T> tracked(
        context: Context,
        chatId: String,
        messageId: String,
        label: String,
        maxParallelRequests: Int = 0,
        exemptChatId: String? = null,
        cancelNetworkCall: () -> Unit,
        execute: suspend () -> T
    ): T {
        val app = context.applicationContext
        val currentJob = currentCoroutineContext()[Job]
            ?: error("Не удалось привязать работу к coroutine")
        val cleanLabel = label.trim().take(160).ifBlank { "Модель работает…" }

        while (true) {
            val registered = synchronized(lock) {
                val occupied = states.keys.count { it != exemptChatId }
                val limit = maxParallelRequests.coerceAtLeast(0)
                if (chatId !in states && (limit == 0 || occupied < limit)) {
                    val startedAt = System.currentTimeMillis()
                    states[chatId] = RequestState(chatId, messageId, cleanLabel, startedAt)
                    jobs[chatId] = currentJob
                    cancelCalls[chatId] = cancelNetworkCall
                    stoppingService = false
                    persistLocked(app)
                    publishLocked()
                    true
                } else false
            }
            if (registered) break
            delay(120L)
        }

        acquireWakeLock(app)
        RequestKeepAliveService.start(app, notificationLabel())
        DiagnosticLog.record(app, "REQUEST", "worker registered chat=${chatId.take(8)}; active=${activeCount()}")
        try {
            return execute()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            fail(chatId, error.message ?: "Запрос прерван")
            throw error
        } finally {
            finish(app, chatId, messageId)
        }
    }

    fun updatePhase(context: Context, chatId: String, label: String) {
        val clean = label.trim().take(160).ifBlank { "Модель работает…" }
        val changed = synchronized(lock) {
            val current = states[chatId] ?: return@synchronized false
            states[chatId] = current.copy(label = clean)
            publishLocked()
            true
        }
        if (changed) RequestKeepAliveService.update(context.applicationContext, notificationLabel())
    }

    fun fail(chatId: String, message: String) {
        synchronized(lock) {
            val current = states[chatId]
            if (current != null) states[chatId] = current.copy(lastError = message)
            publishLocked(message)
        }
    }

    fun failAll(message: String) {
        synchronized(lock) {
            states.replaceAll { _, value -> value.copy(lastError = message) }
            publishLocked(message)
        }
    }

    fun cancel(chatId: String) {
        val cancel: (() -> Unit)?
        val job: Job?
        synchronized(lock) {
            cancel = cancelCalls[chatId]
            job = jobs[chatId]
        }
        runCatching { cancel?.invoke() }
        job?.cancel()
    }

    fun cancelAll() {
        val callbacks: List<() -> Unit>
        val running: List<Job>
        synchronized(lock) {
            callbacks = cancelCalls.values.toList()
            running = jobs.values.distinct()
        }
        callbacks.forEach { callback -> runCatching(callback) }
        running.forEach(Job::cancel)
    }

    fun serviceStoppedUnexpectedly(context: Context) {
        if (!stoppingService && hasActiveRequest()) {
            DiagnosticLog.record(
                context.applicationContext,
                "REQUEST",
                "Foreground service destroyed while ${activeCount()} request(s) are active; network jobs kept alive"
            )
        }
    }

    private fun finish(app: Context, chatId: String, messageId: String) {
        runCatching {
            ChatRepository(app).updateMessage(chatId, messageId) {
                if (it.deliveryState == "pending") it.copy(deliveryState = "failed") else it
            }
        }
        val remain = synchronized(lock) {
            states.remove(chatId)
            jobs.remove(chatId)
            cancelCalls.remove(chatId)
            persistLocked(app)
            publishLocked()
            states.size
        }
        DiagnosticLog.record(app, "REQUEST", "finished chat=${chatId.take(8)}; active=$remain")
        if (remain == 0) {
            releaseWakeLock(app)
            stoppingService = true
            RequestKeepAliveService.stop(app)
        } else {
            RequestKeepAliveService.update(app, notificationLabel())
        }
    }

    private fun publishLocked(lastError: String? = mutableSnapshots.value.lastError) {
        mutableSnapshots.value = Snapshot(
            activeRequests = LinkedHashMap(states),
            sequence = mutableSnapshots.value.sequence + 1L,
            lastError = lastError
        )
    }

    private fun persistLocked(app: Context) {
        val encoded = states.values.mapTo(linkedSetOf()) { state ->
            listOf(state.chatId, state.messageId, state.startedAt.toString()).joinToString(FIELD_SEPARATOR)
        }
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_ACTIVE_REQUESTS, encoded)
            .remove("chat_id")
            .remove("message_id")
            .remove("started_at")
            .commit()
    }

    private fun decode(raw: String): PersistedRequest? {
        val parts = raw.split(FIELD_SEPARATOR)
        if (parts.size < 3 || parts[0].isBlank() || parts[1].isBlank()) return null
        return PersistedRequest(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L)
    }

    private fun acquireWakeLock(app: Context) {
        synchronized(lock) {
            if (wakeLock?.isHeld == true) return
            runCatching {
                val power = app.getSystemService(PowerManager::class.java)
                power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${app.packageName}:active_request").apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
            }.onSuccess { lock ->
                wakeLock = lock
                DiagnosticLog.record(app, "REQUEST", "Partial wake lock acquired for active requests")
            }.onFailure { error ->
                DiagnosticLog.record(app, "REQUEST", "Could not acquire wake lock", error)
            }
        }
    }

    private fun releaseWakeLock(app: Context) {
        synchronized(lock) {
            val held = wakeLock
            wakeLock = null
            if (held?.isHeld == true) {
                runCatching { held.release() }
                    .onFailure { DiagnosticLog.record(app, "REQUEST", "Could not release wake lock", it) }
            }
        }
    }
}
