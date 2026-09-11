from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)

# Snackbar styling: darker and lifted above the composer.
ui_path = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
ui = ui_path.read_text()
ui = replace_once(
    ui,
    "import androidx.compose.material3.Scaffold\n",
    "import androidx.compose.material3.Scaffold\nimport androidx.compose.material3.Snackbar\n",
    "snackbar import",
)
ui = replace_once(
    ui,
    '''        Scaffold(\n            containerColor = MaterialTheme.colorScheme.surface,\n            snackbarHost = { SnackbarHost(snackbar) }\n        ) { padding ->\n''',
    '''        Scaffold(\n            containerColor = MaterialTheme.colorScheme.surface,\n            snackbarHost = {\n                SnackbarHost(\n                    hostState = snackbar,\n                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 86.dp)\n                ) { data ->\n                    Snackbar(\n                        snackbarData = data,\n                        shape = RoundedCornerShape(14.dp),\n                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,\n                        contentColor = MaterialTheme.colorScheme.onSurface\n                    )\n                }\n            }\n        ) { padding ->\n''',
    "snackbar host style",
)
ui_path.write_text(ui)

# Model switching is already visible in the header, so do not show a redundant snackbar.
vm_path = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
vm = vm_path.read_text()
vm = replace_once(
    vm,
    '''            reasoningEnabled = keepReasoning,\n            apiKeyConfigured = isProfileConfigured(profile),\n            status = "${clean.substringAfterLast('/')} · ${profile.name}"\n        )\n        refreshModelCapabilities()\n''',
    '''            reasoningEnabled = keepReasoning,\n            apiKeyConfigured = isProfileConfigured(profile),\n            status = null\n        )\n        refreshModelCapabilities()\n''',
    "remove quick model snackbar",
)
vm_path.write_text(vm)

# Version bump.
build_path = Path("app/build.gradle.kts")
build = build_path.read_text()
build = replace_once(build, "// Umnik v1.2.0-beta.4\n", "// Umnik v1.2.0-beta.5\n", "version comment")
build = replace_once(build, "versionCode = 33", "versionCode = 34", "version code")
build = replace_once(build, 'versionName = "1.2.0-beta.4"', 'versionName = "1.2.0-beta.5"', "version name")
build_path.write_text(build)

# Changelog.
changelog_path = Path("CHANGELOG.md")
changelog = changelog_path.read_text()
insert = '''## v1.2.0-beta.5 - 2026-09-11\n\n- Убрано лишнее всплывающее уведомление при переключении быстрой модели: выбранная модель и так сразу видна в заголовке чата.\n- Остальные уведомления подняты над полем ввода и получили более спокойный тёмно-серый фон, чтобы не перекрывать набор текста и не выбиваться из тёмной темы.\n\n'''
changelog = replace_once(changelog, "## Unreleased\n\n", "## Unreleased\n\n" + insert, "changelog insertion")
changelog_path.write_text(changelog)
