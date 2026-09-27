#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "app/src/main/java/com/ayuemin/ymnik/browser/LocalBrowserRuntime.kt"
CLIENT = ROOT / "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"
VIEWMODEL = ROOT / "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
PROMPT_TEST = ROOT / "app/src/test/java/com/ayuemin/ymnik/network/PromptBaselineProbeTest.kt"
BROWSER_TEST = ROOT / "app/src/test/java/com/ayuemin/ymnik/browser/LocalBrowserRuntimeBrowser2RegressionTest.kt"


def replace_once(path: Path, old: str, new: str):
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one exact match, got {count}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1))


def sub_once(path: Path, pattern: str, replacement: str):
    text = path.read_text()
    updated, count = re.subn(pattern, lambda _: replacement, text, count=1, flags=re.S)
    if count != 1:
        raise RuntimeError(f"{path}: expected one regex match, got {count}: {pattern[:100]!r}")
    path.write_text(updated)


replace_once(
    RUNTIME,
    "    private const val SNAPSHOT_SYNC_ATTEMPTS = 24\n",
    """    private const val SNAPSHOT_SYNC_ATTEMPTS = 24
    private const val DOM_DUMP_MAX_CHARS = 1_000_000
    private const val DOM_STABLE_MS = 450L
"""
)

sub_once(
    RUNTIME,
    r'''    suspend fun read\(chatId: String, full: Boolean = false\): String = commandMutex\.withLock \{.*?\n    \}\n\n    suspend fun click''',
    r'''    suspend fun read(chatId: String, full: Boolean = false): String = commandMutex.withLock {
        val session = requireSession(chatId)
        runCommand(
            session,
            session.currentUrl,
            if (full) "читает страницу подробно" else "читает страницу",
            "read",
            "full=" + full
        ) {
            val webView = webView()
            ensureCurrent(session, webView)
            val page = snapshot(session, webView, forceBaseline = true, expanded = full)
            if (full) attachDomDumpArtifact(webView, session, page) else page
        }
    }

    suspend fun click'''
)

replace_once(
    RUNTIME,
    '''            if (result.get("kind")?.asString == "navigation") {
                val expectedHref = result.get("href")?.asString
                val navigation = settleAfterAction(
                    session = session,
                    webView = webView,
                    beforeUrl = before,
                    expectedUrl = expectedHref,
                    startedBefore = startedBefore,
                    finishedBefore = finishedBefore,
                    errorsBefore = errorsBefore
                )
                if (!navigation.completed) {
                    session.lifecycle = LocalBrowserLifecycle.BLOCKED
                    return@runCommand navigationFailureJson(session, navigation, expectedHref)
                }
            } else {
                delay(250)
            }
''',
    '''            if (result.get("kind")?.asString == "navigation") {
                val expectedHref = result.get("href")?.asString
                val mainFrame = result.get("main_frame")?.asBoolean ?: true
                val navigation = if (mainFrame) {
                    settleAfterAction(
                        session = session,
                        webView = webView,
                        beforeUrl = before,
                        expectedUrl = expectedHref,
                        startedBefore = startedBefore,
                        finishedBefore = finishedBefore,
                        errorsBefore = errorsBefore
                    )
                } else {
                    settleOptionalNavigation(
                        session = session,
                        webView = webView,
                        beforeUrl = before,
                        startedBefore = startedBefore,
                        finishedBefore = finishedBefore,
                        errorsBefore = errorsBefore
                    )
                }
                if (navigation != null && !navigation.completed) {
                    session.lifecycle = LocalBrowserLifecycle.BLOCKED
                    return@runCommand navigationFailureJson(session, navigation, expectedHref)
                }
                if (!mainFrame && navigation == null) delay(300)
            } else {
                delay(250)
            }
'''
)

sub_once(
    RUNTIME,
    r'''    suspend fun type\(chatId: String, ref: Int, text: String\): String = commandMutex\.withLock \{.*?\n    \}\n\n    suspend fun takeover''',
    r'''    suspend fun type(
        chatId: String,
        ref: Int,
        text: String,
        submit: Boolean = false
    ): String = commandMutex.withLock {
        val session = requireSession(chatId)
        runCommand(
            session,
            session.currentUrl,
            if (submit) "вводит текст и отправляет форму" else "вводит текст",
            "type",
            "ref=" + ref + "; chars=" + text.length + "; submit=" + submit,
            timeoutMs = USER_INTERACTION_TIMEOUT_MS + COMMAND_TIMEOUT_MS
        ) {
            val webView = webView()
            ensureCurrent(session, webView)
            val before = currentUrl(webView)
            val startedBefore = session.navigationStartedCount
            val finishedBefore = session.navigationFinishedCount
            val errorsBefore = session.mainFrameErrorCount
            var result = decodedJson(evaluate(webView, typeScript(ref, text, submit)))
            if (result.get("ok")?.asBoolean != true) {
                if (result.get("confirmation_required")?.asBoolean == true) {
                    val label = result.get("name")?.asString.orEmpty().ifBlank { "отправить форму" }
                    val decision = awaitUserGate(
                        session,
                        kind = "CONFIRM_ACTION",
                        message = "Подтвердите: «" + label.take(120) + "». Отправка формы может изменить данные."
                    )
                    if (decision != "confirm") {
                        session.lifecycle = LocalBrowserLifecycle.BLOCKED
                        return@runCommand blockedActionJson(session, result, recoverable = false, reasonOverride = "user_cancelled")
                    }
                    session.lifecycle = LocalBrowserLifecycle.WORKING
                    result = decodedJson(evaluate(webView, confirmedSubmitScript(ref)))
                    if (result.get("ok")?.asBoolean != true) {
                        session.lifecycle = LocalBrowserLifecycle.BLOCKED
                        return@runCommand blockedActionJson(session, result)
                    }
                } else if (result.get("user_takeover")?.asBoolean == true) {
                    val decision = awaitUserGate(
                        session,
                        kind = "TAKEOVER",
                        message = "Нужен ручной ввод на странице. Введите пароль, код, файл или пройдите проверку сами — секретные данные не передаются модели."
                    )
                    if (decision != "done") {
                        session.lifecycle = LocalBrowserLifecycle.BLOCKED
                        return@runCommand blockedActionJson(session, result, recoverable = false, reasonOverride = "user_cancelled")
                    }
                    session.lifecycle = LocalBrowserLifecycle.WORKING
                    delay(250)
                    return@runCommand snapshot(session, webView, forceBaseline = true)
                } else {
                    session.lifecycle = LocalBrowserLifecycle.BLOCKED
                    return@runCommand blockedActionJson(session, result)
                }
            }

            if (result.get("submitted")?.asBoolean == true) {
                val mainFrame = result.get("main_frame")?.asBoolean ?: true
                val navigation = settleOptionalNavigation(
                    session = session,
                    webView = webView,
                    beforeUrl = before,
                    startedBefore = startedBefore,
                    finishedBefore = finishedBefore,
                    errorsBefore = errorsBefore
                )
                if (navigation != null && !navigation.completed) {
                    session.lifecycle = LocalBrowserLifecycle.BLOCKED
                    return@runCommand navigationFailureJson(
                        session,
                        navigation,
                        result.get("form_action")?.asString
                    )
                }
                if (!mainFrame && navigation == null) delay(300)
            } else {
                delay(250)
            }
            mergeActionSnapshot(snapshot(session, webView), result)
        }
    }

    suspend fun takeover'''
)

