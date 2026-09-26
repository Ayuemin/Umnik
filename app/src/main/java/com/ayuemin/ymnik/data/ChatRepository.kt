package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.AsyncJobEvents
import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.network.ContextUsageTracker
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class ChatRepository(context: Context) {
    private companion object { val fileLock = Any() }
    private val gson = Gson()
    private val root = File(context.filesDir, "chats").apply { mkdirs() }
    private val metadata = File(root, "chats.json")
    private val atomic = AtomicJsonFile(metadata)
    private val type = object : TypeToken<List<ChatSession>>() {}.type
    var loadError: String? = null
        private set

    @Synchronized
    fun list(): List<ChatSession> = synchronized(fileLock) {
        runCatching {
            atomic.read(::validJson)?.let { gson.fromJson<List<ChatSession>>(it, type) } ?: emptyList()
        }.onFailure { loadError = "Чаты повреждены. Исходные файлы сохранены; Umnik не будет их перезаписывать. Сделайте копию данных приложения для восстановления." }
            .getOrDefault(emptyList())
    }

    @Synchronized
    fun save(chats: List<ChatSession>) {
        synchronized(fileLock) {
            check(loadError == null) { loadError ?: "Хранилище чатов недоступно" }
            root.mkdirs()
            atomic.write(gson.toJson(chats), ::validJson)
        }
    }

    fun updateChat(chatId: String, transform: (ChatSession) -> ChatSession): List<ChatSession> = synchronized(fileLock) {
        val chats = list()
        val updated = chats.map { chat -> if (chat.id == chatId) transform(chat) else chat }
        save(updated)
        updated
    }

    @Synchronized
    fun updateMessage(chatId: String, messageId: String, transform: (ChatMessage) -> ChatMessage) {
        synchronized(fileLock) {
            val chats = list()
            save(chats.map { chat ->
                if (chat.id == chatId) chat.copy(messages = chat.messages.map { message ->
                    if (message.id == messageId) transform(message) else message
                }) else chat
            })
        }
    }

    /** Complete only the originating synchronous request using the latest on-disk state. */
    fun finishRequest(chatId: String, messageId: String, assistant: ChatMessage?): List<ChatSession> = synchronized(fileLock) {
        val chats = list()
        val updated = chats.map { chat ->
            if (chat.id != chatId) return@map chat
            val userIndex = chat.messages.indexOfFirst { it.id == messageId && it.deliveryState == "pending" }
            if (userIndex < 0) return@map chat
            val completedAssistant = assistant?.let { raw ->
                if (raw.contextUsage != null) {
                    raw
                } else {
                    val usage = raw.requestId
                        ?.let(ContextUsageTracker::consume)
                        ?: ContextUsageTracker.consumeForMessage(chatId, messageId)
                    usage?.let { raw.copy(contextUsage = it) } ?: raw
                }
            }
            val messages = chat.messages.toMutableList()
            messages[userIndex] = messages[userIndex].copy(deliveryState = if (completedAssistant == null) "failed" else null)
            if (completedAssistant != null) messages.add(userIndex + 1, completedAssistant)
            chat.copy(messages = messages, updatedAt = System.currentTimeMillis())
        }
        save(updated)
        updated
    }

    /**
     * Append an asynchronous result exactly once. `sourceKey` is stored only in
     * the assistant message deliveryState; the UI ignores it for completed
     * assistant messages, while background retries can use it for de-duplication.
     */
    fun appendAssistantIfMissing(chatId: String, sourceKey: String, assistant: ChatMessage): List<ChatSession> = synchronized(fileLock) {
        val marker = "async:$sourceKey"
        val chats = list()
        var changed = false
        val updated = chats.map { chat ->
            if (chat.id != chatId || chat.messages.any { it.role == "assistant" && it.deliveryState == marker }) return@map chat
            changed = true
            chat.copy(
                messages = chat.messages + assistant.copy(deliveryState = marker),
                updatedAt = System.currentTimeMillis()
            )
        }
        save(updated)
        if (changed) AsyncJobEvents.notifyChanged()
        updated
    }

    private fun validJson(json: String): Boolean = runCatching {
        gson.fromJson<List<ChatSession>>(json, type) != null
    }.getOrDefault(false)

    fun dataSize(): Long = if (metadata.exists()) metadata.length() else 0L
}
