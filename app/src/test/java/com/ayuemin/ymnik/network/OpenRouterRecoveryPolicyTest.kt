package com.ayuemin.ymnik.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterRecoveryPolicyTest {
    @Test fun recoversOnlyConfirmedResponseCacheRequests() {
        assertTrue(isOpenRouterResponseCacheRecoverable("MISS"))
        assertTrue(isOpenRouterResponseCacheRecoverable("hit"))
        assertFalse(isOpenRouterResponseCacheRecoverable(null))
        assertFalse(isOpenRouterResponseCacheRecoverable("BYPASS"))
    }

    @Test fun apiKeyFingerprintIsStableButDoesNotStoreTheKey() {
        val key = "sk-or-test-secret-123"
        val first = openRouterApiKeyFingerprint(key)
        assertEquals(first, openRouterApiKeyFingerprint(key))
        assertNotEquals(first, openRouterApiKeyFingerprint("sk-or-other"))
        assertFalse(first.contains(key))
        assertEquals(64, first.length)
    }

    @Test fun recoversCachedGenerationAfterRemoteBodyFailure() {
        assertTrue(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "MISS", 0))
        assertTrue(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "HIT", 0))
        assertTrue(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "HIT", 2))
    }

    @Test fun neverRetriesLocalCancelUnknownGenerationUncachedOrPastBudget() {
        assertFalse(shouldRecoverOpenRouterBodyFailure(true, "gen-123", "MISS", 0))
        assertFalse(shouldRecoverOpenRouterBodyFailure(false, null, "MISS", 0))
        assertFalse(shouldRecoverOpenRouterBodyFailure(false, "gen-123", null, 0))
        assertFalse(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "BYPASS", 0))
        assertFalse(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "MISS", 3))
    }
}
