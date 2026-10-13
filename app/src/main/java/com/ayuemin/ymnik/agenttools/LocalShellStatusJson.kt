package com.ayuemin.ymnik.agenttools

import com.ayuemin.ymnik.LocalShellActivity
import com.google.gson.Gson

/** The existing Shell-status wire shape, shared by the legacy start path and ToolBus. */
internal object LocalShellStatusJson {
    private val gson = Gson()

    fun render(chatId: String, active: LocalShellActivity?, nowMillis: Long): String {
        if (active == null) return gson.toJson(mapOf("ok" to true, "running" to false))
        if (active.chatId != chatId) {
            return gson.toJson(
                mapOf(
                    "ok" to true,
                    "running" to true,
                    "in_this_chat" to false,
                    "message" to "Local Shell занят задачей из другого чата"
                )
            )
        }
        return gson.toJson(
            mapOf(
                "ok" to true,
                "running" to true,
                "in_this_chat" to true,
                "model" to active.modelId,
                "status" to active.status,
                "turn" to active.turn,
                "max_turns" to active.maxTurns,
                "tool_calls" to active.toolCalls,
                "attachments" to active.attachmentCount,
                "elapsed_seconds" to ((nowMillis - active.startedAt).coerceAtLeast(0L) / 1_000L)
            )
        )
    }
}
