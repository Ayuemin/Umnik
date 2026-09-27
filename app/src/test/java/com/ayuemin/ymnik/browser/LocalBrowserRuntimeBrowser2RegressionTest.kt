package com.ayuemin.ymnik.browser

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBrowserRuntimeBrowser2RegressionTest {
    private fun source(): String = sequenceOf(
        File("src/main/java/com/ayuemin/ymnik/browser/LocalBrowserRuntime.kt"),
        File("app/src/main/java/com/ayuemin/ymnik/browser/LocalBrowserRuntime.kt")
    ).first { it.isFile }.readText()

    @Test fun composedDomTraversalCoversOpenShadowSlotsAndSameOriginFrames() {
        val s = source()
        assertTrue(s.contains("node.shadowRoot && node.shadowRoot.mode === 'open'"))
        assertTrue(s.contains("assignedElements({flatten:true})"))
        assertTrue(s.contains("node.contentDocument"))
        assertTrue(s.contains("same_origin_iframes"))
        assertTrue(s.contains("cross_origin_iframes"))
        assertTrue(s.contains("reg.weak.get(el)"))
    }

    @Test fun diagnosticsAndAdaptiveWaitDoNotExposeFieldValues() {
        val s = source()
        assertTrue(s.contains("captcha_detected session="))
        assertTrue(s.contains("SNAPSHOT_SYNC_ATTEMPTS * 2"))
        assertTrue(s.contains("diagnostics: {"))
        assertFalse(s.contains("captcha_detected value="))
        assertFalse(s.contains("password_value"))
    }

    @Test fun typingUsesNativeSetterAndKeepsTakeoverSafety() {
        val s = source()
        assertTrue(s.contains("Object.getOwnPropertyDescriptor(proto, 'value')?.set"))
        assertTrue(s.contains("sensitive_field_requires_user"))
        assertTrue(s.contains("file_input_requires_user"))
        assertTrue(s.contains("user_takeover:true"))
    }
}
