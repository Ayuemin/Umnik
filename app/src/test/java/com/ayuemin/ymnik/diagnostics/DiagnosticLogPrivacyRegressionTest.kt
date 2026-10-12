package com.ayuemin.ymnik.diagnostics

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogPrivacyRegressionTest {
    private fun source(): String = sequenceOf(
        File("src/main/java/com/ayuemin/ymnik/diagnostics/DiagnosticLog.kt"),
        File("app/src/main/java/com/ayuemin/ymnik/diagnostics/DiagnosticLog.kt")
    ).first { it.isFile }.readText()

    @Test fun shellTaskLoggingOmitsUserPrompt() {
        val src = source()
        val section = src.substring(
            src.indexOf("private fun recordLocalShellTask("),
            src.indexOf("private fun recordLocalShellResponse(")
        )
        assertTrue(section.contains("content_omitted=true"))
        assertTrue(section.contains("task.length"))
        assertFalse(section.contains("; task="))
        assertFalse(section.contains("oneLine"))
    }

    @Test fun providerErrorLoggingOmitsRawResponse() {
        val src = source()
        val section = src.substring(
            src.indexOf("private fun recordLocalShellResponse("),
            src.indexOf("companion object", src.indexOf("private fun recordLocalShellResponse("))
        )
        assertTrue(section.contains("body_omitted=true"))
        assertFalse(section.contains("detail.take("))
        assertFalse(section.contains("; body="))
    }
}
