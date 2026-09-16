package com.ayuemin.ymnik

import android.content.Context
import android.os.PowerManager
import com.ayuemin.ymnik.data.BatchJobRepository
import com.ayuemin.ymnik.data.ChatRepository
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.network.OpenRouterRecoveryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide owner of all foreground model jobs.
 *
 * Requests are isolated by requestId and chatId, so switching Activity/chat or launching
 * another chat does not cancel or overwrite the first request. One chat may have at most
 * one active top-level request; different chats may run concurrently.
 */
internal object RequestExecutionManager {
    data class Snapshot(
        val requestId: String,
        val chatId: String,
        val messageId: String,
        val sequence: Long,
        val lastError: String? = null,
        val label: String = "Модель работает…",
        val startedAt: Long = System.currentTimeMillis()
    )

    private data class Runtime(
        var snapshot: Snapshot,
        val job: Job,
        val cancelNetworkCall: () -> Unit,
        val appContext: Context
    )

    private data class PersistedRequest(
        val requestId: String,
        val chatId: String,
        val messageId: String?
    )

    private const val PREFS = "request_execution"
    private const val ACTIVE_PREFIX = "active_request::"
    private const val FIELD_SEPARATOR = "\u001F"
    private const val WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Any()
    private val runtimes = linkedMapOf<String, Runtime>()
    private val reservations = linkedMapOf<String, String>() // chatId -> owning requestId
    private val mutableSnapshots = MutableStateFlow<List<Snapshot>>(emptyList())
    val snapshots: StateFlow<List<Snapshot>> = mutableSnapshots
    private var sequence = 0L
    private var stoppingService = false
    private var wakeLock: PowerManager.WakeLock? = null

    fun hasActiveRequest(): Boolean = synchronized(lock) { runtimes.isNotEmpty() }

    fun activeCount(): Int = synchronized(lock) { runtimes.size }

    fun activeChatIds(): Set<String> = synchronized(lock) {
        linkedSetOf<String>().apply {
            runtimes.values.forEach { add(it.snapshot.chatId) }
            addAll(reservations.keys)
        }
    }

    fun hasActiveChat(chatId: String): Boolean = synchronized(lock) {
        runtimes.values.any { it.snapshot.chatId == chatId } || chatId in reservations
    }

    fun snapshotForChat(chatId: String): Snapshot? = synchronized(lock) {
        runtimes.values.firstOrNull { it.snapshot.chatId == chatId }?.snapshot
            ?: reservations[chatId]?.let { owner -> runtimes[owner]?.snapshot }
    }

    fun snapshotForRequest(requestId: String): Snapshot? = synchronized(lock) {
        runtimes[requestId]?.snapshot
    }

    fun reserveChat(requestId: String, chatId: String): Boolean = synchronized(lock) {
        val owner = runtimes[requestId] ?: return@synchronized false
        val directOwner = runtimes.values.firstOrNull { it.snapshot.chatId == chatId }
        if (directOwner != null && directOwner.snapshot.requestId != requestId) return@synchronized false
        val reservedBy = reservations[chatId]
        if (reservedBy != null && reservedBy != requestId) return@synchronized false
        if (owner.snapshot.chatId == chatId || reservedBy == requestId) return@synchronized true
        reservations[chatId] = requestId
        sequence += 1L
        owner.snapshot = owner.snapshot.copy(sequence = sequence)
        publishLocked()
        true
    }

    fun releaseChat(requestId: String, chatId: String) {
        synchronized(lock) {
            if (reservations[chatId] != requestId) return
            reservations.remove(chatId)
            runtimes[requestId]?.let { owner ->
                sequence += 1L
                owner.snapshot = owner.snapshot.copy(sequence = sequence)
            }
            publishLocked()
        }
    }

