package com.ayuemin.ymnik.agenttools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubFileUpdatePolicyTest {
    private val current = "9c1e922bb0b445e04c649d5cdd8756d56dc86c34"
    private val stale = "13c36fd2e4e3c5af20b992eebc9c844d12583c71"

    @Test fun newFileWithoutRevisionCanBeCreated() {
        assertTrue(GitHubFileUpdatePolicy.matches(null, null, deleting = false))
        assertFalse(GitHubFileUpdatePolicy.matches(null, stale, deleting = false))
        assertFalse(GitHubFileUpdatePolicy.matches(null, null, deleting = true))
    }

    @Test fun existingFileNeedsMatchingShaForEditAndDeletion() {
        assertTrue(GitHubFileUpdatePolicy.matches(current, current, deleting = false))
        assertTrue(GitHubFileUpdatePolicy.matches(current, current.uppercase(), deleting = true))
        assertFalse(GitHubFileUpdatePolicy.matches(current, null, deleting = false))
        assertFalse(GitHubFileUpdatePolicy.matches(current, "", deleting = true))
        assertFalse(GitHubFileUpdatePolicy.matches(current, stale, deleting = false))
    }

    @Test fun rejectsInvalidAndPartialShaValues() {
        assertFalse(GitHubFileUpdatePolicy.matches(current, "not-a-sha", deleting = false))
        assertFalse(GitHubFileUpdatePolicy.matches(current, current.take(12), deleting = false))
    }
}
