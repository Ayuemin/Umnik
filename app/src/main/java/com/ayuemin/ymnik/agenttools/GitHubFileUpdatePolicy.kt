package com.ayuemin.ymnik.agenttools

/**
 * Optimistic concurrency for GitHub commit_files.
 * Existing files cannot be silently overwritten by a model working with an
 * outdated repository snapshot; newly created files must be genuinely absent.
 */
internal object GitHubFileUpdatePolicy {
    fun matches(existingSha: String?, expectedSha: String?, deleting: Boolean): Boolean {
        if (existingSha.isNullOrBlank()) return !deleting && expectedSha.isNullOrBlank()
        val expected = expectedSha?.takeIf { it.matches(Regex("[0-9a-fA-F]{40,64}")) }
            ?: return false
        return existingSha.equals(expected, ignoreCase = true)
    }
}
