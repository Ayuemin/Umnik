#!/usr/bin/env python3
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "app/src/main/java/com/ayuemin/ymnik/browser/LocalBrowserRuntime.kt"
TEST = ROOT / "app/src/test/java/com/ayuemin/ymnik/browser/LocalBrowserRuntimeBrowser2RegressionTest.kt"


def replace_once(path: Path, old: str, new: str):
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one match, got {count}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1))

replace_once(
    RUNTIME,
    """          const clean = (value, n = 180) => String(value || '').replace(/\\s+/g, ' ').trim().slice(0, n);
          let serializeDocument;
""",
    """          const clean = (value, n = 180) => String(value || '').replace(/\\s+/g, ' ').trim().slice(0, n);
          const secretField = (el) => {
            const type = String(el.getAttribute?.('type') || '').toLowerCase();
            const autoComplete = String(el.getAttribute?.('autocomplete') || '').toLowerCase();
            const meta = [
              el.getAttribute?.('name'),
              el.getAttribute?.('id'),
              el.getAttribute?.('aria-label'),
              el.getAttribute?.('placeholder'),
              autoComplete
            ].join(' ').toLowerCase();
            return type === 'password' || type === 'hidden' || type === 'file' ||
              autoComplete === 'one-time-code' ||
              /(^|\\W)(otp|2fa|mfa|password|passwd|passcode|secret|token)(\\W|$)|verification.?code|one.?time/.test(meta);
          };
          const sanitizeMarkup = (source) => {
            const template = document.createElement('template');
            template.innerHTML = String(source || '');
            for (const field of Array.from(template.content.querySelectorAll('input,textarea'))) {
              if (!secretField(field)) continue;
              field.removeAttribute('value');
              field.setAttribute('data-umnik-redacted', 'secret');
              if ((field.tagName || '').toLowerCase() === 'textarea') field.textContent = '';
            }
            return template.innerHTML;
          };
          let serializeDocument;
"""
)
replace_once(
    RUNTIME,
    """                push(node.shadowRoot.innerHTML || node.shadowRoot.textContent || '');
""",
    """                push(sanitizeMarkup(node.shadowRoot.innerHTML || node.shadowRoot.textContent || ''));
"""
)
replace_once(
    RUNTIME,
    """                  for (const item of assigned) push(item.outerHTML || item.textContent || '');
""",
    """                  for (const item of assigned) push(sanitizeMarkup(item.outerHTML || item.textContent || ''));
"""
)
replace_once(
    RUNTIME,
    """            push(doc.documentElement?.outerHTML || doc.body?.innerHTML || doc.body?.innerText || '');
""",
    """            push(sanitizeMarkup(doc.documentElement?.outerHTML || doc.body?.innerHTML || doc.body?.innerText || ''));
"""
)
replace_once(
    RUNTIME,
    """              iframe_count: sameOriginFrames + crossOriginFrames,
              canvas_count: canvasCount
""",
    """              iframe_count: sameOriginFrames + crossOriginFrames,
              iframe_cross_origin: crossOriginFrames > 0,
              canvas_count: canvasCount
"""
)
replace_once(
    TEST,
    """        assertTrue(s.contains("UMNIK DOCUMENT"))
        assertTrue(s.contains("\\\"browser_dom/\\\" + session.sessionId"))
""",
    """        assertTrue(s.contains("UMNIK DOCUMENT"))
        assertTrue(s.contains("data-umnik-redacted"))
        assertTrue(s.contains("type === 'hidden'"))
        assertTrue(s.contains("autoComplete === 'one-time-code'"))
        assertTrue(s.contains("\\\"browser_dom/\\\" + session.sessionId"))
"""
)
replace_once(
    TEST,
    """        assertTrue(s.contains("canvas_count: canvasCount"))
""",
    """        assertTrue(s.contains("canvas_count: canvasCount"))
        assertTrue(s.contains("iframe_cross_origin: crossOriginFrames > 0"))
"""
)

for rel in [
    ".github/browser2_security.py",
    ".github/browser2_security_trigger.txt",
    ".github/workflows/browser2-security.yml",
]:
    path = ROOT / rel
    if path.exists():
        path.unlink()

subprocess.run(["git", "config", "user.name", "github-actions[bot]"], cwd=ROOT, check=True)
subprocess.run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"], cwd=ROOT, check=True)
subprocess.run(["git", "add", "-A"], cwd=ROOT, check=True)
if subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=ROOT).returncode == 0:
    raise RuntimeError("Browser 2 security patch made no changes")
subprocess.run(["git", "commit", "-m", "Redact secrets from Browser DOM dump"], cwd=ROOT, check=True)
subprocess.run(["git", "push", "origin", "HEAD:work/local-shell-mvp"], cwd=ROOT, check=True)