    fun recoverInterrupted(context: Context): String? {
        if (hasActiveRequest()) return null
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val persisted = mutableListOf<PersistedRequest>()

        prefs.all.forEach { (key, rawValue) ->
            if (!key.startsWith(ACTIVE_PREFIX)) return@forEach
            val requestId = key.removePrefix(ACTIVE_PREFIX)
            val parts = (rawValue as? String).orEmpty().split(FIELD_SEPARATOR)
            val chatId = parts.getOrNull(0).orEmpty()
            if (requestId.isNotBlank() && chatId.isNotBlank()) {
                persisted += PersistedRequest(requestId, chatId, parts.getOrNull(1)?.takeIf { it.isNotBlank() })
            }
        }

        // v1.16.x compatibility: recover the old single-request record once.
        prefs.getString("chat_id", null)?.let { legacyChatId ->
            persisted += PersistedRequest(
                requestId = "legacy",
                chatId = legacyChatId,
                messageId = prefs.getString("message_id", null)
            )
        }

        if (persisted.isEmpty()) return null

        val batches = runCatching { BatchJobRepository(app).list() }.getOrDefault(emptyList())
        val chatRepository = ChatRepository(app)
        val chats = runCatching { chatRepository.list() }.getOrDefault(emptyList())
        val recoveryStore = OpenRouterRecoveryStore(app)
        var batchCount = 0
        var recoveryCount = 0
        var interruptedCount = 0

        persisted.distinctBy { it.requestId }.forEach { saved ->
            val messageId = saved.messageId
            val savedBatch = messageId?.let { id ->
                batches.firstOrNull { it.chatId == saved.chatId && it.userMessageId == id }
            }
            if (savedBatch != null) {
                batchCount += 1
                return@forEach
            }

            val completed = messageId?.let { id ->
                val messages = chats.firstOrNull { it.id == saved.chatId }?.messages.orEmpty()
                val index = messages.indexOfFirst { it.id == id }
                index >= 0 && messages.drop(index + 1)
                    .firstOrNull { it.role == "assistant" || it.role == "user" }
                    ?.role == "assistant"
            } == true
            if (completed) {
                recoveryStore.remove(saved.requestId)
                return@forEach
            }

            if (messageId != null && recoveryStore.get(saved.requestId) != null) {
                recoveryCount += 1
                OpenRouterRecoveryWorker.schedule(app, saved.requestId, initialDelaySeconds = 0L, replaceExisting = true, expedited = true)
                return@forEach
            }

            if (messageId != null) {
                interruptedCount += 1
                runCatching {
                    chatRepository.updateMessage(saved.chatId, messageId) {
                        if (it.deliveryState == "pending") it.copy(deliveryState = "failed") else it
                    }
                }
            }
        }

        prefs.edit().clear().commit()
        if (batchCount > 0) OpenRouterBackgroundWorker.schedule(app, replace = true)

        return buildList {
            if (recoveryCount > 0) add("$recoveryCount запрос(а) восстанавливаются в фоне после перезапуска приложения.")
            if (batchCount > 0) add("$batchCount batch-запрос(а) продолжаются на OpenRouter и будут получены автоматически.")
            if (interruptedCount > 0) add("$interruptedCount запрос(а) были прерваны системой до безопасной точки восстановления; при необходимости повторите их вручную.")
        }.joinToString(" ").takeIf { it.isNotBlank() }
    }

