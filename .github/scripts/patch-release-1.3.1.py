from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


def replace_count(text: str, old: str, new: str, expected: int, label: str) -> str:
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"{label}: expected {expected} matches, got {count}")
    return text.replace(old, new)


# 1) Image retry UI + provider-safe image parameters.
vm_path = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
vm = vm_path.read_text(encoding="utf-8")

old_user_message = '''            attachmentNames = (pending.map { it.name } + persistentChatFiles.map { it.name }).distinct()\n        )\n'''
new_user_message = '''            attachmentNames = (pending.map { it.name } + persistentChatFiles.map { it.name }).distinct(),\n            imageGeneration = mode == ChatMode.IMAGE\n        )\n'''
vm = replace_once(vm, old_user_message, new_user_message, "mark legacy image-mode user messages")

old_params = '''        val imageModel = _state.value.imageModel\n        val imageAspectRatio = _state.value.imageAspectRatio\n        val imageResolution = _state.value.imageResolution\n'''
new_params = '''        val imageModel = _state.value.imageModel\n        val imageInfo = currentImageModelInfo()\n        val imageAspectRatio = _state.value.imageAspectRatio?.takeIf {\n            profile.type != ProviderType.OPENROUTER || imageInfo?.supportedParameters?.contains("aspect_ratio") == true\n        }\n        val imageResolution = _state.value.imageResolution?.takeIf {\n            profile.type != ProviderType.OPENROUTER || imageInfo?.supportedParameters?.contains("resolution") == true\n        }\n'''
vm = replace_count(vm, old_params, new_params, 2, "sanitize image parameters before sending")
vm_path.write_text(vm, encoding="utf-8")

ui_path = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
ui = ui_path.read_text(encoding="utf-8")

old_retry = '''                    onRetry = if (\n                        !message.imageGeneration &&\n                        message.role == "user" &&\n                        message.text.isNotBlank() &&\n                        message.attachmentNames.all { name ->\n                            currentChatFiles.any { file -> file.name == name }\n                        }\n                    ) {\n                        { vm.send(message.text) }\n                    } else null\n'''
new_retry = '''                    onRetry = when {\n                        message.role != "user" || message.text.isBlank() -> null\n                        message.imageGeneration && message.attachmentNames.isEmpty() -> {\n                            { vm.sendImagePrompt(message.text) }\n                        }\n                        !message.imageGeneration && message.attachmentNames.all { name ->\n                            currentChatFiles.any { file -> file.name == name }\n                        } -> {\n                            { vm.send(message.text) }\n                        }\n                        else -> null\n                    }\n'''
ui = replace_once(ui, old_retry, new_retry, "image retry action")
ui = replace_once(
    ui,
    '                        description = "Спросить ещё раз",\n',
    '                        description = if (message.imageGeneration) "Сгенерировать снова" else "Спросить ещё раз",\n',
    "image retry accessibility label",
)
ui_path.write_text(ui, encoding="utf-8")

# 2) Generic OpenAI-compatible Image API: accept either a base URL or a full image endpoint.
compat_path = Path("app/src/main/java/com/ayuemin/ymnik/network/CompatibleApiClient.kt")
compat = compat_path.read_text(encoding="utf-8")
old_endpoint = '''    private fun imageGenerationEndpoint(baseUrl: String): String {\n        val clean = baseUrl.trim().trimEnd('/')\n        return if (clean.endsWith("/images/generations")) clean else endpoint(clean, "images/generations")\n    }\n'''
new_endpoint = '''    private fun imageGenerationEndpoint(baseUrl: String): String {\n        val clean = baseUrl.trim().trimEnd('/')\n        return when {\n            clean.endsWith("/images/generations") -> clean\n            clean.endsWith("/images") -> clean\n            else -> endpoint(clean, "images/generations")\n        }\n    }\n'''
compat = replace_once(compat, old_endpoint, new_endpoint, "compatible image endpoint")
compat_path.write_text(compat, encoding="utf-8")

# 3) Version.
build_path = Path("app/build.gradle.kts")
build = build_path.read_text(encoding="utf-8")
build = replace_once(build, "// Umnik v1.3.0", "// Umnik v1.3.1", "version comment")
build = replace_once(build, '        versionCode = 42', '        versionCode = 43', "version code")
build = replace_once(build, '        versionName = "1.3.0"', '        versionName = "1.3.1"', "version name")
build_path.write_text(build, encoding="utf-8")

# 4) Changelog.
changelog_path = Path("CHANGELOG.md")
changelog = changelog_path.read_text(encoding="utf-8")
marker = "## Unreleased\n\n"
section = '''## v1.3.1 - 2026-09-12\n\n- Исправлен HTTP 422 у NVIDIA NIM: Umnik больше не отправляет необязательный `mode` и другие лишние поля там, где для text-to-image достаточно минимального запроса.\n- Для NVIDIA добавлен защитный повтор: если провайдер отклоняет необязательное поле как `extra_forbidden`, запрос один раз повторяется только с обязательными полями модели.\n- Проверены схемы всех встроенных Image API: OpenRouter получает только заявленные моделью параметры, NVIDIA использует отдельные payload для FLUX/SD3/SDXL, а OpenAI-compatible остаётся на минимальном `model + prompt`.\n- Пользовательский OpenAI-compatible Image API теперь принимает как базовый URL, так и полный endpoint, заканчивающийся на `/images` или `/images/generations`.\n- У текстового запроса на генерацию изображения появилась кнопка повтора; для неё используется подпись «Сгенерировать снова».\n- Параметры `aspect_ratio` и `resolution` больше не отправляются в OpenRouter, если выбранная image-модель не объявляет их в `/images/models`.\n\n'''
if section.strip() in changelog:
    raise RuntimeError("v1.3.1 changelog section already present")
changelog = replace_once(changelog, marker, marker + section, "changelog insertion")
changelog_path.write_text(changelog, encoding="utf-8")

# Static safety assertions for the provider audit.
nvidia = Path("app/src/main/java/com/ayuemin/ymnik/network/NvidiaImageClient.kt").read_text(encoding="utf-8")
if 'addProperty("mode"' in nvidia:
    raise RuntimeError("NVIDIA payload still contains an explicit mode field")
if 'isExtraInputValidation' not in nvidia:
    raise RuntimeError("NVIDIA extra-field fallback is missing")

openrouter = Path("app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt").read_text(encoding="utf-8")
required_openrouter_fragments = [
    'url(endpoint(baseUrl, "images"))',
    'addProperty("model", model)',
    'addProperty("prompt", prompt.ifBlank',
    'addProperty("aspect_ratio", it)',
    'addProperty("resolution", it)',
]
for fragment in required_openrouter_fragments:
    if fragment not in openrouter:
        raise RuntimeError(f"OpenRouter image API audit failed: missing {fragment}")

print("Umnik v1.3.1 patch applied and provider request shapes audited")
