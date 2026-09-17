package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.AgentKind
import com.ayuemin.ymnik.model.AgentProfile
import com.ayuemin.ymnik.model.ReasoningEffort
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Persistent store for the new agent-first project architecture.
 *
 * This repository intentionally does not read ChatSession settings and never clones
 * another agent implicitly. A new agent starts with neutral settings supplied by the
 * caller (connection/model may be provided only as technical defaults).
 */
class AgentRepository(private val context: Context) {
    private val gson = Gson()
    private val root = File(context.filesDir, "agents").apply { mkdirs() }
    private val metadata = File(root, "agents.json")
    private val atomic = AtomicJsonFile(metadata)
    private val type = object : TypeToken<List<AgentProfile>>() {}.type

    var loadError: String? = null
        private set

    fun list(): List<AgentProfile> = runCatching {
        atomic.read(::validJson)?.let { gson.fromJson<List<AgentProfile>>(it, type) } ?: emptyList()
    }.onFailure {
        loadError = "Данные агентов повреждены и защищены от перезаписи."
    }.getOrDefault(emptyList())

    fun listForProject(projectId: String): List<AgentProfile> =
        list().filter { it.projectId == projectId }

    fun get(agentId: String): AgentProfile? = list().firstOrNull { it.id == agentId }

    fun createSpecialist(
        projectId: String,
        name: String = "Новый агент",
        connectionProfileId: String? = null,
        modelId: String? = null
    ): AgentProfile = create(
        projectId = projectId,
        kind = AgentKind.SPECIALIST,
        name = name,
        connectionProfileId = connectionProfileId,
        modelId = modelId
    )

    fun createOrchestrator(
        projectId: String,
        name: String = "Оркестратор",
        connectionProfileId: String? = null,
        modelId: String? = null
    ): AgentProfile = create(
        projectId = projectId,
        kind = AgentKind.ORCHESTRATOR,
        name = name,
        connectionProfileId = connectionProfileId,
        modelId = modelId
    )

    private fun create(
        projectId: String,
        kind: AgentKind,
        name: String,
        connectionProfileId: String?,
        modelId: String?
    ): AgentProfile {
        check(loadError == null) { loadError ?: "Хранилище агентов недоступно" }
        require(projectId.isNotBlank()) { "projectId обязателен" }

        val now = System.currentTimeMillis()
        val agent = AgentProfile(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            kind = kind,
            name = name.trim().ifBlank { if (kind == AgentKind.ORCHESTRATOR) "Оркестратор" else "Новый агент" },
            connectionProfileId = connectionProfileId?.trim()?.takeIf { it.isNotBlank() },
            modelId = modelId?.trim()?.takeIf { it.isNotBlank() },
            reasoningEnabled = false,
            reasoningEffort = ReasoningEffort.MEDIUM,
            webSearchEnabled = false,
            skillIds = emptySet(),
            createdAt = now,
            updatedAt = now
        )
        save(list() + agent)
        agentRoot(agent.id).mkdirs()
        return agent
    }

    fun upsert(profile: AgentProfile): AgentProfile {
        check(loadError == null) { loadError ?: "Хранилище агентов недоступно" }
        val clean = profile.copy(
            name = profile.name.trim().ifBlank {
                if (profile.kind == AgentKind.ORCHESTRATOR) "Оркестратор" else "Агент"
            },
            role = profile.role.trim(),
            instruction = profile.instruction.trim(),
            connectionProfileId = profile.connectionProfileId?.trim()?.takeIf { it.isNotBlank() },
            modelId = profile.modelId?.trim()?.takeIf { it.isNotBlank() },
            updatedAt = System.currentTimeMillis()
        )
        val current = list()
        val next = if (current.any { it.id == clean.id }) {
            current.map { if (it.id == clean.id) clean else it }
        } else {
            current + clean
        }
        save(next)
        agentRoot(clean.id).mkdirs()
        return clean
    }

    fun delete(agentId: String) {
        check(loadError == null) { loadError ?: "Хранилище агентов недоступно" }
        save(list().filterNot { it.id == agentId })
        agentRoot(agentId).deleteRecursively()
    }

    fun deleteProjectAgents(projectId: String) {
        val ids = listForProject(projectId).map { it.id }.toSet()
        if (ids.isEmpty()) return
        save(list().filterNot { it.id in ids })
        ids.forEach { agentRoot(it).deleteRecursively() }
    }

    fun agentRoot(agentId: String): File = File(root, safeId(agentId))

    private fun save(agents: List<AgentProfile>) {
        check(loadError == null) { loadError ?: "Хранилище агентов недоступно" }
        root.mkdirs()
        atomic.write(gson.toJson(agents), ::validJson)
    }

    private fun validJson(json: String): Boolean = runCatching {
        gson.fromJson<List<AgentProfile>>(json, type) != null
    }.getOrDefault(false)

    private fun safeId(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
