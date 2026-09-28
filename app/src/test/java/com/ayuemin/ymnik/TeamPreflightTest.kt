package com.ayuemin.ymnik

import com.ayuemin.ymnik.model.SpecialistKind
import com.ayuemin.ymnik.model.SpecialistModelRef
import com.ayuemin.ymnik.model.SpecialistProfile
import com.ayuemin.ymnik.model.ConnectionProfile
import com.ayuemin.ymnik.model.Team
import com.ayuemin.ymnik.model.KnowledgeDocument
import com.ayuemin.ymnik.model.KnowledgeOwnerKind
import com.ayuemin.ymnik.model.ProviderType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamPreflightTest {
    private val profile = ConnectionProfile(
        id = "openrouter",
        name = "OpenRouter",
        type = ProviderType.OPENROUTER,
        baseUrl = "https://openrouter.ai/api/v1"
    )

    private fun specialist(id: String, kind: SpecialistKind, model: SpecialistModelRef? = SpecialistModelRef("openrouter", "test/model")) =
        SpecialistProfile(
            id = id,
            teamId = "team",
            kind = kind,
            name = if (kind == SpecialistKind.ORCHESTRATOR) "Оркестратор" else id,
            instruction = if (kind == SpecialistKind.SPECIALIST) "Работай по задаче" else "",
            primaryModel = model
        )

    @Test
    fun blocksSpecialistWithoutPrimaryModel() {
        val report = TeamPreflight.inspect(
            team = Team("team", "Test"),
            orchestrator = specialist("orchestrator", SpecialistKind.ORCHESTRATOR),
            specialists = listOf(specialist("broken", SpecialistKind.SPECIALIST, null)),
            profiles = listOf(profile),
            disabledConnectionIds = emptySet(),
            hasApiKey = { true },
            systemModelId = "",
            filesForSpecialist = { emptyList() },
            skillIdsForSpecialist = { emptySet() },
            knowledgeForSpecialist = { emptyList() },
            knowledgeEnabledForSpecialist = { false },
            fileExists = { true }
        )

        assertFalse(report.ready)
        assertTrue(report.blockedMessage().contains("не выбрана основная модель"))
    }

    @Test
    fun passesMinimalReadyTeam() {
        val report = TeamPreflight.inspect(
            team = Team("team", "Test"),
            orchestrator = specialist("orchestrator", SpecialistKind.ORCHESTRATOR),
            specialists = listOf(specialist("writer", SpecialistKind.SPECIALIST)),
            profiles = listOf(profile),
            disabledConnectionIds = emptySet(),
            hasApiKey = { true },
            systemModelId = "",
            filesForSpecialist = { emptyList() },
            skillIdsForSpecialist = { emptySet() },
            knowledgeForSpecialist = { emptyList() },
            knowledgeEnabledForSpecialist = { false },
            fileExists = { true }
        )

        assertTrue(report.ready)
        assertTrue(report.readyNotice().contains("Диагностика команды пройдена"))
    }

    @Test
    fun blocksMissingApiKey() {
        val report = TeamPreflight.inspect(
            team = Team("team", "Test"),
            orchestrator = specialist("orchestrator", SpecialistKind.ORCHESTRATOR),
            specialists = listOf(specialist("writer", SpecialistKind.SPECIALIST)),
            profiles = listOf(profile),
            disabledConnectionIds = emptySet(),
            hasApiKey = { false },
            systemModelId = "",
            filesForSpecialist = { emptyList() },
            skillIdsForSpecialist = { emptySet() },
            knowledgeForSpecialist = { emptyList() },
            knowledgeEnabledForSpecialist = { false },
            fileExists = { true }
        )

        assertFalse(report.ready)
        assertTrue(report.blockers.any { it.message.contains("API-ключ") })
    }
    @Test
    fun blocksKnowledgeBaseWithoutSystemModel() {
        val document = KnowledgeDocument(
            id = "doc",
            ownerKind = KnowledgeOwnerKind.SPECIALIST,
            ownerId = "writer",
            name = "book.txt",
            mimeType = "text/plain",
            localPath = "/tmp/book.txt",
            size = 10,
            embeddingModelId = "test/embed",
            vectorDimension = 3,
            chunkCount = 1,
            charCount = 10
        )
        val report = TeamPreflight.inspect(
            team = Team("team", "Test"),
            orchestrator = specialist("orchestrator", SpecialistKind.ORCHESTRATOR),
            specialists = listOf(specialist("writer", SpecialistKind.SPECIALIST)),
            profiles = listOf(profile),
            disabledConnectionIds = emptySet(),
            hasApiKey = { true },
            systemModelId = "",
            filesForSpecialist = { emptyList() },
            skillIdsForSpecialist = { emptySet() },
            knowledgeForSpecialist = { specialistId -> if (specialistId == "writer") listOf(document) else emptyList() },
            knowledgeEnabledForSpecialist = { specialistId -> specialistId == "writer" },
            fileExists = { true }
        )

        assertFalse(report.ready)
        assertTrue(report.blockers.any { it.message.contains("системная модель") })
    }

    @Test
    fun disabledKnowledgeBaseDoesNotRequireSystemModel() {
        val document = KnowledgeDocument(
            id = "doc",
            ownerKind = KnowledgeOwnerKind.SPECIALIST,
            ownerId = "writer",
            name = "book.txt",
            mimeType = "text/plain",
            localPath = "/tmp/book.txt",
            size = 10,
            embeddingModelId = "test/embed",
            vectorDimension = 3,
            chunkCount = 1,
            charCount = 10
        )
        val report = TeamPreflight.inspect(
            team = Team("team", "Test"),
            orchestrator = specialist("orchestrator", SpecialistKind.ORCHESTRATOR),
            specialists = listOf(specialist("writer", SpecialistKind.SPECIALIST)),
            profiles = listOf(profile),
            disabledConnectionIds = emptySet(),
            hasApiKey = { true },
            systemModelId = "",
            filesForSpecialist = { emptyList() },
            skillIdsForSpecialist = { emptySet() },
            knowledgeForSpecialist = { specialistId -> if (specialistId == "writer") listOf(document) else emptyList() },
            knowledgeEnabledForSpecialist = { false },
            fileExists = { true }
        )

        assertTrue(report.ready)
    }

}
