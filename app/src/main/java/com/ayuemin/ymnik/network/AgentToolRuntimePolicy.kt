package com.ayuemin.ymnik.network

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Shared fail-safe policy for diagnostics and compatibility around Agent -> Browser/Shell calls.
 * Keep this class Android-free so the policy can be covered by local unit tests.
 */
internal object AgentToolRuntimePolicy {
    private val secretAssignment = Regex(
        "(?i)\\b(password|passwd|token|api[_-]?key|authorization|cookie|secret)\\b\\s*[:=]\\s*([^\\s,;]+)"
    )
    private val credentialPrefix = Regex(
        "(?i)\\b(?:sk-[A-Za-z0-9._-]{8,}|ghp_[A-Za-z0-9_]{8,}|github_pat_[A-Za-z0-9_]{8,})\\b"
    )

    fun suppressRequiredBrowserDownload(prompt: String): Boolean {
        val text = prompt.lowercase()
        val explicitlyForbidden =
            Regex("""\bне\s+(?:нужно\s+|надо\s+|следует\s+)?скач(?:ивай|ивайте|ивать|ать)\b""").containsMatchIn(text) ||
                Regex("""\bскач(?:ивать|ать)\b.{0,32}\bне\s+(?:надо|нужно|следует)\b""").containsMatchIn(text) ||
                "do not download" in text ||
                "don't download" in text ||
                "without downloading" in text
        if (explicitlyForbidden) return true

        val lookupOnly = listOf(
            "найди возможность скачать",
            "найди где скачать",
            "найди, где скачать",
            "покажи где скачать",
            "покажи, где скачать",
            "найди ссылку для скач",
            "ссылка для скачивания",
            "какую ссылку",
            "есть ли pdf",
            "есть ли epub",
            "find where to download",
            "find the download link"
        ).any(text::contains)
        val explicitAction = Regex(
            """\bскачай\b|\bскачайте\b|\bзагрузи\s+файл\b|\bdownload\s+(?:the\s+)?file\b"""
        ).containsMatchIn(text)
        return lookupOnly && !explicitAction
    }

    fun isDeterministicShellStartupFailure(turns: Int, toolCalls: Int, error: String?): Boolean =
        turns <= 1 && toolCalls == 0 &&
            error.orEmpty().contains("OpenRouter HTTP 400", ignoreCase = true)

    fun shouldLogMainTool(name: String): Boolean =
        name.startsWith("local_browser_") || name.startsWith("local_shell_")

    fun mainToolArgs(gson: Gson, name: String, argsRaw: String): String {
        val args = jsonObject(gson, argsRaw) ?: return redactText(argsRaw)
        return when (name) {
            "local_shell_start" -> {
                val task = redactText(args.string("task").orEmpty(), 1_800)
                val network = args.bool("network") ?: false
                val files = args.array("files")
                    ?.mapNotNull { it.takeIf { item -> item.isJsonPrimitive }?.asString }
                    .orEmpty()
                "task=$task; network=$network; files=${files.joinToString(prefix = "[", postfix = "]") { redactText(it, 160) }}"
            }
            "local_shell_note" -> "note=${redactText(args.string("note").orEmpty(), 1_200)}"
            "local_browser_open" -> "url=${safeUrl(args.string("url").orEmpty())}"
            "local_browser_type" -> {
                val text = args.string("text").orEmpty()
                "ref=${args.int("ref") ?: "?"}; text=[redacted ${text.length} chars]; submit=${args.bool("submit") ?: false}"
            }
            "local_browser_click", "local_browser_download" -> "ref=${args.int("ref") ?: "?"}"
            "local_browser_scroll" -> "direction=${redactText(args.string("direction").orEmpty(), 80)}"
            "local_browser_read" -> "full=${args.bool("full") ?: false}"
            "local_browser_wait" ->
                "seconds=${args.int("seconds") ?: "?"}; mode=${redactText(args.string("mode").orEmpty(), 80)}; " +
                    "value=${redactText(args.string("value").orEmpty(), 220)}; ref=${args.int("ref") ?: "-"}"
            "local_browser_takeover" -> "reason=${redactText(args.string("reason").orEmpty(), 600)}"
            else -> redactText(gson.toJson(args), 900)
        }
    }

    fun mainToolResult(gson: Gson, name: String, resultRaw: String): String {
        val root = jsonObject(gson, resultRaw) ?: return "chars=${resultRaw.length}"
        val fields = mutableListOf<String>()
        listOf("ok", "started", "state", "source", "model", "turns", "tool_calls", "ready_for_user").forEach { key ->
            root.get(key)?.takeIf { it.isJsonPrimitive }?.let { fields += "$key=${redactText(it.asString, 160)}" }
        }
        (root.string("url") ?: root.string("current_url"))?.let { fields += "url=${safeUrl(it)}" }
        root.string("reason")?.let { fields += "reason=${redactText(it, 260)}" }
        root.string("error")?.let { fields += "error=${redactText(it, 500)}" }
        root.obj("artifact")?.let { artifact ->
            val nameValue = artifact.string("name").orEmpty()
            val size = artifact.get("size")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val shell = artifact.get("available_to_shell")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            fields += "artifact=${redactText(nameValue, 180)}; size=$size; shell=$shell"
        }
        root.array("files")?.let { fields += "files=${it.size()}" }
        return fields.joinToString("; ").ifBlank { "chars=${resultRaw.length}" }.take(1_400)
    }

