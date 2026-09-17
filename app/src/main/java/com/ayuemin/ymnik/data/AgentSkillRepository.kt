package com.ayuemin.ymnik.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ayuemin.ymnik.model.AgentSkill
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Skills owned by one concrete agent.
 *
 * There is deliberately no global skill catalogue here. Importing or creating a skill
 * writes it under filesDir/agents/<agentId>/skills and it is invisible to other agents
 * unless the user explicitly copies it later.
 */
class AgentSkillRepository(private val context: Context) {
    private val gson = Gson()
    private val agentsRoot = File(context.filesDir, "agents").apply { mkdirs() }
    private val type = object : TypeToken<List<AgentSkill>>() {}.type
    private val allowed = setOf("md", "txt", "json", "yaml", "yml")

    fun list(agentId: String): List<AgentSkill> = runCatching {
        val atomic = AtomicJsonFile(metadataFile(agentId))
        atomic.read(::validJson)?.let { gson.fromJson<List<AgentSkill>>(it, type) } ?: emptyList()
    }.getOrDefault(emptyList())

    fun createInline(agentId: String, name: String, body: String): AgentSkill {
        require(agentId.isNotBlank()) { "agentId обязателен" }
        require(body.isNotBlank()) { "Текст навыка пуст" }
        val cleanName = name.trim().ifBlank { "Навык" }
        val id = UUID.randomUUID().toString()
        val dir = skillDir(agentId, id).apply { mkdirs() }
        val fileName = "SKILL.md"
        File(dir, fileName).writeText(body.trim(), Charsets.UTF_8)
        val skill = AgentSkill(
            id = id,
            agentId = agentId,
            name = cleanName,
            files = listOf(fileName)
        )
        save(agentId, list(agentId) + skill)
        return skill
    }

    fun importFile(agentId: String, uri: Uri): AgentSkill {
        val doc = DocumentFile.fromSingleUri(context, uri) ?: error("Не удалось открыть файл")
        val sourceName = doc.name ?: "SKILL.md"
        val ext = sourceName.substringAfterLast('.', "").lowercase()
        require(ext in allowed) { "Навык должен быть текстовым: .md, .txt, .json, .yaml или .yml" }

        val id = UUID.randomUUID().toString()
        val dir = skillDir(agentId, id).apply { mkdirs() }
        val targetName = safeName(sourceName)
        copyText(uri, File(dir, targetName))
        val skill = AgentSkill(
            id = id,
            agentId = agentId,
            name = sourceName.substringBeforeLast('.').ifBlank { sourceName },
            files = listOf(targetName)
        )
        save(agentId, list(agentId) + skill)
        return skill
    }

    fun importTree(agentId: String, uri: Uri): AgentSkill {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: error("Не удалось открыть папку")
        val id = UUID.randomUUID().toString()
        val dir = skillDir(agentId, id).apply { mkdirs() }
        val copied = mutableListOf<String>()
        var total = 0L

        fun walk(node: DocumentFile, prefix: String = "") {
            node.listFiles().forEach { child ->
                if (child.isDirectory) {
                    walk(child, prefix + safeName(child.name ?: "folder") + "/")
                } else {
                    val name = child.name ?: return@forEach
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in allowed) return@forEach
                    val size = child.length()
                    if (size > 1_500_000L || total + size > 4_000_000L) return@forEach
                    val rel = prefix + safeName(name)
                    val target = File(dir, rel)
                    target.parentFile?.mkdirs()
                    copyText(child.uri, target)
                    total += target.length()
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
    }

    fun delete(agentId: String, skillId: String) {
        skillDir(agentId, skillId).deleteRecursively()
        save(agentId, list(agentId).filterNot { it.id == skillId })
    }

    fun promptFor(agentId: String, ids: Set<String>): String {
        if (ids.isEmpty()) return ""
        return list(agentId)
            .filter { it.id in ids }
            .joinToString("\n\n") { skill ->
                val body = skill.files.joinToString("\n\n") { rel ->
                    val file = File(skillDir(agentId, skill.id), rel)
                    if (!file.exists()) "" else "### Файл: $rel\n${file.readText()}"
                }
                "## Навык агента: ${skill.name}\n$body"
            }
    }

    private fun save(agentId: String, skills: List<AgentSkill>) {
        val metadata = metadataFile(agentId)
        metadata.parentFile?.mkdirs()
        AtomicJsonFile(metadata).write(gson.toJson(skills), ::validJson)
    }

    private fun metadataFile(agentId: String): File = File(skillsRoot(agentId), "skills.json")

    private fun skillsRoot(agentId: String): File =
        File(File(agentsRoot, safeId(agentId)), "skills").apply { mkdirs() }

    private fun skillDir(agentId: String, skillId: String): File =
        File(skillsRoot(agentId), safeId(skillId))

    private fun copyText(uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось прочитать файл" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun validJson(json: String): Boolean = runCatching {
        gson.fromJson<List<AgentSkill>>(json, type) != null
    }.getOrDefault(false)

    private fun safeId(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun safeName(value: String): String = value
        .replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_")
        .take(120)
}
