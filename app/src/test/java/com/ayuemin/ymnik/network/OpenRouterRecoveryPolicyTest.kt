package com.ayuemin.ymnik.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterRecoveryPolicyTest {
    @Test fun apiKeyFingerprintIsStableButDoesNotStoreTheKey() {
        val key = "sk-or-test-secret-123"
        val first = openRouterApiKeyFingerprint(key)
        assertEquals(first, openRouterApiKeyFingerprint(key))
        assertNotEquals(first, openRouterApiKeyFingerprint("sk-or-other"))
        assertFalse(first.contains(key))
        assertEquals(64, first.length)
    }

    @Test fun recoversExistingGenerationReadOnlyAfterRemoteBodyFailure() {
        assertTrue(shouldRecoverOpenRouterBodyFailure(false, "gen-123", 0))
        assertTrue(shouldRecoverOpenRouterBodyFailure(false, "gen-123", 2))
    }

    @Test fun neverRecoversLocalCancelUnknownGenerationOrPastBudget() {
        assertFalse(shouldRecoverOpenRouterBodyFailure(true, "gen-123", 0))
        assertFalse(shouldRecoverOpenRouterBodyFailure(false, null, 0))
        assertFalse(shouldRecoverOpenRouterBodyFailure(false, "gen-123", 3))
    }
}
