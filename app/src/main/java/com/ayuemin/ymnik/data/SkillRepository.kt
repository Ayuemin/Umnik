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
    private val atomic = AtomicJsonFile(metadata)
    private val type = object : TypeToken<List<Skill>>() {}.type
    var loadError: String? = null
        private set
    private val allowed = setOf("md", "txt", "json", "yaml", "yml")

    fun list(): List<Skill> = runCatching {
        atomic.read(::validJson)?.let { gson.fromJson<List<Skill>>(it, type) } ?: emptyList()
    }.onFailure { loadError = "Данные навыков повреждены и защищены от перезаписи." }
        .getOrDefault(emptyList())

    fun importFile(uri: Uri): Skill {
        val doc = DocumentFile.fromSingleUri(context, uri) ?: error("Не удалось открыть файл")
        val name = doc.name ?: "SKILL.md"
        val ext = name.substringAfterLast('.', "").lowercase()
        require(ext in allowed) { "Навык должен быть текстовым: .md, .txt, .json, .yaml или .yml" }
        val declaredSize = doc.length()
        require(declaredSize <= MAX_FILE_BYTES || declaredSize <= 0L) { "Один файл навыка ограничен 1,5 МБ" }

        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val targetName = safeName(name)
        val target = File(dir, targetName)
        return try {
            copyText(uri, target)
            require(target.length() <= MAX_FILE_BYTES) { "Один файл навыка ограничен 1,5 МБ" }
            val skill = Skill(id, name.substringBeforeLast('.').ifBlank { name }, listOf(targetName))
            save(list() + skill)
            skill
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun createText(text: String): Skill {
        val body = text.trim()
        require(body.isNotBlank()) { "Введите текст навыка" }
        require(body.length <= MAX_INLINE_CHARS) { "Короткий навык ограничен 12 000 символов" }

        val firstLine = body.lineSequence()
            .map { it.trim().removePrefix("#").trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        val name = firstLine.replace(Regex("\\s+"), " ").take(48).ifBlank { "Короткий навык" }
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val fileName = "SKILL.md"
        return try {
            File(dir, fileName).writeText(body)
            val skill = Skill(id, name, listOf(fileName))
            save(list() + skill)
            skill
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun importTree(uri: Uri): Skill {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: error("Не удалось открыть папку")
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
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
            val preferredName = tree.name ?: copied.first().substringBeforeLast('.')
            val skill = Skill(
                id,
                preferredName,
                copied.sortedWith(compareBy<String> { if (it.endsWith("SKILL.md", true)) 0 else 1 }.thenBy { it })
            )
            save(list() + skill)
            return skill
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    fun delete(id: String) {
        save(list().filterNot { it.id == id })
        File(root, id).deleteRecursively()
    }

    fun promptFor(ids: Set<String>): String {
        if (ids.isEmpty()) return ""
        val builder = StringBuilder()
        list().filter { it.id in ids }.forEach { skill ->
            appendBounded(builder, "## Подключённый навык: ${skill.name}\n")
            skill.files.forEach { rel ->
                val file = File(File(root, skill.id), rel)
                if (!file.exists()) return@forEach
                appendBounded(builder, "### Файл: $rel\n")
                appendFileBounded(builder, file)
                appendBounded(builder, "\n\n")
            }
        }
        return builder.toString().trim()
    }

    private fun appendBounded(builder: StringBuilder, text: String) {
        require(builder.length + text.length <= MAX_PROMPT_CHARS) {
            "Подключённые навыки слишком велики. Оставьте меньше навыков или сократите их текст."
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
                    "Подключённые навыки слишком велики. Оставьте меньше навыков или сократите их текст."
                }
                builder.append(buffer, 0, read)
            }
        }
    }

    private fun copyText(uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Не удалось прочитать файл" }
            target.outputStream().buffered().use { output -> input.copyTo(output) }
        }
    }

    private fun save(skills: List<Skill>) {
        check(loadError == null) { loadError ?: "Хранилище навыков недоступно" }
        atomic.write(gson.toJson(skills), ::validJson)
    }

    private fun validJson(json: String): Boolean = runCatching {
        gson.fromJson<List<Skill>>(json, type) != null
    }.getOrDefault(false)

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_")

    companion object {
        private const val MAX_FILE_BYTES = 1_500_000L
        private const val MAX_TREE_BYTES = 4_000_000L
        private const val MAX_INLINE_CHARS = 12_000
        private const val MAX_PROMPT_CHARS = 200_000
    }
}
