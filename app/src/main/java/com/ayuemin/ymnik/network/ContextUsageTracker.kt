package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.ContextLayerUsage
import com.ayuemin.ymnik.model.ContextUsageBreakdown
import com.google.gson.JsonObject
import java.util.LinkedHashMap

/**
 * Snapshot of the context that actually reaches /chat/completions.
 * Exact provider input tokens live in ChatMessage.inputTokens; token counts here are estimates.
 * The latest call wins so Agent/tool loops match the provider usage stored for the final answer.
 */
internal object ContextUsageTracker {
    private const val MAX_ENTRIES = 128
    private const val TTL_MS = 30L * 60L * 1000L
    private const val SKILL_START = "===== НАЧАЛО ПОДКЛЮЧЁННЫХ НАВЫКОВ ====="
    private const val SKILL_END = "===== КОНЕЦ ПОДКЛЮЧЁННЫХ НАВЫКОВ ====="
    private const val MEMORY_START = "===== ДОЛГОВРЕМЕННАЯ ПАМЯТЬ ЭТОГО ЧАТА ====="
    private const val MEMORY_END = "===== КОНЕЦ ДОЛГОВРЕМЕННОЙ ПАМЯТИ ====="
    private const val RAG_START = "===== СКРЫТЫЙ СПРАВОЧНЫЙ КОНТЕКСТ UMNIK ====="
    private const val RAG_END = "===== КОНЕЦ СКРЫТОГО СПРАВОЧНОГО КОНТЕКСТА ====="

