package com.ayuemin.ymnik

import com.ayuemin.ymnik.model.AgentKind
import com.ayuemin.ymnik.model.AgentModelRef
import com.ayuemin.ymnik.model.AgentProfile
import com.ayuemin.ymnik.model.ConnectionProfile
import com.ayuemin.ymnik.model.Project
import com.ayuemin.ymnik.model.ProviderType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectPreflightTest {
    private val profile = ConnectionProfile(
        id = "openrouter",
        name = "OpenRouter",
        type = ProviderType.OPENROUTER,
        baseUrl = "https://openrouter.ai/api/v1"
    )

    private fun agent(id: String, kind: AgentKind, model: AgentModelRef? = AgentModelRef("openrouter", "test/model")) =
        AgentProfile(
            id = id,
            projectId = "project",
            kind = kind,
            name = if (kind == AgentKind.ORCHESTRATOR) "Оркестратор" else id,
            instruction = if (kind == AgentKind.SPECIALIST) "Работай по задаче" else "",
            primaryModel = model
        )

    @Test
    fun blocksSpecialistWithoutPrimaryModel() {
        val report = ProjectPreflight.inspect(
            project = Project("project", "Test"),
            orchestrator = agent("orchestrator", AgentKind.ORCHESTRATOR),
            specialists = listOf(agent("broken", AgentKind.SPECIALIST, null)),
            profiles = listOf(profile),
            disabledConnectionIds = emptySet(),
            hasApiKey = { true },
            filesForAgent = { emptyList() },
            skillIdsForAgent = { emptySet() },
            knowledgeForAgent = { emptyList() },
            fileExists = { true }
        )

        assertFalse(report.ready)
        assertTrue(report.blockedMessage().contains("не выбрана основная модель"))
    }

    @Test
    fun passesMinimalReadyProject() {
        val report = ProjectPreflight.inspect(
            project = Project("project", "Test"),
            orchestrator = agent("orchestrator", AgentKind.ORCHESTRATOR),
            specialists = listOf(agent("writer", AgentKind.SPECIALIST)),
            profiles = listOf(profile),
            disabledConnectionIds = emptySet(),
            hasApiKey = { true },
            filesForAgent = { emptyList() },
            skillIdsForAgent = { emptySet() },
            knowledgeForAgent = { emptyList() },
            fileExists = { true }
        )

        assertTrue(report.ready)
        assertTrue(report.readyNotice().contains("Диагностика проекта пройдена"))
    }

    @Test
    fun blocksMissingApiKey() {
        val report = ProjectPreflight.inspect(
            project = Project("project", "Test"),
            orchestrator = agent("orchestrator", AgentKind.ORCHESTRATOR),
            specialists = listOf(agent("writer", AgentKind.SPECIALIST)),
            profiles = listOf(profile),
            disabledConnectionIds = emptySet(),
            hasApiKey = { false },
            filesForAgent = { emptyList() },
            skillIdsForAgent = { emptySet() },
            knowledgeForAgent = { emptyList() },
            fileExists = { true }
        )

        assertFalse(report.ready)
        assertTrue(report.blockers.any { it.message.contains("API-ключ") })
    }
}