sub_once(
    RUNTIME,
    r'''    suspend fun wait\(chatId: String, seconds: Int\): String = commandMutex\.withLock \{.*?\n    \}\n\n    suspend fun done''',
    r'''    suspend fun wait(
        chatId: String,
        seconds: Int,
        modeRaw: String = "dom_stable",
        valueRaw: String = "",
        ref: Int? = null
    ): String = commandMutex.withLock {
        val mode = modeRaw.trim().lowercase().ifBlank { "dom_stable" }
        require(mode in setOf("dom_stable", "selector_present", "text_present", "ref_present")) {
            "Неизвестный режим ожидания"
        }
        val value = valueRaw.trim().take(500)
        if (mode == "selector_present" || mode == "text_present") {
            require(value.isNotBlank()) { "Для $mode нужен value" }
        }
        if (mode == "ref_present") require(ref != null && ref > 0) { "Для ref_present нужен ref" }
        val maxSeconds = seconds.coerceIn(1, 15)
        val session = requireSession(chatId)
        runCommand(
            session,
            session.currentUrl,
            "ждёт обновления страницы",
            "wait",
            "seconds=" + maxSeconds + "; mode=" + mode
        ) {
            val webView = webView()
            ensureCurrent(session, webView)
            val startedAt = SystemClock.elapsedRealtime()
            val deadline = startedAt + maxSeconds * 1_000L
            var condition = JsonObject()
            var fulfilled = false
            do {
                condition = decodedJson(evaluate(webView, waitConditionScript(mode, value, ref)))
                fulfilled = condition.get("fulfilled")?.asBoolean == true
                if (fulfilled || condition.get("reason")?.asString == "invalid_selector") break
                delay(150)
            } while (SystemClock.elapsedRealtime() < deadline)

            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val page = gson.fromJson(snapshot(session, webView), JsonObject::class.java)
            page.add("wait", JsonObject().apply {
                addProperty("mode", mode)
                when (mode) {
                    "selector_present", "text_present" -> addProperty("expected", value)
                    "ref_present" -> addProperty("expected_ref", ref)
                }
                addProperty("fulfilled", fulfilled)
                addProperty("timeout", !fulfilled && elapsed >= maxSeconds * 1_000L)
                addProperty("actual_wait_ms", elapsed)
                condition.get("reason")?.takeUnless { it.isJsonNull }?.let { add("reason", it) }
                condition.get("detail")?.takeUnless { it.isJsonNull }?.let { add("detail", it) }
            })
            gson.toJson(page)
        }
    }

    suspend fun done'''
)

replace_once(
    RUNTIME,
    '''            val pageElements = value.getAsJsonArray("elements") ?: JsonArray()
            val isWebPage = pageUrl.startsWith("http://") || pageUrl.startsWith("https://")
            return isWebPage && pageContent.isBlank() && pageElements.size() == 0
''',
    '''            val pageElements = value.getAsJsonArray("elements") ?: JsonArray()
            val traversal = value.getAsJsonObject("traversal") ?: JsonObject()
            val diagnostics = value.getAsJsonObject("diagnostics") ?: JsonObject()
            val structuralContent =
                (traversal.get("open_shadow_roots")?.asInt ?: 0) > 0 ||
                (traversal.get("same_origin_iframes")?.asInt ?: 0) > 0 ||
                (traversal.get("cross_origin_iframes")?.asInt ?: 0) > 0 ||
                (diagnostics.get("canvas_count")?.asInt ?: 0) > 0
            val isWebPage = pageUrl.startsWith("http://") || pageUrl.startsWith("https://")
            return isWebPage && pageContent.isBlank() && pageElements.size() == 0 && !structuralContent
'''
)

replace_once(
    RUNTIME,
    '''        if (diagnostics.get("captcha")?.asBoolean == true) {
            DiagnosticLog.record(webView.context.applicationContext, "LOCAL_BROWSER", "captcha_detected session=" + session.sessionId.take(8))
        }
''',
    '''        val blockedBy = diagnostics.get("blocked_by")?.asString.orEmpty()
        if (blockedBy.isNotBlank()) {
            DiagnosticLog.record(
                webView.context.applicationContext,
                "LOCAL_BROWSER",
                "blocked_by=" + blockedBy + " session=" + session.sessionId.take(8)
            )
        }
'''
)

replace_once(
    RUNTIME,
    '''                add("type_non_secret")
                add("scroll")
                add("back")
                add("wait")
''',
    '''                add("type_non_secret")
                add("type_submit")
                add("scroll")
                add("back")
                add("adaptive_wait")
                add("dom_dump_on_full_read")
'''
)

