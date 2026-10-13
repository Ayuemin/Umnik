# ADR-0007 — observed read through the original guarded Browser ToolBus

**13.10.2026.** Scope: `work/modular-umnik-v2`. Detailed findings: [Browser audit](../BROWSER_RUNTIME_AUDIT.md).

Only `local_browser_read` is opted into `ExecutableToolAdapter` via the existing `ExistingLocalToolCatalog` and `AgentToolHub.executeRead`. The trusted direct-UI `TaskGrant`, original permission gate and Browser runtime URL checks are reused, not copied. The observed WebView URL and last successfully bound chat must match the selected session; a READ call cannot silently reload a stale page. The runtime rechecks under its serialized command mutex and immediately around DOM extraction. Compact/full JSON and full DOM cache artifact persistence are retained.

All navigation, click, type, download, scroll and wait operations remain legacy; no extra permissions, user data changes, deletions, UI settings, second Bus or new Gradle module. A stale, detached or cross-chat WebView now requires an explicit reopen.

**Limitations:** WebView connection IP is not pinned to the checked DNS answers. Browser is not yet SSRF-proof or verified against redirect/service-worker races. Full-read DOM cache privacy and actual Android behavior require E2E. A green lint/unit/debug/R8 workflow is not on-device verification.

**Rollback:** revert the isolated Browser code commit in `work/modular-umnik-v2` without history rewrite. Keep the legacy runtime and pre-change audit intact.
