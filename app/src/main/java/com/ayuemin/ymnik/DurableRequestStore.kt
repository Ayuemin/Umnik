package com.ayuemin.ymnik

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.io.RandomAccessFile

/**
 * Durable, process-safe journal for top-level user requests.
 *
 * This store is intentionally independent from the UI process. A later runtime process can
 * update the same records without relying on in-memory singletons or SharedPreferences.
 */
internal data class DurableRequestRecord(
    val requestId: String,
    val chatId: String,
    val messageId: String,
    val phase: String = "registered",
    val label: String = "Модель работает…",
    val partialText: String = "",
    val lastError: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

internal class DurableRequestStore(context: Context) {
    private val gson = Gson()
    private val root = File(context.applicationContext.filesDir, "request_runtime").apply { mkdirs() }
    private val lockFile = File(root, ".lock")

    fun get(requestId: String): DurableRequestRecord? = withFileLock {
        readUnlocked(requestId)
    }

    fun list(): List<DurableRequestRecord> = withFileLock {
        root.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .mapNotNull { file -> runCatching { gson.fromJson(file.readText(), DurableRequestRecord::class.java) }.getOrNull() }
            .sortedBy { it.startedAt }
    }

    fun put(record: DurableRequestRecord) = withFileLock {
        writeAtomicUnlocked(record.copy(updatedAt = System.currentTimeMillis()))
    }

    fun update(
        requestId: String,
        transform: (DurableRequestRecord) -> DurableRequestRecord
    ): DurableRequestRecord? = withFileLock {
        val current = readUnlocked(requestId) ?: return@withFileLock null
        val updated = transform(current).copy(updatedAt = System.currentTimeMillis())
        writeAtomicUnlocked(updated)
        updated
    }

    fun remove(requestId: String) = withFileLock {
        val target = fileFor(requestId)
        if (target.exists()) target.delete()
        File(root, target.name + ".tmp").delete()
    }

    private fun readUnlocked(requestId: String): DurableRequestRecord? {
        val file = fileFor(requestId)
        if (!file.exists()) return null
        return runCatching { gson.fromJson(file.readText(), DurableRequestRecord::class.java) }.getOrNull()
    }

    private fun writeAtomicUnlocked(record: DurableRequestRecord) {
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

    private inline fun <T> withFileLock(block: () -> T): T {
        root.mkdirs()
        RandomAccessFile(lockFile, "rw").use { raf ->
            raf.channel.use { channel ->
                channel.lock().use {
                    return block()
                }
            }
        }
    }
}
