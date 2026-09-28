package com.ayuemin.ymnik.network

import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRuntimePolicyTest {
    private val gson = Gson()

    @Test
    fun `explicit no download suppresses required browser download`() {
        assertTrue(AgentToolRuntimePolicy.suppressRequiredBrowserDownload("Найди PDF, но пока сам файл не скачивай."))
    }

    @Test
    fun `lookup-only download wording does not force download`() {
        assertTrue(AgentToolRuntimePolicy.suppressRequiredBrowserDownload("Найди возможность скачать EPUB и скажи, какая ссылка нужна."))
    }

    @Test
    fun `explicit download action stays required`() {
        assertFalse(AgentToolRuntimePolicy.suppressRequiredBrowserDownload("Найди PDF и скачай его."))
    }

    @Test
    fun `explicit local shell request wins over browser download routing`() {
        assertTrue(AgentToolRuntimePolicy.explicitlyRequestsLocalShell("Запусти Local Shell и скачай ZIP репозитория."))
        assertTrue(AgentToolRuntimePolicy.suppressRequiredBrowserDownload("Запусти Local Shell и скачай ZIP репозитория."))
        assertTrue(AgentToolRuntimePolicy.explicitlyRequestsLocalShell("Use Local Shell to download and unpack this repository."))
    }

    @Test
    fun `discussion about local shell does not force execution`() {
        assertFalse(AgentToolRuntimePolicy.explicitlyRequestsLocalShell("Расскажи, что такое Local Shell и зачем он нужен."))
    }

    @Test
    fun `deterministic shell startup 400 is recognized only before tools`() {
        assertTrue(AgentToolRuntimePolicy.isDeterministicShellStartupFailure(1, 0, "OpenRouter HTTP 400: Provider returned error"))
        assertFalse(AgentToolRuntimePolicy.isDeterministicShellStartupFailure(2, 1, "OpenRouter HTTP 400: Provider returned error"))
    }

    @Test
    fun `terminal shell network failure suppresses automatic restart even after progress`() {
        assertTrue(AgentToolRuntimePolicy.isDeterministicShellStartupFailure(86, 173, "stream was reset: PROTOCOL_ERROR"))
        assertTrue(AgentToolRuntimePolicy.isDeterministicShellStartupFailure(18, 17, "Socket closed after idle timeout"))
        assertTrue(AgentToolRuntimePolicy.isDeterministicShellStartupFailure(9, 12, "LOCAL_SHELL_START_TIMEOUT: no response headers within 60s"))
        assertTrue(AgentToolRuntimePolicy.isDeterministicShellStartupFailure(9, 12, "LOCAL_SHELL_NETWORK_PAUSE: slow provider"))
    }

    @Test
    fun `terminal structural shell failure suppresses automatic restart`() {
        assertTrue(AgentToolRuntimePolicy.isDeterministicShellStartupFailure(9, 12, "GIT_CAPABILITY_UNAVAILABLE: JGit runtime probe failed"))
        assertFalse(AgentToolRuntimePolicy.isDeterministicShellStartupFailure(9, 12, "Файл не найден"))
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
        assertTrue(LocalShellProviderPolicy.shouldRetryRequiredAsAuto(1, false, IllegalStateException("OpenRouter HTTP 400: Provider returned error")))
        assertFalse(LocalShellProviderPolicy.shouldRetryRequiredAsAuto(2, false, IllegalStateException("OpenRouter HTTP 400: Provider returned error")))
        assertFalse(LocalShellProviderPolicy.shouldRetryRequiredAsAuto(1, false, IllegalStateException("OpenRouter HTTP 500: Provider returned error")))
    }

    @Test
    fun `local list string entries diagnostics never crash`() {
        val summary = AgentToolRuntimePolicy.localShellToolResult(
            gson,
            """{"ok":true,"path":".","entries":"input/Hello-World-master.zip (351 B)","truncated":false}"""
        )
        assertTrue(summary.contains("ok=true"))
        assertTrue(summary.contains("entries_chars="))
    }

    @Test
    fun `string matches diagnostics never crash`() {
        val summary = AgentToolRuntimePolicy.localShellToolResult(
            gson,
            """{"ok":true,"matches":"README:1: Hello World","count":1}"""
        )
        assertTrue(summary.contains("ok=true"))
        assertTrue(summary.contains("matches_chars="))
    }

    @Test
    fun `malformed structured main result fields are ignored safely`() {
        val summary = AgentToolRuntimePolicy.mainToolResult(
            gson,
            "local_shell_start",
            """{"ok":false,"artifact":"not-an-object","files":"not-an-array"}"""
        )
        assertTrue(summary.contains("ok=false"))
    }
}
