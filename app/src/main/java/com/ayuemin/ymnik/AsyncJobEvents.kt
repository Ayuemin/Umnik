package com.ayuemin.ymnik

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal object AsyncJobEvents {
    private val mutableSequence = MutableStateFlow(0L)
    val sequence: StateFlow<Long> = mutableSequence

    private val mutableHubRequest = MutableStateFlow<String?>(null)
    val hubRequest: StateFlow<String?> = mutableHubRequest

    fun notifyChanged() {
        mutableSequence.value = mutableSequence.value + 1L
    }

    fun requestHub(page: String) {
        mutableHubRequest.value = page.trim().lowercase().ifBlank { "models" }
    }

    fun consumeHubRequest() {
        mutableHubRequest.value = null
    }
}
