package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.ChatRuntimeProfile
import com.ayuemin.ymnik.model.ServerToolSettings
import com.ayuemin.ymnik.model.normalized
import com.google.gson.Gson

/** Per-conversation runtime settings for team/specialist chats. */
class ChatRuntimeRepository(context: Context) {
    private val prefs = LegacyDomainStorageMigration.migratePreferences(context, "project_automation", "chat_runtime")
    private val gson = Gson()

    init {
        // v1.19 removes the obsolete fixed-stage/chat-orchestrator engine. Purge only its
        // preference keys; conversation runtime profiles remain valid.
        val legacyKeys = prefs.all.keys.filter {
            it.startsWith("orchestrator::") || it.startsWith("steps::")
        }
        if (legacyKeys.isNotEmpty()) {
            prefs.edit().also { editor -> legacyKeys.forEach(editor::remove) }.apply()
        }
    }

    private fun profileKey(chatId: String) = "profile::$chatId"

    fun profile(chatId: String): ChatRuntimeProfile? = runCatching {
        prefs.getString(profileKey(chatId), null)?.let {
            gson.fromJson(it, ChatRuntimeProfile::class.java)
        }?.let { profile ->
            val tools = runCatching { profile.tools }.getOrNull()?.normalized() ?: ServerToolSettings()
            profile.copy(tools = tools)
        }
    }.getOrNull()

    fun saveProfile(chatId: String, value: ChatRuntimeProfile) {
        prefs.edit().putString(profileKey(chatId), gson.toJson(value)).apply()
    }

    fun deleteChat(chatId: String) {
        prefs.edit().remove(profileKey(chatId)).apply()
    }
}
