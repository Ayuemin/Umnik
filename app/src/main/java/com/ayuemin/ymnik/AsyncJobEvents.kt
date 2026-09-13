package com.ayuemin.ymnik

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal object AsyncJobEvents {
    private val mutableSequence = MutableStateFlow(0L)
    val sequence: StateFlow<Long> = mutableSequence

    fun notifyChanged() {
        mutableSequence.value = mutableSequence.value + 1L
    }
}