helpers = r'''
    private fun blockedActionJson(
        session: BrowserSession,
        result: JsonObject,
        recoverable: Boolean = true,
        reasonOverride: String? = null
    ): String = gson.toJson(
        JsonObject().apply {
            addProperty("ok", false)
            addProperty("source", "local_browser")
            addProperty("session_id", session.sessionId)
            addProperty("state", "BLOCKED")
            addProperty("reason", reasonOverride ?: result.get("reason")?.asString ?: "browser_action_blocked")
            addProperty("recoverable", recoverable)
            add("diagnostic", result.deepCopy())
        }
    )

    private fun mergeActionSnapshot(snapshotRaw: String, action: JsonObject): String {
        val page = gson.fromJson(snapshotRaw, JsonObject::class.java)
        page.add("action", action.deepCopy())
        return gson.toJson(page)
    }

    private suspend fun attachDomDumpArtifact(
        webView: WebView,
        session: BrowserSession,
        snapshotRaw: String
    ): String {
        val dump = decodedJson(evaluate(webView, domDumpScript(DOM_DUMP_MAX_CHARS)))
        val content = dump.get("dump")?.asString.orEmpty()
        if (content.isBlank()) return snapshotRaw

        val safeStep = session.stepNumber.coerceAtLeast(1)
        val name = "browser_dom_" + session.sessionId.take(8) + "_" + safeStep + ".html"
        val target = withContext(Dispatchers.IO) {
            val dir = File(
                webView.context.applicationContext.cacheDir,
                "browser_dom/" + session.sessionId
            ).apply { mkdirs() }
            File(dir, name).apply { writeText(content) }
        }
        val result = gson.fromJson(snapshotRaw, JsonObject::class.java)
        result.add("dom_dump", JsonObject().apply {
            addProperty("chars", content.length)
            addProperty("truncated", dump.get("truncated")?.asBoolean ?: false)
            addProperty("open_shadow_roots", dump.get("open_shadow_roots")?.asInt ?: 0)
            addProperty("same_origin_iframes", dump.get("same_origin_iframes")?.asInt ?: 0)
            addProperty("cross_origin_iframes", dump.get("cross_origin_iframes")?.asInt ?: 0)
        })
        result.add("artifact", JsonObject().apply {
            addProperty("name", name)
            addProperty("mime_type", "text/html")
            addProperty("size", target.length())
            addProperty("source_url", session.currentUrl)
            addProperty("local_path", target.absolutePath)
        })
        return gson.toJson(result)
    }

    private fun waitConditionScript(mode: String, value: String, ref: Int?): String {
        val modeJson = gson.toJson(mode)
        val valueJson = gson.toJson(value)
        val refValue = ref?.toString() ?: "null"
        return """
            (() => {
              const mode = $modeJson;
              const expected = $valueJson;
              const expectedRef = $refValue;
              const reg = window.__umnikRegistry;
              const roots = [];
              const seenRoots = new Set();
              const collect = (root) => {
                if (!root || seenRoots.has(root)) return;
                seenRoots.add(root);
                roots.push(root);
                const nodes = root.querySelectorAll ? Array.from(root.querySelectorAll('*')) : [];
                for (const node of nodes) {
                  if (node.shadowRoot && node.shadowRoot.mode === 'open') collect(node.shadowRoot);
                  if ((node.tagName || '').toLowerCase() === 'iframe') {
                    try {
                      const doc = node.contentDocument;
                      if (doc && doc.documentElement) collect(doc);
                    } catch (_) {}
                  }
                }
              };
              collect(document);
              if (reg) reg.roots = roots;
              const observed = roots
                .map(root => root.nodeType === 9 ? root.documentElement : root)
                .filter(Boolean);
              const state = window.__umnikWaitState || (window.__umnikWaitState = {
                lastMutation: performance.now(),
                observers: [],
                observedRoots: []
              });
              const rootsChanged = state.observedRoots.length !== observed.length ||
                state.observedRoots.some((root, index) => root !== observed[index]);
              if (rootsChanged) {
                (state.observers || []).forEach(observer => { try { observer.disconnect(); } catch (_) {} });
                state.observers = [];
                state.observedRoots = observed.slice();
                state.lastMutation = performance.now();
                for (const root of observed) {
                  try {
                    const observer = new MutationObserver(() => { state.lastMutation = performance.now(); });
                    observer.observe(root, {subtree:true, childList:true, attributes:true, characterData:true});
                    state.observers.push(observer);
                  } catch (_) {}
                }
              }
              const queryAll = (selector) => {
                const out = [];
                const seen = new Set();
                for (const root of roots) {
                  const found = root.querySelectorAll ? Array.from(root.querySelectorAll(selector)) : [];
                  for (const el of found) if (!seen.has(el)) { seen.add(el); out.push(el); }
                }
                return out;
              };
              let fulfilled = false;
              let reason = '';
              let detail = '';
              if (mode === 'dom_stable') {
                const age = performance.now() - state.lastMutation;
                fulfilled = document.readyState !== 'loading' && age >= $DOM_STABLE_MS;
                detail = 'mutation_age_ms=' + Math.round(age);
              } else if (mode === 'selector_present') {
                try {
                  const matches = queryAll(expected);
                  fulfilled = matches.some(el => !!el && el.isConnected);
                  detail = 'matches=' + matches.length;
                } catch (_) {
                  reason = 'invalid_selector';
                }
              } else if (mode === 'text_present') {
                const haystack = roots.map(root =>
                  String(root.body?.innerText || root.host?.innerText || root.textContent || '')
                ).join('\n').toLowerCase();
                fulfilled = haystack.includes(String(expected || '').toLowerCase());
                detail = 'visible_text_chars=' + haystack.length;
              } else if (mode === 'ref_present') {
                const el = reg?.refs?.get(Number(expectedRef));
                fulfilled = !!el && el.isConnected;
                detail = fulfilled ? 'ref_connected' : 'ref_missing_or_stale';
              }
              return JSON.stringify({ok:true, fulfilled, reason, detail});
            })()
        """.trimIndent()
    }

    private fun domDumpScript(limit: Int): String = """
        (() => {
          const maxChars = $limit;
          const parts = [];
          let chars = 0;
          let truncated = false;
          let shadowRoots = 0;
          let sameOriginFrames = 0;
          let crossOriginFrames = 0;
          const seenDocs = new Set();
          const seenShadows = new Set();
          const push = (value) => {
            if (truncated || value == null) return;
            const text = String(value);
            const room = maxChars - chars;
            if (room <= 0) { truncated = true; return; }
            if (text.length > room) {
              parts.push(text.slice(0, room));
              chars += room;
              truncated = true;
            } else {
              parts.push(text);
              chars += text.length;
            }
          };
          const clean = (value, n = 180) => String(value || '').replace(/\s+/g, ' ').trim().slice(0, n);
          let serializeDocument;
          const scanRoot = (root, label) => {
            const nodes = root.querySelectorAll ? Array.from(root.querySelectorAll('*')) : [];
            for (const node of nodes) {
              if (truncated) break;
              if (node.shadowRoot && node.shadowRoot.mode === 'open' && !seenShadows.has(node.shadowRoot)) {
                seenShadows.add(node.shadowRoot);
                shadowRoots++;
                push('\n<!-- UMNIK OPEN SHADOW ' + label + ' host=' + clean(node.tagName + '#' + (node.id || '')) + ' -->\n');
                push(node.shadowRoot.innerHTML || node.shadowRoot.textContent || '');
                scanRoot(node.shadowRoot, label + '/shadow' + shadowRoots);
              }
              if ((node.tagName || '').toLowerCase() === 'slot' && typeof node.assignedElements === 'function') {
                const assigned = node.assignedElements({flatten:true});
                if (assigned.length) {
                  push('\n<!-- UMNIK SLOT ' + label + ' assigned=' + assigned.length + ' -->\n');
                  for (const item of assigned) push(item.outerHTML || item.textContent || '');
                }
              }
              if ((node.tagName || '').toLowerCase() === 'iframe') {
                const src = clean(node.src || node.getAttribute('src') || '', 500);
                try {
                  const doc = node.contentDocument;
                  if (doc && doc.documentElement) {
                    sameOriginFrames++;
                    serializeDocument(doc, label + '/iframe' + sameOriginFrames);
                  } else {
                    crossOriginFrames++;
                    push('\n<!-- UMNIK CROSS ORIGIN IFRAME src=' + src + ' -->\n');
                  }
                } catch (_) {
                  crossOriginFrames++;
                  push('\n<!-- UMNIK CROSS ORIGIN IFRAME src=' + src + ' -->\n');
                }
              }
            }
          };
          serializeDocument = (doc, label) => {
            if (!doc || seenDocs.has(doc) || truncated) return;
            seenDocs.add(doc);
            push('\n<!-- UMNIK DOCUMENT ' + label + ' url=' + clean(doc.location?.href || '', 500) + ' -->\n');
            push(doc.documentElement?.outerHTML || doc.body?.innerHTML || doc.body?.innerText || '');
            scanRoot(doc, label);
          };
          serializeDocument(document, 'main');
          return JSON.stringify({
            ok:true,
            dump:parts.join(''),
            chars,
            truncated,
            open_shadow_roots:shadowRoots,
            same_origin_iframes:sameOriginFrames,
            cross_origin_iframes:crossOriginFrames
          });
        })()
    """.trimIndent()

'''
replace_once(
    RUNTIME,
    '    private fun snapshotScript(startRef: Int, textLimit: Int, elementLimit: Int): String = """\n',
    helpers + '    private fun snapshotScript(startRef: Int, textLimit: Int, elementLimit: Int): String = """\n'
)

