package com.ayuemin.ymnik.data

import android.util.AtomicFile
import java.io.File

/** Keeps a last-known-good copy and never replaces the main file with a partial write. */
internal class AtomicJsonFile(file: File) {
    private val primary = AtomicFile(file)
    private val backupPath = File(file.parentFile, "${file.name}.lastgood")
    private val backup = AtomicFile(backupPath)
    private val path = file

    fun read(parse: (String) -> Boolean): String? {
        if (!path.exists() && !File(path.path + ".bak").exists() &&
            !backupPath.exists() && !File(backupPath.path + ".bak").exists()
        ) return null
        val current = runCatching { primary.openRead().bufferedReader().use { it.readText() } }
            .getOrNull()?.takeIf(parse)
        if (current != null) return current
        return runCatching { backup.openRead().bufferedReader().use { it.readText() } }
            .getOrNull()?.takeIf(parse)
            ?: error("Не удалось прочитать ${path.name}: основная и резервная копии повреждены. Данные сохранены для восстановления.")
    }

    fun write(json: String, parse: (String) -> Boolean) {
        // Do not turn an already-corrupt file into a valid empty collection.
        val previous = read(parse)
        if (previous != null) writeAtomic(backup, previous)
        writeAtomic(primary, json)
    }

    private fun writeAtomic(target: AtomicFile, value: String) {
        val stream = target.startWrite()
        try {
            stream.write(value.toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (error: Throwable) {
            target.failWrite(stream)
            throw error
        }
    }
}
