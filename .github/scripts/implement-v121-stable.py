from pathlib import Path

root = Path(__file__).resolve().parents[2]
vm = root / "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
build = root / "app/build.gradle.kts"
changelog = root / "CHANGELOG.md"

text = vm.read_text(encoding="utf-8")
text = text.replace("import kotlinx.coroutines.Job\n", "import kotlinx.coroutines.Job\nimport kotlinx.coroutines.delay\n", 1)
old = """    fun refreshProviderUsage() {\n        val profile = _state.value.connectionProfiles.firstOrNull { it.type == ProviderType.OPENROUTER }\n            ?: return\n        if (profile.id in _state.value.disabledConnectionIds || !isProfileConfigured(profile)) {\n            _state.value = _state.value.copy(providerUsage = null)\n            return\n        }\n        val key = secrets.getProfileApiKey(profile.id).orEmpty()\n        viewModelScope.launch {\n            runCatching { api.keyUsage(key, profile.baseUrl) }\n"""
new = """    fun refreshProviderUsage(delayMs: Long = 0L) {\n        val profile = _state.value.connectionProfiles.firstOrNull { it.type == ProviderType.OPENROUTER }\n            ?: return\n        if (profile.id in _state.value.disabledConnectionIds || !isProfileConfigured(profile)) {\n            _state.value = _state.value.copy(providerUsage = null)\n            return\n        }\n        val key = secrets.getProfileApiKey(profile.id).orEmpty()\n        viewModelScope.launch {\n            if (delayMs > 0L) delay(delayMs)\n            runCatching { api.keyUsage(key, profile.baseUrl) }\n"""
if old not in text:
    raise SystemExit("usage block not found")
text = text.replace(old, new, 1)
old_success = """                playReadySound()\n                if (profile.type == ProviderType.OPENROUTER) refreshProviderUsage()\n"""
first = """                playReadySound()\n                if (profile.type == ProviderType.OPENROUTER) {\n                    refreshProviderUsage()\n                    if (mode == ChatMode.IMAGE) refreshProviderUsage(2500L)\n                }\n"""
second = """                playReadySound()\n                if (profile.type == ProviderType.OPENROUTER) {\n                    refreshProviderUsage()\n                    refreshProviderUsage(2500L)\n                }\n"""
if text.count(old_success) != 2:
    raise SystemExit(f"expected 2 success hooks, found {text.count(old_success)}")
text = text.replace(old_success, first, 1)
text = text.replace(old_success, second, 1)
vm.write_text(text, encoding="utf-8")

text = build.read_text(encoding="utf-8")
text = text.replace("// Umnik v1.2.1-beta.2", "// Umnik v1.2.1", 1)
text = text.replace('        versionCode = 39\n        versionName = "1.2.1-beta.2"', '        versionCode = 40\n        versionName = "1.2.1"', 1)
build.write_text(text, encoding="utf-8")

text = changelog.read_text(encoding="utf-8")
anchor = "## Unreleased\n\n"
entry = """## v1.2.1 - 2026-09-12\n\n- Стабильный релиз индикатора расходов OpenRouter после проверки beta-версий.\n- Рядом с моделью показывается компактный расход за сегодня; по нажатию доступны день, неделя, месяц и всё время.\n- После генерации изображения расход обновляется сразу и повторно через 2.5 секунды, чтобы учесть задержку обновления статистики OpenRouter.\n- Название модели прижато к левому краю, а диагностика ошибок OpenRouter 403 стала понятнее.\n\n"""
if anchor not in text:
    raise SystemExit("changelog anchor not found")
text = text.replace(anchor, anchor + entry, 1)
changelog.write_text(text, encoding="utf-8")
