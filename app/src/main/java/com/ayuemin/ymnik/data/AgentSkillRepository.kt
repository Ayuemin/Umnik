package com.ayuemin.ymnik.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ayuemin.ymnik.model.AgentSkill
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/** Skills owned by one concrete agent. */
class AgentSkillRepository(private val context: Context) {
    private val gson = Gson()
    private val agentsRoot = File(context.filesDir, "agents").apply { mkdirs() }
    private val type = object : TypeToken<List<AgentSkill>>() {}.type
    private val allowed = setOf("md", "txt", "json", "yaml", "yml")
    private val loadErrors = mutableMapOf<String, String>()

    fun list(agentId: String): List<AgentSkill> = runCatching {
        val atomic = AtomicJsonFile(metadataFile(agentId))
        atomic.read(::validJson)?.let { gson.fromJson<List<AgentSkill>>(it, type) } ?: emptyList()
    }.onSuccess { loadErrors.remove(agentId) }
        .onFailure { loadErrors[agentId] = "Данные навыков агента повреждены и защищены от перезаписи." }
        .getOrDefault(emptyList())

    fun createInline(agentId: String, name: String, body: String): AgentSkill {
        require(agentId.isNotBlank()) { "agentId обязателен" }
        val cleanBody = body.trim()
        require(cleanBody.isNotBlank()) { "Текст навыка пуст" }
        require(cleanBody.length <= MAX_INLINE_CHARS) { "Короткий навык агента ограничен 12 000 символов" }
        val cleanName = name.trim().ifBlank { "Навык" }
        val id = UUID.randomUUID().toString()
        val dir = skillDir(agentId, id).apply { mkdirs() }
        val fileName = "SKILL.md"
        return try {
            File(dir, fileName).writeText(cleanBody, Charsets.UTF_8)
            val skill = AgentSkill(id = id, agentId = agentId, name = cleanName, files = listOf(fileName))
            save(agentId, list(agentId) + skill)
            skill
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun importFile(agentId: String, uri: Uri): AgentSkill {
        val doc = DocumentFile.fromSingleUri(context, uri) ?: error("Не удалось открыть файл")
        val sourceName = doc.name ?: "SKILL.md"
        val ext = sourceName.substringAfterLast('.', "").lowercase()
        require(ext in allowed) { "Навык должен быть текстовым: .md, .txt, .json, .yaml или .yml" }
        val declaredSize = doc.length()
        require(declaredSize <= MAX_FILE_BYTES || declaredSize <= 0L) { "Один файл навыка ограничен 1,5 МБ" }

        val id = UUID.randomUUID().toString()
        val dir = skillDir(agentId, id).apply { mkdirs() }
        val targetName = safeName(sourceName)
        val target = File(dir, targetName)
        return try {
            copyText(uri, target)
            require(target.length() <= MAX_FILE_BYTES) { "Один файл навыка ограничен 1,5 МБ" }
            val skill = AgentSkill(
                id = id,
                agentId = agentId,
                name = sourceName.substringBeforeLast('.').ifBlank { sourceName },
                files = listOf(targetName)
            )
            save(agentId, list(agentId) + skill)
            skill
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun importTree(agentId: String, uri: Uri): AgentSkill {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: error("Не удалось открыть папку")
        val id = UUID.randomUUID().toString()
        val dir = skillDir(agentId, id).apply { mkdirs() }
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
            val skill = AgentSkill(
                id = id,
                agentId = agentId,
                name = tree.name ?: copied.first().substringBeforeLast('.'),
                files = copied.sortedWith(compareBy<String> { if (it.endsWith("SKILL.md", true)) 0 else 1 }.thenBy { it })
            )
            save(agentId, list(agentId) + skill)
            return skill
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun delete(agentId: String, skillId: String) {
        save(agentId, list(agentId).filterNot { it.id == skillId })
        skillDir(agentId, skillId).deleteRecursively()
    }

    fun promptFor(agentId: String, ids: Set<String>): String {
        if (ids.isEmpty()) return ""
        val builder = StringBuilder()
        list(agentId).filter { it.id in ids }.forEach { skill ->
            appendBounded(builder, "## Навык агента: ${skill.name}\n")
            skill.files.forEach { rel ->
                val file = File(skillDir(agentId, skill.id), rel)
                if (!file.exists()) return@forEach
                appendBounded(builder, "### Файл: $rel\n")
                appendFileBounded(builder, file)
                appendBounded(builder, "\n\n")
            }
        }
        return builder.toString().trim()
    }

    private fun save(agentId: String, skills: List<AgentSkill>) {
        check(loadErrors[agentId] == null) { loadErrors[agentId] ?: "Хранилище навыков агента недоступно" }
        val metadata = metadataFile(agentId)
        metadata.parentFile?.mkdirs()
        AtomicJsonFile(metadata).write(gson.toJson(skills), ::validJson)
    }

    private fun appendBounded(builder: StringBuilder, text: String) {
        require(builder.length + text.length <= MAX_PROMPT_CHARS) {
            "Навыки агента слишком велики. Оставьте меньше навыков или сократите их текст."
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
                    "Навыки агента слишком велики. Оставьте меньше навыков или сократите их текст."
                }
                builder.append(buffer, 0, read)
            }
        }
    }

    private fun metadataFile(agentId: String): File = File(skillsRoot(agentId), "skills.json")
    private fun skillsRoot(agentId: String): File = File(File(agentsRoot, safeId(agentId)), "skills").apply { mkdirs() }
    private fun skillDir(agentId: String, skillId: String): File = File(skillsRoot(agentId), safeId(skillId))

    private fun copyText(uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось прочитать файл" }
            target.outputStream().buffered().use { output -> input.copyTo(output) }
        }
    }

    private fun validJson(json: String): Boolean = runCatching {
        gson.fromJson<List<AgentSkill>>(json, type) != null
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