snapshot_function = r'''    private fun snapshotScript(startRef: Int, textLimit: Int, elementLimit: Int): String = """
        (() => {
          const reg = window.__umnikRegistry || (window.__umnikRegistry = {
            next: $startRef,
            weak: new WeakMap(),
            refs: new Map()
          });
          reg.next = Math.max(reg.next || $startRef, $startRef);
          for (const [ref, el] of Array.from(reg.refs.entries())) {
            if (!el || !el.isConnected) reg.refs.delete(ref);
          }
          const refFor = (el) => {
            let ref = reg.weak.get(el);
            if (!ref) {
              ref = reg.next++;
              reg.weak.set(el, ref);
              reg.refs.set(ref, el);
            }
            return ref;
          };
          reg.refFor = refFor;
          const clean = (v, n = 180) => String(v || '').replace(/\s+/g, ' ').trim().slice(0, n);
          const selector = 'a[href],button,input,textarea,select,summary,[contenteditable="true"],[role="button"],[role="link"],[role="tab"]';
          const textSelector = 'h1,h2,h3,h4,p,li,label,a,button,[role="heading"]';
          const roots = [];
          const seenRoots = new Set();
          const frameLabels = new WeakMap();
          let shadowRoots = 0;
          let slotExpansions = 0;
          let sameOriginFrames = 0;
          let crossOriginFrames = 0;
          const crossOriginSources = [];
          const addRoot = (root, frameLabel = 'main') => {
            if (!root || seenRoots.has(root)) return;
            seenRoots.add(root);
            roots.push({root, frameLabel});
            if (root.nodeType === 9) frameLabels.set(root, frameLabel);
            const walker = root.querySelectorAll ? Array.from(root.querySelectorAll('*')) : [];
            for (const node of walker) {
              if (node.shadowRoot && node.shadowRoot.mode === 'open') {
                shadowRoots++;
                addRoot(node.shadowRoot, frameLabel + '/shadow' + shadowRoots);
              }
              if ((node.tagName || '').toLowerCase() === 'slot' && typeof node.assignedElements === 'function') {
                const assigned = node.assignedElements({flatten:true});
                if (assigned.length) slotExpansions += assigned.length;
              }
              if ((node.tagName || '').toLowerCase() === 'iframe') {
                const src = clean(node.src || node.getAttribute('src') || '', 500);
                try {
                  const doc = node.contentDocument;
                  if (doc && doc.documentElement) {
                    sameOriginFrames++;
                    addRoot(doc, frameLabel + '/iframe' + sameOriginFrames);
                  } else {
                    crossOriginFrames++;
                    if (src) crossOriginSources.push(src);
                  }
                } catch (_) {
                  crossOriginFrames++;
                  if (src) crossOriginSources.push(src);
                }
              }
            }
          };
          addRoot(document);
          reg.roots = roots.map(entry => entry.root);
          const composedQuery = (sel) => {
            const out = [];
            const seen = new Set();
            for (const entry of roots) {
              const found = entry.root.querySelectorAll ? Array.from(entry.root.querySelectorAll(sel)) : [];
              for (const el of found) {
                if (!seen.has(el)) { seen.add(el); out.push(el); }
              }
            }
            return out;
          };
          const ownerView = (el) => el.ownerDocument?.defaultView || window;
          const inViewport = (el) => {
            const r = el.getBoundingClientRect();
            const view = ownerView(el);
            return r.bottom >= 0 && r.top <= (view.innerHeight || 0) &&
              r.right >= 0 && r.left <= (view.innerWidth || 0);
          };
          const visible = (el) => {
            const view = ownerView(el);
            const style = view.getComputedStyle(el);
            return style.display !== 'none' && style.visibility !== 'hidden' && style.opacity !== '0';
          };
          const all = composedQuery(selector).filter(visible);
          const prioritized = all
            .map((el, index) => ({el, index, viewport: inViewport(el)}))
            .sort((a, b) => (a.viewport === b.viewport) ? (a.index - b.index) : (a.viewport ? -1 : 1))
            .map(item => item.el);
          const fieldMetaFor = (el) => [
            el.getAttribute('name'),
            el.getAttribute('id'),
            el.getAttribute('aria-label'),
            el.getAttribute('placeholder')
          ].join(' ').toLowerCase();
          const isSecret = (el) => {
            const type = clean(el.getAttribute('type'), 40).toLowerCase();
            const autoComplete = clean(el.getAttribute('autocomplete'), 80).toLowerCase();
            const meta = fieldMetaFor(el);
            return type === 'password' || type === 'file' || autoComplete === 'one-time-code' ||
              /(^|\W)(otp|2fa|mfa)(\W|$)|verification.?code|one.?time/.test(meta);
          };
          const elementMeta = (el) => {
            const tag = (el.tagName || '').toLowerCase();
            const type = clean(el.getAttribute('type'), 40).toLowerCase();
            const role = clean(el.getAttribute('role') || tag, 40);
            const secret = isSecret(el);
            const doc = el.ownerDocument;
            const frame = doc === document ? 'main' : (frameLabels.get(doc) || 'same_origin_iframe');
            const name = clean(
              el.getAttribute('aria-label') ||
              el.innerText ||
              el.getAttribute('placeholder') ||
              (!secret ? el.value : '') ||
              el.getAttribute('title')
            );
            return {
              ref: refFor(el),
              tag,
              role,
              type,
              name,
              href: tag === 'a' ? clean(el.href, 600) : '',
              placeholder: clean(el.getAttribute('placeholder'), 120),
              aria_label: clean(el.getAttribute('aria-label'), 120),
              field_name: clean(el.getAttribute('name'), 120),
              frame,
              expanded: el.getAttribute('aria-expanded'),
              disabled: !!el.disabled
            };
          };
          const elements = prioritized.slice(0, $elementLimit).map(elementMeta);
          const linkCandidates = all
            .filter(el => (el.tagName || '').toLowerCase() === 'a')
            .filter(el => /^https?:\/\//i.test(String(el.href || '')))
            .map((el, index) => {
              const name = clean(
                el.getAttribute('aria-label') || el.innerText || el.getAttribute('title') || '',
                120
              );
              const navLike = !!el.closest('nav,header,[role="navigation"]');
              return {
                el,
                index,
                name,
                href: clean(el.href, 360),
                download: el.hasAttribute('download'),
                priority: (navLike ? 1000 : 0) + (inViewport(el) ? 200 : 0) - index
              };
            })
            .filter(item => item.name && item.href)
            .sort((a, b) => b.priority - a.priority);
          const linkIndex = [];
          const seenLinkHrefs = new Set();
          for (const item of linkCandidates) {
            const key = item.href.replace(/#.*$/, '');
            if (seenLinkHrefs.has(key)) continue;
            seenLinkHrefs.add(key);
            const doc = item.el.ownerDocument;
            linkIndex.push({
              ref:refFor(item.el),
              name:item.name,
              href:item.href,
              download:item.download,
              frame:doc === document ? 'main' : (frameLabels.get(doc) || 'same_origin_iframe')
            });
            if (linkIndex.length >= $LINK_INDEX_LIMIT) break;
          }
          const bodyText = roots.map(entry =>
            String(entry.root.body?.innerText || entry.root.host?.innerText || entry.root.textContent || '')
          ).filter(Boolean).join('\n')
            .replace(/\r/g, '')
            .replace(/\n{3,}/g, '\n\n')
            .trim();
          const viewportText = composedQuery(textSelector)
            .filter(inViewport)
            .map(el => clean(el.innerText || el.getAttribute('aria-label') || '', 500))
            .filter(Boolean)
            .filter((value, index, array) => array.indexOf(value) === index)
            .join('\n')
            .slice(0, 3500);
          const dynamic = document.readyState !== 'complete' ||
            !!document.querySelector('[aria-busy="true"],[data-loading="true"],.loading,.spinner,[class*="skeleton" i]');
          const probe = bodyText.slice(0, 12000).toLowerCase();
          const captcha =
            !!document.querySelector('iframe[src*="captcha" i],iframe[src*="recaptcha" i],iframe[src*="hcaptcha" i],[class*="captcha" i],[id*="captcha" i],[class*="turnstile" i],[id*="turnstile" i]') ||
            /captcha|verify you are human|провер.{0,12}что вы человек|я не робот/.test(probe);
          const antibot =
            !!document.querySelector('[id*="cf-chl" i],[class*="cf-chl" i],[class*="challenge" i]') ||
            /just a moment|checking your browser|attention required|cloudflare ray id|access denied|forbidden|доступ ограничен/.test(probe);
          const blockedBy = captcha ? 'captcha' : (antibot ? 'antibot' : '');
          const canvasCount = composedQuery('canvas').length;
          const visibleTextChars = bodyText.length;
          const empty = !bodyText && all.length === 0;
          const contentState = blockedBy ? 'blocked' : (empty ? 'empty_or_dynamic' : (dynamic ? 'dynamic' : 'ready'));
          const pageClass = blockedBy ? 'blocked' :
            (canvasCount > 0 && visibleTextChars < 40 ? 'canvas' :
            (shadowRoots > 0 && visibleTextChars > 0 ? 'shadow_dom' :
            ((sameOriginFrames + crossOriginFrames) > 0 && visibleTextChars < 80 ? 'iframe' :
            (dynamic ? 'spa_or_dynamic' : 'ordinary_dom'))));
          return JSON.stringify({
            url: location.href,
            title: document.title || '',
            state: document.readyState || 'unknown',
            viewport: {
              scrollY: Math.round(window.scrollY || 0),
              height: Math.round(window.innerHeight || 0),
              documentHeight: Math.round(document.documentElement?.scrollHeight || 0)
            },
            content: bodyText.slice(0, $textLimit),
            viewport_content: viewportText,
            elements,
            link_index: linkIndex,
            link_index_truncated: linkCandidates.length > linkIndex.length,
            traversal: {
              roots: roots.length,
              open_shadow_roots: shadowRoots,
              slot_assignments: slotExpansions,
              same_origin_iframes: sameOriginFrames,
              cross_origin_iframes: crossOriginFrames,
              cross_origin_iframe_srcs: crossOriginSources.slice(0, 8)
            },
            diagnostics: {
              content_state: contentState,
              page_class: pageClass,
              blocked_by: blockedBy,
              captcha,
              antibot,
              empty,
              dynamic,
              visible_text_chars: visibleTextChars,
              interactive_count: all.length,
              shadow_hosts: shadowRoots,
              iframe_count: sameOriginFrames + crossOriginFrames,
              canvas_count: canvasCount
            },
            next_ref: reg.next,
            truncated: bodyText.length > $textLimit || all.length > $elementLimit
          });
        })()
    """.trimIndent()

'''
sub_once(
    RUNTIME,
    r'''    private fun snapshotScript\(startRef: Int, textLimit: Int, elementLimit: Int\): String = """.*?    """\.trimIndent\(\)\n\n    private fun clickScript''',
    snapshot_function + '    private fun clickScript'
)

