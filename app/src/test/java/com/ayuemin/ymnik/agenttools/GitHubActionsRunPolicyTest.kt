package com.ayuemin.ymnik.agenttools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubActionsRunPolicyTest {
    private fun allowed(branch: String?, source: String? = "Ayuemin/SampleApp",
                        status: String? = "completed", conclusion: String? = "success",
                        runRepository: String? = "Ayuemin/SampleApp", success: Boolean = true) =
        GitHubActionsRunPolicy.allows(
            expectedRepo = "Ayuemin/SampleApp",
            runRepo = runRepository,
            headRepo = source,
            branch = branch,
            status = status,
            conclusion = conclusion,
            requireSuccess = success
        )

    @Test fun acceptsSuccessfulBuildFromSelectedWorkingBranch() {
        assertTrue(allowed("work/expense-app"))
        assertTrue(allowed("agent/gradle-fix"))
    }

    @Test fun refusesProtectedOrOtherRepositoryRuns() {
        for (branch in listOf("main", "master", "stable", "release/v1.0", "", "../work")) {
            assertFalse(allowed(branch))
        }
        assertFalse(allowed("work/expense-app", source = "attacker/other"))
        assertFalse(allowed("work/expense-app", runRepository = "attacker/other"))
        assertFalse(allowed("work/expense-app", source = null))
        assertFalse(allowed("work/expense-app", runRepository = null))
    }

    @Test fun apkRequiresConfirmedSuccessfulBuild() {
        assertFalse(allowed("work/expense-app", status = "in_progress", conclusion = null))
        assertFalse(allowed("work/expense-app", conclusion = "failure"))
        assertFalse(allowed("work/expense-app", conclusion = "cancelled"))
        assertTrue(allowed("work/expense-app", conclusion = "failure", success = false))
    }
}
