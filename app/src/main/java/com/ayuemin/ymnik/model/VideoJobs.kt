package com.ayuemin.ymnik.model

enum class VideoJobStatus {
    PENDING,
    QUEUED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED,
    UNKNOWN;

    val terminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED

    companion object {
        fun fromApi(value: String?): VideoJobStatus = when (value?.trim()?.lowercase()) {
            "pending" -> PENDING
            "queued" -> QUEUED
            "in_progress", "processing", "running" -> IN_PROGRESS
            "completed", "succeeded" -> COMPLETED
            "failed" -> FAILED
            "cancelled", "canceled" -> CANCELLED
            "expired" -> EXPIRED
            else -> UNKNOWN
        }
    }
}

data class VideoJob(
    val id: String,
    val remoteId: String,
    val connectionProfileId: String,
    val chatId: String? = null,
    val projectId: String? = null,
    val modelId: String,
    val prompt: String,
    val status: VideoJobStatus = VideoJobStatus.PENDING,
    val generationId: String? = null,
    val pollingUrl: String? = null,
    val remoteUrls: List<String> = emptyList(),
    val localPath: String? = null,
    val costUsd: Double? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
