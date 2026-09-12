from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


ui_path = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
ui = ui_path.read_text(encoding="utf-8")

ui = replace_once(
    ui,
    'import androidx.compose.foundation.Image as ComposeImage\n',
    'import androidx.compose.foundation.Image as ComposeImage\nimport androidx.compose.foundation.border\n',
    "border import",
)

ui = replace_once(
    ui,
    '    var recordingSeconds by remember { mutableIntStateOf(0) }\n',
    '    var recordingSeconds by remember { mutableIntStateOf(0) }\n    var requestElapsedSeconds by remember { mutableIntStateOf(0) }\n',
    "request timer state",
)

recording_effect = '''    LaunchedEffect(isRecording, recordingStartedAt) {\n        while (isRecording) {\n            recordingSeconds = ((System.currentTimeMillis() - recordingStartedAt) / 1000L).toInt().coerceAtLeast(0)\n            if (recordingSeconds >= 600) {\n                val file = voiceRecorder.stop()\n                isRecording = false\n                file?.let { vm.addVoiceRecording(it.absolutePath) }\n                Toast.makeText(context, "Достигнут максимум записи 10 минут", Toast.LENGTH_SHORT).show()\n                break\n            }\n            delay(250)\n        }\n    }\n'''
request_effect = recording_effect + '''\n    LaunchedEffect(state.requestActive) {\n        if (!state.requestActive) {\n            requestElapsedSeconds = 0\n            return@LaunchedEffect\n        }\n        val startedAt = System.currentTimeMillis()\n        while (true) {\n            requestElapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L)\n                .toInt()\n                .coerceAtLeast(0)\n            delay(250)\n        }\n    }\n'''
ui = replace_once(ui, recording_effect, request_effect, "request elapsed timer effect")

old_button_tail = '''                                enabled = state.requestActive || isRecording || (!state.isLoading && (\n                                    text.isNotBlank() || state.pendingAttachments.isNotEmpty() || (!imagePromptMode && currentChatFiles.isNotEmpty())\n                                ))\n                            ) {\n                                if (state.requestActive) {\n                                    WorkingStopIcon()\n                                } else {\n'''
new_button_tail = '''                                enabled = state.requestActive || isRecording || (!state.isLoading && (\n                                    text.isNotBlank() || state.pendingAttachments.isNotEmpty() || (!imagePromptMode && currentChatFiles.isNotEmpty())\n                                )),\n                                modifier = Modifier.size(if (state.requestActive) 58.dp else 48.dp)\n                            ) {\n                                if (state.requestActive) {\n                                    WorkingStopTimer(requestElapsedSeconds)\n                                } else {\n'''
ui = replace_once(ui, old_button_tail, new_button_tail, "composer stop timer button")

old_working = '''@Composable\nprivate fun WorkingStopIcon() {\n    val transition = rememberInfiniteTransition(label = "workingStop")\n    val pulse by transition.animateFloat(\n        initialValue = 0.86f,\n        targetValue = 1.08f,\n        animationSpec = infiniteRepeatable(\n            animation = tween(durationMillis = 520),\n            repeatMode = RepeatMode.Reverse\n        ),\n        label = "workingStopPulse"\n    )\n\n    Icon(\n        Icons.Outlined.Stop,\n        contentDescription = "Остановить работу модели",\n        modifier = Modifier.scale(pulse),\n        tint = MaterialTheme.colorScheme.primary\n    )\n}\n'''
new_working = '''@Composable\nprivate fun WorkingStopTimer(seconds: Int) {\n    Box(\n        modifier = Modifier\n            .size(52.dp)\n            .border(\n                width = 2.dp,\n                color = MaterialTheme.colorScheme.primary,\n                shape = RoundedCornerShape(4.dp)\n            ),\n        contentAlignment = Alignment.Center\n    ) {\n        Text(\n            text = formatRequestDuration(seconds),\n            style = MaterialTheme.typography.titleMedium,\n            fontWeight = FontWeight.Medium,\n            color = MaterialTheme.colorScheme.primary,\n            maxLines = 1\n        )\n    }\n}\n\nprivate fun formatRequestDuration(seconds: Int): String {\n    val safe = seconds.coerceAtLeast(0)\n    return "%d:%02d".format(Locale.US, safe / 60, safe % 60)\n}\n'''
ui = replace_once(ui, old_working, new_working, "replace breathing stop icon with stopwatch")
ui_path.write_text(ui, encoding="utf-8")

build_path = Path("app/build.gradle.kts")
build = build_path.read_text(encoding="utf-8")
build = replace_once(build, "// Umnik v1.3.1", "// Umnik v1.3.2", "version comment")
build = replace_once(build, "        versionCode = 43", "        versionCode = 44", "version code")
build = replace_once(build, '        versionName = "1.3.1"', '        versionName = "1.3.2"', "version name")
build_path.write_text(build, encoding="utf-8")

changelog_path = Path("CHANGELOG.md")
changelog = changelog_path.read_text(encoding="utf-8")
marker = "## Unreleased\n\n"
section = '''## v1.3.2 - 2026-09-12\n\n- Пульсирующий значок остановки во время запроса заменён на крупный квадратный секундомер в поле ввода.\n- Секундомер показывает полное время текущего запроса в формате `м:с`, включая ожидание очереди у провайдера и сетевую задержку.\n- Нажатие прямо на секундомер по-прежнему немедленно останавливает активный запрос; отдельный стоп-значок больше не нужен.\n- После ответа, ошибки или остановки таймер сбрасывается и снова появляется обычная кнопка отправки.\n\n'''
if "## v1.3.2 - 2026-09-12" in changelog:
    raise RuntimeError("v1.3.2 changelog already exists")
changelog = replace_once(changelog, marker, marker + section, "changelog insertion")
changelog_path.write_text(changelog, encoding="utf-8")

# Safety assertions.
final_ui = ui_path.read_text(encoding="utf-8")
for required in [
    "WorkingStopTimer(requestElapsedSeconds)",
    "private fun formatRequestDuration(seconds: Int)",
    "vm.stopGeneration()",
    "modifier = Modifier.size(if (state.requestActive) 58.dp else 48.dp)",
]:
    if required not in final_ui:
        raise RuntimeError(f"missing expected timer fragment: {required}")
if "private fun WorkingStopIcon()" in final_ui:
    raise RuntimeError("old breathing stop icon still present")

print("Umnik v1.3.2 stopwatch UI patch applied")
