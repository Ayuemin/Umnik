package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.Team
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** Team metadata. A team is only a room that owns isolated specialists. */
class TeamRepository(context: Context) {
    private val gson = Gson()
    private val root = LegacyDomainStorageMigration.migrateDirectory(context, "projects", "teams")
    private val metadata = LegacyDomainStorageMigration.migrateFile(root, "projects.json", "teams.json")
    private val atomic = AtomicJsonFile(metadata)
    private val type = object : TypeToken<List<Team>>() {}.type

    var loadError: String? = null
        private set

    fun list(): List<Team> = runCatching {
        atomic.read(::validJson)?.let { gson.fromJson<List<Team>>(it, type) } ?: emptyList()
    }.onSuccess {
        loadError = null
    }.onFailure {
        loadError = "Данные команд повреждены и защищены от перезаписи."
    }.getOrDefault(emptyList())

    fun save(teams: List<Team>) {
        check(loadError == null) { loadError ?: "Хранилище команд недоступно" }
        root.mkdirs()
        atomic.write(gson.toJson(teams), ::validJson)
    }

    /** Removes files left by pre-specialist team versions during team deletion. */
    fun deleteLegacyTeamFiles(teamId: String) {
        File(root, safeId(teamId)).deleteRecursively()
    }

    private fun validJson(json: String): Boolean = runCatching {
        gson.fromJson<List<Team>>(json, type) != null
    }.getOrDefault(false)

    private fun safeId(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
