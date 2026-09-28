package com.ayuemin.ymnik.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalShellSafetyPolicyTest {
    @Test
    fun gitMetadataDetectionTargetsSegmentOnly() {
        assertTrue(LocalShellSafetyPolicy.containsGitMetadataSegment("repo/.git/HEAD"))
        assertTrue(LocalShellSafetyPolicy.containsGitMetadataSegment(".git/config"))
        assertFalse(LocalShellSafetyPolicy.containsGitMetadataSegment("repo/.gitignore"))
        assertFalse(LocalShellSafetyPolicy.containsGitMetadataSegment("repo/git/config"))
    }

    @Test
    fun cloneUrlNormalizationCollapsesDotGitVariants() {
        assertEquals(
            LocalShellSafetyPolicy.normalizeGitCloneUrl("https://github.com/Ayuemin/Umnik.git"),
            LocalShellSafetyPolicy.normalizeGitCloneUrl("https://github.com/Ayuemin/Umnik/")
        )
        assertEquals(
            "github.com/ayuemin/umnik",
            LocalShellSafetyPolicy.repositoryKey("https://codeload.github.com/Ayuemin/Umnik/zip/refs/heads/main")
        )
    }

    @Test
    fun loopGuardWarnsAtThreeAndBlocksAtFive() {
        val guard = LocalShellSessionGuard()
        val signature = "git:public_clone:https://github.com/ayuemin/umnik"
        repeat(2) {
            assertNull(guard.recordFailure("local_git", signature, LocalShellFailureClass.STRUCTURAL))
        }
        val warning = guard.recordFailure("local_git", signature, LocalShellFailureClass.STRUCTURAL)
        requireNotNull(warning)
        assertTrue(warning.warn)
        assertFalse(warning.blocked)

        assertNull(guard.recordFailure("local_git", signature, LocalShellFailureClass.STRUCTURAL))
        val blocked = guard.recordFailure("local_git", signature, LocalShellFailureClass.STRUCTURAL)
        requireNotNull(blocked)
        assertTrue(blocked.blocked)
        assertTrue(guard.blockReason("local_git", signature) != null)
    }

    @Test
    fun networkFailuresDoNotAdvanceMechanicalLoopCounter() {
        val guard = LocalShellSessionGuard()
        val signature = "fetch:https://example.com/a.zip"
        repeat(12) {
            assertNull(guard.recordFailure("local_fetch", signature, LocalShellFailureClass.NETWORK))
        }
        assertNull(guard.blockReason("local_fetch", signature))
    }

    @Test
    fun stickySuccessBlocksCloneForSameRepositoryOnly() {
        val guard = LocalShellSessionGuard()
        guard.markRepositorySourceAcquired("github.com/ayuemin/umnik", "local_fetch + local_archive")
        val same = guard.blockReason(
            "local_git",
            "git:public_clone:https://github.com/ayuemin/umnik",
            "github.com/ayuemin/umnik"
        )
        val other = guard.blockReason(
            "local_git",
            "git:public_clone:https://github.com/octocat/hello-world",
            "github.com/octocat/hello-world"
        )
        assertTrue(same?.contains("SOURCE_ALREADY_ACQUIRED") == true)
        assertNull(other)
    }

    @Test
    fun structuralFailuresAcrossSignaturesEventuallyBanBrokenTool() {
        val guard = LocalShellSessionGuard()
        repeat(7) { index ->
            guard.recordFailure(
                "local_git",
                "git:public_clone:https://github.com/example/repo-$index",
                LocalShellFailureClass.STRUCTURAL
            )
        }
        assertTrue(
            guard.blockReason(
                "local_git",
                "git:public_clone:https://github.com/example/another"
            )?.contains("TOOL_STRUCTURAL_BAN") == true
        )
    }
}
