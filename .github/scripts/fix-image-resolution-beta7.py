from pathlib import Path

root = Path('.')
vm_path = root / 'app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt'
gradle_path = root / 'app/build.gradle.kts'
changelog_path = root / 'CHANGELOG.md'

vm = vm_path.read_text(encoding='utf-8')

old_positional = '''                            api.generateImage(
                                key,
                                imageModel,
                                imagePrompt,
                                pending + projectImages,
                                profile.baseUrl,
                                imageAspectRatio,
                                imageResolution
                            )'''
new_positional = '''                            generateOpenRouterImageWithResolutionFallback(
                                profileId = profile.id,
                                apiKey = key,
                                model = imageModel,
                                prompt = imagePrompt,
                                attachments = pending + projectImages,
                                baseUrl = profile.baseUrl,
                                aspectRatio = imageAspectRatio,
                                resolution = imageResolution
                            )'''
if old_positional not in vm:
    raise SystemExit('positional image generation call not found')
vm = vm.replace(old_positional, new_positional, 1)

old_named = '''                    api.generateImage(
                        apiKey = key,
                        model = imageModel,
                        prompt = prompt,
                        attachments = pending,
                        baseUrl = profile.baseUrl,
                        aspectRatio = imageAspectRatio,
                        resolution = imageResolution
                    )'''
new_named = '''                    generateOpenRouterImageWithResolutionFallback(
                        profileId = profile.id,
                        apiKey = key,
                        model = imageModel,
                        prompt = prompt,
                        attachments = pending,
                        baseUrl = profile.baseUrl,
                        aspectRatio = imageAspectRatio,
                        resolution = imageResolution
                    )'''
if old_named not in vm:
    raise SystemExit('named image generation call not found')
vm = vm.replace(old_named, new_named, 1)

marker = '''    override fun onCleared() {
'''
helper = '''    private suspend fun generateOpenRouterImageWithResolutionFallback(
        profileId: String,
        apiKey: String,
        model: String,
        prompt: String,
        attachments: List<PendingAttachment>,
        baseUrl: String,
        aspectRatio: String?,
        resolution: String?
    ): OpenRouterClient.Result {
        try {
            return api.generateImage(
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                attachments = attachments,
                baseUrl = baseUrl,
                aspectRatio = aspectRatio,
                resolution = resolution
            )
        } catch (first: Throwable) {
            val message = first.message.orEmpty().lowercase()
            val canRetryWithoutResolution = !resolution.isNullOrBlank() &&
                "output pixels" in message &&
                ("omit resolution" in message || "larger resolution" in message)
            if (!canRetryWithoutResolution) throw first

            val result = api.generateImage(
                apiKey = apiKey,
                model = model,
                prompt = prompt,
                attachments = attachments,
                baseUrl = baseUrl,
                aspectRatio = aspectRatio,
                resolution = null
            )
            prefs.edit()
                .remove(imageParameterPrefKey("resolution", profileId, model))
                .apply()
            _state.value = _state.value.copy(
                imageResolution = null,
                status = "Выбранное разрешение несовместимо с этим форматом. Umnik переключил разрешение на «Авто», сохранив ${aspectRatio ?: "соотношение сторон"}."
            )
            return result
        }
    }

'''
if marker not in vm:
    raise SystemExit('onCleared marker not found')
vm = vm.replace(marker, helper + marker, 1)
vm_path.write_text(vm, encoding='utf-8')

gradle = gradle_path.read_text(encoding='utf-8')
gradle = gradle.replace('// Umnik v1.2.0-beta.6', '// Umnik v1.2.0-beta.7', 1)
gradle = gradle.replace('versionCode = 35', 'versionCode = 36', 1)
gradle = gradle.replace('versionName = "1.2.0-beta.6"', 'versionName = "1.2.0-beta.7"', 1)
if 'versionName = "1.2.0-beta.7"' not in gradle:
    raise SystemExit('version bump failed')
gradle_path.write_text(gradle, encoding='utf-8')

changelog = changelog_path.read_text(encoding='utf-8')
entry = '''## v1.2.0-beta.7 - 2026-09-11

- Исправлена генерация изображений для сочетаний параметров, которые модель заявляет как поддерживаемые по отдельности, но отклоняет вместе из-за минимального числа пикселей.
- Если OpenRouter возвращает такую ошибку для явно выбранного разрешения, Umnik один раз автоматически повторяет запрос с разрешением «Авто», сохраняя выбранное соотношение сторон.
- После успешного повтора настройка разрешения переключается на «Авто», чтобы интерфейс не показывал значение, которое фактически не использовалось.
- Это устраняет ошибку Seedream 4.5 при широких форматах вроде `16:9`, когда фиксированное разрешение оказывается ниже фактического минимума модели.

'''
needle = '## Unreleased\n\n'
if needle not in changelog:
    raise SystemExit('Unreleased section not found')
changelog = changelog.replace(needle, needle + entry, 1)
changelog_path.write_text(changelog, encoding='utf-8')

print('beta.7 patch applied')
