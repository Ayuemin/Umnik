package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.ProjectChatRuntimeProfile
import com.ayuemin.ymnik.model.ServerToolSettings
import com.ayuemin.ymnik.model.normalized
import com.google.gson.Gson

/** Per-conversation runtime settings for project/agent chats. */
class ProjectAutomationRepository(context: Context) {
    private val prefs = context.getSharedPreferences("project_automation", Context.MODE_PRIVATE)
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

    fun profile(chatId: String): ProjectChatRuntimeProfile? = runCatching {
        prefs.getString(profileKey(chatId), null)?.let {
            gson.fromJson(it, ProjectChatRuntimeProfile::class.java)
        }?.let { profile ->
            val tools = runCatching { profile.tools }.getOrNull()?.normalized() ?: ServerToolSettings()
            profile.copy(tools = tools)
        }
    }.getOrNull()

    fun saveProfile(chatId: String, value: ProjectChatRuntimeProfile) {
        prefs.edit().putString(profileKey(chatId), gson.toJson(value)).apply()
    }

    fun deleteChat(chatId: String) {
        prefs.edit().remove(profileKey(chatId)).apply()
    }
}
