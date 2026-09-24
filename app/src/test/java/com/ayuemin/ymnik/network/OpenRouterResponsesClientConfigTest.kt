package com.ayuemin.ymnik.network

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenRouterResponsesClientConfigTest {
    @Test
    fun shellCallHasNoAbsoluteDeadline() {
        assertEquals(0L, OpenRouterResponsesClient.SHELL_CALL_TIMEOUT_MILLIS)
    }
}
