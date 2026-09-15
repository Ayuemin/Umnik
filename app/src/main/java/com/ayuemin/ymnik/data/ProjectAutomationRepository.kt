package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.OrchestratorStep
import com.ayuemin.ymnik.model.ProjectChatRuntimeProfile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class ProjectAutomationRepository(context: Context) {
    private val prefs = context.getSharedPreferences("project_automation", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val stepsType = object : TypeToken<List<OrchestratorStep>>() {}.type

    private fun orchestratorKey(projectId: String) = "orchestrator::$projectId"
    private fun profileKey(chatId: String) = "profile::$chatId"
    private fun stepsKey(chatId: String) = "steps::$chatId"

    fun orchestratorChatId(projectId: String): String? = prefs.getString(orchestratorKey(projectId), null)

    fun registerOrchestrator(projectId: String, chatId: String) {
        prefs.edit().putString(orchestratorKey(projectId), chatId).apply()
    }

    fun isOrchestrator(chatId: String): Boolean = prefs.all.any { (key, value) ->
        key.startsWith("orchestrator::") && value == chatId
    }

    fun profile(chatId: String): ProjectChatRuntimeProfile? = runCatching {
        prefs.getString(profileKey(chatId), null)?.let { gson.fromJson(it, ProjectChatRuntimeProfile::class.java) }
    }.getOrNull()

    fun saveProfile(chatId: String, value: ProjectChatRuntimeProfile) {
        prefs.edit().putString(profileKey(chatId), gson.toJson(value)).apply()
    }

    fun steps(chatId: String): List<OrchestratorStep> = runCatching {
        prefs.getString(stepsKey(chatId), null)?.let { gson.fromJson<List<OrchestratorStep>>(it, stepsType) }.orEmpty()
    }.getOrDefault(emptyList())

    fun saveSteps(chatId: String, steps: List<OrchestratorStep>) {
        if (steps.isEmpty()) prefs.edit().remove(stepsKey(chatId)).apply()
        else prefs.edit().putString(stepsKey(chatId), gson.toJson(steps)).apply()
    }

    fun deleteChat(chatId: String) {
        prefs.edit().remove(profileKey(chatId)).remove(stepsKey(chatId)).apply()
    }

    fun deleteProject(projectId: String) {
        val orchestratorId = orchestratorChatId(projectId)
        val editor = prefs.edit().remove(orchestratorKey(projectId))
        if (orchestratorId != null) {
            editor.remove(profileKey(orchestratorId)).remove(stepsKey(orchestratorId))
        }
        editor.apply()
    }
}
