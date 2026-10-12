package com.ayuemin.ymnik.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.security.MessageDigest

/**
 * Provider prompt caching (not replaying cached model/tool responses).
 * Apply AFTER request enhancement so the immutable system/tool prefix stays
 * cacheable. Unknown models keep their native implicit provider behavior.
 */
internal object OpenRouterPromptCachePolicy {
    private val explicitQwen = setOf(
        "qwen/qwen3-max", "qwen/qwen-plus", "qwen/qwen3.6-plus",
        "qwen/qwen3-coder-plus", "qwen/qwen3-coder-flash"
    )

    fun apply(payload: JsonObject, trustedChatId: String?) {
        trustedChatId?.trim()?.takeIf { it.isNotBlank() }?.let { id ->
            payload.addProperty("session_id", sessionId(id))
        }
        val model = payload.get("model")?.takeIf { it.isJsonPrimitive }
            ?.asString?.lowercase()?.removePrefix("~").orEmpty()
        if (model.startsWith("anthropic/")) {
            // The cheaper default five-minute TTL. One-hour writes are optional
            // and cost more, so never enable them without an explicit choice.
            payload.add("cache_control", JsonObject().apply {
                addProperty("type", "ephemeral")
            })
        } else if (model in explicitQwen) {
            val firstSystem = payload.getAsJsonArray("messages")
                ?.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject }
                ?.firstOrNull { it.get("role")?.asString == "system" }
            val content = firstSystem?.get("content")?.takeIf { it.isJsonPrimitive }
                ?.asString.orEmpty()
            if (content.isNotBlank()) {
                firstSystem?.add("content", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "text")
                        addProperty("text", content)
                        add("cache_control", JsonObject().apply {
                            addProperty("type", "ephemeral")
                        })
                    })
                })
            }
        }
    }

    internal fun sessionId(chatId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("umnik:openrouter:chat:v1:" + chatId).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "umnik_" + digest.take(40)
    }
}
