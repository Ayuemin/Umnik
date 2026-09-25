
package com.ayuemin.ymnik.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ayuemin.ymnik.diagnostics.DiagnosticLog
import com.ayuemin.ymnik.model.GeneratedFile
import com.ayuemin.ymnik.model.PendingAttachment
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.eclipse.jgit.api.Git
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class LocalShellEngine(
    private val context: Context,
    private val runId: String = UUID.randomUUID().toString(),
    private val networkEnabled: Boolean = true
) {
    private val gson = Gson()
    private val root = File(context.cacheDir, "local-shell/" + runId).apply { mkdirs() }.canonicalFile
    private val exports = linkedMapOf<String, GeneratedFile>()
    private val http = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                require(addresses.isNotEmpty() && addresses.none(::isPrivateNetworkAddress)) {
                    "Доступ к локальным и служебным сетевым адресам запрещён"
                }
                return addresses
            }
        })
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    data class ImportedFile(val name: String, val size: Long)

    fun prepare(uris: List<Uri>): List<ImportedFile> {
        cleanupOldRuns()
        val inputDir = resolve("input").apply { mkdirs() }
        return uris.take(MAX_ATTACHMENTS).mapIndexed { index, uri ->
            val displayName = queryName(uri).ifBlank { "attachment_" + (index + 1) }
            val safe = uniqueName(inputDir, safeName(displayName))
            val target = File(inputDir, safe)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Не удалось прочитать " + displayName)
            require(target.length() <= MAX_SINGLE_FILE_BYTES) {
                "Файл " + displayName + " слишком большой для локального теста"
            }
            ImportedFile("input/" + safe, target.length())
        }
    }

    fun prepareAttachments(items: List<PendingAttachment>): List<ImportedFile> {
        cleanupOldRuns()
        val inputDir = resolve("input").apply { mkdirs() }
        return items.take(MAX_ATTACHMENTS).mapIndexed { index, item ->
            val displayName = item.name.trim().ifBlank { "attachment_" + (index + 1) }
            val safe = uniqueName(inputDir, safeName(displayName))
            val target = File(inputDir, safe)
            val input = item.localPath
                ?.takeIf { it.isNotBlank() }
                ?.let { path ->
                    val file = File(path)
                    require(file.isFile) { "Файл не найден: " + displayName }
                    file.inputStream()
                }
                ?: context.contentResolver.openInputStream(Uri.parse(item.uri))
                ?: error("Не удалось прочитать " + displayName)
            input.use { source ->
                target.outputStream().use { output -> source.copyTo(output) }
            }
            require(target.length() <= MAX_SINGLE_FILE_BYTES) {
                "Файл " + displayName + " слишком большой для локального теста"
            }
            ImportedFile("input/" + safe, target.length())
        }
    }

    fun importedSummary(): String {
        val input = resolve("input")
        val items = input.listFiles()?.sortedBy { it.name }.orEmpty()
        if (items.isEmpty()) return "Вложений нет."
        return items.joinToString("\n") { "- input/" + it.name + " (" + it.length() + " B)" }
    }

    fun exportedFiles(): List<GeneratedFile> = exports.values.toList()

    fun toolDefinitions(): JsonArray = JsonArray().apply {
        add(tool(
            "local_list",
            "Показать файлы и папки локальной рабочей области. Используй в начале задачи и после существенных изменений.",
            mapOf(
                "path" to stringProperty("Относительный путь внутри рабочей области. По умолчанию ."),
                "recursive" to boolProperty("Показать дерево рекурсивно")
            )
        ))
        add(tool(
            "local_read",
            "Прочитать текстовый файл. Для больших файлов читай только нужный диапазон строк.",
            mapOf(
                "path" to stringProperty("Относительный путь к файлу"),
                "start_line" to intProperty("Первая строка, начиная с 1"),
                "max_lines" to intProperty("Максимум строк, до 400")
            ),
            listOf("path")
        ))
        add(tool(
            "local_search",
            "Найти текст или регулярное выражение в файлах рабочей области.",
            mapOf(
                "query" to stringProperty("Искомый текст или regex"),
                "path" to stringProperty("Папка или файл, по умолчанию ."),
                "regex" to boolProperty("Считать query регулярным выражением")
            ),
            listOf("query")
        ))
        add(tool(
            "local_write",
            "Создать или полностью перезаписать текстовый файл.",
            mapOf(
                "path" to stringProperty("Относительный путь"),
                "content" to stringProperty("Полное содержимое файла")
            ),
            listOf("path", "content")
        ))
        add(tool(
            "local_replace",
            "Точно заменить фрагмент текста в существующем файле. Предпочитай для небольших правок.",
            mapOf(
                "path" to stringProperty("Относительный путь"),
                "old" to stringProperty("Точный старый фрагмент"),
                "new" to stringProperty("Новый фрагмент"),
                "replace_all" to boolProperty("Заменить все совпадения")
            ),
            listOf("path", "old", "new")
        ))
        add(tool(
            "local_command",
            "Запустить безопасную команду Android Toybox без shell. Доступны ls, find, grep, cat, head, tail, wc, sort, uniq, cut, sha256sum, diff, stat. Пути только относительные.",
            mapOf(
                "command" to stringProperty("Имя разрешённой команды"),
                "args" to arrayProperty("Аргументы команды")
            ),
            listOf("command")
        ))
        add(tool(
            "local_python",
            "Выполнить Python 3 локально на телефоне в рабочей области. Python не получает сеть и не должен запускать внешние процессы.",
            mapOf("code" to stringProperty("Код Python")),
            listOf("code")
        ))
        add(tool(
            "local_archive",
            "Служебно распаковать или упаковать ZIP, TAR или TAR.GZ внутри рабочей области. Для итогового файла пользователю используй local_export.",
            mapOf(
                "action" to stringProperty("unpack или pack"),
                "source" to stringProperty("Источник"),
                "destination" to stringProperty("Назначение"),
                "format" to stringProperty("zip, tar или tar.gz")
            ),
            listOf("action", "source", "destination", "format")
        ))
        add(tool(
            "local_git",
            "Git в локальной рабочей области. Поддержаны status, log, diff, init, add, commit, checkout и public_clone. Push отсутствует.",
            mapOf(
                "action" to stringProperty("status, log, diff, init, add, commit, checkout или public_clone"),
                "path" to stringProperty("Путь репозитория"),
                "arg" to stringProperty("Паттерн add, сообщение commit, ref checkout или HTTPS URL"),
                "destination" to stringProperty("Папка назначения для public_clone")
            ),
            listOf("action")
        ))
        add(tool(
            "local_fetch",
            "Получить URL через сетевой шлюз Umnik. Только HTTP GET, без отправки локальных файлов.",
            mapOf(
                "url" to stringProperty("HTTP/HTTPS URL"),
                "save_as" to stringProperty("Необязательный относительный путь для сохранения")
            ),
            listOf("url")
        ))
        add(tool(
            "local_export",
            "Опубликовать итоговый ZIP для пользователя. Это пользовательский результат, а не служебная упаковка: для промежуточных архивов используй local_archive. Повторный экспорт с тем же именем заменяет предыдущую версию результата.",
            mapOf(
                "source" to stringProperty("Папка или файл внутри рабочей области"),
                "filename" to stringProperty("Имя итогового ZIP")
            ),
            listOf("source", "filename")
        ))
    }

    fun execute(name: String, argsRaw: String): String {
        val started = System.currentTimeMillis()
        val result = runCatching {
            val args = gson.fromJson(argsRaw.ifBlank { "{}" }, JsonObject::class.java)
            when (name) {
                "local_list" -> list(args)
                "local_read" -> read(args)
                "local_search" -> search(args)
                "local_write" -> write(args)
                "local_replace" -> replace(args)
                "local_command" -> command(args)
                "local_python" -> python(args)
                "local_archive" -> archive(args)
                "local_git" -> git(args)
                "local_fetch" -> fetch(args)
                "local_export" -> export(args)
                else -> error("Неизвестный локальный инструмент: " + name)
            }
        }
        val elapsed = System.currentTimeMillis() - started
        DiagnosticLog.record(
            context,
            "LOCAL_SHELL_TOOL",
            "tool=" + name + "; ok=" + result.isSuccess + "; elapsedMs=" + elapsed +
                "; outputChars=" + (result.getOrNull()?.length ?: 0)
        )
        return result.getOrElse { error ->
            gson.toJson(mapOf("ok" to false, "error" to (error.message ?: "Ошибка локального инструмента")))
        }
    }

    private fun list(args: JsonObject): String {
        val base = resolve(args.string("path").ifBlank { "." })
        val recursive = args.bool("recursive")
        require(base.exists()) { "Путь не найден: " + relative(base) }
        val entries = if (recursive && base.isDirectory) {
            base.walkTopDown().drop(1).take(MAX_LIST_ENTRIES).toList()
        } else if (base.isDirectory) {
            base.listFiles()?.sortedBy { it.name }?.take(MAX_LIST_ENTRIES).orEmpty()
        } else listOf(base)
        val text = entries.joinToString("\n") { file ->
            relative(file) + if (file.isDirectory) "/" else " (" + file.length() + " B)"
        }.ifBlank { "(пусто)" }
        return ok(mapOf("path" to relative(base), "entries" to text, "truncated" to (entries.size >= MAX_LIST_ENTRIES)))
    }

    private fun read(args: JsonObject): String {
        val file = resolve(args.string("path"))
        require(file.isFile) { "Файл не найден: " + relative(file) }
        require(file.length() <= MAX_TEXT_FILE_BYTES) { "Файл слишком большой для чтения этим инструментом" }
        val start = args.int("start_line").coerceAtLeast(1)
        val maxLines = args.int("max_lines").let { if (it <= 0) 200 else it.coerceAtMost(400) }
        val lines = file.readLines(Charsets.UTF_8)
        val selected = lines.drop(start - 1).take(maxLines)
        val numbered = selected.mapIndexed { index, line -> (start + index).toString() + ": " + line }.joinToString("\n")
        return ok(mapOf("path" to relative(file), "start_line" to start, "returned_lines" to selected.size, "total_lines" to lines.size, "text" to numbered))
    }

    private fun search(args: JsonObject): String {
        val query = args.string("query")
        require(query.isNotBlank()) { "Пустой поисковый запрос" }
        val base = resolve(args.string("path").ifBlank { "." })
        require(base.exists()) { "Путь не найден" }
        val regex = if (args.bool("regex")) Regex(query) else null
        val candidates = if (base.isDirectory) base.walkTopDown().filter { it.isFile } else sequenceOf(base)
        val matches = mutableListOf<String>()
        for (file in candidates) {
            if (matches.size >= MAX_SEARCH_MATCHES) break
            if (file.length() > MAX_TEXT_FILE_BYTES) continue
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            text.lineSequence().forEachIndexed { index, line ->
                val hit = regex?.containsMatchIn(line) ?: line.contains(query, ignoreCase = true)
                if (hit && matches.size < MAX_SEARCH_MATCHES) {
                    matches += relative(file) + ":" + (index + 1) + ": " + line.take(500)
                }
            }
        }
        return ok(mapOf("matches" to matches.joinToString("\n"), "count" to matches.size, "truncated" to (matches.size >= MAX_SEARCH_MATCHES)))
    }

    private fun write(args: JsonObject): String {
        val path = args.string("path")
        require(path.isNotBlank()) { "Не указан путь" }
        val content = args.string("content")
        require(content.length <= MAX_WRITE_CHARS) { "Слишком большой текст для одной записи" }
        val file = resolve(path)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return ok(mapOf("path" to relative(file), "bytes" to file.length()))
    }

    private fun replace(args: JsonObject): String {
        val file = resolve(args.string("path"))
        require(file.isFile) { "Файл не найден" }
        val old = args.string("old")
        val new = args.string("new")
        require(old.isNotEmpty()) { "Старый фрагмент пуст" }
        val text = file.readText(Charsets.UTF_8)
        require(old in text) { "Указанный старый фрагмент не найден" }
        val replaceAll = args.bool("replace_all")
        file.writeText(if (replaceAll) text.replace(old, new) else text.replaceFirst(old, new), Charsets.UTF_8)
        return ok(mapOf("path" to relative(file), "replaced_all" to replaceAll, "bytes" to file.length()))
    }

    private fun command(args: JsonObject): String {
        val command = args.string("command").trim()
        require(command in ALLOWED_TOYBOX) { "Команда " + command + " не разрешена" }
        val rawArgs = args.getAsJsonArray("args")?.mapNotNull {
            it.takeIf { value -> value.isJsonPrimitive }?.asString
        }.orEmpty()
        rawArgs.forEach(::validateCommandArg)
        val toybox = File("/system/bin/toybox")
        val processArgs = if (toybox.canExecute()) listOf(toybox.absolutePath, command) + rawArgs
            else listOf("/system/bin/" + command) + rawArgs
        val process = ProcessBuilder(processArgs).directory(root).redirectErrorStream(false).start()
        val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("Команда превысила лимит времени")
        }
        val stdout = process.inputStream.bufferedReader().use { it.readText().take(MAX_TOOL_OUTPUT_CHARS) }
        val stderr = process.errorStream.bufferedReader().use { it.readText().take(MAX_TOOL_OUTPUT_CHARS) }
        return ok(mapOf("exit_code" to process.exitValue(), "stdout" to stdout, "stderr" to stderr))
    }

    private fun python(args: JsonObject): String {
        val code = args.string("code")
        require(code.isNotBlank()) { "Python-код пуст" }
        require(code.length <= MAX_PYTHON_CHARS) { "Python-код слишком большой для одного запуска" }
        synchronized(PYTHON_LOCK) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        }
        return Python.getInstance()
            .getModule("umnik_local_runtime")
            .callAttr("run", code, root.absolutePath)
            .toString()
            .take(MAX_TOOL_OUTPUT_CHARS * 2)
    }

    private fun archive(args: JsonObject): String {
        val action = args.string("action").lowercase()
        val source = resolve(args.string("source"))
        val destination = resolve(args.string("destination"))
        val format = args.string("format").lowercase()
        when (action) {
            "unpack" -> {
                require(source.isFile) { "Архив не найден" }
                destination.mkdirs()
                when (format) {
                    "zip" -> unzip(source, destination)
                    "tar" -> untar(source, destination, false)
                    "tar.gz", "tgz" -> untar(source, destination, true)
                    else -> error("Неподдерживаемый формат архива: " + format)
                }
            }
            "pack" -> {
                destination.parentFile?.mkdirs()
                when (format) {
                    "zip" -> zip(source, destination)
                    "tar" -> tar(source, destination, false)
                    "tar.gz", "tgz" -> tar(source, destination, true)
                    else -> error("Неподдерживаемый формат архива: " + format)
                }
            }
            else -> error("action должен быть unpack или pack")
        }
        return ok(mapOf("action" to action, "source" to relative(source), "destination" to relative(destination), "bytes" to destination.length()))
    }

    private fun git(args: JsonObject): String {
        val action = args.string("action").lowercase()
        val path = args.string("path").ifBlank { "." }
        if (action == "public_clone") {
            require(networkEnabled) { "Сеть для локального Shell выключена" }
            val url = args.string("arg")
            requirePublicNetworkUrl(url, httpsOnly = true)
            require(!url.contains("@")) { "URL с учётными данными запрещён" }
            val destination = resolve(args.string("destination").ifBlank { "repo" })
            require(!destination.exists() || destination.list().isNullOrEmpty()) { "Папка назначения уже занята" }
            Git.cloneRepository().setURI(url).setDirectory(destination).setCloneAllBranches(false).call().use { }
            return ok(mapOf("action" to action, "path" to relative(destination)))
        }
        val repo = resolve(path)
        if (action == "init") {
            repo.mkdirs()
            Git.init().setDirectory(repo).call().use { }
            return ok(mapOf("action" to action, "path" to relative(repo)))
        }
        require(File(repo, ".git").exists()) { "Git-репозиторий не найден: " + relative(repo) }
        Git.open(repo).use { git ->
            return when (action) {
                "status" -> {
                    val status = git.status().call()
                    ok(mapOf(
                        "added" to status.added.sorted().joinToString("\n"),
                        "changed" to status.changed.sorted().joinToString("\n"),
                        "modified" to status.modified.sorted().joinToString("\n"),
                        "missing" to status.missing.sorted().joinToString("\n"),
                        "removed" to status.removed.sorted().joinToString("\n"),
                        "untracked" to status.untracked.sorted().joinToString("\n")
                    ))
                }
                "log" -> ok(mapOf("log" to git.log().setMaxCount(20).call().joinToString("\n") { it.name.take(10) + " " + it.shortMessage }))
                "diff" -> {
                    val changes = git.diff().call().map { it.changeType.toString() + " " + it.oldPath + " -> " + it.newPath }
                    ok(mapOf("changes" to changes.joinToString("\n"), "count" to changes.size))
                }
                "add" -> {
                    git.add().addFilepattern(args.string("arg").ifBlank { "." }).call()
                    ok(mapOf("action" to action))
                }
                "commit" -> {
                    val commit = git.commit()
                        .setMessage(args.string("arg").ifBlank { "Local Shell changes" }.take(240))
                        .setAuthor("Umnik Local Shell", "local-shell@umnik.invalid")
                        .setCommitter("Umnik Local Shell", "local-shell@umnik.invalid")
                        .call()
                    ok(mapOf("commit" to commit.name, "message" to commit.shortMessage))
                }
                "checkout" -> {
                    val ref = args.string("arg")
                    require(ref.isNotBlank()) { "Не указан ref для checkout" }
                    git.checkout().setName(ref).call()
                    ok(mapOf("action" to action, "ref" to ref))
                }
                else -> error("Неподдерживаемая Git-операция: " + action)
            }
        }
    }

    private fun fetch(args: JsonObject): String {
        require(networkEnabled) { "Сеть для локального Shell выключена" }
        val url = args.string("url")
        requirePublicNetworkUrl(url, httpsOnly = false)
        val request = Request.Builder().url(url).header("User-Agent", "Umnik-Local-Shell/1").get().build()
        http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "HTTP " + response.code }
            val body = response.body ?: error("Пустой ответ")
            val bytes = body.byteStream().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_FETCH_BYTES) { "Ответ слишком большой" }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            val saveAs = args.string("save_as")
            if (saveAs.isNotBlank()) {
                val file = resolve(saveAs)
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                return ok(mapOf("status" to response.code, "saved_as" to relative(file), "bytes" to bytes.size, "content_type" to response.header("Content-Type").orEmpty()))
            }
            return ok(mapOf("status" to response.code, "bytes" to bytes.size, "content_type" to response.header("Content-Type").orEmpty(), "text" to bytes.toString(Charsets.UTF_8).take(MAX_FETCH_PREVIEW_CHARS)))
        }
    }

    private fun export(args: JsonObject): String {
        val source = resolve(args.string("source"))
        require(source.exists()) { "Источник для экспорта не найден" }
        val rawName = safeName(args.string("filename"))
        val filename = when {
            rawName.isBlank() -> "local-shell-result.zip"
            rawName.endsWith(".zip", true) -> rawName
            else -> rawName + ".zip"
        }
        val temp = resolve("exports/" + filename)
        temp.parentFile?.mkdirs()
        zip(source, temp, cleanRuntimeArtifacts = true)
        val generatedDir = File(context.filesDir, "generated").apply { mkdirs() }
        val stored = File(generatedDir, UUID.randomUUID().toString() + "_" + filename)
        temp.copyTo(stored, true)
        val generated = GeneratedFile(UUID.randomUUID().toString(), filename, "application/zip", stored.absolutePath, stored.length())
        exports.remove(filename)?.let { previous ->
            if (previous.localPath != generated.localPath) runCatching { File(previous.localPath).delete() }
        }
        exports[filename] = generated
        return ok(mapOf(
            "filename" to filename,
            "bytes" to generated.size,
            "ready_for_user" to true,
            "export_state" to "READY_TO_FINISH"
        ))
    }

    private fun unzip(source: File, destination: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val out = safeArchiveTarget(destination, entry.name)
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
                input.closeEntry()
            }
        }
    }

    private fun zip(source: File, destination: File, cleanRuntimeArtifacts: Boolean = false) {
        if (destination.exists()) destination.delete()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(destination))).use { output ->
            val base = if (source.isDirectory) source else source.parentFile ?: root
            val files = if (source.isDirectory) {
                source.walkTopDown().onEnter { dir ->
                    !cleanRuntimeArtifacts || dir == source || dir.name !in RUNTIME_CACHE_DIRS
                }
            } else sequenceOf(source)
            files.forEach { file ->
                if (file == source && source.isDirectory) return@forEach
                if (file.canonicalFile == destination.canonicalFile) return@forEach
                if (cleanRuntimeArtifacts && isRuntimeArtifact(file)) return@forEach
                val name = file.relativeTo(base).invariantSeparatorsPath + if (file.isDirectory) "/" else ""
                output.putNextEntry(ZipEntry(name))
                if (file.isFile) file.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
    }

    private fun isRuntimeArtifact(file: File): Boolean {
        if (file.name in RUNTIME_CACHE_DIRS) return true
        val lower = file.name.lowercase()
        return file.isFile && (lower.endsWith(".pyc") || lower.endsWith(".pyo"))
    }

    private fun untar(source: File, destination: File, gz: Boolean) {
        val raw = BufferedInputStream(FileInputStream(source))
        val wrapped = if (gz) GzipCompressorInputStream(raw) else raw
        TarArchiveInputStream(wrapped).use { input ->
            while (true) {
                val entry = input.nextTarEntry ?: break
                val out = safeArchiveTarget(destination, entry.name)
                if (entry.isSymbolicLink || entry.isLink) continue
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun tar(source: File, destination: File, gz: Boolean) {
        if (destination.exists()) destination.delete()
        val raw = BufferedOutputStream(FileOutputStream(destination))
        val wrapped = if (gz) GzipCompressorOutputStream(raw) else raw
        TarArchiveOutputStream(wrapped).use { output ->
            output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            val base = if (source.isDirectory) source else source.parentFile ?: root
            val files = if (source.isDirectory) source.walkTopDown() else sequenceOf(source)
            files.forEach { file ->
                if (file == source && source.isDirectory) return@forEach
                if (file.canonicalFile == destination.canonicalFile) return@forEach
                val name = file.relativeTo(base).invariantSeparatorsPath + if (file.isDirectory) "/" else ""
                output.putArchiveEntry(TarArchiveEntry(file, name))
                if (file.isFile) file.inputStream().use { it.copyTo(output) }
                output.closeArchiveEntry()
            }
            output.finish()
        }
    }

    private fun safeArchiveTarget(base: File, entryName: String): File {
        val target = File(base, entryName).canonicalFile
        val basePath = base.canonicalFile.path
        require(target.path == basePath || target.path.startsWith(basePath + File.separator)) { "Архив содержит небезопасный путь" }
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) { "Архив пытается выйти за рабочую область" }
        return target
    }

    private fun requirePublicNetworkUrl(url: String, httpsOnly: Boolean) {
        val uri = runCatching { URI(url) }.getOrElse { error("Некорректный URL") }
        val scheme = uri.scheme?.lowercase().orEmpty()
        require(if (httpsOnly) scheme == "https" else scheme == "https" || scheme == "http") {
            if (httpsOnly) "Для clone разрешены только публичные HTTPS URL" else "Разрешены только HTTP/HTTPS URL"
        }
        require(uri.userInfo.isNullOrBlank()) { "URL с учётными данными запрещён" }
        val host = uri.host?.trim().orEmpty()
        require(host.isNotBlank()) { "URL должен содержать имя хоста" }
        require(!host.equals("localhost", true) && !host.endsWith(".local", true)) {
            "Локальные адреса запрещены"
        }
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }
            .getOrElse { error("Не удалось проверить адрес хоста") }
        require(addresses.isNotEmpty() && addresses.none(::isPrivateNetworkAddress)) {
            "Доступ к локальным и служебным сетевым адресам запрещён"
        }
    }

    private fun isPrivateNetworkAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        val raw = address.address
        if (raw.size == 4) {
            val a = raw[0].toInt() and 0xFF
            val b = raw[1].toInt() and 0xFF
            if (a == 0 || a == 127) return true
            if (a == 100 && b in 64..127) return true
            if (a == 169 && b == 254) return true
        } else if (raw.size == 16) {
            val first = raw[0].toInt() and 0xFF
            if ((first and 0xFE) == 0xFC) return true
        }
        return false
    }
    private fun validateCommandArg(arg: String) {
        require('\u0000' !in arg) { "Недопустимый аргумент" }
        val normalized = arg.replace('\\', '/')
        require(!normalized.startsWith("/")) { "Абсолютные пути запрещены" }
        require(normalized.split('/').none { it == ".." }) { "Выход из рабочей области запрещён" }
        require(!normalized.startsWith("~")) { "Домашние пути запрещены" }
    }

    private fun resolve(pathRaw: String): File {
        val clean = pathRaw.trim().ifBlank { "." }.replace('\\', '/')
        require(!clean.startsWith("/")) { "Абсолютные пути запрещены" }
        require(clean.split('/').none { it == ".." }) { "Выход из рабочей области запрещён" }
        val file = File(root, clean).canonicalFile
        require(file.path == root.path || file.path.startsWith(root.path + File.separator)) { "Путь выходит за рабочую область" }
        return file
    }

    private fun relative(file: File): String {
        val canonical = file.canonicalFile
        return if (canonical == root) "." else canonical.relativeTo(root).invariantSeparatorsPath
    }

    private fun queryName(uri: Uri): String {
        var name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
            }
        }
        return name
    }

    private fun safeName(value: String): String = value
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .replace(Regex("[^A-Za-zА-Яа-я0-9._ -]"), "_")
        .trim()
        .take(120)
        .ifBlank { "file" }

    private fun uniqueName(dir: File, candidate: String): String {
        if (!File(dir, candidate).exists()) return candidate
        val dot = candidate.lastIndexOf('.')
        val stem = if (dot > 0) candidate.substring(0, dot) else candidate
        val ext = if (dot > 0) candidate.substring(dot) else ""
        var index = 2
        while (File(dir, stem + "_" + index + ext).exists()) index += 1
        return stem + "_" + index + ext
    }

    private fun cleanupOldRuns() {
        val parent = root.parentFile ?: return
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        parent.listFiles()?.forEach { dir ->
            if (dir != root && dir.lastModified() < cutoff) runCatching { dir.deleteRecursively() }
        }
    }

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, JsonObject>,
        required: List<String> = emptyList()
    ) = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description)
            add("parameters", JsonObject().apply {
                addProperty("type", "object")
                add("properties", JsonObject().apply { properties.forEach { (key, value) -> add(key, value) } })
                if (required.isNotEmpty()) add("required", JsonArray().apply { required.forEach(::add) })
                addProperty("additionalProperties", false)
            })
        })
    }

    private fun stringProperty(description: String) = JsonObject().apply {
        addProperty("type", "string")
        addProperty("description", description)
    }

    private fun boolProperty(description: String) = JsonObject().apply {
        addProperty("type", "boolean")
        addProperty("description", description)
    }

    private fun intProperty(description: String) = JsonObject().apply {
        addProperty("type", "integer")
        addProperty("description", description)
    }

    private fun arrayProperty(description: String) = JsonObject().apply {
        addProperty("type", "array")
        addProperty("description", description)
        add("items", JsonObject().apply { addProperty("type", "string") })
    }

    private fun JsonObject.string(name: String): String = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    }.getOrDefault("")

    private fun JsonObject.bool(name: String): Boolean = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: false
    }.getOrDefault(false)

    private fun JsonObject.int(name: String): Int = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asInt ?: 0
    }.getOrDefault(0)

    private fun ok(values: Map<String, Any?>): String = gson.toJson(linkedMapOf<String, Any?>("ok" to true).apply { putAll(values) })

    companion object {
        private val PYTHON_LOCK = Any()
        private val ALLOWED_TOYBOX = setOf("ls", "find", "grep", "cat", "head", "tail", "wc", "sort", "uniq", "cut", "sha256sum", "diff", "stat")
        private val RUNTIME_CACHE_DIRS = setOf("__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache")
        private const val MAX_ATTACHMENTS = 10
        private const val MAX_SINGLE_FILE_BYTES = 50L * 1024L * 1024L
        private const val MAX_TEXT_FILE_BYTES = 2L * 1024L * 1024L
        private const val MAX_WRITE_CHARS = 1_000_000
        private const val MAX_PYTHON_CHARS = 100_000
        private const val MAX_FETCH_BYTES = 2 * 1024 * 1024
        private const val MAX_FETCH_PREVIEW_CHARS = 40_000
        private const val MAX_LIST_ENTRIES = 400
        private const val MAX_SEARCH_MATCHES = 120
        private const val MAX_TOOL_OUTPUT_CHARS = 50_000
        private const val COMMAND_TIMEOUT_SECONDS = 20L
    }
}
