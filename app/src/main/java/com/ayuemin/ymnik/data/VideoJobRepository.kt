package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.VideoJob
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class VideoJobRepository(context: Context) {
    private companion object { val fileLock = Any() }

    private val gson = Gson()
    private val root = File(context.filesDir, "jobs").apply { mkdirs() }
    private val metadata = File(root, "videos.json")
    private val atomic = AtomicJsonFile(metadata)
    private val type = object : TypeToken<List<VideoJob>>() {}.type

    var loadError: String? = null
        private set

    @Synchronized
    fun list(): List<VideoJob> = synchronized(fileLock) {
        runCatching {
            atomic.read(::validJson)?.let { gson.fromJson<List<VideoJob>>(it, type) } ?: emptyList()
        }.onFailure {
            loadError = "Задания видео повреждены. Исходные файлы сохранены; Umnik не будет их перезаписывать."
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(jobs: List<VideoJob>) = synchronized(fileLock) {
        check(loadError == null) { loadError ?: "Хранилище заданий видео недоступно" }
        root.mkdirs()
        atomic.write(gson.toJson(jobs.sortedByDescending { it.updatedAt }), ::validJson)
    }

    @Synchronized
    fun upsert(job: VideoJob): List<VideoJob> = synchronized(fileLock) {
        val jobs = list().toMutableList()
        val index = jobs.indexOfFirst { it.id == job.id || it.remoteId == job.remoteId }
        if (index >= 0) jobs[index] = job else jobs += job
        save(jobs)
        jobs.sortedByDescending { it.updatedAt }
    }

    @Synchronized
    fun remove(id: String): List<VideoJob> = synchronized(fileLock) {
        val jobs = list().filterNot { it.id == id }
        save(jobs)
        jobs
    }

    fun active(): List<VideoJob> = list().filterNot { it.status.terminal }

    private fun validJson(json: String): Boolean = runCatching {
        gson.fromJson<List<VideoJob>>(json, type) != null
    }.getOrDefault(false)
}