click_function = r'''    private fun clickScript(ref: Int): String = """
        (() => {
          const reg = window.__umnikRegistry;
          const candidates = () => Array.from(reg?.refs?.entries?.() || [])
            .filter(([, item]) => item && item.isConnected)
            .slice(0, 10)
            .map(([candidateRef, item]) => ({
              ref:candidateRef,
              tag:(item.tagName || '').toLowerCase(),
              name:String(item.getAttribute('aria-label') || item.innerText || item.getAttribute('title') || '')
                .replace(/\s+/g, ' ').trim().slice(0, 100)
            }));
          const el = reg?.refs?.get($ref);
          if (!el) return JSON.stringify({ok:false, reason:'not_found', candidates:candidates()});
          if (!el.isConnected) return JSON.stringify({ok:false, reason:'stale_ref', candidates:candidates()});
          if (el.disabled) return JSON.stringify({ok:false, reason:'disabled'});
          const tag = (el.tagName || '').toLowerCase();
          const role = (el.getAttribute('role') || '').toLowerCase();
          const label = String(
            el.getAttribute('aria-label') ||
            el.innerText ||
            el.getAttribute('title') ||
            el.getAttribute('name') ||
            ''
          ).replace(/\s+/g, ' ').trim().slice(0, 140);
          const doc = el.ownerDocument || document;
          const view = doc.defaultView || window;
          el.scrollIntoView({block:'center', inline:'nearest'});
          const rect = el.getBoundingClientRect();
          if (rect.width > 1 && rect.height > 1 && doc.elementFromPoint) {
            const top = doc.elementFromPoint(
              Math.min(Math.max(rect.left + rect.width / 2, 0), Math.max((view.innerWidth || 1) - 1, 0)),
              Math.min(Math.max(rect.top + rect.height / 2, 0), Math.max((view.innerHeight || 1) - 1, 0))
            );
            if (top && top !== el && !el.contains(top)) {
              return JSON.stringify({
                ok:false,
                reason:'covered_by_overlay',
                matched_element:{ref:$ref, tag, role, name:label}
              });
            }
          }
          const mainFrame = doc === document;
          const matched = {ref:$ref, tag, role, name:label, frame:mainFrame ? 'main' : 'same_origin_iframe'};
          if (tag === 'a') {
            const href = String(el.href || '');
            if (!/^https?:\/\//i.test(href)) return JSON.stringify({ok:false, reason:'unsafe_link_scheme', matched_element:matched});
            if (el.hasAttribute('download')) return JSON.stringify({ok:false, reason:'download_requires_artifact_pipeline', matched_element:matched});
            const method = String(el.getAttribute('data-method') || el.getAttribute('formmethod') || '').toLowerCase();
            const signal = (label + ' ' + href).toLowerCase();
            const consequential =
              (method && method !== 'get') ||
              /(delete|remove|logout|log out|signout|sign out|unsubscribe|purchase|checkout|pay now|place order|confirm order|удал|выйти|отпис|оплат|купить|оформить заказ)/i.test(signal);
            if (consequential) {
              return JSON.stringify({
                ok:false,
                reason:'consequential_link',
                confirmation_required:true,
                name:label || 'действие по ссылке',
                matched_element:matched
              });
            }
            el.click();
            return JSON.stringify({ok:true, kind:'navigation', href, main_frame:mainFrame, matched_element:matched});
          }
          const safeUi =
            tag === 'summary' ||
            role === 'tab' ||
            el.hasAttribute('aria-expanded') ||
            el.hasAttribute('aria-controls');
          if (safeUi) {
            el.click();
            return JSON.stringify({ok:true, kind:'safe_ui', main_frame:mainFrame, matched_element:matched});
          }
          return JSON.stringify({
            ok:false,
            reason:'consequential_or_unknown_action',
            confirmation_required:true,
            name:label || 'действие',
            matched_element:matched
          });
        })()
    """.trimIndent()

'''
sub_once(
    RUNTIME,
    r'''    private fun clickScript\(ref: Int\): String = """.*?    """\.trimIndent\(\)\n\n    private fun confirmedClickScript''',
    click_function + '    private fun confirmedClickScript'
)

