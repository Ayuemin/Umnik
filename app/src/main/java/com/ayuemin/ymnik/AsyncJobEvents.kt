package com.ayuemin.ymnik

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class OpenRouterSpeechRequest(val chatId: String, val text: String)
internal data class ShellActivity(val chatId: String, val startedAt: Long = System.currentTimeMillis())
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

    fun markShellRunning(chatId: String) {
        if (chatId.isBlank()) return
        mutableShellActivity.value = ShellActivity(chatId)
    }

    fun markShellFinished(chatId: String) {
        if (mutableShellActivity.value?.chatId == chatId) {
            mutableShellActivity.value = null
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
