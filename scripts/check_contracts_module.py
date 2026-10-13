#!/usr/bin/env python3
"""Enforce the first Android-free Gradle module boundary."""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
module = root / "core" / "contracts"
sources = sorted((module / "src" / "main" / "kotlin").rglob("*.kt"))
errors = []

if not sources:
    errors.append("No production contract sources found")

gradle = (module / "build.gradle.kts").read_text(encoding="utf-8")
if "com.android" in gradle or "project(" in gradle:
    errors.append("core:contracts must not depend on Android or other Gradle modules")

for source in sources:
    text = source.read_text(encoding="utf-8")
    imports = re.findall(r"^\\s*import\\s+([^\\s]+)", text, re.MULTILINE)
    forbidden = (
        "android.", "androidx.", "com.ayuemin.ymnik.ui.",
        "com.ayuemin.ymnik.network.", "com.ayuemin.ymnik.agenttools.",
        "com.ayuemin.ymnik.browser.", "com.ayuemin.ymnik.local.",
        "com.ayuemin.ymnik.data.", "com.ayuemin.ymnik.audio.",
        "com.ayuemin.ymnik.tts."
    )
    for imported in imports:
        if imported.startswith(forbidden):
            errors.append(f"{source.relative_to(root)}: forbidden import {imported}")
    if len(text.splitlines()) > 400:
        errors.append(f"{source.relative_to(root)}: contract file exceeds 400 lines")

new_model = module / "src/main/kotlin/com/ayuemin/ymnik/model/TokenUsageStats.kt"
legacy_model = root / "app/src/main/java/com/ayuemin/ymnik/model/TokenUsageStats.kt"
if not new_model.is_file() or legacy_model.exists():
    errors.append("TokenUsageStats must exist only in the extracted module")
if 'implementation(project(":core:contracts"))' not in (root / "app/build.gradle.kts").read_text(encoding="utf-8"):
    errors.append("App must explicitly depend on core:contracts")

if errors:
    for error in errors:
        print("ERROR:", error, file=sys.stderr)
    sys.exit(1)
print(f"core:contracts architecture OK ({len(sources)} production Kotlin files)")
