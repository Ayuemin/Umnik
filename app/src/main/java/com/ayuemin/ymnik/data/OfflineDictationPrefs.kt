package com.ayuemin.ymnik.data

import android.content.Context

/** Only offline engines are allowed by the composer microphone. */
enum class OfflineDictationEngine {
    VOSK_RU_SMALL,
    ANDROID_ON_DEVICE
}

class OfflineDictationPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("umnik_offline_dictation", Context.MODE_PRIVATE)

    fun engine(): OfflineDictationEngine =
        runCatching {
            OfflineDictationEngine.valueOf(
                prefs.getString("engine", OfflineDictationEngine.VOSK_RU_SMALL.name).orEmpty()
            )
        }.getOrDefault(OfflineDictationEngine.VOSK_RU_SMALL)

    fun setEngine(engine: OfflineDictationEngine) {
        prefs.edit().putString("engine", engine.name).apply()
    }
}
