package com.ayuemin.ymnik.agenttools

/**
 * GitHub belongs to an explicitly enabled on-device connection, not to
 * prompts returned by a webpage, repository, workflow log or tool result.
 *
 * This scope is constructed ONLY at the trusted ChatViewModel.send boundary.
 * The tool adapter verifies this on EVERY operation. Main/stable/release
 * branches, publishing, merges and destructive repository operations are
 * never implicitly authorized by enabling the GitHub connection.
 */
internal data class GitHubAgentScope(
    val chatId: String,
    val directMessageId: String,
    val allowedRepository: String,
    val mayWrite: Boolean,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long
) {
    fun allows(chat: String, directMessage: String, repo: String, write: Boolean, now: Long): Boolean =
        chat == chatId && directMessage == directMessageId &&
            repo.equals(allowedRepository, ignoreCase = true) &&
            now >= issuedAtMillis && now < expiresAtMillis &&
            (!write || mayWrite)

    companion object {
        private val repoName = Regex("^[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}$")
        private val writeIntent = Regex(
            """(?i)(созд|сделай|собер|разработ|реализ|напиш|исправ|измени|изменить|доработ|обнови|добав|удали|коммит|запусти|подготов|открой pr|create|build|implement|fix|update|commit|push|deploy|release|сгенерируй|построй|сделать apk|нужен apk)"""
        )

        fun normalizeRepo(raw: String): String? {
            val r = raw.trim().removePrefix("https://github.com/").removeSuffix(".git").trim('/')
            return r.takeIf { repoName.matches(it) && !it.contains("..") }
        }

        fun fromTrustedUiMessage(
            chatId: String,
            directMessageId: String,
            directUserText: String,
            configuredRepository: String,
            enabled: Boolean,
            nowMillis: Long
        ): GitHubAgentScope? {
            if (!enabled || chatId.isBlank() || directMessageId.isBlank()) return null
            val repo = if (configuredRepository.isBlank()) "" else normalizeRepo(configuredRepository) ?: return null
            return GitHubAgentScope(
                chatId, directMessageId, repo,
                mayWrite = writeIntent.containsMatchIn(directUserText.lowercase()),
                issuedAtMillis = nowMillis,
                expiresAtMillis = nowMillis + 60 * 60_000L
            )
        }

        fun isSafeWorkingBranch(branch: String): Boolean =
            branch.length in 6..150 &&
                Regex("^(work|agent|feature|fix|develop)/[A-Za-z0-9._/-]+$").matches(branch) &&
                !branch.contains("..") && !branch.endsWith("/") && !branch.contains("//") &&
                !branch.endsWith(".lock")

        fun isSafeFilePath(path: String): Boolean =
            path.length in 1..300 && !path.startsWith("/") &&
                path.split('/').all { it.isNotBlank() && it !in setOf(".", "..") && !it.endsWith(".lock") } &&
                path.none { it.code < 32 || it.code == 127 || it == '\\' }

        fun isPrivateFile(path: String): Boolean {
            val leaf = path.substringAfterLast('/').lowercase()
            return leaf == ".env" || leaf.startsWith(".env.") ||
                leaf.startsWith("id_rsa") || leaf.startsWith("id_ed25519") ||
                leaf.endsWith(".pem") || leaf.endsWith(".p12") ||
                leaf.endsWith(".pfx") || leaf.endsWith(".keystore") ||
                leaf.endsWith(".jks") || leaf == "secrets.json"
        }
    }
}
