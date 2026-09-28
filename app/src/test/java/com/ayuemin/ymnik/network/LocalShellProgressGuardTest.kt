package com.ayuemin.ymnik.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalShellProgressGuardTest {
    @Test
    fun repeatedIntentTriggersSemanticCheckpoint() {
        val guard = LocalShellProgressGuard(repeatedIntentThreshold = 3)

        assertNull(guard.observe("python", durableProgress = false))
        repeat(2) { assertNull(guard.observe("python", durableProgress = false)) }
        val decision = guard.observe("python", durableProgress = false)

        requireNotNull(decision)
        assertFalse(decision.forceFinish)
    }

    @Test
    fun secondStallForcesFinishDecision() {
        val guard = LocalShellProgressGuard(
            repeatedIntentThreshold = 2,
            maxStrikes = 2,
            resetAfterNovelKeys = 3
        )

        assertNull(guard.observe("python", durableProgress = false))
        assertNull(guard.observe("python", durableProgress = false))
        val first = guard.observe("python", durableProgress = false)
        requireNotNull(first)
        assertFalse(first.forceFinish)

        assertNull(guard.observe("python", durableProgress = false))
        val second = guard.observe("python", durableProgress = false)
        requireNotNull(second)
        assertTrue(second.forceFinish)
    }

    @Test
    fun durableProgressResetsOldStrike() {
        val guard = LocalShellProgressGuard(repeatedIntentThreshold = 2)

        guard.observe("python", durableProgress = false)
        guard.observe("python", durableProgress = false)
        requireNotNull(guard.observe("python", durableProgress = false))

        assertNull(guard.observe("write:a", durableProgress = true))

        assertNull(guard.observe("python", durableProgress = false))
        assertNull(guard.observe("python", durableProgress = false))
        val later = guard.observe("python", durableProgress = false)
        requireNotNull(later)
        assertFalse(later.forceFinish)
    }

    @Test
    fun sustainedNovelEvidenceForgetsWarning() {
        val guard = LocalShellProgressGuard(
            repeatedIntentThreshold = 2,
            maxStrikes = 2,
            resetAfterNovelKeys = 3
        )

        guard.observe("python", durableProgress = false)
        guard.observe("python", durableProgress = false)
        requireNotNull(guard.observe("python", durableProgress = false))

        assertNull(guard.observe("read:a", durableProgress = false))
        assertNull(guard.observe("read:b", durableProgress = false))
        assertNull(guard.observe("read:c", durableProgress = false))

        assertNull(guard.observe("python", durableProgress = false))
        assertNull(guard.observe("python", durableProgress = false))
        val later = guard.observe("python", durableProgress = false)
        requireNotNull(later)
        assertFalse(later.forceFinish)
    }

    @Test
    fun differentEvidenceTargetsDoNotLookLikeStall() {
        val guard = LocalShellProgressGuard(repeatedIntentThreshold = 2)

        repeat(12) { index ->
            assertNull(guard.observe("read:file-$index", durableProgress = false))
        }
    }
}
