package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.BatchJob
import com.ayuemin.ymnik.model.BatchJobStatus
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Durable storage for asynchronous provider jobs.
 *
 * A batch id is persisted as soon as OpenRouter accepts the request. From that
 * point Umnik may poll the remote job repeatedly, but must never recreate the
 * paid batch implicitly after an app/process restart.
 */
class BatchJobRepository(context: Context) {
    private companion object { val fileLock = Any() }

    private val gson = Gson()
    private val root = File(context.filesDir, "jobs").apply { mkdirs() }
    private val metadata = File(root, "batches.json")
    private val atomic = AtomicJsonFile(metadata)
    private val type = object : TypeToken<List<BatchJob>>() {}.type

    var loadError: String? = null
        private set

    @Synchronized
    fun list(): List<BatchJob> = synchronized(fileLock) {
        runCatching {
            atomic.read(::validJson)?.let { gson.fromJson<List<BatchJob>>(it, type) } ?: emptyList()
        }.onFailure {
            loadError = "Пакетные задания повреждены. Исходные файлы сохранены; Umnik не будет их перезаписывать."
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(jobs: List<BatchJob>) = synchronized(fileLock) {
        check(loadError == null) { loadError ?: "Хранилище пакетных заданий недоступно" }
        root.mkdirs()
        atomic.write(gson.toJson(jobs.sortedByDescending { it.updatedAt }), ::validJson)
    }

    @Synchronized
    fun upsert(job: BatchJob): List<BatchJob> = synchronized(fileLock) {
        val jobs = list().toMutableList()
        val index = jobs.indexOfFirst { it.id == job.id || it.remoteId == job.remoteId }
        if (index >= 0) jobs[index] = job else jobs += job
        save(jobs)
        jobs.sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun remove(id: String): List<BatchJob> = synchronized(fileLock) {
        val jobs = list().filterNot { it.id == id }
        save(jobs)
        jobs
    }

    fun active(): List<BatchJob> = list().filterNot { it.status.terminal }

    fun completed(): List<BatchJob> = list().filter { it.status == BatchJobStatus.COMPLETED }

    fun dataSize(): Long = if (metadata.exists()) metadata.length() else 0L

    private fun validJson(json: String): Boolean = runCatching {
        gson.fromJson<List<BatchJob>>(json, type) != null
    }.getOrDefault(false)
}
