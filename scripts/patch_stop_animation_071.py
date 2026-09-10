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

old = '''                    ) {\n                        Icon(\n                            if (state.requestActive) Icons.Outlined.Stop else Icons.Outlined.Send,\n                            contentDescription = if (state.requestActive) "Остановить работу модели" else "Отправить"\n                        )\n                    }'''
new = '''                    ) {\n                        if (state.requestActive) {\n                            WorkingStopIcon()\n                        } else {\n                            Icon(\n                                Icons.Outlined.Send,\n                                contentDescription = "Отправить"\n                            )\n                        }\n                    }'''
if old not in text:
    raise SystemExit("Send/Stop icon block not found")
text = text.replace(old, new, 1)

helper = r'''@Composable
private fun WorkingStopIcon() {
    val transition = rememberInfiniteTransition(label = "workingStop")
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720),
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
if "private fun WorkingStopIcon()" not in text:
    if marker not in text:
        raise SystemExit("ChatHeader marker not found")
    text = text.replace(marker, helper + marker, 1)

UI.write_text(text, encoding="utf-8")

if CHANGELOG.exists():
    changelog = CHANGELOG.read_text(encoding="utf-8")
    note = "- Кнопка Stop теперь мягко пульсирует во время активного запроса, показывая, что модель продолжает работать.\n"
    if note not in changelog:
        marker = "## v0.7.1"
        idx = changelog.find(marker)
        if idx >= 0:
            line_end = changelog.find("\n", idx)
            changelog = changelog[:line_end+1] + "\n" + note + changelog[line_end+1:]
        else:
            changelog = "## v0.7.1\n\n" + note + "\n" + changelog
        CHANGELOG.write_text(changelog, encoding="utf-8")

print("Stop animation patch applied")
