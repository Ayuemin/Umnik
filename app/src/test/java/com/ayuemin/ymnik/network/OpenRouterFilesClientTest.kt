package com.ayuemin.ymnik.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterFilesClientTest {
    @Test
    fun detectsHttp2ProtocolReset() {
        assertTrue(
            OpenRouterFilesClient.isHttp2ProtocolFailure(
                RuntimeException("stream was reset: PROTOCOL_ERROR")
            )
        )
    }

    @Test
    fun ignoresRegularApiErrors() {
        assertFalse(
            OpenRouterFilesClient.isHttp2ProtocolFailure(
                RuntimeException("OpenRouter Files HTTP 400: File type is not allowed")
            )
        )
    }
}
