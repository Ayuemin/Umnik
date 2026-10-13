#!/usr/bin/env python3
"""Enforce single ownership and an acyclic JVM permission boundary."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
package = Path("com/ayuemin/ymnik/agenttools")
security = root / "core/security"
contracts = root / "core/contracts"
toolbus = root / "core/toolbus"
app = root / "app"
errors = []

for name in ("TaskGrant.kt", "ToolPermissionGate.kt", "GuardedToolDispatcher.kt"):
    target = security / "src/main/kotlin" / package / name
    old = app / "src/main/java" / package / name
    if not target.is_file() or old.exists():
        errors.append(f"{name} must exist only in :core:security")

gate_test = "ToolPermissionGateTest.kt"
if (app / "src/test/java" / package / gate_test).exists():
    errors.append("ToolPermissionGateTest must not be duplicated")
if not (security / "src/test/kotlin" / package / gate_test).exists():
    errors.append("Extracted gate must retain its existing regression tests")

settings = (root / "settings.gradle.kts").read_text(encoding="utf-8")
module_build = (security / "build.gradle.kts").read_text(encoding="utf-8")
app_build = (app / "build.gradle.kts").read_text(encoding="utf-8")
if 'include(":core:security")' not in settings:
    errors.append("Missing :core:security Gradle module")
if 'implementation(project(":core:security"))' not in app_build:
    errors.append("App is not wired to original security boundary")
if 'implementation(project(":core:contracts"))' not in module_build:
    errors.append("Security module must depend on contracts")
if 'project(":core:toolbus")' in module_build.split("testImplementation")[0]:
    errors.append("Security main code must not depend on ToolBus")
if 'implementation(project(":core:toolbus"))' in module_build:
    errors.append("Security production code must not depend on ToolBus")
if 'project(":app")' in module_build or "com.android" in module_build:
    errors.append("Security production code cannot depend on app/Android")

registry_code = (toolbus / "src/main/kotlin" / package / "ToolRegistry.kt").read_text(encoding="utf-8")
port_code = (contracts / "src/main/kotlin" / package / "ToolDescriptor.kt").read_text(encoding="utf-8")
gate_code = (security / "src/main/kotlin" / package / "ToolPermissionGate.kt").read_text(encoding="utf-8")
grant_code = (security / "src/main/kotlin" / package / "TaskGrant.kt").read_text(encoding="utf-8")
dispatch_code = (security / "src/main/kotlin" / package / "GuardedToolDispatcher.kt").read_text(encoding="utf-8")
if "interface ToolDescriptorLookup" not in port_code or "class ToolRegistry : ToolDescriptorLookup" not in registry_code:
    errors.append("Original ToolRegistry must implement the read-only descriptor lookup port")
if "private val registry: ToolDescriptorLookup" not in gate_code or "registry.descriptor(call.toolId)" not in gate_code:
    errors.append("Permission gate must use only the read-only lookup port")
if "private constructor(" not in grant_code or "internal fun allows(" not in grant_code:
    errors.append("TaskGrant must retain its private constructor and internal allowance query")
if "resolveActualResource()" not in dispatch_code or "gate.check(" not in dispatch_code:
    errors.append("Dispatcher must verify real resource and gate before invoking operation")
if "import kotlin.coroutines.cancellation.CancellationException" not in dispatch_code:
    errors.append("Dispatcher must propagate coroutine cancellation without Android imports")

for source in sorted((security / "src/main/kotlin").rglob("*.kt")):
    body = source.read_text(encoding="utf-8")
    imports = re.findall(r"^\s*import\s+([^\s]+)", body, re.MULTILINE)
    forbidden = ("android.", "androidx.", "kotlinx.", "com.ayuemin.ymnik.ui.",
                 "com.ayuemin.ymnik.network.", "com.ayuemin.ymnik.browser.",
                 "com.ayuemin.ymnik.local.", "com.ayuemin.ymnik.data.")
    if any(item.startswith(forbidden) for item in imports):
        errors.append(f"Forbidden import: {source.relative_to(root)}")
    if len(body.splitlines()) > 400:
        errors.append(f"Too long: {source.relative_to(root)}")

if errors:
    for error in errors:
        print("ERROR:", error, file=sys.stderr)
    sys.exit(1)
print("Security module owns the exact grant/gate/dispatcher; dependency direction OK")
