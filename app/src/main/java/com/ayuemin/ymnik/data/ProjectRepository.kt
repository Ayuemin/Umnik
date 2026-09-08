package com.ayuemin.ymnik.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ayuemin.ymnik.model.Project
import com.ayuemin.ymnik.model.ProjectFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

class ProjectRepository(private val context: Context) {
    private val gson = Gson()
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val metadata = File(root, "projects.json")

    fun list(): List<Project> = runCatching {
        if (!metadata.exists()) return emptyList()
        val type = object : TypeToken<List<Project>>() {}.type
        gson.fromJson<List<Project>>(metadata.readText(), type) ?: emptyList()
    }.getOrDefault(emptyList())

    fun save(projects: List<Project>) {
        root.mkdirs()
        metadata.writeText(gson.toJson(projects))
    }

    fun importFile(projectId: String, uri: Uri): ProjectFile {
        var name = "file"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                    name = cursor.getString(it) ?: name
                }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let {
                    size = cursor.getLong(it)
                }
            }
        }
        if (size > 25L * 1024 * 1024) error("Один файл проекта пока ограничен 25 МБ")

        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val dir = File(root, safeId(projectId)).resolve("files").apply { mkdirs() }
        val displayName = safeName(name).ifBlank { "file" }
        val target = File(dir, "${UUID.randomUUID()}_$displayName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Не удалось прочитать $name")

        return ProjectFile(
            id = UUID.randomUUID().toString(),
            name = name,
            mimeType = mime,
            localPath = target.absolutePath,
            size = target.length()
        )
    }

    fun deleteFile(file: ProjectFile): Boolean {
        val target = File(file.localPath)
        if (!isInside(target, root)) return false
        return !target.exists() || target.delete()
    }

    fun deleteProjectFiles(projectId: String) {
        File(root, safeId(projectId)).deleteRecursively()
    }

    private fun safeId(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun safeName(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_")
        .take(120)

    private fun isInside(file: File, parent: File): Boolean = runCatching {
        val f = file.canonicalFile
        val p = parent.canonicalFile
        f.path.startsWith(p.path + File.separator)
    }.getOrDefault(false)
}
