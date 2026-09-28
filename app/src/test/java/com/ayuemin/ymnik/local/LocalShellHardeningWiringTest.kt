package com.ayuemin.ymnik.local

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalShellHardeningRegressionTest {
    private fun source(vararg candidates: String): String =
        candidates.asSequence().map(::File).first { it.isFile }.readText()

    @Test
    fun `engine hardening is wired not merely declared`() {
        val engine = source(
            "src/main/java/com/ayuemin/ymnik/local/LocalShellEngine.kt",
            "app/src/main/java/com/ayuemin/ymnik/local/LocalShellEngine.kt"
        )
        assertTrue(engine.contains("LocalShellSessionGuard()"))
        assertTrue(engine.contains("sessionGuard.blockReason"))
        assertTrue(engine.contains("GIT_METADATA_PROTECTED"))
        assertTrue(engine.contains("validateGitMetadata"))
        assertTrue(engine.contains("GIT_CAPABILITY_UNAVAILABLE"))
        assertTrue(engine.contains("already_read"))
        assertTrue(engine.contains("markRepositorySourceAcquired"))
        assertTrue(engine.contains("checkpointSummary()"))
        assertTrue(engine.contains("LocalShellBudgetTelemetry.recordTool"))
        assertTrue(engine.contains("DESTINATION_OCCUPIED"))
        assertTrue(engine.contains("MAX_INLINE_TEXT_CHARS = 12_000"))
        assertFalse(engine.contains("Git-репозиторий не найден: \" + relative(repo)"))
    }

    @Test
    fun `python and archive paths cannot fabricate git metadata`() {
        val python = source(
            "src/main/python/umnik_local_runtime.py",
            "app/src/main/python/umnik_local_runtime.py"
        )
        val engine = source(
            "src/main/java/com/ayuemin/ymnik/local/LocalShellEngine.kt",
            "app/src/main/java/com/ayuemin/ymnik/local/LocalShellEngine.kt"
        )
        assertTrue(python.contains("_reject_git_write"))
        assertTrue(python.contains("GIT_METADATA_PROTECTED"))
        assertTrue(python.contains("os.rename"))
        assertTrue(python.contains("os.symlink"))
        assertTrue(engine.contains("safeArchiveTarget"))
        assertTrue(engine.contains("containsGitMetadataSegment(entryName)"))
    }

    @Test
    fun `release keeps jgit and shell network telemetry is bounded`() {
        val proguard = source("proguard-rules.pro", "app/proguard-rules.pro")
        val diagnostic = source(
            "src/main/java/com/ayuemin/ymnik/diagnostics/DiagnosticLog.kt",
            "app/src/main/java/com/ayuemin/ymnik/diagnostics/DiagnosticLog.kt"
        )
        val network = source(
            "src/main/java/com/ayuemin/ymnik/diagnostics/DiagnosticNetworkEventListener.kt",
            "app/src/main/java/com/ayuemin/ymnik/diagnostics/DiagnosticNetworkEventListener.kt"
        )
        assertTrue(proguard.contains("-keep class org.eclipse.jgit.** { *; }"))
        assertTrue(diagnostic.contains("SHELL_READ_IDLE_TIMEOUT_SECONDS = 90"))
        assertTrue(diagnostic.contains("SHELL_SLOW_START_LIMIT = 3"))
        assertTrue(diagnostic.contains("actualModel="))
        assertTrue(diagnostic.contains("cached="))
        assertTrue(diagnostic.contains("SHELL_BUDGET"))
        assertTrue(network.contains("SHELL_RESPONSE_START_TIMEOUT_MS = 60_000L"))
    }

    @Test
    fun `terminal failures preserve a partial checkpoint`() {
        val runtime = source(
            "src/main/java/com/ayuemin/ymnik/LocalShellRuntime.kt",
            "app/src/main/java/com/ayuemin/ymnik/LocalShellRuntime.kt"
        )
        assertTrue(runtime.contains("installCheckpointProvider"))
        assertTrue(runtime.contains("STATUS=PARTIAL"))
        assertTrue(runtime.contains("Артефакты:"))
        assertTrue(runtime.contains("Usage: turns="))
    }
}
