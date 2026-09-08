package com.ayuemin.ymnik.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ayuemin.ymnik.model.Skill
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

class SkillRepository(private val context: Context) {
    private val gson = Gson()
    private val root = File(context.filesDir, "skills").apply { mkdirs() }
    private val metadata = File(root, "skills.json")
    private val allowed = setOf("md", "txt", "json", "yaml", "yml")

    fun list(): List<Skill> = runCatching {
        if (!metadata.exists()) return emptyList()
        val type = object : TypeToken<List<Skill>>() {}.type
        gson.fromJson<List<Skill>>(metadata.readText(), type) ?: emptyList()
    }.getOrDefault(emptyList())

    fun importFile(uri: Uri): Skill {
        val doc = DocumentFile.fromSingleUri(context, uri) ?: error("Не удалось открыть файл")
        val name = doc.name ?: "SKILL.md"
        val ext = name.substringAfterLast('.', "").lowercase()
        require(ext in allowed) { "Навык должен быть текстовым: .md, .txt, .json, .yaml или .yml" }
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        copyText(uri, File(dir, safeName(name)))
        val skill = Skill(id, name.substringBeforeLast('.').ifBlank { name }, listOf(safeName(name)))
        save(list() + skill)
        return skill
    }

    fun importTree(uri: Uri): Skill {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: error("Не удалось открыть папку")
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
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
        val preferredName = tree.name ?: copied.first().substringBeforeLast('.')
        val skill = Skill(id, preferredName, copied.sortedWith(compareBy<String> { if (it.endsWith("SKILL.md", true)) 0 else 1 }.thenBy { it }))
        save(list() + skill)
        return skill
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        save(list().filterNot { it.id == id })
    }

    fun promptFor(ids: Set<String>): String {
        if (ids.isEmpty()) return ""
        return list().filter { it.id in ids }.joinToString("\n\n") { skill ->
            val body = skill.files.joinToString("\n\n") { rel ->
                val file = File(File(root, skill.id), rel)
                if (!file.exists()) "" else "### Файл: $rel\n${file.readText()}"
            }
            "## Подключённый навык: ${skill.name}\n$body"
        }
    }

    private fun copyText(uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось прочитать файл" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun save(skills: List<Skill>) {
        metadata.writeText(gson.toJson(skills))
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_")
}
