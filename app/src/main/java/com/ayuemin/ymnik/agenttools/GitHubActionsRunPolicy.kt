package com.ayuemin.ymnik.agenttools

/**
 * Fail-closed ownership and branch checks before any GitHub Actions mutation
 * or artifact import. The workflow run's actual metadata, not model-provided
 * branch/repository strings, determines authorization.
 */
internal object GitHubActionsRunPolicy {
    fun allows(
        expectedRepo: String,
        runRepo: String?,
        headRepo: String?,
        branch: String?,
        status: String?,
        conclusion: String?,
        requireSuccess: Boolean
    ): Boolean {
        val expected = GitHubAgentScope.normalizeRepo(expectedRepo) ?: return false
        val actual = GitHubAgentScope.normalizeRepo(runRepo.orEmpty()) ?: return false
        val head = GitHubAgentScope.normalizeRepo(headRepo.orEmpty()) ?: return false
        if (!actual.equals(expected, ignoreCase = true) ||
            !head.equals(expected, ignoreCase = true)
        ) return false
        if (!GitHubAgentScope.isSafeWorkingBranch(branch.orEmpty())) return false
        if (requireSuccess && (status != "completed" || conclusion != "success")) return false
        return true
    }
}
