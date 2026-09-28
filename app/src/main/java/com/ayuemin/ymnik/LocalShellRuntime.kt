package com.ayuemin.ymnik

import com.ayuemin.ymnik.diagnostics.LocalShellBudgetTelemetry
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

internal object LocalShellRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var cancelCurrent: (() -> Unit)? = null
    @Volatile private var terminalResult = CompletableDeferred<LocalShellTerminalResult>()
    @Volatile private var parentConsumesTerminal = false
    @Volatile private var checkpointProvider: (() -> String)? = null
    private val guidanceQueue = ConcurrentLinkedQueue<String>()

    fun prepareForStart() {
        guidanceQueue.clear()
        cancelCurrent = null
        parentConsumesTerminal = false
        terminalResult = CompletableDeferred()
        LocalShellBudgetTelemetry.reset()
    }

    fun installCheckpointProvider(provider: () -> String) {
        checkpointProvider = provider
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

    fun completeTerminal(result: LocalShellTerminalResult): Boolean {
        val enriched = if (
            result.text.isBlank() &&
            (result.state == LocalShellTerminalState.FAILED || result.state == LocalShellTerminalState.STOPPED)
        ) {
            val checkpoint = runCatching { checkpointProvider?.invoke().orEmpty() }.getOrDefault("")
            val inventory = result.files.joinToString(", ") { "${it.name} (${it.size} B)" }
            result.copy(
                text = buildString {
                    append("STATUS=PARTIAL\n")
                    if (checkpoint.isNotBlank()) append(checkpoint) else append("Рабочий checkpoint недоступен.")
                    if (inventory.isNotBlank()) append("\nАртефакты: ").append(inventory)
                    append("\nUsage: turns=").append(result.turns)
                        .append("; tools=").append(result.toolCalls)
                        .append("; input=").append(result.inputTokens ?: 0)
                        .append("; output=").append(result.outputTokens ?: 0)
                        .append("; cost=").append(result.costUsd?.toString() ?: "unknown")
                    result.error?.takeIf { it.isNotBlank() }?.let { append("\nОшибка: ").append(it) }
                }
            )
        } else result
        return terminalResult.complete(enriched)
    }

    suspend fun awaitTerminal(): LocalShellTerminalResult = terminalResult.await()

    fun clear() {
        cancelCurrent = null
        guidanceQueue.clear()
        if (!terminalResult.isCompleted) {
            completeTerminal(
                LocalShellTerminalResult(
                    state = LocalShellTerminalState.FAILED,
                    error = "Local Shell завершился без итогового результата"
                )
            )
        }
        checkpointProvider = null
    }
}
