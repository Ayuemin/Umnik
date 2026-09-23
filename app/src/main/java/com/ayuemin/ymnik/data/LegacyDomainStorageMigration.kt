package com.ayuemin.ymnik.data

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * One-way compatibility bridge for the terminology migration:
 * projects -> teams and agents -> specialists.
 *
 * Legacy storage is removed only after a successful filesystem/prefs copy.
 */
internal object LegacyDomainStorageMigration {
    fun migrateDirectory(context: Context, legacyName: String, currentName: String): File {
        val legacy = File(context.filesDir, legacyName)
        val current = File(context.filesDir, currentName)
        if (legacy.isDirectory && !current.exists()) {
            if (!legacy.renameTo(current)) {
                val copied = runCatching {
                    legacy.copyRecursively(current, overwrite = false)
                }.getOrDefault(false)
                if (copied) legacy.deleteRecursively()
            }
        }
        current.mkdirs()
        return current
    }

    fun migrateFile(root: File, legacyName: String, currentName: String): File {
        val legacy = File(root, legacyName)
        val current = File(root, currentName)
        if (!current.exists() && legacy.isFile) {
            if (!legacy.renameTo(current)) {
                val copied = runCatching {
                    legacy.copyTo(current, overwrite = false)
                    current.length() == legacy.length()
                }.getOrDefault(false)
                if (copied) legacy.delete()
            }
        }
        return current
    }

    fun migratePreferences(context: Context, legacyName: String, currentName: String): SharedPreferences {
        val current = context.getSharedPreferences(currentName, Context.MODE_PRIVATE)
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        if (current.all.isEmpty() && legacy.all.isNotEmpty()) {
            val editor = current.edit()
            legacy.all.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            if (editor.commit()) legacy.edit().clear().commit()
        }
        return current
    }
}