    fun start(
        context: Context,
        requestId: String,
        chatId: String,
        messageId: String,
        label: String,
        cancelNetworkCall: () -> Unit,
        execute: suspend () -> Unit
    ): Job {
        val app = context.applicationContext
        val cleanLabel = label.trim().take(160).ifBlank { "Модель работает…" }
        val startedAt = System.currentTimeMillis()
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        synchronized(lock) {
            check(requestId !in runtimes) { "Запрос уже зарегистрирован" }
            check(runtimes.values.none { it.snapshot.chatId == chatId } && chatId !in reservations) { "В этом чате запрос уже выполняется" }
        }

        val persisted = listOf(chatId, messageId, startedAt.toString()).joinToString(FIELD_SEPARATOR)
        check(prefs.edit().putString(ACTIVE_PREFIX + requestId, persisted).commit()) {
            "Не удалось сохранить состояние запроса"
        }

        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                execute()
            } catch (error: Throwable) {
                DiagnosticLog.record(app, "REQUEST", "Background execution failed request=${requestId.take(8)} chat=${chatId.take(8)}", error)
                fail(requestId, error.message ?: "Запрос прерван")
            } finally {
                val recoveryPending = OpenRouterRecoveryStore(app).get(requestId) != null
                if (recoveryPending) {
                    OpenRouterRecoveryWorker.schedule(app, requestId, initialDelaySeconds = 0L, replaceExisting = true, expedited = true)
                    DiagnosticLog.record(app, "REQUEST_RECOVERY", "Foreground request handed to WorkManager request=${requestId.take(8)} chat=${chatId.take(8)}")
                } else {
                    runCatching {
                        ChatRepository(app).updateMessage(chatId, messageId) {
                            if (it.deliveryState == "pending") it.copy(deliveryState = "failed") else it
                        }
                    }
                }
                prefs.edit().remove(ACTIVE_PREFIX + requestId).commit()
                val remaining = synchronized(lock) {
                    runtimes.remove(requestId)
                    reservations.entries.removeAll { it.value == requestId }
                    publishLocked()
                    runtimes.size
                }
                if (remaining == 0) {
                    releaseWakeLock(app)
                    stoppingService = true
                    RequestKeepAliveService.stop(app)
                } else {
                    RequestKeepAliveService.update(app)
                }
            }
        }

        val snapshot = synchronized(lock) {
            sequence += 1L
            Snapshot(
                requestId = requestId,
                chatId = chatId,
                messageId = messageId,
                sequence = sequence,
                label = cleanLabel,
                startedAt = startedAt
            ).also { snapshot ->
                runtimes[requestId] = Runtime(snapshot, job, cancelNetworkCall, app)
                publishLocked()
            }
        }

        acquireWakeLock(app)
        stoppingService = false
        runCatching { RequestKeepAliveService.start(app) }
            .onFailure { error ->
                synchronized(lock) {
                    runtimes.remove(requestId)
                    reservations.entries.removeAll { it.value == requestId }
                    publishLocked()
                }
                prefs.edit().remove(ACTIVE_PREFIX + requestId).commit()
                if (!hasActiveRequest()) releaseWakeLock(app)
                throw error
            }
        DiagnosticLog.record(app, "REQUEST", "registered request=${snapshot.requestId.take(8)} chat=${chatId.take(8)} active=${activeCount()}")
        job.start()
        return job
    }

    fun updatePhase(context: Context, requestId: String, label: String) {
        val clean = label.trim().take(160).ifBlank { "Модель работает…" }
        val changed = synchronized(lock) {
            val runtime = runtimes[requestId] ?: return@synchronized false
            sequence += 1L
            runtime.snapshot = runtime.snapshot.copy(sequence = sequence, label = clean)
            publishLocked()
            true
        }
        if (changed) RequestKeepAliveService.update(context.applicationContext)
    }

    fun fail(requestId: String, message: String) {
        synchronized(lock) {
            val runtime = runtimes[requestId] ?: return
            sequence += 1L
            runtime.snapshot = runtime.snapshot.copy(sequence = sequence, lastError = message)
            publishLocked()
        }
    }

    fun cancel(requestId: String) {
        val runtime = synchronized(lock) { runtimes[requestId] } ?: return
        // Manual Stop is final: no WorkManager recovery may resurrect this answer later.
        OpenRouterRecoveryStore(runtime.appContext).remove(requestId)
        OpenRouterRecoveryWorker.cancel(runtime.appContext, requestId)
        runCatching { runtime.cancelNetworkCall.invoke() }
        runtime.job.cancel()
        DiagnosticLog.record(runtime.appContext, "REQUEST_RECOVERY", "Manual cancellation cleared recovery request=${requestId.take(8)}")
    }

    fun cancelChat(chatId: String) {
        val requestId = snapshotForChat(chatId)?.requestId ?: return
        cancel(requestId)
    }

    fun cancelAll() {
        val ids = synchronized(lock) { runtimes.keys.toList() }
        ids.forEach(::cancel)
    }

    /** Destroying the foreground service does not own/cancel in-process requests. */
    fun serviceStoppedUnexpectedly(context: Context) {
        if (!stoppingService && hasActiveRequest()) {
            DiagnosticLog.record(
                context.applicationContext,
                "REQUEST",
                "Foreground service destroyed while ${activeCount()} request(s) are active; network jobs kept alive"
            )
        }
    }

    private fun publishLocked() {
        mutableSnapshots.value = runtimes.values.map { it.snapshot }
    }

    private fun acquireWakeLock(app: Context) {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val power = app.getSystemService(PowerManager::class.java)
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "${app.packageName}:active_requests").apply {
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

    private fun releaseWakeLock(app: Context) {
        val lock = wakeLock
        wakeLock = null
        if (lock?.isHeld == true) {
            runCatching { lock.release() }
                .onFailure { DiagnosticLog.record(app, "REQUEST", "Could not release wake lock", it) }
        }
    }
}