    fun localShellToolArgs(gson: Gson, name: String, argsRaw: String): String {
        val args = jsonObject(gson, argsRaw) ?: return redactText(argsRaw, 1_200)
        fun value(key: String, limit: Int = 260): String = redactText(args.string(key).orEmpty(), limit)
        return when (name) {
            "local_write" -> "path=${value("path")}; content_chars=${args.string("content").orEmpty().length}"
            "local_replace" ->
                "path=${value("path")}; old_chars=${args.string("old").orEmpty().length}; " +
                    "new_chars=${args.string("new").orEmpty().length}; all=${args.bool("replace_all") ?: false}"
            "local_python" -> "code=${redactText(args.string("code").orEmpty(), 1_200)}"
            "local_command" -> "command=${value("command", 180)}; args=${redactText(args.get("args")?.toString().orEmpty(), 700)}"
            "local_git" -> "action=${value("action", 100)}; path=${value("path")}; arg=${value("arg", 600)}"
            "local_fetch" -> "url=${safeUrl(args.string("url").orEmpty())}; save_as=${value("save_as")}; max_bytes=${args.get("max_bytes")?.toString().orEmpty()}"
            "local_read" -> "path=${value("path")}; start=${args.get("start_line") ?: "-"}; max=${args.get("max_lines") ?: "-"}"
            "local_list" -> "path=${value("path")}; recursive=${args.bool("recursive") ?: false}"
            "local_search" -> "path=${value("path")}; query=${value("query", 500)}; regex=${args.bool("regex") ?: false}"
            "local_archive" -> "action=${value("action", 100)}; source=${value("source")}; destination=${value("destination")}"
            "local_export" -> "source=${value("source")}; filename=${value("filename")}"
            else -> redactText(gson.toJson(args), 1_200)
        }
    }

    fun localShellToolResult(gson: Gson, resultRaw: String): String {
        val root = jsonObject(gson, resultRaw) ?: return "chars=${resultRaw.length}"
        val fields = mutableListOf<String>()
        listOf("ok", "changed", "ready_for_user", "exit_code", "size", "path", "filename").forEach { key ->
            root.get(key)?.takeIf { it.isJsonPrimitive }?.let { fields += "$key=${redactText(it.asString, 260)}" }
        }
        root.string("error")?.let { fields += "error=${redactText(it, 500)}" }
        root.string("stdout")?.let { fields += "stdout_chars=${it.length}" }
        root.string("stderr")?.let { fields += "stderr_chars=${it.length}" }
        root.array("files")?.let { fields += "files=${it.size()}" }
            ?: root.string("files")?.let { fields += "files_chars=${it.length}" }
        root.array("entries")?.let { fields += "entries=${it.size()}" }
            ?: root.string("entries")?.let { fields += "entries_chars=${it.length}" }
        root.array("matches")?.let { fields += "matches=${it.size()}" }
            ?: root.string("matches")?.let { fields += "matches_chars=${it.length}" }
        return fields.joinToString("; ").ifBlank { "chars=${resultRaw.length}" }.take(1_000)
    }

    fun redactText(value: String, limit: Int = 900): String {
        var clean = value.replace(secretAssignment) { match -> "${match.groupValues[1]}=[redacted]" }
        clean = clean.replace(credentialPrefix, "[redacted-credential]")
        clean = clean.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        return clean.take(limit.coerceAtLeast(32))
    }

    private fun safeUrl(value: String): String {
        val clean = redactText(value, 1_000)
        val withoutFragment = clean.substringBefore('#')
        return if ('?' in withoutFragment) withoutFragment.substringBefore('?') + "?[redacted]" else withoutFragment
    }

    private fun jsonObject(gson: Gson, raw: String): JsonObject? = runCatching {
        gson.fromJson(raw.ifBlank { "{}" }, JsonObject::class.java)
    }.getOrNull()

    private fun JsonObject.string(name: String): String? = runCatching {
        get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
    }.getOrNull()

    private fun JsonObject.bool(name: String): Boolean? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean
    }.getOrNull()

    private fun JsonObject.int(name: String): Int? = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asInt
    }.getOrNull()

    private fun JsonObject.array(name: String) =
        get(name)?.takeIf { !it.isJsonNull && it.isJsonArray }?.asJsonArray

    private fun JsonObject.obj(name: String) =
        get(name)?.takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject
}

internal object LocalShellProviderPolicy {
    fun shouldRetryRequiredAsAuto(turn: Int, forceDecision: Boolean, error: Throwable): Boolean =
        turn == 1 && !forceDecision &&
            error.message.orEmpty().contains("OpenRouter HTTP 400", ignoreCase = true)
}
