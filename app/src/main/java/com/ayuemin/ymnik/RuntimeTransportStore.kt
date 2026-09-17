package com.ayuemin.ymnik

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.io.RandomAccessFile

internal data class RuntimeTransportRecord(
    val transportId: String,
    val requestId: String,
    val profileId: String,
    val baseUrl: String,
    val phase: String = "queued",
    val payloadJson: String,
    val allowEmpty: Boolean = false,
    val partialText: String = "",
    val completionJson: String? = null,
    val generationId: String? = null,
    val cacheStatus: String? = null,
    val httpCode: Int? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** Thread-safe and process-safe mailbox shared by the UI process and private :runtime process. */
internal class RuntimeTransportStore(context: Context) {
    private val gson = Gson()
    private val root = File(context.applicationContext.filesDir, "runtime_transport").apply { mkdirs() }
    private val lockFile = File(root, ".lock")

    fun get(transportId: String): RuntimeTransportRecord? = withFileLock { readUnlocked(transportId) }

    fun put(record: RuntimeTransportRecord) = withFileLock {
        writeAtomicUnlocked(record.copy(updatedAt = System.currentTimeMillis()))
    }

    fun update(
        transportId: String,
        transform: (RuntimeTransportRecord) -> RuntimeTransportRecord
    ): RuntimeTransportRecord? = withFileLock {
        val current = readUnlocked(transportId) ?: return@withFileLock null
        val updated = transform(current).copy(updatedAt = System.currentTimeMillis())
        writeAtomicUnlocked(updated)
        updated
    }

    fun remove(transportId: String) = withFileLock {
        val target = fileFor(transportId)
        if (target.exists()) target.delete()
        File(root, target.name + ".tmp").delete()
    }

    private fun readUnlocked(transportId: String): RuntimeTransportRecord? {
        val target = fileFor(transportId)
        if (!target.exists()) return null
        return runCatching { gson.fromJson(target.readText(), RuntimeTransportRecord::class.java) }.getOrNull()
    }

    private fun writeAtomicUnlocked(record: RuntimeTransportRecord) {
        root.mkdirs()
        val target = fileFor(record.transportId)
        val temp = File(root, target.name + ".tmp")
        temp.writeText(gson.toJson(record))
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun fileFor(transportId: String): File {
        val safe = transportId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180)
        return File(root, "$safe.json")
    }

    private inline fun <T> withFileLock(block: () -> T): T = synchronized(PROCESS_LOCK) {
        root.mkdirs()
        RandomAccessFile(lockFile, "rw").use { raf ->
            raf.channel.use { channel ->
                channel.lock().use { return@synchronized block() }
            }
        }
    }

    companion object {
        /**
         * FileChannel locks coordinate the UI and :runtime processes, but Java throws
         * OverlappingFileLockException when two threads in the same JVM try to acquire the same
         * region concurrently. A process-local monitor closes that gap for all store instances.
         */
        private val PROCESS_LOCK = Any()
    }
}