type_function = r'''    private fun typeScript(ref: Int, text: String, submit: Boolean): String {
        val encoded = gson.toJson(text)
        return """
            (() => {
              const reg = window.__umnikRegistry;
              const roots = (reg?.roots || [document]).filter(Boolean);
              const fieldCandidates = () => {
                const out = [];
                const seen = new Set();
                for (const root of roots) {
                  const found = root.querySelectorAll
                    ? Array.from(root.querySelectorAll('input,textarea,[contenteditable="true"]'))
                    : [];
                  for (const item of found) {
                    if (seen.has(item) || !item.isConnected) continue;
                    seen.add(item);
                    const candidateRef = reg?.weak?.get(item);
                    if (!candidateRef) continue;
                    const candidateType = String(item.getAttribute('type') || '').toLowerCase();
                    if (candidateType === 'password' || candidateType === 'hidden' || candidateType === 'file') continue;
                    out.push({
                      ref:candidateRef,
                      tag:(item.tagName || '').toLowerCase(),
                      type:candidateType,
                      name:String(item.getAttribute('name') || '').slice(0, 100),
                      placeholder:String(item.getAttribute('placeholder') || '').slice(0, 120),
                      aria_label:String(item.getAttribute('aria-label') || '').slice(0, 120)
                    });
                    if (out.length >= 8) return out;
                  }
                }
                return out;
              };
              const el = reg?.refs?.get($ref);
              if (!el) return JSON.stringify({ok:false, reason:'not_found', candidates:fieldCandidates()});
              if (!el.isConnected) return JSON.stringify({ok:false, reason:'stale_ref', candidates:fieldCandidates()});
              const tag = (el.tagName || '').toLowerCase();
              const type = String(el.getAttribute('type') || 'text').toLowerCase();
              const placeholder = String(el.getAttribute('placeholder') || '').slice(0, 140);
              const ariaLabel = String(el.getAttribute('aria-label') || '').slice(0, 140);
              const fieldName = String(el.getAttribute('name') || '').slice(0, 140);
              const matched = {
                ref:$ref,
                tag,
                type,
                placeholder,
                aria_label:ariaLabel,
                name:fieldName,
                frame:el.ownerDocument === document ? 'main' : 'same_origin_iframe'
              };
              if (el.disabled) return JSON.stringify({ok:false, reason:'disabled', matched_element:matched});
              if (el.readOnly) return JSON.stringify({ok:false, reason:'not_editable', matched_element:matched});
              const autoComplete = String(el.getAttribute('autocomplete') || '').toLowerCase();
              const fieldMeta = [
                fieldName,
                el.getAttribute('id'),
                ariaLabel,
                placeholder
              ].join(' ').toLowerCase();
              if (type === 'password' || autoComplete === 'one-time-code' || /(^|\W)(otp|2fa|mfa)(\W|$)|verification.?code|one.?time/.test(fieldMeta)) {
                return JSON.stringify({ok:false, reason:'secret_field', user_takeover:true, matched_element:matched});
              }
              if (type === 'file') {
                return JSON.stringify({ok:false, reason:'file_input_requires_user', user_takeover:true, matched_element:matched});
              }
              if (type === 'hidden') {
                return JSON.stringify({ok:false, reason:'hidden_field_blocked', matched_element:matched});
              }
              const editable = tag === 'textarea' || tag === 'input' || el.isContentEditable;
              if (!editable) return JSON.stringify({ok:false, reason:'not_editable', matched_element:matched});
              const doc = el.ownerDocument || document;
              const view = doc.defaultView || window;
              el.scrollIntoView({block:'center', inline:'nearest'});
              const rect = el.getBoundingClientRect();
              if (rect.width > 1 && rect.height > 1 && doc.elementFromPoint) {
                const top = doc.elementFromPoint(
                  Math.min(Math.max(rect.left + rect.width / 2, 0), Math.max((view.innerWidth || 1) - 1, 0)),
                  Math.min(Math.max(rect.top + rect.height / 2, 0), Math.max((view.innerHeight || 1) - 1, 0))
                );
                if (top && top !== el && !el.contains(top)) {
                  return JSON.stringify({ok:false, reason:'covered_by_overlay', matched_element:matched});
                }
              }
              const value = $encoded;
              el.focus();
              if (el.isContentEditable) {
                el.textContent = value;
                const InputCtor = view.InputEvent || InputEvent;
                el.dispatchEvent(new InputCtor('input', {bubbles:true, inputType:'insertText', data:value}));
              } else {
                const proto = tag === 'textarea' ? view.HTMLTextAreaElement.prototype : view.HTMLInputElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                if (setter) setter.call(el, value); else el.value = value;
                const EventCtor = view.Event || Event;
                el.dispatchEvent(new EventCtor('input', {bubbles:true}));
              }
              const EventCtor = view.Event || Event;
              el.dispatchEvent(new EventCtor('change', {bubbles:true}));
              const valueAfter = String(el.isContentEditable ? el.textContent : el.value).slice(0, 180);
              const form = el.form || el.closest?.('form') || null;
              const formAction = form ? String(form.action || doc.location?.href || '').slice(0, 600) : '';
              const formMethod = form ? String(form.method || 'get').toLowerCase() : '';
              const mainFrame = doc === document;
              if ($submit) {
                if (!form) {
                  return JSON.stringify({
                    ok:false,
                    reason:'no_form_for_submit',
                    matched_element:matched,
                    value_after:valueAfter,
                    type,
                    placeholder,
                    aria_label:ariaLabel,
                    name:fieldName,
                    form_action:formAction,
                    submitted:false
                  });
                }
                const signal = (
                  String(formAction || '') + ' ' +
                  String(form.getAttribute('id') || '') + ' ' +
                  String(form.getAttribute('name') || '')
                ).toLowerCase();
                const consequential = formMethod !== 'get' ||
                  /(delete|remove|purchase|checkout|pay|order|subscribe|unsubscribe|logout|signout|удал|оплат|куп|заказ|подпис|выйти)/i.test(signal);
                if (consequential) {
                  return JSON.stringify({
                    ok:false,
                    reason:'submit_requires_confirmation',
                    confirmation_required:true,
                    name:'Отправить форму',
                    matched_element:matched,
                    value_after:valueAfter,
                    type,
                    placeholder,
                    aria_label:ariaLabel,
                    form_action:formAction,
                    form_method:formMethod,
                    submitted:false
                  });
                }
                if (typeof form.requestSubmit === 'function') form.requestSubmit();
                else {
                  const event = new EventCtor('submit', {bubbles:true, cancelable:true});
                  if (form.dispatchEvent(event)) form.submit();
                }
                return JSON.stringify({
                  ok:true,
                  matched_element:matched,
                  value_after:valueAfter,
                  type,
                  placeholder,
                  aria_label:ariaLabel,
                  name:fieldName,
                  form_action:formAction,
                  form_method:formMethod,
                  submitted:true,
                  main_frame:mainFrame
                });
              }
              return JSON.stringify({
                ok:true,
                matched_element:matched,
                value_after:valueAfter,
                type,
                placeholder,
                aria_label:ariaLabel,
                name:fieldName,
                form_action:formAction,
                form_method:formMethod,
                submitted:false,
                main_frame:mainFrame
              });
            })()
        """.trimIndent()
    }

    private fun confirmedSubmitScript(ref: Int): String = """
        (() => {
          const reg = window.__umnikRegistry;
          const el = reg?.refs?.get($ref);
          if (!el) return JSON.stringify({ok:false, reason:'not_found'});
          if (!el.isConnected) return JSON.stringify({ok:false, reason:'stale_ref'});
          const type = String(el.getAttribute('type') || '').toLowerCase();
          if (type === 'password' || type === 'file' || type === 'hidden') {
            return JSON.stringify({ok:false, reason:'secret_field'});
          }
          const form = el.form || el.closest?.('form') || null;
          if (!form) return JSON.stringify({ok:false, reason:'no_form_for_submit'});
          const doc = el.ownerDocument || document;
          const view = doc.defaultView || window;
          const formAction = String(form.action || doc.location?.href || '').slice(0, 600);
          const formMethod = String(form.method || 'get').toLowerCase();
          if (typeof form.requestSubmit === 'function') form.requestSubmit();
          else {
            const EventCtor = view.Event || Event;
            const event = new EventCtor('submit', {bubbles:true, cancelable:true});
            if (form.dispatchEvent(event)) form.submit();
          }
          return JSON.stringify({
            ok:true,
            submitted:true,
            main_frame:doc === document,
            form_action:formAction,
            form_method:formMethod
          });
        })()
    """.trimIndent()

'''
sub_once(
    RUNTIME,
    r'''    private fun typeScript\(ref: Int, text: String\): String \{.*?\n    \}\n\n    private fun downloadTargetScript''',
    type_function + '    private fun downloadTargetScript'
)

