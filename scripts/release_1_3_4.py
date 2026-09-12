from pathlib import Path
import json


def replace_between(text: str, start_marker: str, end_marker: str, replacement: str) -> str:
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    return text[:start] + replacement + text[end:]


# 1) Text model/provider selection must actually switch the current chat and new-chat default.
vm_path = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
vm = vm_path.read_text()
new_select = '''    fun selectDefaultTextModel(profileId: String, model: String) {
        val clean = model.trim()
        if (clean.isBlank()) return
        val profile = _state.value.connectionProfiles.firstOrNull { it.id == profileId } ?: return
        if (profile.id in _state.value.disabledConnectionIds) return
        if (!isProfileConfigured(profile)) return

        val saved = prefs.edit()
            .putString(profilePrefKey("text_model", profile.id), clean)
            .putString("active_connection_profile", profile.id)
            .commit()
        if (!saved) {
            _state.value = _state.value.copy(status = "Не удалось сохранить выбор модели")
            return
        }

        val catalog = when {
            _state.value.modelCatalogConnectionId == profile.id -> _state.value.modelCatalog
            _state.value.activeConnectionProfileId == profile.id -> _state.value.availableTextModels
            else -> emptyList()
        }
        val info = catalog.firstOrNull { it.id == clean }
        val effort = preferredReasoningEffort(clean, info)
        val keepReasoning = reasoningStillValid(info, effort)
        val chats = _state.value.chats.map { chat ->
            if (chat.id == _state.value.currentChatId) chat.copy(
                connectionProfileId = profile.id,
                textModelOverride = null,
                mode = ChatMode.TEXT,
                updatedAt = System.currentTimeMillis()
            ) else chat
        }
        chatsRepository.save(chats)
        prefs.edit()
            .putString("reasoning_effort", effort.name)
            .putBoolean("reasoning_enabled", keepReasoning)
            .apply()

        _state.value = _state.value.copy(
            chats = chats,
            activeConnectionProfileId = profile.id,
            textModel = clean,
            currentChatTextModel = null,
            mode = ChatMode.TEXT,
            availableTextModels = catalog,
            quickTextModels = loadAllQuickTextModels(_state.value.connectionProfiles, _state.value.disabledConnectionIds),
            webSearchEnabled = if (profile.type == ProviderType.OPENROUTER) prefs.getBoolean("web_search", false) else false,
            reasoningEffort = effort,
            reasoningEnabled = keepReasoning,
            apiKeyConfigured = isProfileConfigured(profile),
            status = "Сохранено · ${profile.name}: ${clean.substringAfterLast('/')} · текущий и новые чаты"
        )
        refreshModelCapabilities()
        if (profile.type == ProviderType.OPENROUTER) refreshProviderUsage()
    }

'''
vm = replace_between(
    vm,
    "    fun selectDefaultTextModel(profileId: String, model: String) {",
    "    fun selectModel(mode: ChatMode, model: String) {",
    new_select,
)
vm_path.write_text(vm)

# 2) Picker copy should match the new behavior.
ui_path = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
ui = ui_path.read_text()
old_help = "Нажмите модель, затем «Сохранить выбор». Для текущего подключения модель сразу применяется к этому и новым чатам. Для другого подключения сохраняется его модель по умолчанию без переключения сервиса текущего чата."
new_help = "Выберите поставщика и модель, затем нажмите «Сохранить выбор». Выбранный поставщик и модель сразу применятся к текущему чату и станут значениями по умолчанию для новых чатов."
if old_help not in ui:
    raise RuntimeError("Model picker help text not found")
ui_path.write_text(ui.replace(old_help, new_help, 1))

# 3) NVIDIA image requests: Schnell follows NVIDIA's published seed/steps example.
nim_path = Path("app/src/main/java/com/ayuemin/ymnik/network/NvidiaImageClient.kt")
nim = nim_path.read_text()
nim = nim.replace(
'''            if (
                !response.successful &&
                response.code == 422 &&
                isExtraInputValidation(response.body) &&
                gson.toJson(primaryPayload) != gson.toJson(minimalPayload)
            ) {
                response = execute(url, apiKey, minimalPayload)
            }
''',
'''            if (
                !response.successful &&
                response.code in setOf(400, 422) &&
                gson.toJson(primaryPayload) != gson.toJson(minimalPayload)
            ) {
                response = execute(url, apiKey, minimalPayload)
            }
''',
1,
)
nim = nim.replace(
'''        model.contains("flux.1-schnell") || model.contains("flux.1-dev") -> JsonObject().apply {
            addProperty("prompt", prompt)
            flux1Dimensions(aspectRatio)?.let { (width, height) ->
                addProperty("width", width)
                addProperty("height", height)
            }
        }
''',
'''        model.contains("flux.1-schnell") -> JsonObject().apply {
            addProperty("prompt", prompt)
            addProperty("seed", 0)
            addProperty("steps", 4)
            flux1Dimensions(aspectRatio)?.let { (width, height) ->
                addProperty("width", width)
                addProperty("height", height)
            }
        }
        model.contains("flux.1-dev") -> JsonObject().apply {
            addProperty("prompt", prompt)
            flux1Dimensions(aspectRatio)?.let { (width, height) ->
                addProperty("width", width)
                addProperty("height", height)
            }
        }
''',
1,
)
nim = nim.replace(
'''    private fun minimalPayload(model: String, prompt: String): JsonObject =
        if (model.endsWith("stable-diffusion-xl")) {
            stableDiffusionXlPayload(prompt)
        } else {
            JsonObject().apply { addProperty("prompt", prompt) }
        }
''',
'''    private fun minimalPayload(model: String, prompt: String): JsonObject = when {
        model.contains("flux.1-schnell") -> JsonObject().apply {
            addProperty("prompt", prompt)
            addProperty("seed", 0)
            addProperty("steps", 4)
        }
        model.endsWith("stable-diffusion-xl") -> stableDiffusionXlPayload(prompt)
        else -> JsonObject().apply { addProperty("prompt", prompt) }
    }
''',
1,
)
if 'addProperty("steps", 4)' not in nim:
    raise RuntimeError("Schnell payload patch failed")
