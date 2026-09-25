package com.ayuemin.ymnik

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class OpenRouterSpeechRequest(val chatId: String, val text: String)
internal data class ShellActivity(
    val chatId: String,
    val modelId: String = "",
    val attachmentCount: Int = 0,
    val status: String = "Подготавливаю задачу…",
    val startedAt: Long = System.currentTimeMillis(),
    val lastRemoteEventAt: Long? = null,
    val responseId: String? = null,
    val eventCount: Int = 0,
    val shellSteps: Int = 0
)
internal data class LocalShellActivity(
    val chatId: String,
    val modelId: String = "",
    val attachmentCount: Int = 0,
    val maxTurns: Int = 24,
    val status: String = "Готовлю локальную рабочую область",
    val startedAt: Long = System.currentTimeMillis(),
    val turn: Int = 0,
    val toolCalls: Int = 0
)
internal data class HubToolActivity(
    val chatId: String,
    val page: String,
    val title: String,
    val startedAt: Long = System.currentTimeMillis()
)

internal object AsyncJobEvents {
    private val mutableSequence = MutableStateFlow(0L)
    val sequence: StateFlow<Long> = mutableSequence

    private val mutableHubRequest = MutableStateFlow<String?>(null)
    val hubRequest: StateFlow<String?> = mutableHubRequest
    private val mutableHubReturnLabel = MutableStateFlow<String?>(null)
    val hubReturnLabel: StateFlow<String?> = mutableHubReturnLabel

    private val mutableSpeechRequest = MutableStateFlow<OpenRouterSpeechRequest?>(null)
    val speechRequest: StateFlow<OpenRouterSpeechRequest?> = mutableSpeechRequest

    private val mutableShellActivity = MutableStateFlow<ShellActivity?>(null)
    val shellActivity: StateFlow<ShellActivity?> = mutableShellActivity

    private val mutableLocalShellActivity = MutableStateFlow<LocalShellActivity?>(null)
    val localShellActivity: StateFlow<LocalShellActivity?> = mutableLocalShellActivity

    private val mutableHubToolActivity = MutableStateFlow<HubToolActivity?>(null)
    val hubToolActivity: StateFlow<HubToolActivity?> = mutableHubToolActivity

    fun notifyChanged() {
        mutableSequence.value = mutableSequence.value + 1L
    }

    fun requestHub(page: String, returnLabel: String? = null) {
        mutableHubReturnLabel.value = returnLabel?.trim()?.takeIf { it.isNotBlank() }
        mutableHubRequest.value = page.trim().lowercase().ifBlank { "models" }
    }

    fun consumeHubRequest() {
        mutableHubRequest.value = null
        mutableHubReturnLabel.value = null
    }

    fun requestSpeech(chatId: String, text: String) {
        if (chatId.isBlank() || text.isBlank()) return
        mutableSpeechRequest.value = OpenRouterSpeechRequest(chatId, text)
    }

    fun consumeSpeechRequest() {
        mutableSpeechRequest.value = null
    }

    fun markShellRunning(chatId: String, modelId: String = "", attachmentCount: Int = 0) {
        if (chatId.isBlank()) return
        mutableShellActivity.value = ShellActivity(
            chatId = chatId,
            modelId = modelId,
            attachmentCount = attachmentCount.coerceAtLeast(0)
        )
    }

    fun updateShellProgress(
        chatId: String,
        status: String,
        responseId: String? = null,
        shellStepDelta: Int = 0,
        remoteSignal: Boolean = false
    ) {
        val current = mutableShellActivity.value ?: return
        if (current.chatId != chatId) return
        val clean = status.trim().take(160).ifBlank { current.status }
        val now = System.currentTimeMillis()
        val sameStatus = clean == current.status
        val repeatedRemote = remoteSignal && sameStatus && responseId == null && shellStepDelta == 0 &&
            current.lastRemoteEventAt?.let { now - it < 1_000L } == true
        if (repeatedRemote) return
        mutableShellActivity.value = current.copy(
            status = clean,
            lastRemoteEventAt = if (remoteSignal) now else current.lastRemoteEventAt,
            responseId = responseId?.takeIf { it.isNotBlank() } ?: current.responseId,
            eventCount = current.eventCount + if (remoteSignal) 1 else 0,
            shellSteps = (current.shellSteps + shellStepDelta).coerceAtLeast(0)
        )
    }

    fun markShellFinished(chatId: String) {
        if (mutableShellActivity.value?.chatId == chatId) {
            mutableShellActivity.value = null
        }
    }

    fun markLocalShellRunning(chatId: String, modelId: String = "", attachmentCount: Int = 0, maxTurns: Int = 24) {
        if (chatId.isBlank()) return
        mutableLocalShellActivity.value = LocalShellActivity(
            chatId = chatId,
            modelId = modelId,
            attachmentCount = attachmentCount.coerceAtLeast(0),
            maxTurns = maxTurns.coerceAtLeast(1)
        )
    }

    fun updateLocalShellProgress(chatId: String, status: String, turn: Int, toolCalls: Int) {
        val current = mutableLocalShellActivity.value ?: return
        if (current.chatId != chatId) return
        mutableLocalShellActivity.value = current.copy(
            status = status.trim().take(160).ifBlank { current.status },
            turn = turn.coerceAtLeast(0),
            toolCalls = toolCalls.coerceAtLeast(0)
        )
    }

    fun markLocalShellFinished(chatId: String) {
        if (mutableLocalShellActivity.value?.chatId == chatId) {
            mutableLocalShellActivity.value = null
        }
    }

    fun markHubToolRunning(chatId: String, page: String, title: String) {
        if (chatId.isBlank() || page.isBlank() || title.isBlank()) return
        mutableHubToolActivity.value = HubToolActivity(
            chatId = chatId,
            page = page.trim().lowercase(),
            title = title.trim()
        )
    }

    fun markHubToolFinished(chatId: String, page: String) {
        val active = mutableHubToolActivity.value
        if (active?.chatId == chatId && active.page == page.trim().lowercase()) {
            mutableHubToolActivity.value = null
        }
    }
}
