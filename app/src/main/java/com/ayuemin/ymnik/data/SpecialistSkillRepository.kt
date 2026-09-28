package com.ayuemin.ymnik.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ayuemin.ymnik.model.SpecialistSkill
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/** Skills owned by one concrete specialist. */
class SpecialistSkillRepository(private val context: Context) {
    private val gson = Gson()
    private val specialistsRoot = LegacyDomainStorageMigration.migrateDirectory(context, "agents", "specialists")
    private val type = object : TypeToken<List<SpecialistSkill>>() {}.type
    private val allowed = setOf("md", "txt", "json", "yaml", "yml")
    private val loadErrors = mutableMapOf<String, String>()

    fun list(specialistId: String): List<SpecialistSkill> = runCatching {
        val atomic = AtomicJsonFile(metadataFile(specialistId))
        atomic.read(::validJson)?.let { gson.fromJson<List<SpecialistSkill>>(it, type) } ?: emptyList()
    }.onSuccess { loadErrors.remove(specialistId) }
        .onFailure { loadErrors[specialistId] = "Данные навыков специалиста повреждены и защищены от перезаписи." }
        .getOrDefault(emptyList())

    fun createInline(specialistId: String, name: String, body: String): SpecialistSkill {
        require(specialistId.isNotBlank()) { "specialistId обязателен" }
        val cleanBody = body.trim()
        require(cleanBody.isNotBlank()) { "Текст навыка пуст" }
        require(cleanBody.length <= MAX_INLINE_CHARS) { "Короткий навык специалиста ограничен 12 000 символов" }
        val cleanName = name.trim().ifBlank { "Навык" }
        val id = UUID.randomUUID().toString()
        val dir = skillDir(specialistId, id).apply { mkdirs() }
        val fileName = "SKILL.md"
        return try {
            File(dir, fileName).writeText(cleanBody, Charsets.UTF_8)
            val skill = SpecialistSkill(id = id, specialistId = specialistId, name = cleanName, files = listOf(fileName))
            save(specialistId, list(specialistId) + skill)
            skill
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun importFile(specialistId: String, uri: Uri): SpecialistSkill {
        val doc = DocumentFile.fromSingleUri(context, uri) ?: error("Не удалось открыть файл")
        val sourceName = doc.name ?: "SKILL.md"
        val ext = sourceName.substringAfterLast('.', "").lowercase()
        require(ext in allowed) { "Навык должен быть текстовым: .md, .txt, .json, .yaml или .yml" }
        val declaredSize = doc.length()
        require(declaredSize <= MAX_FILE_BYTES || declaredSize <= 0L) { "Один файл навыка ограничен 1,5 МБ" }

        val id = UUID.randomUUID().toString()
        val dir = skillDir(specialistId, id).apply { mkdirs() }
        val targetName = safeName(sourceName)
        val target = File(dir, targetName)
        return try {
            copyText(uri, target)
            require(target.length() <= MAX_FILE_BYTES) { "Один файл навыка ограничен 1,5 МБ" }
            val skill = SpecialistSkill(
                id = id,
                specialistId = specialistId,
                name = sourceName.substringBeforeLast('.').ifBlank { sourceName },
                files = listOf(targetName)
            )
            save(specialistId, list(specialistId) + skill)
            skill
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun importTree(specialistId: String, uri: Uri): SpecialistSkill {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: error("Не удалось открыть папку")
        val id = UUID.randomUUID().toString()
        val dir = skillDir(specialistId, id).apply { mkdirs() }
        val copied = mutableListOf<String>()
        var total = 0L

        try {
            fun walk(node: DocumentFile, prefix: String = "") {
                node.listFiles().forEach { child ->
                    if (child.isDirectory) {
                        walk(child, prefix + safeName(child.name ?: "folder") + "/")
                    } else {
                        val name = child.name ?: return@forEach
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext !in allowed) return@forEach
                        val declared = child.length()
                        if (declared > MAX_FILE_BYTES || (declared > 0L && total + declared > MAX_TREE_BYTES)) return@forEach
                        val rel = prefix + safeName(name)
                        val target = File(dir, rel)
                        target.parentFile?.mkdirs()
                        copyText(child.uri, target)
                        val actual = target.length()
                        if (actual > MAX_FILE_BYTES || total + actual > MAX_TREE_BYTES) {
                            target.delete()
                            return@forEach
                        }
                        total += actual
                        copied += rel
                    }
                }
            }

            walk(tree)
            require(copied.isNotEmpty()) { "В папке нет поддерживаемых текстовых файлов" }
            val skill = SpecialistSkill(
                id = id,
                specialistId = specialistId,
                name = tree.name ?: copied.first().substringBeforeLast('.'),
                files = copied.sortedWith(compareBy<String> { if (it.endsWith("SKILL.md", true)) 0 else 1 }.thenBy { it })
            )
            save(specialistId, list(specialistId) + skill)
            return skill
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun delete(specialistId: String, skillId: String) {
        save(specialistId, list(specialistId).filterNot { it.id == skillId })
        skillDir(specialistId, skillId).deleteRecursively()
    }

    fun promptFor(specialistId: String, ids: Set<String>): String {
        if (ids.isEmpty()) return ""
        val builder = StringBuilder()
        list(specialistId).filter { it.id in ids }.forEach { skill ->
            appendBounded(builder, "## Навык специалиста: ${skill.name}\n")
            skill.files.forEach { rel ->
                val file = File(skillDir(specialistId, skill.id), rel)
                if (!file.exists()) return@forEach
                appendBounded(builder, "### Файл: $rel\n")
                appendFileBounded(builder, file)
                appendBounded(builder, "\n\n")
            }
        }
        return builder.toString().trim()
    }

    private fun save(specialistId: String, skills: List<SpecialistSkill>) {
        check(loadErrors[specialistId] == null) { loadErrors[specialistId] ?: "Хранилище навыков специалиста недоступно" }
        val metadata = metadataFile(specialistId)
        metadata.parentFile?.mkdirs()
        AtomicJsonFile(metadata).write(gson.toJson(skills), ::validJson)
    }

    private fun appendBounded(builder: StringBuilder, text: String) {
        require(builder.length + text.length <= MAX_PROMPT_CHARS) {
            "Навыки специалиста слишком велики. Оставьте меньше навыков или сократите их текст."
        }
        builder.append(text)
    }

    private fun appendFileBounded(builder: StringBuilder, file: File) {
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8192)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                require(builder.length + read <= MAX_PROMPT_CHARS) {
                    "Навыки специалиста слишком велики. Оставьте меньше навыков или сократите их текст."
                }
                builder.append(buffer, 0, read)
            }
        }
    }

    private fun metadataFile(specialistId: String): File = File(skillsRoot(specialistId), "skills.json")
    private fun skillsRoot(specialistId: String): File = File(File(specialistsRoot, safeId(specialistId)), "skills").apply { mkdirs() }
    private fun skillDir(specialistId: String, skillId: String): File = File(skillsRoot(specialistId), safeId(skillId))

    private fun copyText(uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось прочитать файл" }
            target.outputStream().buffered().use { output -> input.copyTo(output) }
        }
    }

    private fun validJson(json: String): Boolean = runCatching {
        gson.fromJson<List<SpecialistSkill>>(json, type) != null
    }.getOrDefault(false)

    private fun safeId(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_").take(120)

    companion object {
        private const val MAX_FILE_BYTES = 1_500_000L
        private const val MAX_TREE_BYTES = 4_000_000L
        private const val MAX_INLINE_CHARS = 12_000
        private const val MAX_PROMPT_CHARS = 200_000
    }
}
