package com.ayuemin.ymnik.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainLegacyJsonTest {
    private val gson = Gson()

    @Test
    fun legacyChatProjectIdLoadsAsTeamIdAndWritesNewName() {
        val chat = gson.fromJson(
            """{"id":"chat","title":"Legacy","projectId":"team-1"}""",
            ChatSession::class.java
        )

        assertEquals("team-1", chat.teamId)

        val current = gson.toJson(chat)
        assertTrue(current.contains("\"teamId\":\"team-1\""))
        assertFalse(current.contains("\"projectId\""))
    }

    @Test
    fun legacySpecialistFieldsLoadWithoutChangingActiveDomainNames() {
        val specialist = gson.fromJson(
            """{"id":"s1","projectId":"team-1","kind":"SPECIALIST","name":"Writer"}""",
            SpecialistProfile::class.java
        )
        val link = gson.fromJson(
            """{"conversationId":"c1","agentId":"s1"}""",
            SpecialistConversationRef::class.java
        )

        assertEquals("team-1", specialist.teamId)
        assertEquals("s1", link.specialistId)
        assertTrue(gson.toJson(specialist).contains("\"teamId\""))
        assertTrue(gson.toJson(link).contains("\"specialistId\""))
    }

    @Test
    fun legacyKnowledgeOwnerNamesLoadAsCurrentOwners() {
        val specialistDoc = gson.fromJson(
            """{"id":"d1","ownerKind":"AGENT","ownerId":"s1","name":"a.txt","mimeType":"text/plain","localPath":"/tmp/a","size":1,"embeddingModelId":"e","vectorDimension":1,"chunkCount":1,"charCount":1}""",
            KnowledgeDocument::class.java
        )
        val teamDoc = gson.fromJson(
            """{"id":"d2","ownerKind":"PROJECT","ownerId":"t1","name":"b.txt","mimeType":"text/plain","localPath":"/tmp/b","size":1,"embeddingModelId":"e","vectorDimension":1,"chunkCount":1,"charCount":1}""",
            KnowledgeDocument::class.java
        )

        assertEquals(KnowledgeOwnerKind.SPECIALIST, specialistDoc.ownerKind)
        assertEquals(KnowledgeOwnerKind.TEAM, teamDoc.ownerKind)
        assertTrue(gson.toJson(specialistDoc).contains("\"ownerKind\":\"SPECIALIST\""))
        assertTrue(gson.toJson(teamDoc).contains("\"ownerKind\":\"TEAM\""))
    }
}
