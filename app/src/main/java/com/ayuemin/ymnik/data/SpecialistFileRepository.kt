package com.ayuemin.ymnik.data

import android.content.Context
import com.ayuemin.ymnik.model.ChatFile
import com.ayuemin.ymnik.model.PendingAttachment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Persistent files owned by exactly one specialist.
 *
 * Physical layout:
 * filesDir/specialists/<specialistId>/files/
 *   files.json
 *   <uuid>_<name>
 */
class SpecialistFileRepository(private val context: Context) {
    private val gson = Gson()
    private val specialistsRoot = LegacyDomainStorageMigration.migrateDirectory(context, "agents", "specialists")
    private val type = object : TypeToken<List<ChatFile>>() {}.type
    private val loadErrors = mutableMapOf<String, String>()

    fun list(specialistId: String): List<ChatFile> = runCatching {
        AtomicJsonFile(metadata(specialistId)).read(::validJson)
            ?.let { gson.fromJson<List<ChatFile>>(it, type) }
            .orEmpty()
    }.onSuccess { loadErrors.remove(specialistId) }
        .onFailure { loadErrors[specialistId] = "Данные файлов специалиста повреждены и защищены от перезаписи." }
        .getOrDefault(emptyList())

    fun importFile(specialistId: String, attachment: PendingAttachment): ChatFile {
        require(specialistId.isNotBlank()) { "specialistId обязателен" }
        require(attachment.size <= MAX_BYTES || attachment.size <= 0L) {
            "Файл специалиста должен быть не больше ${MAX_BYTES / 1024 / 1024} МБ"
        }
        val dir = root(specialistId).apply { mkdirs() }
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
            "Файл специалиста должен быть не больше ${MAX_BYTES / 1024 / 1024} МБ"
        }

        val file = ChatFile(
            id = UUID.randomUUID().toString(),
            name = attachment.name,
            mimeType = attachment.mimeType,
            localPath = target.absolutePath,
            size = target.length()
        )
        return try {
            save(specialistId, list(specialistId) + file)
            file
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun delete(specialistId: String, fileId: String): Boolean {
        val files = list(specialistId)
        val target = files.firstOrNull { it.id == fileId } ?: return false
        val physical = File(target.localPath)
        if (isInside(physical, root(specialistId))) physical.delete()
        save(specialistId, files.filterNot { it.id == fileId })
        return true
    }

    private fun save(specialistId: String, files: List<ChatFile>) {
        check(loadErrors[specialistId] == null) { loadErrors[specialistId] ?: "Хранилище файлов специалиста недоступно" }
        val meta = metadata(specialistId)
        meta.parentFile?.mkdirs()
        AtomicJsonFile(meta).write(gson.toJson(files), ::validJson)
    }

    private fun root(specialistId: String): File =
        File(File(specialistsRoot, safeId(specialistId)), "files").apply { mkdirs() }

    private fun metadata(specialistId: String): File = File(root(specialistId), "files.json")

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
