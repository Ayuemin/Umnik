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
for guard_name in ("TrustedBrowserSessionGuard.kt", "TrustedShellStatusGuard.kt"):
    guard = legacy / guard_name
    if not guard.is_file():
        errors.append(f"Missing existing trusted guarded tool: {guard_name}")
    else:
        body = guard.read_text(encoding="utf-8")
        if "AgentToolHub(registry)" not in body or "toolHub.dispatch(" not in body:
            errors.append(f"Existing trusted guard must pass through AgentToolHub: {guard_name}")

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
