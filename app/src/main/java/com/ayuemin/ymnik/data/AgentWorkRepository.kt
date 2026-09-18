package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.JobWorkspace
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** Durable local journal for orchestrated project jobs. */
class AgentWorkRepository(context: Context) {
    private val gson = Gson()
    private val root = File(context.filesDir, "agent_workspaces").apply { mkdirs() }
    private val metadata = File(root, "workspaces.json")
    private val atomic = AtomicJsonFile(metadata)
    private val type = object : TypeToken<List<JobWorkspace>>() {}.type

    var loadError: String? = null
        private set

    fun list(): List<JobWorkspace> = runCatching {
        atomic.read(::validJson)?.let { gson.fromJson<List<JobWorkspace>>(it, type) } ?: emptyList()
    }.onFailure {
        loadError = "Журнал работы агентов повреждён и защищён от перезаписи."
    }.getOrDefault(emptyList())

    fun get(id: String): JobWorkspace? = list().firstOrNull { it.id == id }

    fun latestForProject(projectId: String): JobWorkspace? =
        list().filter { it.projectId == projectId }.maxByOrNull { it.updatedAt }

    fun upsert(workspace: JobWorkspace): JobWorkspace {
        check(loadError == null) { loadError ?: "Журнал работы агентов недоступен" }
        val clean = workspace.copy(updatedAt = System.currentTimeMillis())
        val current = list()
        save(
            if (current.any { it.id == clean.id }) {
                current.map { if (it.id == clean.id) clean else it }
            } else {
                listOf(clean) + current
            }
        )
        return clean
    }

    fun deleteProject(projectId: String) {
        check(loadError == null) { loadError ?: "Журнал работы агентов недоступен" }
        save(list().filterNot { it.projectId == projectId })
    }

    private fun save(items: List<JobWorkspace>) {
        root.mkdirs()
        atomic.write(gson.toJson(items), ::validJson)
    }

    private fun validJson(json: String): Boolean = runCatching {
        gson.fromJson<List<JobWorkspace>>(json, type) != null
    }.getOrDefault(false)
}
