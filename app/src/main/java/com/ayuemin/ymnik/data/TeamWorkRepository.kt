package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.JobWorkspace
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** Durable local journal for orchestrated team jobs. */
class TeamWorkRepository(context: Context) {
    private val gson = Gson()
    private val root = LegacyDomainStorageMigration.migrateDirectory(context, "agent_workspaces", "team_workspaces")
    private val metadata = File(root, "workspaces.json")
    private val atomic = AtomicJsonFile(metadata)
    private val type = object : TypeToken<List<JobWorkspace>>() {}.type

    var loadError: String? = null
        private set

    fun list(): List<JobWorkspace> = runCatching {
        atomic.read(::validJson)?.let { gson.fromJson<List<JobWorkspace>>(it, type) } ?: emptyList()
    }.onFailure {
        loadError = "Журнал работы специалистов повреждён и защищён от перезаписи."
    }.getOrDefault(emptyList())

    fun get(id: String): JobWorkspace? = list().firstOrNull { it.id == id }

    fun latestForTeam(teamId: String): JobWorkspace? =
        list().filter { it.teamId == teamId }.maxByOrNull { it.updatedAt }

    fun upsert(workspace: JobWorkspace): JobWorkspace {
        check(loadError == null) { loadError ?: "Журнал работы специалистов недоступен" }
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

    fun deleteTeam(teamId: String) {
        check(loadError == null) { loadError ?: "Журнал работы специалистов недоступен" }
        save(list().filterNot { it.teamId == teamId })
    }

    private fun save(items: List<JobWorkspace>) {
        root.mkdirs()
        atomic.write(gson.toJson(items), ::validJson)
    }

    private fun validJson(json: String): Boolean = runCatching {
        gson.fromJson<List<JobWorkspace>>(json, type) != null
    }.getOrDefault(false)
}
