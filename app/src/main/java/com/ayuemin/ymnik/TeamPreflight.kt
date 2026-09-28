package com.ayuemin.ymnik

import com.ayuemin.ymnik.model.SpecialistKind
import com.ayuemin.ymnik.model.SpecialistProfile
import com.ayuemin.ymnik.model.ChatFile
import com.ayuemin.ymnik.model.ConnectionProfile
import com.ayuemin.ymnik.model.KnowledgeDocument
import com.ayuemin.ymnik.model.Team
import com.ayuemin.ymnik.model.ProviderType

internal enum class TeamPreflightSeverity {
    WARNING,
    BLOCKING
}

internal data class TeamPreflightIssue(
    val severity: TeamPreflightSeverity,
    val message: String,
    val hint: String = ""
)

internal data class TeamPreflightReport(
    val fingerprint: String,
    val issues: List<TeamPreflightIssue>
) {
    val blockers: List<TeamPreflightIssue>
        get() = issues.filter { it.severity == TeamPreflightSeverity.BLOCKING }

    val warnings: List<TeamPreflightIssue>
        get() = issues.filter { it.severity == TeamPreflightSeverity.WARNING }

    val ready: Boolean
        get() = blockers.isEmpty()

    fun blockedMessage(): String = buildString {
        appendLine("⛔ Команда пока не готова к запуску")
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
            "✓ Диагностика команды пройдена"
        } else {
            "✓ Диагностика команды пройдена · замечаний: " + warnings.size
        }
}

/**
 * Fast local validation before the first run of a specialist team (and again after its
 * configuration changes). It intentionally does not call an LLM or the network.
 */
internal object TeamPreflight {
    fun inspect(
        team: Team,
        orchestrator: SpecialistProfile,
        specialists: List<SpecialistProfile>,
        profiles: List<ConnectionProfile>,
        disabledConnectionIds: Set<String>,
        hasApiKey: (String) -> Boolean,
        systemModelId: String,
        filesForSpecialist: (String) -> List<ChatFile>,
        skillIdsForSpecialist: (String) -> Set<String>,
        knowledgeForSpecialist: (String) -> List<KnowledgeDocument>,
        knowledgeEnabledForSpecialist: (String) -> Boolean,
        fileExists: (String) -> Boolean
    ): TeamPreflightReport {
        val issues = mutableListOf<TeamPreflightIssue>()
        val allSpecialists = listOf(orchestrator) + specialists

        fun blocking(message: String, hint: String = "") {
            issues += TeamPreflightIssue(TeamPreflightSeverity.BLOCKING, message, hint)
        }

        fun warning(message: String) {
            issues += TeamPreflightIssue(TeamPreflightSeverity.WARNING, message)
        }

        fun validateModel(specialist: SpecialistProfile) {
            val label = if (specialist.kind == SpecialistKind.ORCHESTRATOR) "Оркестратор" else "Специалист «" + specialist.name + "»"
            val ref = specialist.primaryModel
            if (ref == null || ref.modelId.isBlank()) {
                blocking(
                    "$label: не выбрана основная модель.",
                    if (specialist.kind == SpecialistKind.ORCHESTRATOR) {
                        "Откройте Оркестратор → Настройки → Основная модель."
                    } else {
                        "Откройте ${specialist.name} → Настройки → Основная модель."
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
                blocking("$label: текущее выполнение команды поддерживает только OpenRouter.")
            }
            if (!hasApiKey(profile.id)) {
                blocking(
                    "$label: для подключения «${profile.name}» не сохранён API-ключ.",
                    "Откройте Настройки → Подключения и сохраните ключ."
                )
            }
        }

        if (orchestrator.teamId != team.id) {
            blocking("Оркестратор не принадлежит текущей команде.")
        }
        validateModel(orchestrator)

        if (specialists.isEmpty()) {
            blocking(
                "В команде нет ни одного специалиста.",
                "Добавьте хотя бы одного специалиста."
            )
        }

        specialists.forEach { specialist ->
            validateModel(specialist)
            if (specialist.instruction.isBlank()) {
                warning("У специалиста «${specialist.name}» не заполнена личная инструкция.")
            }
        }

        val specialistsUsingKnowledge = allSpecialists.filter { specialist ->
            knowledgeEnabledForSpecialist(specialist.id) && knowledgeForSpecialist(specialist.id).isNotEmpty()
        }
        if (specialistsUsingKnowledge.isNotEmpty() && systemModelId.isBlank()) {
            blocking(
                "Для баз знаний специалистов не выбрана системная модель.",
                "Откройте Настройки → Модели → Системная модель."
            )
        }

        allSpecialists.forEach { specialist ->
            val existingSkills = skillIdsForSpecialist(specialist.id)
            val missingSkills = specialist.skillIds - existingSkills
            if (missingSkills.isNotEmpty()) {
                warning("У «${specialist.name}» не найдены активные навыки: ${missingSkills.size}.")
            }

            val missingFiles = filesForSpecialist(specialist.id).filterNot { fileExists(it.localPath) }
            if (missingFiles.isNotEmpty()) {
                warning(
                    "У «${specialist.name}» недоступны постоянные файлы: " +
                        missingFiles.take(3).joinToString { it.name } +
                        if (missingFiles.size > 3) " и ещё ${missingFiles.size - 3}" else ""
                )
            }

            val missingKnowledge = knowledgeForSpecialist(specialist.id).filterNot { fileExists(it.localPath) }
            if (missingKnowledge.isNotEmpty()) {
                warning(
                    "У «${specialist.name}» недоступны исходники базы знаний: " +
                        missingKnowledge.take(3).joinToString { it.name }
                )
            }
        }

        val fingerprintParts = buildList {
            add(team.id)
            add(team.updatedAt.toString())
            add("system:$systemModelId")
            allSpecialists.sortedBy { it.id }.forEach { specialist ->
                add(specialist.id)
                add(specialist.updatedAt.toString())
                add(specialist.primaryModel?.connectionProfileId.orEmpty())
                add(specialist.primaryModel?.modelId.orEmpty())
                add(specialist.skillIds.sorted().joinToString(","))
                filesForSpecialist(specialist.id).sortedBy { it.id }.forEach { file ->
                    add("f:${file.id}:${file.size}:${fileExists(file.localPath)}")
                }
                add("knowledgeEnabled:${knowledgeEnabledForSpecialist(specialist.id)}")
                knowledgeForSpecialist(specialist.id).sortedBy { it.id }.forEach { doc ->
                    add("k:${doc.id}:${doc.chunkCount}:${fileExists(doc.localPath)}")
                }
            }
            profiles.sortedBy { it.id }.forEach { profile ->
                add("p:${profile.id}:${profile.type}:${profile.baseUrl}:${profile.id in disabledConnectionIds}:${hasApiKey(profile.id)}")
            }
        }.joinToString("|")

        val fingerprint = fingerprintParts.hashCode().toUInt().toString(16)
        return TeamPreflightReport(
            fingerprint = fingerprint,
            issues = issues.distinctBy { Triple(it.severity, it.message, it.hint) }
        )
    }
}
