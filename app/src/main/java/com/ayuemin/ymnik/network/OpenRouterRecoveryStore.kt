package com.ayuemin.ymnik.network

import android.content.Context
import com.google.gson.Gson
import java.io.File

internal data class OpenRouterRecoveryRecord(
    val requestId: String,
    val chatId: String,
    val messageId: String,
    val connectionProfileId: String,
    val apiKeyFingerprint: String,
    val baseUrl: String,
    val modelId: String,
    val generationId: String? = null,
    val cacheStatus: String? = null,
    val generationSeenAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

internal class OpenRouterRecoveryStore(context: Context) {
    private val gson = Gson()
    private val root = File(context.applicationContext.filesDir, "openrouter_recovery").apply { mkdirs() }

    fun get(requestId: String): OpenRouterRecoveryRecord? = synchronized(lock) {
        val file = fileFor(requestId)
        if (!file.exists()) return@synchronized null
        runCatching { gson.fromJson(file.readText(), OpenRouterRecoveryRecord::class.java) }.getOrNull()
    }

    fun put(record: OpenRouterRecoveryRecord) = synchronized(lock) {
        writeAtomic(record.copy(updatedAt = System.currentTimeMillis()))
    }

    fun updateGeneration(requestId: String, generationId: String, cacheStatus: String?) = synchronized(lock) {
        val current = get(requestId) ?: return@synchronized
        val now = System.currentTimeMillis()
        writeAtomic(
            current.copy(
                generationId = generationId,
                cacheStatus = cacheStatus,
                generationSeenAt = current.generationSeenAt ?: now,
                updatedAt = now
            )
        )
    }


    fun remove(requestId: String) = synchronized(lock) {
        val file = fileFor(requestId)
        if (file.exists()) file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    fun list(): List<OpenRouterRecoveryRecord> = synchronized(lock) {
        root.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .mapNotNull { file -> runCatching { gson.fromJson(file.readText(), OpenRouterRecoveryRecord::class.java) }.getOrNull() }
    }

    private fun writeAtomic(record: OpenRouterRecoveryRecord) {
        root.mkdirs()
        val target = fileFor(record.requestId)
        val temp = File(root, target.name + ".tmp")
        temp.writeText(gson.toJson(record))
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun fileFor(requestId: String): File {
        val safe = requestId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        return File(root, "$safe.json")
    }

    private companion object {
        val lock = Any()
    }
}
