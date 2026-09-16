package com.ayuemin.ymnik.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterRecoveryPolicyTest {
    @Test fun recoversCachedGenerationAfterRemoteBodyFailure() {
        assertTrue(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "MISS", 0))
        assertTrue(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "HIT", 0))
    }

    @Test fun neverRetriesLocalCancelOrUnknownUncachedGeneration() {
        assertFalse(shouldRecoverOpenRouterBodyFailure(true, "gen-123", "MISS", 0))
        assertFalse(shouldRecoverOpenRouterBodyFailure(false, null, "MISS", 0))
        assertFalse(shouldRecoverOpenRouterBodyFailure(false, "gen-123", null, 0))
        assertFalse(shouldRecoverOpenRouterBodyFailure(false, "gen-123", "MISS", 1))
    }
}
