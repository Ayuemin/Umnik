package com.ayuemin.ymnik.data

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * One-way compatibility bridge for the terminology migration:
 * projects -> teams and agents -> specialists.
 *
 * Legacy storage is removed only after the current storage contains the same data.
 */
internal object LegacyDomainStorageMigration {
    @Synchronized
    fun migrateDirectory(context: Context, legacyName: String, currentName: String): File {
        val legacy = File(context.filesDir, legacyName)
        val current = File(context.filesDir, currentName)
        if (!legacy.isDirectory) {
            current.mkdirs()
            return current
        }

        if (!current.exists() && legacy.renameTo(current)) return current

        if (!current.exists()) {
            val staging = File(context.filesDir, ".${currentName}.migrating")
            staging.deleteRecursively()
            val copied = runCatching {
                legacy.copyRecursively(staging, overwrite = false)
            }.getOrDefault(false)
            if (copied && !current.exists() && staging.renameTo(current)) {
                legacy.deleteRecursively()
                return current
            }
            staging.deleteRecursively()
        }

        current.mkdirs()
        if (current.isDirectory && mergeLegacyDirectory(legacy, current)) {
            legacy.deleteRecursively()
        }
        return current
    }

    @Synchronized
    fun migrateFile(root: File, legacyName: String, currentName: String): File {
        val legacy = File(root, legacyName)
        val current = File(root, currentName)
        if (!legacy.isFile) return current

        if (!current.exists() && legacy.renameTo(current)) return current

        if (!current.exists()) {
            val staging = File(root, ".${currentName}.migrating")
            staging.delete()
            val copied = runCatching {
                legacy.copyTo(staging, overwrite = false)
                sameFileContents(legacy, staging)
            }.getOrDefault(false)
            if (copied && !current.exists() && staging.renameTo(current)) {
                legacy.delete()
                return current
            }
            staging.delete()
        }

        if (current.isFile && sameFileContents(legacy, current)) {
            legacy.delete()
        }
        return current
    }

    @Synchronized
    fun migratePreferences(context: Context, legacyName: String, currentName: String): SharedPreferences {
        val current = context.getSharedPreferences(currentName, Context.MODE_PRIVATE)
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        val legacyValues = legacy.all
        if (legacyValues.isEmpty()) return current

        val missing = legacyValues.filterKeys { !current.contains(it) }
        if (missing.isNotEmpty()) {
            val editor = current.edit()
            missing.forEach { (key, value) -> putPreference(editor, key, value) }
            editor.commit()
        }

        val represented = legacyValues.all { (key, value) ->
            preferenceValuesEqual(current.all[key], value)
        }
        if (represented) legacy.edit().clear().commit()
        return current
    }

    private fun mergeLegacyDirectory(legacy: File, current: File): Boolean = runCatching {
        var complete = true
        legacy.walkTopDown().forEach { source ->
            if (source == legacy) return@forEach
            val relative = source.relativeTo(legacy).path
            val target = File(current, relative)
            if (source.isDirectory) {
                if (target.exists() && !target.isDirectory) {
                    complete = false
                } else if (!target.exists() && !target.mkdirs()) {
                    complete = false
                }
            } else {
                if (!target.exists()) {
                    target.parentFile?.mkdirs()
                    source.copyTo(target, overwrite = false)
                }
                if (!target.isFile || !sameFileContents(source, target)) complete = false
            }
        }
        complete
    }.getOrDefault(false)

    private fun sameFileContents(first: File, second: File): Boolean {
        if (!first.isFile || !second.isFile || first.length() != second.length()) return false
        return try {
            first.inputStream().buffered().use { left ->
                second.inputStream().buffered().use { right ->
                    val leftBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val rightBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val leftCount = left.read(leftBuffer)
                        val rightCount = right.read(rightBuffer)
                        if (leftCount != rightCount) return false
                        if (leftCount < 0) return true
                        for (index in 0 until leftCount) {
                            if (leftBuffer[index] != rightBuffer[index]) return false
                        }
                    }
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun putPreference(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }

    private fun preferenceValuesEqual(current: Any?, legacy: Any?): Boolean = when {
        current is Set<*> && legacy is Set<*> ->
            current.filterIsInstance<String>().toSet() == legacy.filterIsInstance<String>().toSet()
        else -> current == legacy
    }
}