nim_path.write_text(nim)

# 4) Provider registry: remove NVIDIA free endpoints that NVIDIA now marks deprecated.
registry_path = Path("docs/provider-registry.json")
registry = json.loads(registry_path.read_text())
registry["version"] = 2
registry["updated"] = "2026-09-12"
ratios = ["1:1", "16:9", "9:16", "5:4", "4:5", "3:2", "2:3"]
registry["providers"]["nvidia"]["imageModels"] = [
    {"id": "black-forest-labs/flux.2-klein-4b", "inputModalities": ["text"], "parameterOptions": {}},
    {"id": "black-forest-labs/flux.1-schnell", "inputModalities": ["text"], "parameterOptions": {"aspect_ratio": ratios}},
    {"id": "black-forest-labs/flux.1-dev", "inputModalities": ["text"], "parameterOptions": {"aspect_ratio": ratios}},
]
registry_path.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + "\n")

pr_path = Path("app/src/main/java/com/ayuemin/ymnik/network/ProviderRegistry.kt")
pr = pr_path.read_text()
pr = pr.replace("val version: Int = 1,", "val version: Int = 2,", 1)
pr = pr.replace("doc.version >= 1 &&", "doc.version >= MIN_REGISTRY_VERSION &&", 1)
pr = pr.replace("version = 1,", "version = 2,", 1)
pr = pr.replace(
'''                    ImageModelDefinition(
                        "black-forest-labs/flux.1-dev",
                        parameterOptions = mapOf("aspect_ratio" to COMMON_RATIOS)
                    ),
                    ImageModelDefinition(
                        "stabilityai/stable-diffusion-3-medium",
                        parameterOptions = mapOf("aspect_ratio" to COMMON_RATIOS)
                    ),
                    ImageModelDefinition("stabilityai/stable-diffusion-xl")
''',
'''                    ImageModelDefinition(
                        "black-forest-labs/flux.1-dev",
                        parameterOptions = mapOf("aspect_ratio" to COMMON_RATIOS)
                    )
''',
1,
)
marker = '        private const val KEY_JSON = "registry_json"\n'
if "MIN_REGISTRY_VERSION" not in pr:
    pr = pr.replace(marker, '        private const val MIN_REGISTRY_VERSION = 2\n' + marker, 1)
else:
    # Function reference above may already contain the symbol, but the constant still needs adding.
    if 'private const val MIN_REGISTRY_VERSION' not in pr:
        pr = pr.replace(marker, '        private const val MIN_REGISTRY_VERSION = 2\n' + marker, 1)
if 'stabilityai/stable-diffusion-xl' in pr:
    raise RuntimeError("Deprecated SDXL fallback was not removed")
pr_path.write_text(pr)

# 5) Version/changelog.
gradle = Path("app/build.gradle.kts")
g = gradle.read_text()
g = g.replace("// Umnik v1.3.3", "// Umnik v1.3.4", 1)
g = g.replace("versionCode = 45", "versionCode = 46", 1)
g = g.replace('versionName = "1.3.3"', 'versionName = "1.3.4"', 1)
gradle.write_text(g)

changelog = Path("CHANGELOG.md")
c = changelog.read_text()
entry = '''## v1.3.4 - 2026-09-12

- Исправлен выбор текстовой модели: выбранные поставщик и модель теперь действительно переключают текущий чат и становятся значениями по умолчанию для новых чатов.
- После смены поставщика выбранная модель сразу становится текущей и видна в быстром списке моделей в шапке чата.
- Исправлено переключение в обе стороны между NVIDIA и OpenRouter.
- Из каталога NVIDIA удалены Stable Diffusion 3 Medium и Stable Diffusion XL: NVIDIA пометила их бесплатные endpoint-ы как deprecated.
- FLUX.1-schnell теперь отправляется по официальному примеру NVIDIA с `seed=0` и `steps=4`; при 400/422 выполняется один повтор без необязательных размеров.
- Реестр провайдеров поднят до версии 2, поэтому старый кэш с недоступными image-моделями сбрасывается после обновления приложения.

'''
if "## v1.3.4 - 2026-09-12" not in c:
    c = c.replace("## Unreleased\n\n", "## Unreleased\n\n" + entry, 1)
changelog.write_text(c)
