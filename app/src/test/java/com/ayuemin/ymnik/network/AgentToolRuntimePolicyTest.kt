package com.ayuemin.ymnik.network

import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRuntimePolicyTest {
    private val gson = Gson()

    @Test
    fun `explicit no download suppresses required browser download`() {
        assertTrue(
            AgentToolRuntimePolicy.suppressRequiredBrowserDownload(
                "Найди PDF, но пока сам файл не скачивай."
            )
        )
    }

    @Test
    fun `lookup-only download wording does not force download`() {
        assertTrue(
            AgentToolRuntimePolicy.suppressRequiredBrowserDownload(
                "Найди возможность скачать EPUB и скажи, какая ссылка нужна."
            )
        )
    }

    @Test
    fun `explicit download action stays required`() {
        assertFalse(
            AgentToolRuntimePolicy.suppressRequiredBrowserDownload(
                "Найди PDF и скачай его."
            )
        )
    }

    @Test
    fun `deterministic shell startup 400 is recognized only before tools`() {
        assertTrue(
            AgentToolRuntimePolicy.isDeterministicShellStartupFailure(
                turns = 1,
                toolCalls = 0,
                error = "OpenRouter HTTP 400: Provider returned error"
            )
        )
        assertFalse(
            AgentToolRuntimePolicy.isDeterministicShellStartupFailure(
                turns = 2,
                toolCalls = 1,
                error = "OpenRouter HTTP 400: Provider returned error"
            )
        )
    }

    @Test
    fun `browser type diagnostics never include typed text`() {
        val summary = AgentToolRuntimePolicy.mainToolArgs(
            gson,
            "local_browser_type",
            """{"ref":17,"text":"SuperSecretPassword!","submit":true}"""
        )
        assertTrue(summary.contains("ref=17"))
        assertTrue(summary.contains("text=[redacted"))
        assertFalse(summary.contains("SuperSecretPassword"))
    }

    @Test
    fun `diagnostic urls drop query parameters`() {
        val summary = AgentToolRuntimePolicy.mainToolArgs(
            gson,
            "local_browser_open",
            """{"url":"https://example.com/path?token=secret&x=1#frag"}"""
        )
        assertTrue(summary.contains("https://example.com/path?[redacted]"))
        assertFalse(summary.contains("token=secret"))
    }

    @Test
    fun `required tool choice 400 gets one compatibility fallback`() {
        assertTrue(
            LocalShellProviderPolicy.shouldRetryRequiredAsAuto(
                turn = 1,
                forceDecision = false,
                error = IllegalStateException("OpenRouter HTTP 400: Provider returned error")
            )
        )
        assertFalse(
            LocalShellProviderPolicy.shouldRetryRequiredAsAuto(
                turn = 2,
                forceDecision = false,
                error = IllegalStateException("OpenRouter HTTP 400: Provider returned error")
            )
        )
        assertFalse(
            LocalShellProviderPolicy.shouldRetryRequiredAsAuto(
                turn = 1,
                forceDecision = false,
                error = IllegalStateException("OpenRouter HTTP 500: Provider returned error")
            )
        )
    }
}
