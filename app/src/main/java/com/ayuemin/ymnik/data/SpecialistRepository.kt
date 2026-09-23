package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.SpecialistKind
import com.ayuemin.ymnik.model.SpecialistProfile
import com.ayuemin.ymnik.model.ReasoningEffort
import com.ayuemin.ymnik.model.ServerToolSettings
import com.ayuemin.ymnik.model.normalized
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Persistent store for isolated team specialists.
 *
 * Ordinary-chat defaults never enter this repository. A newly created specialist is bare:
 * its model slots, skills, knowledge base and memory are empty until configured from
 * that specialist's own settings.
 */
class SpecialistRepository(private val context: Context) {
    private val gson = Gson()
    private val root = LegacyDomainStorageMigration.migrateDirectory(context, "agents", "specialists")
    private val metadata = LegacyDomainStorageMigration.migrateFile(root, "agents.json", "specialists.json")
    private val atomic = AtomicJsonFile(metadata)
    private val type = object : TypeToken<List<SpecialistProfile>>() {}.type

    var loadError: String? = null
        private set

    fun list(): List<SpecialistProfile> = runCatching {
        val stored = atomic.read(::validJson)?.let { gson.fromJson<List<SpecialistProfile>>(it, type) } ?: emptyList()
        stored.map { profile ->
            val tools = runCatching { profile.tools }.getOrNull()?.normalized() ?: ServerToolSettings()
            profile.copy(tools = tools)
        }
    }.onFailure {
        loadError = "Данные специалистов повреждены и защищены от перезаписи."
    }.getOrDefault(emptyList())

    fun listForTeam(teamId: String): List<SpecialistProfile> =
        list().filter { it.teamId == teamId }

    fun get(specialistId: String): SpecialistProfile? = list().firstOrNull { it.id == specialistId }

    fun createSpecialist(
        teamId: String,
        name: String = "Новый специалист"
    ): SpecialistProfile = create(
        teamId = teamId,
        kind = SpecialistKind.SPECIALIST,
        name = name
    )

    fun createOrchestrator(
        teamId: String,
        name: String = "Оркестратор"
    ): SpecialistProfile = create(
        teamId = teamId,
        kind = SpecialistKind.ORCHESTRATOR,
        name = name
    )

    private fun create(
        teamId: String,
        kind: SpecialistKind,
        name: String
    ): SpecialistProfile {
        check(loadError == null) { loadError ?: "Хранилище специалистов недоступно" }
        require(teamId.isNotBlank()) { "teamId обязателен" }

        val now = System.currentTimeMillis()
        val specialist = SpecialistProfile(
            id = UUID.randomUUID().toString(),
            teamId = teamId,
            kind = kind,
            name = name.trim().ifBlank {
                if (kind == SpecialistKind.ORCHESTRATOR) "Оркестратор" else "Новый специалист"
            },
            reasoningEnabled = false,
            reasoningEffort = ReasoningEffort.MEDIUM,
            webSearchEnabled = false,
            skillIds = emptySet(),
            createdAt = now,
            updatedAt = now
        )
        save(list() + specialist)
        ensureSpecialistLayout(specialist.id)
        return specialist
    }

    fun upsert(profile: SpecialistProfile): SpecialistProfile {
        check(loadError == null) { loadError ?: "Хранилище специалистов недоступно" }
        val clean = profile.copy(
            name = profile.name.trim().ifBlank {
                if (profile.kind == SpecialistKind.ORCHESTRATOR) "Оркестратор" else "Специалист"
            },
            role = profile.role.trim(),
            instruction = profile.instruction.trim(),
            updatedAt = System.currentTimeMillis()
        )
        val current = list()
        val next = if (current.any { it.id == clean.id }) {
            current.map { if (it.id == clean.id) clean else it }
        } else {
            current + clean
        }
        save(next)
        ensureSpecialistLayout(clean.id)
        return clean
    }

    fun delete(specialistId: String) {
        check(loadError == null) { loadError ?: "Хранилище специалистов недоступно" }
        save(list().filterNot { it.id == specialistId })
        specialistRoot(specialistId).deleteRecursively()
    }

    fun deleteTeamSpecialists(teamId: String) {
        val ids = listForTeam(teamId).map { it.id }.toSet()
        if (ids.isEmpty()) return
        save(list().filterNot { it.id in ids })
        ids.forEach { specialistRoot(it).deleteRecursively() }
    }

    fun specialistRoot(specialistId: String): File = File(root, safeId(specialistId))

    fun conversationsRoot(specialistId: String): File = File(specialistRoot(specialistId), "conversations")
    fun skillsRoot(specialistId: String): File = File(specialistRoot(specialistId), "skills")
    fun filesRoot(specialistId: String): File = File(specialistRoot(specialistId), "files")
    fun knowledgeRoot(specialistId: String): File = File(specialistRoot(specialistId), "knowledge")
    fun memoryRoot(specialistId: String): File = File(specialistRoot(specialistId), "memory")
    fun generatedRoot(specialistId: String): File = File(specialistRoot(specialistId), "generated")

    private fun ensureSpecialistLayout(specialistId: String) {
        specialistRoot(specialistId).mkdirs()
        conversationsRoot(specialistId).mkdirs()
        skillsRoot(specialistId).mkdirs()
        filesRoot(specialistId).mkdirs()
        knowledgeRoot(specialistId).mkdirs()
        memoryRoot(specialistId).mkdirs()
        generatedRoot(specialistId).mkdirs()
    }

    private fun save(specialists: List<SpecialistProfile>) {
        check(loadError == null) { loadError ?: "Хранилище специалистов недоступно" }
        root.mkdirs()
        atomic.write(gson.toJson(specialists), ::validJson)
    }

    private fun validJson(json: String): Boolean = runCatching {
        gson.fromJson<List<SpecialistProfile>>(json, type) != null
    }.getOrDefault(false)

    private fun safeId(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
