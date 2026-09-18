package com.ayuemin.ymnik

import com.ayuemin.ymnik.model.AgentKind
import com.ayuemin.ymnik.model.AgentProfile
import com.ayuemin.ymnik.model.ChatFile
import com.ayuemin.ymnik.model.ConnectionProfile
import com.ayuemin.ymnik.model.KnowledgeDocument
import com.ayuemin.ymnik.model.Project
import com.ayuemin.ymnik.model.ProviderType

internal enum class ProjectPreflightSeverity {
    WARNING,
    BLOCKING
}

internal data class ProjectPreflightIssue(
    val severity: ProjectPreflightSeverity,
    val message: String,
    val hint: String = ""
)

internal data class ProjectPreflightReport(
    val fingerprint: String,
    val issues: List<ProjectPreflightIssue>
) {
    val blockers: List<ProjectPreflightIssue>
        get() = issues.filter { it.severity == ProjectPreflightSeverity.BLOCKING }

    val warnings: List<ProjectPreflightIssue>
        get() = issues.filter { it.severity == ProjectPreflightSeverity.WARNING }

    val ready: Boolean
        get() = blockers.isEmpty()

    fun blockedMessage(): String = buildString {
        appendLine("⛔ Проект пока не готов к запуску")
        appendLine()
        blockers.forEach { issue ->
            appendLine("• " + issue.message)
            if (issue.hint.isNotBlank()) appendLine("  " + issue.hint)
        }
        if (warnings.isNotEmpty()) {
            appendLine()
            appendLine("Дополнительно:")
            warnings.take(4).forEach { appendLine("• " + it.message) }
        }
        appendLine()
        append("Исправьте настройки и повторите поручение. Уже введённый текст останется в чате.")
    }

    fun readyNotice(): String =
        if (warnings.isEmpty()) {
            "✓ Диагностика проекта пройдена"
        } else {
            "✓ Диагностика проекта пройдена · замечаний: " + warnings.size
        }
}

/**
 * Fast local validation before the first run of an agent project (and again after its
 * configuration changes). It intentionally does not call an LLM or the network.
 */
internal object ProjectPreflight {
    fun inspect(
        project: Project,
        orchestrator: AgentProfile,
        specialists: List<AgentProfile>,
        profiles: List<ConnectionProfile>,
        disabledConnectionIds: Set<String>,
        hasApiKey: (String) -> Boolean,
        filesForAgent: (String) -> List<ChatFile>,
        skillIdsForAgent: (String) -> Set<String>,
        knowledgeForAgent: (String) -> List<KnowledgeDocument>,
        fileExists: (String) -> Boolean
    ): ProjectPreflightReport {
        val issues = mutableListOf<ProjectPreflightIssue>()
        val allAgents = listOf(orchestrator) + specialists

        fun blocking(message: String, hint: String = "") {
            issues += ProjectPreflightIssue(ProjectPreflightSeverity.BLOCKING, message, hint)
        }

        fun warning(message: String) {
            issues += ProjectPreflightIssue(ProjectPreflightSeverity.WARNING, message)
        }

        fun validateModel(agent: AgentProfile) {
            val label = if (agent.kind == AgentKind.ORCHESTRATOR) "Оркестратор" else "Агент «" + agent.name + "»"
            val ref = agent.primaryModel
            if (ref == null || ref.modelId.isBlank()) {
                blocking(
                    "$label: не выбрана основная модель.",
                    if (agent.kind == AgentKind.ORCHESTRATOR) {
                        "Откройте Оркестратор → Настройки → Основная модель."
                    } else {
                        "Откройте ${agent.name} → Настройки → Основная модель."
                    }
                )
                return
            }

            val profile = profiles.firstOrNull { it.id == ref.connectionProfileId }
            if (profile == null) {
                blocking("$label: подключение «${ref.connectionProfileId}» не найдено.")
                return
            }
            if (profile.id in disabledConnectionIds) {
                blocking("$label: подключение «${profile.name}» отключено.")
            }
            if (profile.type != ProviderType.OPENROUTER) {
                blocking("$label: текущее агентное выполнение поддерживает только OpenRouter.")
            }
            if (!hasApiKey(profile.id)) {
                blocking(
                    "$label: для подключения «${profile.name}» не сохранён API-ключ.",
                    "Откройте Настройки → Подключения и сохраните ключ."
                )
            }
        }

        if (orchestrator.projectId != project.id) {
            blocking("Оркестратор не принадлежит текущему проекту.")
        }
        validateModel(orchestrator)

        if (specialists.isEmpty()) {
            blocking(
                "В проекте нет ни одного специалиста.",
                "Добавьте хотя бы одного агента-специалиста."
            )
        }

        specialists.forEach { agent ->
            validateModel(agent)
            if (agent.instruction.isBlank()) {
                warning("У агента «${agent.name}» не заполнена личная инструкция.")
            }
        }

        allAgents.forEach { agent ->
            val existingSkills = skillIdsForAgent(agent.id)
            val missingSkills = agent.skillIds - existingSkills
            if (missingSkills.isNotEmpty()) {
                warning("У «${agent.name}» не найдены активные навыки: ${missingSkills.size}.")
            }

            val missingFiles = filesForAgent(agent.id).filterNot { fileExists(it.localPath) }
            if (missingFiles.isNotEmpty()) {
                warning(
                    "У «${agent.name}» недоступны постоянные файлы: " +
                        missingFiles.take(3).joinToString { it.name } +
                        if (missingFiles.size > 3) " и ещё ${missingFiles.size - 3}" else ""
                )
            }

            val missingKnowledge = knowledgeForAgent(agent.id).filterNot { fileExists(it.localPath) }
            if (missingKnowledge.isNotEmpty()) {
                warning(
                    "У «${agent.name}» недоступны исходники базы знаний: " +
                        missingKnowledge.take(3).joinToString { it.name }
                )
            }
        }

        val fingerprintParts = buildList {
            add(project.id)
            add(project.updatedAt.toString())
            allAgents.sortedBy { it.id }.forEach { agent ->
                add(agent.id)
                add(agent.updatedAt.toString())
                add(agent.primaryModel?.connectionProfileId.orEmpty())
                add(agent.primaryModel?.modelId.orEmpty())
                add(agent.skillIds.sorted().joinToString(","))
                filesForAgent(agent.id).sortedBy { it.id }.forEach { file ->
                    add("f:${file.id}:${file.size}:${fileExists(file.localPath)}")
                }
                knowledgeForAgent(agent.id).sortedBy { it.id }.forEach { doc ->
                    add("k:${doc.id}:${doc.chunkCount}:${fileExists(doc.localPath)}")
                }
            }
            profiles.sortedBy { it.id }.forEach { profile ->
                add("p:${profile.id}:${profile.type}:${profile.baseUrl}:${profile.id in disabledConnectionIds}:${hasApiKey(profile.id)}")
            }
        }.joinToString("|")

        val fingerprint = fingerprintParts.hashCode().toUInt().toString(16)
        return ProjectPreflightReport(
            fingerprint = fingerprint,
            issues = issues.distinctBy { Triple(it.severity, it.message, it.hint) }
        )
    }
}