    private data class Entry(val capturedAt: Long, val usage: ContextUsageBreakdown)
    private data class AttachmentStats(val count: Int, val bytes: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private val messageAliases = LinkedHashMap<String, String>()

    @Synchronized
    fun capture(requestId: String?, payload: JsonObject): ContextUsageBreakdown? {
        val key = requestId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val usage = measurePayload(payload)
        val now = System.currentTimeMillis()
        prune(now)
        entries[key] = Entry(now, usage)
        while (entries.size > MAX_ENTRIES) {
            entries.entries.firstOrNull()?.key?.let(::removeRequest) ?: break
        }
        return usage
    }

    /**
     * Links the network request UUID to the originating chat message. ChatViewModel also keeps
     * a separate per-chat generation counter, so the persisted ChatMessage.requestId is not
     * guaranteed to be the UUID used by the HTTP client. This stable alias keeps persistence
     * correlated without changing the existing request-generation semantics.
     */
    @Synchronized
    fun linkToMessage(requestId: String?, chatId: String?, messageId: String?) {
        val requestKey = requestId?.trim()?.takeIf { it.isNotBlank() } ?: return
        val alias = messageKey(chatId, messageId) ?: return
        val now = System.currentTimeMillis()
        prune(now)
        if (requestKey in entries) messageAliases[alias] = requestKey
    }

    @Synchronized
    fun consume(requestId: String): ContextUsageBreakdown? {
        val now = System.currentTimeMillis()
        prune(now)
        return removeRequest(requestId)
    }

    @Synchronized
    fun consumeForMessage(chatId: String, messageId: String): ContextUsageBreakdown? {
        val now = System.currentTimeMillis()
        prune(now)
        val alias = messageKey(chatId, messageId) ?: return null
        val requestKey = messageAliases.remove(alias) ?: return null
        return removeRequest(requestKey)
    }

    private fun messageKey(chatId: String?, messageId: String?): String? {
        val chat = chatId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val message = messageId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "$chat\u001F$message"
    }

    private fun removeRequest(requestKey: String): ContextUsageBreakdown? {
        val usage = entries.remove(requestKey)?.usage
        if (usage != null) {
            val aliases = messageAliases.filterValues { it == requestKey }.keys.toList()
            aliases.forEach(messageAliases::remove)
        }
        return usage
    }

    @Synchronized
    private fun prune(now: Long) {
        val stale = entries.filterValues { now - it.capturedAt > TTL_MS }.keys.toList()
        stale.forEach(::removeRequest)
        val orphanedAliases = messageAliases.filterValues { it !in entries }.keys.toList()
        orphanedAliases.forEach(messageAliases::remove)
    }

    internal fun measurePayload(payload: JsonObject): ContextUsageBreakdown {
        val messages = payload.get("messages")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
            .orEmpty()

        val currentUserIndex = messages.indexOfLast { it.string("role") == "user" }
        val systemText = messages
            .filter { it.string("role") == "system" }
            .joinToString("\n") { messageText(it) }
        val (withoutSkills, skillText) = peelMarkedBlock(systemText, SKILL_START, SKILL_END)
        val (withoutMemory, memorySystemText) = peelMarkedBlock(withoutSkills, MEMORY_START, MEMORY_END)
        val (baseSystemText, ragSystemText) = peelMarkedBlock(withoutMemory, RAG_START, RAG_END)

        val toolNamesById = mutableMapOf<String, String>()
        val historyParts = mutableListOf<String>()
        val ragParts = mutableListOf<String>()
        if (memorySystemText.isNotBlank()) ragParts += memorySystemText
        if (ragSystemText.isNotBlank()) ragParts += ragSystemText

        messages.forEachIndexed { index, message ->
            val role = message.string("role").orEmpty()
            if (role == "assistant") {
                message.get("tool_calls")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.forEach { element ->
                        val call = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                        val id = call.string("id") ?: return@forEach
                        val name = call.getAsJsonObject("function")?.string("name") ?: return@forEach
                        toolNamesById[id] = name
                    }
            }
            if (role == "system" || index == currentUserIndex) return@forEachIndexed
            if (role == "tool") {
                val toolCallId = message.string("tool_call_id")
                if (toolCallId != null && toolNamesById[toolCallId] == "knowledge_search") {
                    ragParts += message.toString()
                    return@forEachIndexed
                }
            }
            historyParts += message.toString()
        }

        val currentUser = messages.getOrNull(currentUserIndex)
        val currentPrompt = currentUser?.let(::messageText).orEmpty()
        val attachments = currentUser?.let(::attachmentStats) ?: AttachmentStats(0, 0L)
        val toolsJson = payload.get("tools")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.toString()
            .orEmpty()

        return ContextUsageBreakdown(
            systemPrompt = layer(baseSystemText),
            tools = layer(toolsJson),
            history = layer(historyParts.joinToString("\n")),
            memoryRag = layer(ragParts.joinToString("\n")),
            skills = layer(skillText),
            currentUserPrompt = layer(currentPrompt),
            attachmentCount = attachments.count,
            attachmentBytes = attachments.bytes
        )
    }

    private fun layer(text: String): ContextLayerUsage = ContextSizeEstimator.measure(text).let {
        ContextLayerUsage(chars = it.chars, bytes = it.utf8Bytes, estimatedTokens = it.estimatedTokens)
    }

    private fun peelMarkedBlock(source: String, startMarker: String, endMarker: String): Pair<String, String> {
        val start = source.indexOf(startMarker)
        if (start < 0) return source to ""
        val markerEnd = source.indexOf(endMarker, start + startMarker.length)
        if (markerEnd < 0) return source to ""
        var from = start
        var to = markerEnd + endMarker.length
        if (from > 0 && source[from - 1] == '\n') from -= 1
        if (to < source.length && source[to] == '\n') to += 1
        val block = source.substring(from, to)
        val remainder = source.removeRange(from, to).trim()
        return remainder to block
    }

    private fun messageText(message: JsonObject): String {
        val content = message.get("content") ?: return ""
        if (content.isJsonPrimitive) return content.asString
        if (!content.isJsonArray) return content.toString()
        return content.asJsonArray.mapNotNull { part ->
            val obj = part.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            if (obj.string("type") == "text") obj.string("text") else null
        }.joinToString("\n")
    }

    private fun attachmentStats(message: JsonObject): AttachmentStats {
        val content = message.get("content")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return AttachmentStats(0, 0L)
        var count = 0
        var bytes = 0L
        content.forEach { element ->
            val part = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            when (part.string("type")) {
                "file" -> {
                    count += 1
                    bytes += part.getAsJsonObject("file")?.string("file_data")?.let(::dataUrlBytes) ?: 0L
                }
                "image_url", "video_url" -> {
                    count += 1
                    val key = if (part.string("type") == "image_url") "image_url" else "video_url"
                    bytes += part.getAsJsonObject(key)?.string("url")?.let(::dataUrlBytes) ?: 0L
                }
                "input_audio" -> {
                    count += 1
                    bytes += part.getAsJsonObject("input_audio")?.string("data")
                        ?.let { base64DecodedBytes(it, 0) } ?: 0L
                }
            }
        }
        return AttachmentStats(count, bytes)
    }

    private fun dataUrlBytes(value: String): Long {
        if (!value.startsWith("data:")) return 0L
        val comma = value.indexOf(',')
        if (comma < 0 || comma == value.lastIndex) return 0L
        return base64DecodedBytes(value, comma + 1)
    }

    private fun base64DecodedBytes(value: String, start: Int): Long {
        var meaningful = 0L
        var padding = 0L
        for (index in start until value.length) {
            val c = value[index]
            if (c.isWhitespace()) continue
            meaningful += 1
            if (c == '=') padding += 1
        }
        if (meaningful == 0L) return 0L
        val groups = meaningful / 4L
        val remainder = meaningful % 4L
        val decoded = groups * 3L + when (remainder.toInt()) {
            2 -> 1L
            3 -> 2L
            else -> 0L
        } - padding
        return decoded.coerceAtLeast(0L)
    }

    private fun JsonObject.string(name: String): String? = runCatching {
        get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
    }.getOrNull()
}
