package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.StorageStats
import com.ayuemin.ymnik.model.StoredFile
import java.io.File
import java.util.UUID

class StorageRepository(private val context: Context) {
    private val generatedRoot = File(context.filesDir, "generated").apply { mkdirs() }
    private val exportsRoot = File(context.filesDir, "exports").apply { mkdirs() }
    private val skillsRoot = File(context.filesDir, "skills").apply { mkdirs() }
    private val projectsRoot = File(context.filesDir, "projects").apply { mkdirs() }
    private val chatFilesRoot = File(context.filesDir, "chat_files").apply { mkdirs() }
    private val soundsRoot = File(context.filesDir, "sounds").apply { mkdirs() }
    private val chatsFile = File(File(context.filesDir, "chats"), "chats.json")

    fun list(): List<StoredFile> {
        val items = mutableListOf<StoredFile>()
        collect(generatedRoot, "Сгенерировано", true, items)
        collect(exportsRoot, "Экспорт", true, items)
        collect(soundsRoot, "Звуки", true, items)
        collect(skillsRoot, "Навыки", false, items, skipName = "skills.json")
        collect(projectsRoot, "Проекты", false, items, skipName = "projects.json")
        collect(chatFilesRoot, "Файлы чатов", false, items)
        return items.sortedByDescending { it.modifiedAt }
    }

    fun stats(): StorageStats = StorageStats(
        generatedBytes = sizeOf(generatedRoot),
        exportBytes = sizeOf(exportsRoot),
        skillBytes = sizeOf(skillsRoot),
        projectBytes = sizeOf(projectsRoot),
        chatBytes = (if (chatsFile.exists()) chatsFile.length() else 0L) + sizeOf(chatFilesRoot),
        soundBytes = sizeOf(soundsRoot)
    )

    fun delete(path: String): Boolean {
        val target = File(path)
        if (!isInside(target, generatedRoot) && !isInside(target, exportsRoot) && !isInside(target, soundsRoot)) return false
        return target.delete()
    }

    fun clearWorkingFiles() {
        generatedRoot.deleteRecursively()
        exportsRoot.deleteRecursively()
        generatedRoot.mkdirs()
        exportsRoot.mkdirs()
    }

    private fun collect(
        root: File,
        category: String,
        deletable: Boolean,
        out: MutableList<StoredFile>,
        skipName: String? = null
    ) {
        if (!root.exists()) return
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            if (skipName != null && file.name == skipName) return@forEach
            val displayName = if (category == "Сгенерировано") {
                file.name.substringAfter('_', file.name)
            } else {
                file.name.substringAfter('_', file.name)
            }
            out += StoredFile(
                id = UUID.nameUUIDFromBytes(file.absolutePath.toByteArray()).toString(),
                name = displayName,
                mimeType = mimeFor(file),
                localPath = file.absolutePath,
                size = file.length(),
                modifiedAt = file.lastModified(),
                category = category,
                deletable = deletable
            )
        }
    }

    private fun sizeOf(root: File): Long {
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun isInside(file: File, root: File): Boolean = runCatching {
        val canonicalFile = file.canonicalFile
        val canonicalRoot = root.canonicalFile
        canonicalFile.path.startsWith(canonicalRoot.path + File.separator)
    }.getOrDefault(false)

    private fun mimeFor(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        "md" -> "text/markdown"
        "txt" -> "text/plain"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "yaml", "yml" -> "application/yaml"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }
}
