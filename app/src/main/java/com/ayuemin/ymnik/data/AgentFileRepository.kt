package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.ChatFile
import com.ayuemin.ymnik.model.PendingAttachment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Persistent files owned by exactly one agent.
 *
 * Physical layout:
 * filesDir/agents/<agentId>/files/
 *   files.json
 *   <uuid>_<name>
 */
class AgentFileRepository(private val context: Context) {
    private val gson = Gson()
    private val agentsRoot = File(context.filesDir, "agents").apply { mkdirs() }
    private val type = object : TypeToken<List<ChatFile>>() {}.type

    fun list(agentId: String): List<ChatFile> = runCatching {
        AtomicJsonFile(metadata(agentId)).read(::validJson)
            ?.let { gson.fromJson<List<ChatFile>>(it, type) }
            .orEmpty()
    }.getOrDefault(emptyList())

    fun importFile(agentId: String, attachment: PendingAttachment): ChatFile {
        require(agentId.isNotBlank()) { "agentId обязателен" }
        require(attachment.size <= MAX_BYTES || attachment.size <= 0L) {
            "Файл агента должен быть не больше ${MAX_BYTES / 1024 / 1024} МБ"
        }
        val dir = root(agentId).apply { mkdirs() }
        val name = safeName(attachment.name).ifBlank { "file" }
        val target = File(dir, "${UUID.randomUUID()}_$name")

        val source = attachment.localPath
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?.inputStream()
            ?: context.contentResolver.openInputStream(android.net.Uri.parse(attachment.uri))
            ?: error("Не удалось открыть ${attachment.name}")

        source.use { input ->
            target.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        require(target.length() <= MAX_BYTES) {
            target.delete()
            "Файл агента должен быть не больше ${MAX_BYTES / 1024 / 1024} МБ"
        }

        val file = ChatFile(
            id = UUID.randomUUID().toString(),
            name = attachment.name,
            mimeType = attachment.mimeType,
            localPath = target.absolutePath,
            size = target.length()
        )
        save(agentId, list(agentId) + file)
        return file
    }

    fun delete(agentId: String, fileId: String): Boolean {
        val files = list(agentId)
        val target = files.firstOrNull { it.id == fileId } ?: return false
        val physical = File(target.localPath)
        if (isInside(physical, root(agentId))) physical.delete()
        save(agentId, files.filterNot { it.id == fileId })
        return true
    }

    private fun save(agentId: String, files: List<ChatFile>) {
        val meta = metadata(agentId)
        meta.parentFile?.mkdirs()
        AtomicJsonFile(meta).write(gson.toJson(files), ::validJson)
    }

    private fun root(agentId: String): File =
        File(File(agentsRoot, safeId(agentId)), "files").apply { mkdirs() }

    private fun metadata(agentId: String): File = File(root(agentId), "files.json")

    private fun validJson(raw: String): Boolean = runCatching {
        gson.fromJson<List<ChatFile>>(raw, type) != null
    }.getOrDefault(false)

    private fun safeId(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")

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

    companion object {
        private const val MAX_BYTES = 50L * 1024L * 1024L
    }
}
