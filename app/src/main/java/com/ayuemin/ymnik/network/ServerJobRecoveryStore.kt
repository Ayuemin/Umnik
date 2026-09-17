package com.ayuemin.ymnik.network

import android.content.Context
import com.google.gson.Gson

data class ServerJobRecoveryRecord(
    val requestId: String,
    val chatId: String,
    val messageId: String,
    val clientRequestId: String,
    val serverBaseUrl: String,
    val payloadJson: String,
    val modelId: String,
    val createdAt: Long = System.currentTimeMillis()
)

class ServerJobRecoveryStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun put(record: ServerJobRecoveryRecord) {
        prefs.edit().putString(KEY_PREFIX + record.requestId, gson.toJson(record)).commit()
    }

    fun get(requestId: String): ServerJobRecoveryRecord? = prefs.getString(KEY_PREFIX + requestId, null)
        ?.let { raw -> runCatching { gson.fromJson(raw, ServerJobRecoveryRecord::class.java) }.getOrNull() }

    fun remove(requestId: String) {
        prefs.edit().remove(KEY_PREFIX + requestId).commit()
    }

    companion object {
        private const val PREFS = "server_job_recovery"
        private const val KEY_PREFIX = "job::"
    }
}
