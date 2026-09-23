package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.SpecialistConversationRef
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Migration bridge between the legacy ChatSession store and the new specialist domain.
 *
 * ChatSession stays untouched for now. A conversation becomes an specialist conversation
 * only when it has an explicit link here. This avoids the old ChatSession = specialist rule.
 */
class SpecialistConversationRepository(context: Context) {
    private val gson = Gson()
    private val root = LegacyDomainStorageMigration.migrateDirectory(context, "agents", "specialists")
    private val metadata = File(root, "conversation_links.json")
    private val atomic = AtomicJsonFile(metadata)
    private val type = object : TypeToken<List<SpecialistConversationRef>>() {}.type

    var loadError: String? = null
        private set

    fun list(): List<SpecialistConversationRef> = runCatching {
        atomic.read(::validJson)?.let { gson.fromJson<List<SpecialistConversationRef>>(it, type) } ?: emptyList()
    }.onFailure {
        loadError = "Связи разговоров с специалистами повреждены и защищены от перезаписи."
    }.getOrDefault(emptyList())

    fun specialistIdForConversation(conversationId: String): String? =
        list().firstOrNull { it.conversationId == conversationId }?.specialistId

    fun conversationsForSpecialist(specialistId: String): List<String> =
        list().filter { it.specialistId == specialistId }.map { it.conversationId }

    fun link(conversationId: String, specialistId: String) {
        check(loadError == null) { loadError ?: "Хранилище связей недоступно" }
        require(conversationId.isNotBlank()) { "conversationId обязателен" }
        require(specialistId.isNotBlank()) { "specialistId обязателен" }

        val current = list().filterNot { it.conversationId == conversationId }
        save(current + SpecialistConversationRef(conversationId = conversationId, specialistId = specialistId))
    }

    fun unlinkConversation(conversationId: String) {
        save(list().filterNot { it.conversationId == conversationId })
    }

    fun unlinkSpecialist(specialistId: String) {
        save(list().filterNot { it.specialistId == specialistId })
    }

    private fun save(links: List<SpecialistConversationRef>) {
        check(loadError == null) { loadError ?: "Хранилище связей недоступно" }
        atomic.write(gson.toJson(links), ::validJson)
    }

    private fun validJson(json: String): Boolean = runCatching {
        gson.fromJson<List<SpecialistConversationRef>>(json, type) != null
    }.getOrDefault(false)
}