replace_once(
    CLIENT,
    '        localBrowserType: (suspend (Int, String) -> String)? = null,\n',
    '        localBrowserType: (suspend (Int, String, Boolean) -> String)? = null,\n'
)
replace_once(
    CLIENT,
    '        localBrowserWait: (suspend (Int) -> String)? = null,\n',
    '        localBrowserWait: (suspend (Int, String, String, Int?) -> String)? = null,\n'
)

replace_once(
    CLIENT,
    '''                    "local_browser_type" -> {
                        val callback = localBrowserType
                        if (callback == null) gson.toJson(mapOf("ok" to false, "error" to "Local Browser недоступен"))
                        else runCatching {
                            val args = gson.fromJson(argsRaw, JsonObject::class.java)
                            val ref = args.get("ref")?.asInt ?: error("Не передан ref")
                            callback(ref, args.get("text")?.asString.orEmpty())
                        }.getOrElse {
                            gson.toJson(mapOf("ok" to false, "error" to (it.message ?: "Не удалось ввести текст")))
                        }
                    }
''',
    '''                    "local_browser_type" -> {
                        val callback = localBrowserType
                        if (callback == null) gson.toJson(mapOf("ok" to false, "error" to "Local Browser недоступен"))
                        else runCatching {
                            val args = gson.fromJson(argsRaw, JsonObject::class.java)
                            val ref = args.get("ref")?.asInt ?: error("Не передан ref")
                            val submit = runCatching { args.get("submit")?.asBoolean ?: false }.getOrDefault(false)
                            callback(ref, args.get("text")?.asString.orEmpty(), submit)
                        }.getOrElse {
                            gson.toJson(mapOf("ok" to false, "error" to (it.message ?: "Не удалось ввести текст")))
                        }
                    }
'''
)

replace_once(
    CLIENT,
    '''                    "local_browser_wait" -> {
                        val callback = localBrowserWait
                        if (callback == null) gson.toJson(mapOf("ok" to false, "error" to "Local Browser недоступен"))
                        else runCatching {
                            val args = gson.fromJson(argsRaw, JsonObject::class.java)
                            callback((args.get("seconds")?.asInt ?: 1).coerceIn(1, 5))
                        }.getOrElse {
                            gson.toJson(mapOf("ok" to false, "error" to (it.message ?: "Не удалось дождаться обновления страницы")))
                        }
                    }
''',
    '''                    "local_browser_wait" -> {
                        val callback = localBrowserWait
                        if (callback == null) gson.toJson(mapOf("ok" to false, "error" to "Local Browser недоступен"))
                        else runCatching {
                            val args = gson.fromJson(argsRaw, JsonObject::class.java)
                            val seconds = (args.get("seconds")?.asInt ?: 5).coerceIn(1, 15)
                            val mode = args.get("mode")?.asString.orEmpty().ifBlank { "dom_stable" }
                            val value = args.get("value")?.asString.orEmpty()
                            val ref = runCatching { args.get("ref")?.asInt }.getOrNull()
                            callback(seconds, mode, value, ref)
                        }.getOrElse {
                            gson.toJson(mapOf("ok" to false, "error" to (it.message ?: "Не удалось дождаться обновления страницы")))
                        }
                    }
'''
)

