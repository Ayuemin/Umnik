package com.ayuemin.umniklab

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LabState {
    private const val PREFS = "lab_state"
    private const val KEY_API = "api_key"
    private const val KEY_PROMPT = "prompt"
    private const val KEY_STATUS = "status"
    private val lock = Any()

    fun saveInput(context: Context, apiKey: String, prompt: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API, apiKey.trim())
            .putString(KEY_PROMPT, prompt)
            .commit()
    }

    fun apiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API, "").orEmpty()

    fun prompt(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PROMPT, DEFAULT_PROMPT).orEmpty()

    fun status(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATUS, "Готово к тесту").orEmpty()

    fun setStatus(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STATUS, value).apply()
        log(context, "STATUS", value)
    }

    fun log(context: Context, tag: String, message: String, error: Throwable? = null) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = buildString {
            append(time).append(" | ").append(tag).append(" | ").append(message.replace('\n', ' '))
            if (error != null) {
                append(" | ").append(error::class.java.simpleName).append(": ").append(error.message.orEmpty())
            }
            append('\n')
        }
        synchronized(lock) {
            val file = File(context.filesDir, "background-lab.log")
            file.appendText(line)
            if (file.length() > 2L * 1024L * 1024L) {
                val tail = file.readText().takeLast(1024 * 1024)
                file.writeText("--- log trimmed ---\n$tail")
            }
        }
    }

    fun readLog(context: Context): String = synchronized(lock) {
        File(context.filesDir, "background-lab.log").takeIf { it.exists() }?.readText().orEmpty()
    }

    fun clearLog(context: Context) = synchronized(lock) {
        File(context.filesDir, "background-lab.log").writeText("")
    }

    const val DEFAULT_PROMPT = "Напиши подробный ответ примерно на 1500-2000 слов о том, как устроена работа Android-приложений в фоне. Не сокращай ответ."
}
