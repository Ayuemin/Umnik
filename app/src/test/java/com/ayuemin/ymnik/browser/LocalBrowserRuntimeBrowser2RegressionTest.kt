package com.ayuemin.ymnik.browser

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBrowserRuntimeBrowser2RegressionTest {
    private fun source(path: String): String = sequenceOf(
        File(path),
        File("app/$path")
    ).first { it.isFile }.readText()

    private fun runtime(): String =
        source("src/main/java/com/ayuemin/ymnik/browser/LocalBrowserRuntime.kt")

    @Test fun composedDomTraversalCoversOpenShadowSlotsAndFrames() {
        val s = runtime()
        assertTrue(s.contains("node.shadowRoot && node.shadowRoot.mode === 'open'"))
        assertTrue(s.contains("assignedElements({flatten:true})"))
        assertTrue(s.contains("node.contentDocument"))
        assertTrue(s.contains("same_origin_iframes"))
        assertTrue(s.contains("cross_origin_iframe_srcs"))
        assertTrue(s.contains("reg.weak.get(el)"))
        assertTrue(s.contains("main_frame:mainFrame"))
    }

    @Test fun emptySpaCanvasAndAntibotDiagnosticsAreExplicit() {
        val s = runtime()
        assertTrue(s.contains("content_state: contentState"))
        assertTrue(s.contains("page_class: pageClass"))
        assertTrue(s.contains("blocked_by: blockedBy"))
        assertTrue(s.contains("canvas_count: canvasCount"))
        assertTrue(s.contains("iframe_cross_origin: crossOriginFrames > 0"))
        assertTrue(s.contains("'captcha'"))
        assertTrue(s.contains("'antibot'"))
        assertFalse(s.contains("captcha_detected value="))
        assertFalse(s.contains("password_value"))
    }

    @Test fun fullReadCreatesComposedDomArtifactForShell() {
        val s = runtime()
        assertTrue(s.contains("DOM_DUMP_MAX_CHARS"))
        assertTrue(s.contains("UMNIK OPEN SHADOW"))
        assertTrue(s.contains("UMNIK SLOT"))
        assertTrue(s.contains("UMNIK DOCUMENT"))
        assertTrue(s.contains("data-umnik-redacted"))
        assertTrue(s.contains("type === 'hidden'"))
        assertTrue(s.contains("autoComplete === 'one-time-code'"))
        assertTrue(s.contains("\"browser_dom/\" + session.sessionId"))
        assertTrue(s.contains("addProperty(\"local_path\", target.absolutePath)"))

        val vm = source("src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
        assertTrue(vm.contains("persistBrowserDownload("))
        assertTrue(vm.contains("LocalBrowserRuntime.read(chatId, full)"))
    }

    @Test fun adaptiveWaitSupportsStableSelectorTextAndRef() {
        val s = runtime()
        assertTrue(s.contains("MutationObserver"))
        assertTrue(s.contains("\"dom_stable\", \"selector_present\", \"text_present\", \"ref_present\""))
        assertTrue(s.contains("actual_wait_ms"))
        assertTrue(s.contains("invalid_selector"))
        assertTrue(s.contains("ref_missing_or_stale"))
    }

    @Test fun typingHasSafeSubmitDiagnosticsAndTakeover() {
        val s = runtime()
        assertTrue(s.contains("Object.getOwnPropertyDescriptor(proto, 'value')?.set"))
        assertTrue(s.contains("value_after"))
        assertTrue(s.contains("form_action"))
        assertTrue(s.contains("submit_requires_confirmation"))
        assertTrue(s.contains("confirmedSubmitScript"))
        assertTrue(s.contains("secret_field"))
        assertTrue(s.contains("file_input_requires_user"))
        assertTrue(s.contains("user_takeover:true"))
        assertTrue(s.contains("covered_by_overlay"))
        assertTrue(s.contains("candidates:fieldCandidates()"))
    }

    @Test fun takeoverReturnsFreshSnapshotWithoutAutoRepeatingAction() {
        val s = runtime()
        assertTrue(s.contains("snapshot(session, webView, forceBaseline = true)"))
        assertFalse(s.contains("repeatLastBrowserAction"))
    }
}
