#!/usr/bin/env python3
"""Guard original tool metadata against duplicate classes and reverse imports."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
base = "com/ayuemin/ymnik/agenttools"
moves = {
    "ToolDescriptor.kt": root / "core/contracts/src/main/kotlin" / base,
    "ToolRegistry.kt": root / "core/toolbus/src/main/kotlin" / base,
    "AgentToolHub.kt": root / "core/toolbus/src/main/kotlin" / base,
}
legacy = root / "app/src/main/java" / base
errors = []
for name, directory in moves.items():
    if not (directory / name).is_file() or (legacy / name).exists():
        errors.append(f"{name}: expected exactly one extracted source")
if (root / "app/src/test/java" / base / "AgentToolHubTest.kt").exists():
    errors.append("Old app Hub test must not be duplicated")
if not (root / "core/toolbus/src/test/kotlin" / base / "AgentToolHubTest.kt").is_file():
    errors.append("Moved Hub tests are missing")
settings = (root / "settings.gradle.kts").read_text(encoding="utf-8")
app = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
gradle = (root / "core/toolbus/build.gradle.kts").read_text(encoding="utf-8")
if 'include(":core:toolbus")' not in settings:
    errors.append("Unregistered Gradle module")
if 'implementation(project(":core:toolbus"))' not in app:
    errors.append("App does not depend on toolbus")
if 'implementation(project(":core:contracts"))' not in gradle:
    errors.append("Toolbus requires contracts")
if 'implementation(project(":core:security"))' not in gradle:
    errors.append("Guarded ToolBus requires security; security main must not depend on ToolBus")
if not (root / "core/toolbus/src/test/kotlin" / base / "GuardedHubDispatchTest.kt").is_file():
    errors.append("Guarded AgentToolHub needs cross-module permission tests")
if "com.android" in gradle or 'project(":app")' in gradle:
    errors.append("Toolbus must remain a standalone JVM module")
hub_code = (root / "core/toolbus/src/main/kotlin" / base / "AgentToolHub.kt").read_text(encoding="utf-8")
if "GuardedToolDispatcher(ToolPermissionGate(registry))" not in hub_code:
    errors.append("AgentToolHub must reuse original GuardedToolDispatcher with original registry")
if "dispatcher.invoke(" not in hub_code:
    errors.append("Hub must invoke the security dispatcher before calling any operation")
# Read-only executable adapters are stored in the SAME original ToolRegistry.
new_adapter = root / "core/toolbus/src/main/kotlin" / base / "ExecutableToolAdapter.kt"
if not new_adapter.is_file():
    errors.append("Missing opt-in executable adapter contract")
else:
    adapter_code = new_adapter.read_text(encoding="utf-8")
    if "interface ExecutableToolAdapter : ToolAdapter" not in adapter_code:
        errors.append("ExecutableToolAdapter must extend the original metadata contract")
    if "suspend fun resolveActualResource()" not in adapter_code or "suspend fun execute()" not in adapter_code:
        errors.append("Adapter must expose trusted runtime resource resolution and execution")
registry_code = (root / "core/toolbus/src/main/kotlin" / base / "ToolRegistry.kt").read_text(encoding="utf-8")
if "private val registrations =" not in registry_code or "internal fun executable(" not in registry_code:
    errors.append("Executable adapters must use the original single registration map")
if "private val descriptions =" in registry_code:
    errors.append("Old parallel descriptor map must not coexist with registrations")
if "requested.action != ToolAction.READ" not in hub_code:
    errors.append("First opt-in adapter step must remain read-only")
if "registry.executable(requested.toolId)" not in hub_code:
    errors.append("Hub must only execute trusted registered adapters")
if "resolveActualResource = { adapter.resolveActualResource() }" not in hub_code:
    errors.append("Hub must resolve adapter's actual runtime target")
if "operation = { adapter.execute() }" not in hub_code:
    errors.append("Executable callback must remain enclosed in the original guarded dispatcher")
if not (root / "core/toolbus/src/test/kotlin" / base / "ExecutableToolAdapterTest.kt").is_file():
    errors.append("Opt-in adapter denial tests required")
for guard_name in ("TrustedBrowserSessionGuard.kt", "TrustedShellStatusGuard.kt"):
    guard = legacy / guard_name
    if not guard.is_file():
        errors.append(f"Missing existing trusted guarded tool: {guard_name}")
    else:
        body = guard.read_text(encoding="utf-8")
        if "AgentToolHub(registry)" not in body or "toolHub.dispatch(" not in body:
            errors.append(f"Existing trusted guard must pass through AgentToolHub: {guard_name}")

# First real read-only adapter reuses the original Shell status and runtime state.
app_adapter = legacy / "LocalShellStatusReadAdapter.kt"
formatter = legacy / "LocalShellStatusJson.kt"
catalog = legacy / "ExistingLocalToolCatalog.kt"
status_guard = legacy / "TrustedShellStatusGuard.kt"
app_vm = root / "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
app_test = root / "app/src/test/java" / base / "LocalShellStatusReadAdapterTest.kt"
for path in (app_adapter, formatter, catalog, status_guard, app_vm, app_test):
    if not path.is_file():
        errors.append(f"Missing production read-adapter wiring: {path.relative_to(root)}")
if all(p.is_file() for p in (app_adapter, formatter, catalog, status_guard, app_vm, app_test)):
    adapter_body = app_adapter.read_text(encoding="utf-8")
    catalog_body = catalog.read_text(encoding="utf-8")
    guard_body = status_guard.read_text(encoding="utf-8")
    vm_body = app_vm.read_text(encoding="utf-8")
    if "AsyncJobEvents.localShellActivity.value" not in adapter_body:
        errors.append("Shell read must observe runtime-owned state, not model arguments")
    if "observe()?.chatId" not in adapter_body or "active.chatId != trustedChatId" not in adapter_body:
        errors.append("Actual runtime chat owner must be checked, including on ownership change")
    if "ToolTextResult(LocalShellStatusJson.render(" not in adapter_body:
        errors.append("New read path must reuse existing status JSON shape")
    if "localShellStatusAdapter.descriptor == shellStatusDescriptor()" not in catalog_body:
        errors.append("Opt-in adapter must match existing Shell capability exactly")
    if "AgentToolHub(ExistingLocalToolCatalog.createRegistry(adapter))" not in guard_body:
        errors.append("Trusted Shell status must use the original protected hub/registry")
    if "hub.executeRead(" not in guard_body:
        errors.append("Trusted Shell status must call the guarded read-only executor")
    if "trustedShellStatusGuard.readObservedStatus(" not in vm_body:
        errors.append("Active chat agent callback must reach the real read adapter")
    if "trustedShellStatusGuard.readStatus(" in vm_body:
        errors.append("Chat agent must not bypass the adapter through legacy callback")
    for dangerous in ("LocalShellRuntime.cancel(", "LocalShellEngine(", "exec(", "delete("):
        if dangerous in adapter_body:
            errors.append(f"Shell status adapter must stay read-only: {dangerous}")

for mod in ("contracts", "toolbus"):
    src = root / "core" / mod / "src/main/kotlin"
    kotlin_files = list(src.rglob("*.kt"))
    if not kotlin_files:
        errors.append(f"Empty module: {mod}")
    for file in kotlin_files:
        source = file.read_text(encoding="utf-8")
        imports = re.findall(r"^\s*import\s+([^\s]+)", source, re.MULTILINE)
        forbidden = ("android.", "androidx.", "com.ayuemin.ymnik.ui.",
                     "com.ayuemin.ymnik.network.", "com.ayuemin.ymnik.browser.",
                     "com.ayuemin.ymnik.local.", "com.ayuemin.ymnik.data.",
                     "com.ayuemin.ymnik.audio.", "com.ayuemin.ymnik.tts.")
        if any(imp.startswith(forbidden) for imp in imports):
            errors.append(f"Forbidden import: {file.relative_to(root)}")
        if len(source.splitlines()) > 400:
            errors.append(f"Too large: {file.relative_to(root)}")
        if mod == "contracts" and any("ToolRegistry" in imp for imp in imports):
            errors.append("Core contracts cannot depend on ToolRegistry")
metadata = moves["ToolDescriptor.kt"] / "ToolDescriptor.kt"
if metadata.is_file() and re.search(r"\b(?:suspend\s+)?fun\s+execute\s*\(", metadata.read_text(encoding="utf-8")):
    errors.append("ToolAdapter must remain metadata-only during this stage")
if errors:
    for e in errors:
        print("ERROR:", e, file=sys.stderr)
    sys.exit(1)
print("Tool module ownership, JVM dependency direction and metadata checks passed")
