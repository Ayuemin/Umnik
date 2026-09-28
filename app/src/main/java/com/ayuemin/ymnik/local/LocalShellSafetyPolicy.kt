package com.ayuemin.ymnik.local

import java.io.File
import java.net.URI
import java.security.MessageDigest

internal enum class LocalShellFailureClass {
    NETWORK,
    STRUCTURAL,
    ACTIONABLE,
    TOOL
}

internal data class LocalShellGuardDecision(
    val signature: String,
    val failureCount: Int,
    val warn: Boolean,
    val blocked: Boolean,
    val toolBanned: Boolean,
    val reason: String
)

internal class LocalShellSessionGuard(
    private val warnAfter: Int = 3,
    private val blockAfter: Int = 5,
    private val structuralToolBanAfter: Int = 7
) {
    private val failureCounts = linkedMapOf<String, Int>()
    private val structuralToolFailures = linkedMapOf<String, Int>()
    private val blockedSignatures = linkedMapOf<String, String>()
    private val bannedTools = linkedMapOf<String, String>()
    private val stickyRepositoryRoutes = linkedMapOf<String, String>()

    fun blockReason(tool: String, signature: String, repositoryKey: String? = null): String? {
        bannedTools[tool]?.let { return it }
        blockedSignatures[signature]?.let { return it }
        if (tool == "local_git" && signature.startsWith("git:public_clone:")) {
            repositoryKey?.let { key ->
                stickyRepositoryRoutes[key]?.let { route ->
                    return "SOURCE_ALREADY_ACQUIRED: исходники этого репозитория уже успешно получены через $route. Не возвращайся к public_clone."
                }
            }
        }
        return null
    }

    fun recordFailure(tool: String, signature: String, failureClass: LocalShellFailureClass): LocalShellGuardDecision? {
        if (failureClass == LocalShellFailureClass.NETWORK) return null

        val count = (failureCounts[signature] ?: 0) + 1
        failureCounts[signature] = count

        var toolBanned = false
        if (failureClass == LocalShellFailureClass.STRUCTURAL) {
            val toolCount = (structuralToolFailures[tool] ?: 0) + 1
            structuralToolFailures[tool] = toolCount
            if (toolCount >= structuralToolBanAfter.coerceAtLeast(blockAfter)) {
                val reason = "TOOL_STRUCTURAL_BAN: $tool дал $toolCount структурных ошибок и заблокирован до конца Shell-сессии."
                bannedTools[tool] = reason
                toolBanned = true
            }
        }

        val blocked = count >= blockAfter.coerceAtLeast(2)
        if (blocked) {
            blockedSignatures[signature] =
                "LOOP_BLOCKED: одна и та же нормализованная операция уже завершилась без нового результата $count раз. Сигнатура заблокирована до конца Shell-сессии."
        }
        val warn = count == warnAfter.coerceIn(2, blockAfter.coerceAtLeast(2))
        if (!warn && !blocked && !toolBanned) return null

        return LocalShellGuardDecision(
            signature = signature,
            failureCount = count,
            warn = warn,
            blocked = blocked,
            toolBanned = toolBanned,
            reason = when {
                toolBanned -> bannedTools.getValue(tool)
                blocked -> blockedSignatures.getValue(signature)
                else -> "LOOP_WARNING: операция повторилась без нового результата $count раза. Следующий шаг должен использовать другой путь."
            }
        )
    }

    fun recordSuccess(signature: String) {
        failureCounts.remove(signature)
        blockedSignatures.remove(signature)
    }

    fun markRepositorySourceAcquired(repositoryKey: String, route: String) {
        if (repositoryKey.isNotBlank()) stickyRepositoryRoutes[repositoryKey] = route
    }

    fun summary(): String = buildString {
        if (stickyRepositoryRoutes.isNotEmpty()) {
            append("canonical=")
            append(stickyRepositoryRoutes.entries.joinToString(" | ") { "${it.key}:${it.value}" })
        }
        if (blockedSignatures.isNotEmpty()) {
            if (isNotEmpty()) append("; ")
            append("blocked=")
            append(blockedSignatures.keys.take(12).joinToString(","))
        }
        if (bannedTools.isNotEmpty()) {
            if (isNotEmpty()) append("; ")
            append("banned=")
            append(bannedTools.keys.joinToString(","))
        }
    }.ifBlank { "none" }
}

internal object LocalShellSafetyPolicy {
    private val networkMarkers = listOf(
        "protocol_error",
        "enetworkunreach",
        "enetunreach",
        "econnaborted",
        "econnreset",
        "connection reset",
        "stream was reset",
        "sockettimeout",
        "timeout",
        "timed out",
        "unable to resolve host",
        "unknownhost",
        "dns",
        "network is unreachable",
        "failed to connect",
        "broken pipe"
    )

    fun containsGitMetadataSegment(path: String): Boolean = path
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() && it != "." }
        .any { it.equals(".git", ignoreCase = true) }

    fun classify(tool: String, action: String?, error: Throwable): LocalShellFailureClass {
        val text = buildString {
            append(error.javaClass.simpleName)
            append(' ')
            append(error.message.orEmpty())
        }.lowercase()

        if (networkMarkers.any(text::contains)) return LocalShellFailureClass.NETWORK
        if (text.contains("папка назначения уже занята") || text.contains("destination_occupied")) {
            return LocalShellFailureClass.ACTIONABLE
        }
        if (
            text.contains("git_metadata_protected") ||
            text.contains("git_metadata_invalid") ||
            text.contains("git_capability_unavailable") ||
            text.contains("loop_blocked") ||
            text.contains("tool_structural_ban") ||
            text.contains("source_already_acquired")
        ) {
            return LocalShellFailureClass.STRUCTURAL
        }
        if (tool == "local_git") {
            val normalizedAction = action.orEmpty().lowercase()
            if (normalizedAction in setOf("public_clone", "init", "status", "log", "diff", "add", "commit", "checkout") &&
                (error is ReflectiveOperationException || text.contains("nosuchmethod") || text.contains("reflection"))
            ) {
                return LocalShellFailureClass.STRUCTURAL
            }
        }
        return LocalShellFailureClass.TOOL
    }

    fun retryAllowed(errorClass: LocalShellFailureClass): Boolean =
        errorClass == LocalShellFailureClass.ACTIONABLE || errorClass == LocalShellFailureClass.TOOL

    fun normalizeGitCloneUrl(raw: String): String {
        val clean = raw.trim().removeSuffix("/")
        val withoutGit = if (clean.endsWith(".git", ignoreCase = true)) clean.dropLast(4) else clean
        return runCatching {
            val uri = URI(withoutGit)
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.path.orEmpty().trimEnd('/').lowercase()
            if (host.isBlank()) withoutGit.lowercase() else "${uri.scheme.orEmpty().lowercase()}://$host$path"
        }.getOrDefault(withoutGit.lowercase())
    }

    fun repositoryKey(rawUrl: String): String? = runCatching {
        val uri = URI(rawUrl.trim())
        val host = uri.host?.lowercase().orEmpty()
        val parts = uri.path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
        when {
            host == "github.com" && parts.size >= 2 -> {
                val repo = parts[1].removeSuffix(".git").lowercase()
                "github.com/${parts[0].lowercase()}/$repo"
            }
            host == "codeload.github.com" && parts.size >= 2 ->
                "github.com/${parts[0].lowercase()}/${parts[1].removeSuffix(".git").lowercase()}"
            else -> null
        }
    }.getOrNull()

    fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
