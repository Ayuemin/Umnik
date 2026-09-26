package com.ayuemin.ymnik

import com.ayuemin.ymnik.model.GeneratedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentLinkedQueue

internal enum class LocalShellTerminalState {
    DONE,
    STOPPED,
    FAILED
}

internal data class LocalShellTerminalResult(
    val state: LocalShellTerminalState,
    val text: String = "",
    val files: List<GeneratedFile> = emptyList(),
    val modelId: String? = null,
    val turns: Int = 0,
    val toolCalls: Int = 0,
    val costUsd: Double? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val error: String? = null
) {
    val ok: Boolean
        get() = state == LocalShellTerminalState.DONE
}

/**
 * Process-wide runtime bridge for the single active Local Shell task.
 *
 * UI and the main chat model share the same cancel hook and guidance queue,
 * so a task started from either surface is still one Local Shell process.
 * The terminal deferred lets the parent agent suspend without spending model
 * calls on local_shell_status polling while the child keeps running normally.
 */
internal object LocalShellRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var cancelCurrent: (() -> Unit)? = null

    @Volatile
    private var terminalResult = CompletableDeferred<LocalShellTerminalResult>()

    @Volatile
    private var parentConsumesTerminal = false

    private val guidanceQueue = ConcurrentLinkedQueue<String>()

    fun prepareForStart() {
        guidanceQueue.clear()
        cancelCurrent = null
        parentConsumesTerminal = false
        terminalResult = CompletableDeferred()
    }

    fun installCancel(cancel: () -> Unit) {
        cancelCurrent = cancel
    }

    fun cancel(): Boolean {
        val current = cancelCurrent ?: return false
        current.invoke()
        return true
    }

    fun addGuidance(value: String): Boolean {
        if (AsyncJobEvents.localShellActivity.value == null) return false
        val clean = value.trim().take(4_000)
        if (clean.isBlank()) return false
        guidanceQueue.add(clean)
        return true
    }

    fun drainGuidance(): List<String> {
        val items = mutableListOf<String>()
        while (items.size < 20) {
            val next = guidanceQueue.poll() ?: break
            items += next
        }
        return items
    }

    fun markParentConsumesTerminal() {
        parentConsumesTerminal = true
    }

    fun parentConsumesTerminal(): Boolean = parentConsumesTerminal

    fun completeTerminal(result: LocalShellTerminalResult): Boolean =
        terminalResult.complete(result)

    suspend fun awaitTerminal(): LocalShellTerminalResult = terminalResult.await()

    fun clear() {
        cancelCurrent = null
        guidanceQueue.clear()
        if (!terminalResult.isCompleted) {
            terminalResult.complete(
                LocalShellTerminalResult(
                    state = LocalShellTerminalState.FAILED,
                    error = "Local Shell завершился без итогового результата"
                )
            )
        }
        // Keep the completed deferred and parent-consumption flag until the next
        // prepareForStart(). This avoids races between child cleanup and parent resume.
    }
}