replace_once(
    CLIENT,
    '''        add(functionTool(
            name = "local_browser_type",
            description = "Ввести несекретный текст в поле по ref без отправки формы. Пароли, файлы и скрытые поля блокируются.",
            properties = mapOf(
                "ref" to JsonObject().apply { addProperty("type", "integer") },
                "text" to JsonObject().apply { addProperty("type", "string") }
            ),
            required = listOf("ref", "text")
        ))
''',
    '''        add(functionTool(
            name = "local_browser_type",
            description = "Ввести несекретный текст по ref. submit=true отправляет безопасную GET-форму; иная отправка требует подтверждения. Возвращает диагностику поля без секретов.",
            properties = mapOf(
                "ref" to JsonObject().apply { addProperty("type", "integer") },
                "text" to JsonObject().apply { addProperty("type", "string") },
                "submit" to JsonObject().apply {
                    addProperty("type", "boolean")
                    addProperty("description", "После ввода отправить форму. По умолчанию false.")
                }
            ),
            required = listOf("ref", "text")
        ))
'''
)

replace_once(
    CLIENT,
    '''        add(functionTool(
            name = "local_browser_wait",
            description = "Подождать 1–5 секунд, чтобы JS-страница обновилась, затем вернуть свежий PageSnapshot.",
            properties = mapOf(
                "seconds" to JsonObject().apply {
                    addProperty("type", "integer")
                    addProperty("minimum", 1)
                    addProperty("maximum", 5)
                }
            ),
            required = listOf("seconds")
        ))
''',
    '''        add(functionTool(
            name = "local_browser_wait",
            description = "Адаптивно ждать до 1–15 секунд: стабильный DOM, CSS-селектор, текст или ref; вернуть условие и свежий PageSnapshot.",
            properties = mapOf(
                "seconds" to JsonObject().apply {
                    addProperty("type", "integer")
                    addProperty("minimum", 1)
                    addProperty("maximum", 15)
                },
                "mode" to JsonObject().apply {
                    addProperty("type", "string")
                    add("enum", JsonArray().apply {
                        add("dom_stable")
                        add("selector_present")
                        add("text_present")
                        add("ref_present")
                    })
                },
                "value" to JsonObject().apply { addProperty("type", "string") },
                "ref" to JsonObject().apply { addProperty("type", "integer") }
            ),
            required = listOf("seconds")
        ))
'''
)

replace_once(
    CLIENT,
    '''            description = "Получить свежий PageSnapshot текущей Browser-страницы без навигации. По умолчанию возвращает компактное состояние/delta и отдельный компактный link_index с ref+name+href. Ставь full=true только когда компактного состояния и link_index недостаточно для уверенного решения.",
''',
    '''            description = "Получить свежий PageSnapshot без навигации. full=true даёт расширенный снимок и сохраняет composed DOM dump как ресурс чата, доступный Local Shell.",
'''
)
replace_once(
    CLIENT,
    '''                    addProperty("description", "Запросить расширенный снимок до 24 000 символов и 120 элементов. По умолчанию false.")
''',
    '''                    addProperty("description", "Расширенный снимок + DOM dump в файл чата. По умолчанию false.")
'''
)

replace_once(
    VIEWMODEL,
    '''                                localBrowserRead = if (localBrowserToolsEnabled) {
                                    { full -> LocalBrowserRuntime.read(chatId, full) }
                                } else null,
''',
    '''                                localBrowserRead = if (localBrowserToolsEnabled) {
                                    { full ->
                                        persistBrowserDownload(
                                            chatId,
                                            LocalBrowserRuntime.read(chatId, full)
                                        )
                                    }
                                } else null,
'''
)
replace_once(
    VIEWMODEL,
    '''                                localBrowserType = if (localBrowserToolsEnabled) {
                                    { ref, value -> LocalBrowserRuntime.type(chatId, ref, value) }
                                } else null,
''',
    '''                                localBrowserType = if (localBrowserToolsEnabled) {
                                    { ref, value, submit -> LocalBrowserRuntime.type(chatId, ref, value, submit) }
                                } else null,
'''
)
replace_once(
    VIEWMODEL,
    '''                                localBrowserWait = if (localBrowserToolsEnabled) {
                                    { seconds -> LocalBrowserRuntime.wait(chatId, seconds) }
                                } else null,
''',
    '''                                localBrowserWait = if (localBrowserToolsEnabled) {
                                    { seconds, mode, value, ref ->
                                        LocalBrowserRuntime.wait(chatId, seconds, mode, value, ref)
                                    }
                                } else null,
'''
)

replace_once(
    PROMPT_TEST,
    '''        assertEquals(5, property(function(all, "local_browser_wait"), "seconds").get("maximum").asInt)
''',
    '''        assertEquals(15, property(function(all, "local_browser_wait"), "seconds").get("maximum").asInt)
        assertEquals("boolean", property(function(all, "local_browser_type"), "submit").get("type").asString)
        assertEquals(
            listOf("dom_stable", "selector_present", "text_present", "ref_present"),
            property(function(all, "local_browser_wait"), "mode").getAsJsonArray("enum").map { it.asString }
        )
'''
)
replace_once(
    PROMPT_TEST,
    '''        assertContains(description(function(all, "local_browser_download")), "Local Shell")
        assertContains(description(function(all, "local_browser_takeover")), "CAPTCHA")
''',
    '''        assertContains(description(function(all, "local_browser_download")), "Local Shell")
        assertContains(description(function(all, "local_browser_read")), "DOM dump")
        assertContains(description(function(all, "local_browser_type")), "submit=true")
        assertContains(description(function(all, "local_browser_wait")), "Адаптивно")
        assertContains(description(function(all, "local_browser_takeover")), "CAPTCHA")
'''
)

BROWSER_TEST.parent.mkdir(parents=True, exist_ok=True)
BROWSER_TEST.write_text(r'''package com.ayuemin.ymnik.browser

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
''')

for rel in [
    ".github/browser2_complete.py",
    ".github/browser2_complete_trigger.txt",
    ".github/workflows/browser2-complete.yml",
]:
    path = ROOT / rel
    if path.exists():
        path.unlink()

subprocess.run(["git", "config", "user.name", "github-actions[bot]"], cwd=ROOT, check=True)
subprocess.run(
    ["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"],
    cwd=ROOT,
    check=True
)
subprocess.run(["git", "add", "-A"], cwd=ROOT, check=True)
if subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=ROOT).returncode == 0:
    raise RuntimeError("Browser 2 completion patch made no changes")
subprocess.run(
    ["git", "commit", "-m", "Complete Browser 2 composed DOM interactions"],
    cwd=ROOT,
    check=True
)
subprocess.run(["git", "push", "origin", "HEAD:work/local-shell-mvp"], cwd=ROOT, check=True)
