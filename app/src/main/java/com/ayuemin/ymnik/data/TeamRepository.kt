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
        val current = atomic.read(::validJson)?.let(::decode).orEmpty()
        mergeLegacyMetadata(current)
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

    private fun mergeLegacyMetadata(current: List<Team>): List<Team> {
        val legacyFile = File(root, "projects.json")
        if (!legacyMetadataExists(legacyFile)) return current
        val legacy = runCatching {
            AtomicJsonFile(legacyFile).read(::validJson)?.let(::decode).orEmpty()
        }.getOrNull() ?: return current

        val legacyById = legacy.associateBy(Team::id)
        val currentIds = current.mapTo(hashSetOf(), Team::id)
        val merged = current.map { item ->
            legacyById[item.id]?.takeIf { it.updatedAt > item.updatedAt } ?: item
        } + legacy.filterNot { it.id in currentIds }

        if (merged != current) {
            atomic.write(gson.toJson(merged), ::validJson)
        }
        deleteLegacyMetadataFiles(legacyFile)
        return merged
    }

    private fun decode(json: String): List<Team> = gson.fromJson(json, type)

    private fun legacyMetadataExists(file: File): Boolean = legacyMetadataFiles(file).any(File::exists)

    private fun deleteLegacyMetadataFiles(file: File) {
        legacyMetadataFiles(file).forEach { it.delete() }
    }

    private fun legacyMetadataFiles(file: File): List<File> = listOf(
        file,
        File(file.path + ".bak"),
        File(file.parentFile, "${file.name}.lastgood"),
        File(file.parentFile, "${file.name}.lastgood.bak")
    )

    private fun validJson(json: String): Boolean = runCatching {
        gson.fromJson<List<Team>>(json, type) != null
    }.getOrDefault(false)

    private fun safeId(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
