package com.ayuemin.ymnik.network

import com.ayuemin.ymnik.model.InternetMode

internal object AgentModePolicy {
    fun explicitlyRequestsLocalShell(prompt: String): Boolean {
        val value = prompt.lowercase()
        return listOf(
            "запусти local shell",
            "запускай local shell",
            "используй local shell",
            "сделай в local shell",
            "выполни в local shell",
            "проверь в local shell",
            "создай в local shell",
            "через local shell",
            "запусти shell",
            "используй shell",
            "сделай в shell",
            "выполни в shell",
            "проверь в shell",
            "через shell",
            "run local shell",
            "use local shell"
        ).any(value::contains)
    }

    fun explicitlyRequestsLocalBrowser(prompt: String): Boolean {
        val value = prompt.lowercase()
        return listOf(
            "открой в браузере",
            "открой через браузер",
            "используй браузер",
            "запусти браузер",
            "через браузер",
            "открой в browser",
            "use browser",
            "open in browser",
            "перейди по",
            "нажми",
            "кликни",
            "введи",
            "впиши",
            "прокрути",
            "пролистай",
            "вернись назад",
            "скачай"
        ).any(value::contains)
    }

    fun browserRequested(
        agentEnabled: Boolean,
        webSearchEnabled: Boolean,
        internetMode: InternetMode,
        prompt: String
    ): Boolean = explicitlyRequestsLocalBrowser(prompt) ||
        (webSearchEnabled && (
            internetMode == InternetMode.BROWSER ||
                (agentEnabled && internetMode == InternetMode.AUTO)
        ))

    fun shellRequested(agentEnabled: Boolean, prompt: String): Boolean =
        agentEnabled || explicitlyRequestsLocalShell(prompt)
}
