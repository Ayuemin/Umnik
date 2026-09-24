package com.ayuemin.ymnik.model

data class OpenRouterMediaSettings(
    val batchModel: String = "",
    val videoModel: String = "",
    val speechModel: String = "",
    val transcriptionModel: String = "",
    val voice: String = "",
    val responseFormat: String? = null
)
