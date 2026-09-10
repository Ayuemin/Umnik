from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
CHANGELOG = ROOT / "CHANGELOG.md"

text = UI.read_text(encoding="utf-8")

animation_imports = """import androidx.compose.animation.core.RepeatMode\nimport androidx.compose.animation.core.animateFloat\nimport androidx.compose.animation.core.infiniteRepeatable\nimport androidx.compose.animation.core.rememberInfiniteTransition\nimport androidx.compose.animation.core.tween\n"""
if "import androidx.compose.animation.core.RepeatMode" not in text:
    text = text.replace(
        "import androidx.activity.result.contract.ActivityResultContracts\n",
        "import androidx.activity.result.contract.ActivityResultContracts\n" + animation_imports
    )

if "import androidx.compose.ui.draw.scale" not in text:
    text = text.replace(
        "import androidx.compose.ui.draw.clip\n",
        "import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.draw.scale\n"
    )

if "private fun WorkingStopIcon()" not in text:
    old = '''                    ) {\n                        Icon(\n                            if (state.requestActive) Icons.Outlined.Stop else Icons.Outlined.Send,\n                            contentDescription = if (state.requestActive) "Остановить работу модели" else "Отправить"\n                        )\n                    }'''
    new = '''                    ) {\n                        if (state.requestActive) {\n                            WorkingStopIcon()\n                        } else {\n                            Icon(\n                                Icons.Outlined.Send,\n                                contentDescription = "Отправить"\n                            )\n                        }\n                    }'''
    if old not in text:
        raise SystemExit("Send/Stop icon block not found")
    text = text.replace(old, new, 1)

    helper = r'''@Composable
private fun WorkingStopIcon() {
    val transition = rememberInfiniteTransition(label = "workingStop")
    val pulse by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520),
            repeatMode = RepeatMode.Reverse
        ),
        label = "workingStopPulse"
    )

    Icon(
        Icons.Outlined.Stop,
        contentDescription = "Остановить работу модели",
        modifier = Modifier.scale(pulse),
        tint = MaterialTheme.colorScheme.primary
    )
}

'''
    marker = "@Composable\nprivate fun ChatHeader("
    if marker not in text:
        raise SystemExit("ChatHeader marker not found")
    text = text.replace(marker, helper + marker, 1)
else:
    start = text.index("private fun WorkingStopIcon()")
    end = text.index("@Composable\nprivate fun ChatHeader(", start)
    block = text[start:end]
    block = block.replace("initialValue = 0.82f", "initialValue = 0.86f")
    block = block.replace("durationMillis = 720", "durationMillis = 520")
    text = text[:start] + block + text[end:]

UI.write_text(text, encoding="utf-8")

if CHANGELOG.exists():
    changelog = CHANGELOG.read_text(encoding="utf-8")
    old_note = "- Кнопка Stop теперь мягко пульсирует во время активного запроса, показывая, что модель продолжает работать."
    new_note = "- Кнопка Stop мягко пульсирует примерно раз в секунду во время активного запроса, показывая, что модель продолжает работать."
    if old_note in changelog:
        changelog = changelog.replace(old_note, new_note)
    elif new_note not in changelog:
        marker = "## v0.7.1"
        idx = changelog.find(marker)
        if idx >= 0:
            line_end = changelog.find("\n", idx)
            changelog = changelog[:line_end+1] + "\n" + new_note + "\n" + changelog[line_end+1:]
        else:
            changelog = "## v0.7.1\n\n" + new_note + "\n\n" + changelog
    CHANGELOG.write_text(changelog, encoding="utf-8")

print("Stop pulse tuned")
