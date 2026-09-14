package com.ayuemin.ymnik.model

data class RagSettings(
    val enabled: Boolean = false,
    val embeddingModel: String = "",
    val rerankModel: String = "",
    val topK: Int = 8
)

data class OpenRouterMediaSettings(
    val batchModel: String = "",
    val videoModel: String = "",
    val speechModel: String = "",
    val transcriptionModel: String = "",
    val voice: String = "",
    val responseFormat: String? = null
)
