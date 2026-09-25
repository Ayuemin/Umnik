package com.ayuemin.ymnik

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Process-wide runtime bridge for the single active Local Shell task.
 *
 * UI and the main chat model share the same cancel hook and guidance queue,
 * so a task started from either surface is still one Local Shell process.
 */
internal object LocalShellRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var cancelCurrent: (() -> Unit)? = null

    private val guidanceQueue = ConcurrentLinkedQueue<String>()

    fun prepareForStart() {
        guidanceQueue.clear()
        cancelCurrent = null
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

    fun clear() {
        cancelCurrent = null
        guidanceQueue.clear()
    }
}
