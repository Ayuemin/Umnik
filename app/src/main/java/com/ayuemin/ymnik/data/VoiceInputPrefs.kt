package com.ayuemin.ymnik.data

import android.content.Context

/** The microphone is independent of the chat model unless AUDIO_MESSAGE is selected. */
enum class VoiceInputMode {
    ANDROID_DICTATION,
    OPENROUTER_DICTATION,
    AUDIO_MESSAGE
}

class VoiceInputPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("umnik_voice_input", Context.MODE_PRIVATE)

    fun mode(): VoiceInputMode = runCatching {
        VoiceInputMode.valueOf(prefs.getString("mode", VoiceInputMode.ANDROID_DICTATION.name).orEmpty())
    }.getOrDefault(VoiceInputMode.ANDROID_DICTATION)

    fun setMode(value: VoiceInputMode) {
        prefs.edit().putString("mode", value.name).apply()
    }
}
