from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

app = ROOT / "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
text = app.read_text(encoding="utf-8")

sheet = text.index("    if (actionsOpen) {")
tools_heading = text.index('                Text(\n                    "Инструменты OpenRouter"', sheet)
block_start = text.index("                if (!imagePromptMode) {", sheet, tools_heading)
block = text[block_start:tools_heading].rstrip()

for required in ("Размышление", "Поиск в сети", "state.reasoningEnabled", "state.webSearchEnabled"):
    if required not in block:
        raise SystemExit(f"Quick toggle block changed; missing {required!r}")

text = text[:block_start] + text[tools_heading:]

block = block.replace(
    """                            leadingIcon = {\n                                Icon(Icons.Outlined.Psychology, contentDescription = null, modifier = Modifier.size(18.dp))\n                            },""",
    """                            leadingIcon = {\n                                Icon(\n                                    if (state.reasoningEnabled) Icons.Outlined.Check else Icons.Outlined.Psychology,\n                                    contentDescription = if (state.reasoningEnabled) \"Включено\" else null,\n                                    modifier = Modifier.size(18.dp)\n                                )\n                            },"""
)
block = block.replace(
    """                            leadingIcon = {\n                                Icon(Icons.Outlined.Language, contentDescription = null, modifier = Modifier.size(18.dp))\n                            },""",
    """                            leadingIcon = {\n                                Icon(\n                                    if (state.webSearchEnabled) Icons.Outlined.Check else Icons.Outlined.Language,\n                                    contentDescription = if (state.webSearchEnabled) \"Включено\" else null,\n                                    modifier = Modifier.size(18.dp)\n                                )\n                            },"""
)

if "if (state.reasoningEnabled) Icons.Outlined.Check" not in block or "if (state.webSearchEnabled) Icons.Outlined.Check" not in block:
    raise SystemExit("Failed to add selected-state checks")

shell_pos = text.index('                        label = "Shell"', sheet)
divider_pos = text.index("                HorizontalDivider()", shell_pos)
line_end = text.index("\n", divider_pos) + 1
text = text[:line_end] + "\n                Spacer(Modifier.height(2.dp))\n" + block + "\n" + text[line_end:]

app.write_text(text, encoding="utf-8")

build = ROOT / "app/build.gradle.kts"
build_text = build.read_text(encoding="utf-8")
if 'versionName = "1.10.4"' not in build_text or "versionCode = 104" not in build_text:
    raise SystemExit("Expected v1.10.4 build version not found")
build_text = build_text.replace("versionCode = 104", "versionCode = 105", 1)
build_text = build_text.replace('versionName = "1.10.4"', 'versionName = "1.10.5"', 1)
build_text = build_text.replace("// Umnik v1.10.4", "// Umnik v1.10.5", 1)
build.write_text(build_text, encoding="utf-8")

changelog = ROOT / "CHANGELOG.md"
change_text = changelog.read_text(encoding="utf-8")
if "## v1.10.5" not in change_text:
    marker = "## Unreleased\n\n"
    entry = (
        "## v1.10.5 - 2026-09-14\n\n"
        "- Переключатели «Размышление» и «Поиск в сети» перенесены в самый низ меню `+`, под инструментами OpenRouter.\n"
        "- У включённого переключателя вместо обычного значка показывается галочка, поэтому активное состояние видно сразу.\n"
        "- Версия: 1.10.5 / versionCode 105.\n\n"
    )
    if marker not in change_text:
        raise SystemExit("Unreleased marker not found")
    change_text = change_text.replace(marker, marker + entry, 1)
    changelog.write_text(change_text, encoding="utf-8")

print("v1.10.5 patch applied")
