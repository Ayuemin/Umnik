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
        assertTrue(vm.contains("LocalBrowserRuntime.read(chatId, full, user.id, browserReadGrant)"))
    }

    @Test fun fullReadBudgetIsNotReducedByLargePageHardening() {
        val s = runtime()
        assertTrue(s.contains("FULL_TEXT_LIMIT = 24_000"))
        assertTrue(s.contains("FULL_ELEMENT_LIMIT = 120"))
        assertTrue(s.contains("DOM_DUMP_MAX_CHARS = 1_000_000"))
    }

    @Test fun largePagesPrioritizeFieldsAndContentLinks() {
        val s = runtime()
        assertTrue(s.contains("[role=\"searchbox\"]"))
        assertTrue(s.contains("[role=\"combobox\"]"))
        assertTrue(s.contains("const controlPriority = (el) =>"))
        assertTrue(s.contains("role === 'searchbox' || role === 'combobox') return 3"))
        assertTrue(s.contains("navLike ? -1000 : 0"))
    }

    @Test fun directFileHandoffAndContentMismatchAreExplicit() {
        val s = runtime()
        assertTrue(s.contains("pendingDownloadUrl"))
        assertTrue(s.contains("download_ref"))
        assertTrue(s.contains("suggested_tool"))
        assertTrue(s.contains("ref == 0"))
        assertTrue(s.contains("download_pending_url"))
        assertTrue(s.contains("pendingDownloadNavigationResult"))
        assertTrue(s.contains("result=download_pending"))
        assertTrue(s.contains("reason = \"download_pending\""))
        assertTrue(s.contains("detected_mime"))
        assertTrue(s.contains("content_mismatch"))
        assertTrue(s.contains("%PDF-"))
        assertTrue(s.contains("detectedMime == \"text/html\""))
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

    @Test fun safeSearchControlsAreNarrowlyAllowedWithoutWeakeningSensitiveConfirmation() {
        val s = runtime()
        assertTrue(s.contains("kind:'search_navigation'"))
        assertTrue(s.contains("formMethod === 'get'"))
        assertTrue(s.contains("String(form.getAttribute('role') || '').toLowerCase() === 'search'"))
        assertTrue(s.contains("form.querySelector('input[type=\"search\"],[role=\"searchbox\"]')"))
        assertTrue(s.contains("!form.querySelector('input[type=\"password\"],input[type=\"file\"]')"))
        assertTrue(s.contains("const sensitiveSubmit ="))
        assertTrue(s.contains("const safePostSearch = formMethod === 'post'"))
        assertTrue(s.contains("new URL(formAction, doc.location.href).origin === doc.location.origin"))
        assertTrue(s.contains("const consequential = (formMethod !== 'get' && !safePostSearch) || sensitiveSubmit"))
        assertTrue(s.contains("confirmation_required:true"))
        assertTrue(s.contains("user_takeover:true"))
    }

    @Test fun searchButtonAwaitsNavigationButAllowsSamePageResults() {
        val s = runtime()
        val clickStart = s.indexOf("suspend fun click(")
        val typeStart = s.indexOf("suspend fun type(", clickStart)
        assertTrue(clickStart >= 0 && typeStart > clickStart)
        val click = s.substring(clickStart, typeStart)
        assertTrue(click.contains("actionKind == \"navigation\" || actionKind == \"search_navigation\""))
        assertTrue(click.contains("if (mainFrame && actionKind == \"navigation\")"))
        assertTrue(click.contains("settleAfterAction("))
        assertTrue(click.contains("settleOptionalNavigation("))
        assertTrue(click.contains("if ((actionKind == \"search_navigation\" || !mainFrame) && navigation == null) delay(300)"))
        assertTrue(click.contains("navigationFailureJson(session, navigation, expectedHref)"))
        assertTrue(click.contains("snapshot(session, webView)"))
    }

    @Test fun actualBrowserOpenAndReadRequireDirectUiScopeInsideRuntime() {
        val s = runtime()
        val vm = source("src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
        assertTrue(s.contains("private fun requirePublicReadGrant("))
        assertTrue(s.contains("TrustedPublicBrowserReadGuard()"))
        assertTrue(s.contains("requirePublicReadGrant(chatId, directUserMessageId, publicReadGrant, \"local_browser_open\", safeUrl)"))
        assertTrue(s.contains("requirePublicReadGrant(chatId, directUserMessageId, publicReadGrant, \"local_browser_open\", currentUrl(webView))"))
        assertTrue(s.contains("requirePublicReadGrant(chatId, directUserMessageId, publicReadGrant, \"local_browser_read\", session.currentUrl)"))
        assertTrue(s.contains("requirePublicReadGrant(chatId, directUserMessageId, publicReadGrant, \"local_browser_read\", currentUrl(webView))"))
        assertTrue(vm.contains("browserEnabledForRequest = localBrowserToolsEnabled"))
        assertTrue(vm.contains("LocalBrowserRuntime.open(chatId, url, user.id, browserReadGrant)"))
        assertTrue(vm.contains("LocalBrowserRuntime.read(chatId, full, user.id, browserReadGrant)"))
    }

    @Test fun takeoverReturnsFreshSnapshotWithoutAutoRepeatingAction() {
        val s = runtime()
        assertTrue(s.contains("snapshot(session, webView, forceBaseline = true)"))
        assertFalse(s.contains("repeatLastBrowserAction"))
    }
}
