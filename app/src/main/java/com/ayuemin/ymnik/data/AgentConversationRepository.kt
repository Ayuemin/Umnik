package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.AgentConversationRef
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Migration bridge between the legacy ChatSession store and the new agent domain.
 *
 * ChatSession stays untouched for now. A conversation becomes an agent conversation
 * only when it has an explicit link here. This avoids the old ChatSession = agent rule.
 */
class AgentConversationRepository(context: Context) {
    private val gson = Gson()
    private val root = File(context.filesDir, "agents").apply { mkdirs() }
    private val metadata = File(root, "conversation_links.json")
    private val atomic = AtomicJsonFile(metadata)
    private val type = object : TypeToken<List<AgentConversationRef>>() {}.type

    var loadError: String? = null
        private set

    fun list(): List<AgentConversationRef> = runCatching {
        atomic.read(::validJson)?.let { gson.fromJson<List<AgentConversationRef>>(it, type) } ?: emptyList()
    }.onFailure {
        loadError = "Связи разговоров с агентами повреждены и защищены от перезаписи."
    }.getOrDefault(emptyList())

    fun agentIdForConversation(conversationId: String): String? =
        list().firstOrNull { it.conversationId == conversationId }?.agentId

    fun conversationsForAgent(agentId: String): List<String> =
        list().filter { it.agentId == agentId }.map { it.conversationId }

    fun link(conversationId: String, agentId: String) {
        check(loadError == null) { loadError ?: "Хранилище связей недоступно" }
        require(conversationId.isNotBlank()) { "conversationId обязателен" }
        require(agentId.isNotBlank()) { "agentId обязателен" }

        val current = list().filterNot { it.conversationId == conversationId }
        save(current + AgentConversationRef(conversationId = conversationId, agentId = agentId))
    }

    fun unlinkConversation(conversationId: String) {
        save(list().filterNot { it.conversationId == conversationId })
    }

    fun unlinkAgent(agentId: String) {
        save(list().filterNot { it.agentId == agentId })
    }

    private fun save(links: List<AgentConversationRef>) {
        check(loadError == null) { loadError ?: "Хранилище связей недоступно" }
        atomic.write(gson.toJson(links), ::validJson)
    }

    private fun validJson(json: String): Boolean = runCatching {
        gson.fromJson<List<AgentConversationRef>>(json, type) != null
    }.getOrDefault(false)
}
