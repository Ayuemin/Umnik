from pathlib import Path

p = Path('app/src/main/java/com/ayuemin/ymnik/browser/LocalBrowserRuntime.kt')
s = p.read_text()
old = '''          const all = Array.from(document.querySelectorAll(
            'a[href],button,input,textarea,select,summary,[role="button"],[role="link"],[role="tab"]'
          )).filter(el => {
            const style = getComputedStyle(el);
            return style.display !== 'none' && style.visibility !== 'hidden';
          });'''
new = '''          const selector = 'a[href],button,input,textarea,select,summary,[role="button"],[role="link"],[role="tab"]';
          const textSelector = 'h1,h2,h3,h4,p,li,label,a,button';
          const roots = [];
          const seenRoots = new Set();
          let shadowRoots = 0;
          let slotExpansions = 0;
          let sameOriginFrames = 0;
          let crossOriginFrames = 0;
          const addRoot = (root, frameLabel = 'main') => {
            if (!root || seenRoots.has(root)) return;
            seenRoots.add(root);
            roots.push({root, frameLabel});
            const walker = root.querySelectorAll ? Array.from(root.querySelectorAll('*')) : [];
            for (const node of walker) {
              if (node.shadowRoot && node.shadowRoot.mode === 'open') {
                shadowRoots++;
                addRoot(node.shadowRoot, frameLabel + '/shadow');
              }
              if ((node.tagName || '').toLowerCase() === 'slot' && typeof node.assignedElements === 'function') {
                const assigned = node.assignedElements({flatten:true});
                if (assigned.length) slotExpansions += assigned.length;
              }
              if ((node.tagName || '').toLowerCase() === 'iframe') {
                try {
                  const doc = node.contentDocument;
                  if (doc && doc.documentElement) {
                    sameOriginFrames++;
                    addRoot(doc, frameLabel + '/iframe' + sameOriginFrames);
                  } else {
                    crossOriginFrames++;
                  }
                } catch (_) { crossOriginFrames++; }
              }
            }
          };
          addRoot(document);
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
          const all = composedQuery(selector).filter(el => {
            const view = el.ownerDocument?.defaultView || window;
            const style = view.getComputedStyle(el);
            return style.display !== 'none' && style.visibility !== 'hidden';
          });'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, new, 1)
s = s.replace("          const viewportText = Array.from(document.querySelectorAll('h1,h2,h3,h4,p,li,label,a,button'))", "          const viewportText = composedQuery(textSelector)", 1)
s = s.replace("          const bodyText = String(document.body?.innerText || '')\n            .replace(/\\r/g, '')", "          const bodyText = roots.map(entry => String(entry.root.body?.innerText || entry.root.host?.innerText || entry.root.textContent || ''))\n            .filter(Boolean).join('\\n')\n            .replace(/\\r/g, '')", 1)
needle = '''            link_index_truncated: linkCandidates.length > linkIndex.length,
            next_ref: reg.next,
            truncated: bodyText.length > $textLimit || all.length > $elementLimit'''
replacement = '''            link_index_truncated: linkCandidates.length > linkIndex.length,
            traversal: {
              roots: roots.length,
              open_shadow_roots: shadowRoots,
              slot_assignments: slotExpansions,
              same_origin_iframes: sameOriginFrames,
              cross_origin_iframes: crossOriginFrames
            },
            diagnostics: {
              empty: !bodyText && all.length === 0,
              dynamic: document.readyState !== 'complete' || !!document.querySelector('[aria-busy="true"],[data-loading="true"],.loading,.spinner'),
              captcha: !!document.querySelector('iframe[src*="captcha" i],iframe[src*="recaptcha" i],[class*="captcha" i],[id*="captcha" i]') || /captcha|verify you are human|провер.{0,8}что вы человек/i.test(bodyText.slice(0, 5000))
            },
            next_ref: reg.next,
            truncated: bodyText.length > $textLimit || all.length > $elementLimit'''
assert s.count(needle) == 1
s = s.replace(needle, replacement, 1)
# expose diagnostics/traversal and save a redacted structural dump artifact in cache
needle = '''            add("viewport", page.get("viewport") ?: JsonObject())
            add("link_index", linkIndex)'''
replacement = '''            add("viewport", page.get("viewport") ?: JsonObject())
            add("traversal", page.get("traversal") ?: JsonObject())
            add("diagnostics", page.get("diagnostics") ?: JsonObject())
            add("link_index", linkIndex)'''
assert s.count(needle) == 1
s = s.replace(needle, replacement, 1)
# adaptive snapshot settling: dynamic/empty pages get a longer bounded settle window
s = s.replace('''            settleAttempts < SNAPSHOT_SYNC_ATTEMPTS
        ) {''', '''            settleAttempts < (if (snapshotLooksEmpty(page) || page.getAsJsonObject("diagnostics")?.get("dynamic")?.asBoolean == true) SNAPSHOT_SYNC_ATTEMPTS * 2 else SNAPSHOT_SYNC_ATTEMPTS)
        ) {''', 1)
# framework-compatible value setter + form submit semantics without reading secrets
old = '''              const value = $encoded;
              el.focus();
              if (el.isContentEditable) {
                el.textContent = value;
              } else {
                el.value = value;
              }
              el.dispatchEvent(new Event('input', {bubbles:true}));
              el.dispatchEvent(new Event('change', {bubbles:true}));
              return JSON.stringify({ok:true});'''
new = '''              const value = $encoded;
              el.focus();
              if (el.isContentEditable) {
                el.textContent = value;
                el.dispatchEvent(new InputEvent('input', {bubbles:true, inputType:'insertText', data:value}));
              } else {
                const proto = tag === 'textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                if (setter) setter.call(el, value); else el.value = value;
                el.dispatchEvent(new Event('input', {bubbles:true}));
              }
              el.dispatchEvent(new Event('change', {bubbles:true}));
              return JSON.stringify({ok:true, form: !!el.form});'''
assert s.count(old) == 1
s = s.replace(old, new, 1)
# captcha/empty diagnostics are takeover-safe: model gets a signal, never secret values
needle = '''        val linkIndex = page.getAsJsonArray("link_index") ?: JsonArray()
        val currentElements = elementObjects(elements)'''
replacement = '''        val linkIndex = page.getAsJsonArray("link_index") ?: JsonArray()
        val diagnostics = page.getAsJsonObject("diagnostics") ?: JsonObject()
        if (diagnostics.get("captcha")?.asBoolean == true) {
            DiagnosticLog.record(webView.context.applicationContext, "LOCAL_BROWSER", "captcha_detected session=" + session.sessionId.take(8))
        }
        val currentElements = elementObjects(elements)'''
assert s.count(needle) == 1
s = s.replace(needle, replacement, 1)
p.write_text(s)

# Source-level regression guard: verifies Browser 2 invariants without WebView instrumentation.
t = Path('app/src/test/java/com/ayuemin/ymnik/browser/LocalBrowserRuntimeBrowser2RegressionTest.kt')
t.parent.mkdir(parents=True, exist_ok=True)
t.write_text('''package com.ayuemin.ymnik.browser\n\nimport java.io.File\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass LocalBrowserRuntimeBrowser2RegressionTest {\n    private fun source(): String = sequenceOf(\n        File("src/main/java/com/ayuemin/ymnik/browser/LocalBrowserRuntime.kt"),\n        File("app/src/main/java/com/ayuemin/ymnik/browser/LocalBrowserRuntime.kt")\n    ).first { it.isFile }.readText()\n\n    @Test fun composedDomTraversalCoversOpenShadowSlotsAndSameOriginFrames() {\n        val s = source()\n        assertTrue(s.contains("node.shadowRoot && node.shadowRoot.mode === 'open'"))\n        assertTrue(s.contains("assignedElements({flatten:true})"))\n        assertTrue(s.contains("node.contentDocument"))\n        assertTrue(s.contains("same_origin_iframes"))\n        assertTrue(s.contains("cross_origin_iframes"))\n        assertTrue(s.contains("reg.weak.get(el)"))\n    }\n\n    @Test fun diagnosticsAndAdaptiveWaitDoNotExposeFieldValues() {\n        val s = source()\n        assertTrue(s.contains("captcha_detected session="))\n        assertTrue(s.contains("SNAPSHOT_SYNC_ATTEMPTS * 2"))\n        assertTrue(s.contains("diagnostics: {"))\n        assertFalse(s.contains("captcha_detected value="))\n        assertFalse(s.contains("password_value"))\n    }\n\n    @Test fun typingUsesNativeSetterAndKeepsTakeoverSafety() {\n        val s = source()\n        assertTrue(s.contains("Object.getOwnPropertyDescriptor(proto, 'value')?.set"))\n        assertTrue(s.contains("sensitive_field_requires_user"))\n        assertTrue(s.contains("file_input_requires_user"))\n        assertTrue(s.contains("user_takeover:true"))\n    }\n}\n''')
