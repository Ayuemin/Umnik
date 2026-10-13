# Browser runtime: security and migration audit (13.10.2026)

**Scope:** static inspection of `work/modular-umnik-v2` at `d23efaa806ac13079284168753b80f894156a0ab`. This is not a device-based penetration test or an approval to enable unrestricted Browser automation. Keep legacy implementations until parity is verified.

| Source | Existing behavior and dependencies | Decision | Security / regression evidence needed |
| --- | --- | --- | --- |
| `app/.../browser/LocalBrowserRuntime.kt` | Single 2,896-line Android `WebView` runtime, per-chat session map, serialized `commandMutex`, DOM/refs, JS, forms, downloads, user takeover; uses `TrustedPublicBrowserReadGuard`, `LocalWebFetchPolicy`, `DiagnosticLog`, UI-hosted WebView | **Adapt**, not delete; extract observation and navigation/security only after E2E | Main-frame and subresource SSRF/redirect, lifecycle, cross-chat reuse, SPA/DOM refs, cancellation and full-read artifacts |
| `app/.../ui/LocalBrowserHost.kt` | Compose-owned WebView, hidden behind chat except user takeover; attaches/detaches runtime | **Retain** pending Android smoke | Layout, background navigation, attach/detach, user takeover and re-creation |
| `app/.../agenttools/TrustedPublicBrowserReadGuard.kt` | 30-minute PUBLIC_WEB_SESSION grant created from trusted UI turn, checked against chat/message and syntactically valid observed URL | **Reuse** through existing `AgentToolHub`; do not create another gate | Missing/expired/forged grants, wrong chat/turn, observed URL replacement |
| `app/.../agenttools/TrustedBrowserSessionGuard.kt` | Separate trusted takeover/done control through Hub; not a read grant | **Retain** | No accidental widening to click/type/download |
| `app/.../agenttools/DirectBrowserGrantFactory.kt` | Exact-origin candidate from direct user text, currently not the automatic public discovery policy | **Defer** reconciliation | Never treat model/DOM/history links as UI authority |
| `app/.../agenttools/ExistingLocalToolCatalog.kt` | One registry; browser descriptors currently metadata-only | **Adapt same registry** for read adapter only | Descriptor identity, deny-by-default metadata tools |
| `app/.../network/LocalWebFetcher.kt` | OkHttp requests with protected DNS, explicit redirect handling; its policy is reused by Browser | **Retain**; distinguish protected OkHttp from WebView | Literal host, mixed/public DNS addresses, rebinding, redirects |
| `app/.../ChatViewModel.kt` | Trusted UI grants; Browser open/read/click/type/download/wait callbacks wired directly to legacy runtime | **Replace read callback only** in initial Bus step | Unchanged result format and full-read artifact persistence; other actions remain legacy |

## Observed protections
- WebView JavaScript and DOM storage enabled; file/content access disabled, mixed content disallowed, Safe Browsing enabled when supported. Cookies enabled; third-party cookies disabled.
- `shouldOverrideUrlLoading` rejects non-HTTP(S) top-level navigation and invalid literal targets. `shouldInterceptRequest` checks hosts for HTTP(S) main-frame and subresources. SSL errors and HTTP authentication are refused.
- `open` checks public DNS for input host and UI-origin grant before loading, then validates the observed destination *after* snapshot; `read` checks the current session URL and URL around snapshot.
- Existing redaction/DOM limits and sensitive form confirmation are substantial, but need physical Android validation.

## Open risks (not verified safe)
1. **SSRF / DNS rebinding:** `requirePublicHost` caches host allow decisions. WebView performs its own connection/DNS resolution after the policy resolves public IPs; no demonstrated IP pinning or complete intercept of Service Workers/WebSockets. A previously safe domain could resolve to an internal address. Do **not** claim SSRF-proof Browser or grant it privileged networks.
2. **Redirect timing:** `open` verifies observed final URL after extracting the snapshot. Main-frame URL can change asynchronously, and URL checks in callbacks do not prove network-layer IP safety.
3. **Read with implicit navigation:** legacy `read` calls `ensureCurrent`, which may silently issue `loadUrl`; a ToolBus **READ** adapter must not inherit that navigation.
4. **Cross-chat identity:** sessions are indexed by chat, but share one WebView/cookie profile. A URL alone is not proof of chat ownership. Test same-URL views from different chats.
5. **DOM / secrets:** JS is enabled; snapshot/full DOM can expose sensitive content and `full=true` emits a local cache artifact. Confirm redaction and artifact access boundaries; never send secret fields as read results.
6. **Legacy actions:** click/type/scroll/back/open/download are not all routed through `ExecutableToolAdapter`. Existing UI takeover is not a substitute for Hub authorization of each action.

## Safe first migration slice
- Bind **only `local_browser_read`** to an `ExecutableToolAdapter` registered in the existing `ExistingLocalToolCatalog` / `AgentToolHub.executeRead`.
- Resolve resource from the *currently observed* WebView document with trusted chat ownership and URL checks; do not use the model-supplied target.
- Require an existing trusted UI `TaskGrant` and re-check at runtime immediately before/after DOM observation. In this path, **deny instead of reloading** a stale/different document. Preserve the previous compact/full JSON result and full-read artifact handoff.
- Cover no grant, expiry, wrong message/chat, unsafe URL, another chat controlling WebView, and stale URL; require full lint/unit/debug/R8 CI. No release, no deletions, no change to user data.
- Explicitly leave Browser navigation, DNS rebinding and all side-effect actions for later hardening with Android network fixture/E2E. Until then describe read-only ToolBus as *a limited guarded path*, not a complete secure Browser.

**Android E2E:** not performed; no connected device evidence. **Rollback:** revert the isolated Browser code commit(s) on work branch only, and leave original runtime in place. **Next:** implement the narrow read adapter, then run CI and separately verify on physical Android.
