package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.Project
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** Project metadata. A project is only a room that owns isolated agents. */
class ProjectRepository(context: Context) {
    private val gson = Gson()
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val metadata = File(root, "projects.json")
    private val atomic = AtomicJsonFile(metadata)
    private val type = object : TypeToken<List<Project>>() {}.type

    var loadError: String? = null
        private set

    fun list(): List<Project> = runCatching {
        atomic.read(::validJson)?.let { gson.fromJson<List<Project>>(it, type) } ?: emptyList()
    }.onSuccess {
        loadError = null
    }.onFailure {
        loadError = "Данные проектов повреждены и защищены от перезаписи."
    }.getOrDefault(emptyList())

    fun save(projects: List<Project>) {
        check(loadError == null) { loadError ?: "Хранилище проектов недоступно" }
        root.mkdirs()
        atomic.write(gson.toJson(projects), ::validJson)
    }

    /** Removes files left by pre-agent project versions during project deletion. */
    fun deleteLegacyProjectFiles(projectId: String) {
        File(root, safeId(projectId)).deleteRecursively()
    }

    private fun validJson(json: String): Boolean = runCatching {
        gson.fromJson<List<Project>>(json, type) != null
    }.getOrDefault(false)

    private fun safeId(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
