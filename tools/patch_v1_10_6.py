from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")

def one(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))

# Version.
one("app/build.gradle.kts", "// Umnik v1.10.5", "// Umnik v1.10.6")
one("app/build.gradle.kts", 'versionCode = 105\n        versionName = "1.10.5"', 'versionCode = 106\n        versionName = "1.10.6"')

# Changelog.
path = "CHANGELOG.md"
text = read(path)
marker = "## Unreleased\n"
entry = """## Unreleased\n\n## v1.10.6 - 2026-09-14\n\n- Озвучивание ответов кнопкой `OR` и режим `+ → Озвучить` теперь полностью независимы: у каждого своя модель и свой голос.\n- Для кнопки `OR` добавлена отдельная страница выбора Speech/Audio-модели и голоса. Голос запоминается отдельно для каждой модели ответов.\n- При смене модели озвучивания текста/документов старый голос больше не переносится автоматически на несовместимую модель.\n- Кнопка `OR` активна только когда для озвучивания ответов выбраны и модель, и голос. Дополнительной подсказки на неактивной кнопке нет; правила настройки описаны в памятке Umnik.\n- Памятка дополнена объяснением двух независимых вариантов нейросетевой озвучки и обязательного выбора совместимого голоса.\n- Версия: 1.10.6 / versionCode 106.\n"""
if marker not in text:
    raise SystemExit("CHANGELOG marker missing")
write(path, text.replace(marker, entry, 1))

# UiState gets a separate reply voice.
path = "app/src/main/java/com/ayuemin/ymnik/model/Models.kt"
one(path, '    val openRouterSpeechModel: String = "",\n    val webSearchEnabled: Boolean = false,', '    val openRouterSpeechModel: String = "",\n    val openRouterSpeechVoice: String = "",\n    val webSearchEnabled: Boolean = false,')

# ChatViewModel: decouple reply speech model/voice from the general media Speech settings.
path = "app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt"
text = read(path)
old = '            openRouterSpeechModel = openRouterFeaturePrefs.media().speechModel,\n            webSearchEnabled = prefs.getBoolean("web_search", false),'
new = '''            openRouterSpeechModel = prefs.getString("reply_speech_model", null)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: openRouterFeaturePrefs.media().speechModel,
            openRouterSpeechVoice = run {
                val replyModel = prefs.getString("reply_speech_model", null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: openRouterFeaturePrefs.media().speechModel
                prefs.getString(replySpeechVoiceKey(replyModel), null)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: if (replyModel == openRouterFeaturePrefs.media().speechModel) openRouterFeaturePrefs.media().voice else ""
            },
            webSearchEnabled = prefs.getBoolean("web_search", false),'''
if old not in text:
    raise SystemExit("ChatViewModel initial reply speech anchor missing")
text = text.replace(old, new, 1)

old = '''    fun setOpenRouterSpeechModel(modelId: String) {
        val clean = modelId.trim()
        val media = openRouterFeaturePrefs.media().copy(speechModel = clean)
        openRouterFeaturePrefs.saveMedia(media)
        _state.value = _state.value.copy(
            openRouterSpeechModel = clean,
            status = if (clean.isBlank()) "Модель озвучивания OpenRouter не выбрана" else "Модель озвучивания OpenRouter сохранена"
        )
    }
'''
new = '''    private fun replySpeechVoiceKey(modelId: String): String = "reply_speech_voice::${modelId.trim()}"

    fun setOpenRouterSpeechModel(modelId: String) {
        val clean = modelId.trim()
        if (clean.isBlank()) {
            prefs.edit().remove("reply_speech_model").apply()
        } else {
            prefs.edit().putString("reply_speech_model", clean).apply()
        }
        val savedVoice = if (clean.isBlank()) "" else prefs.getString(replySpeechVoiceKey(clean), "").orEmpty().trim()
        _state.value = _state.value.copy(
            openRouterSpeechModel = clean,
            openRouterSpeechVoice = savedVoice,
            status = if (clean.isBlank()) "Модель озвучивания ответов не выбрана" else "Модель озвучивания ответов сохранена"
        )
    }

    fun setOpenRouterSpeechVoice(voice: String) {
        val model = _state.value.openRouterSpeechModel.trim()
        if (model.isBlank()) return
        val clean = voice.trim()
        val key = replySpeechVoiceKey(model)
        if (clean.isBlank()) prefs.edit().remove(key).apply() else prefs.edit().putString(key, clean).apply()
        _state.value = _state.value.copy(
            openRouterSpeechVoice = clean,
            status = if (clean.isBlank()) "Голос озвучивания ответов снят" else "Голос озвучивания ответов сохранён"
        )
    }
'''
if old not in text:
    raise SystemExit("ChatViewModel speech setter anchor missing")
text = text.replace(old, new, 1)
write(path, text)

# Reply player uses reply-only voice, not media mode voice.
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterSpeechPlayer.kt"
text = read(path)
text = text.replace('import com.ayuemin.ymnik.data.OpenRouterFeaturePrefs\n', '')
text = text.replace('    private val featurePrefs = OpenRouterFeaturePrefs(appContext)\n', '')
one(path, '        val media = featurePrefs.media()\n        val chunks = splitForSpeech(text)', '        val replyVoice = appState.openRouterSpeechVoice.trim()\n        if (replyVoice.isBlank()) {\n            mutableState.value = OpenRouterSpeechPlaybackState(error = "Голос озвучивания ответов не выбран")\n            return\n        }\n        val chunks = splitForSpeech(text)')
# File was reread by one(); now replace both chunk synth calls.
text = read(path)
text = text.replace('voice = media.voice.takeIf { it.isNotBlank() },', 'voice = replyVoice,')
if text.count('voice = replyVoice,') != 2:
    raise SystemExit("OpenRouterSpeechPlayer: expected two reply voice uses")
write(path, text)

# Controller: general Speech model no longer changes reply Speech. Add reply-specific assignments.
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHubController.kt"
text = read(path)
old = '''            ModelCategory.SPEECH, ModelCategory.AUDIO -> {
                val media = mutableState.value.media.copy(speechModel = model.id)
                featurePrefs.saveMedia(media)
                viewModel.setOpenRouterSpeechModel(model.id)
                mutableState.value = mutableState.value.copy(media = media, status = "${model.id} назначена для озвучивания")
            }
'''
new = '''            ModelCategory.SPEECH, ModelCategory.AUDIO -> {
                val current = mutableState.value.media
                val media = current.copy(
                    speechModel = model.id,
                    voice = if (current.speechModel == model.id) current.voice else ""
                )
                featurePrefs.saveMedia(media)
                mutableState.value = mutableState.value.copy(media = media, status = "${model.id} назначена для озвучивания текста и документов")
            }
'''
if old not in text:
    raise SystemExit("Controller assign speech anchor missing")
text = text.replace(old, new, 1)
old = '''            ModelCategory.SPEECH, ModelCategory.AUDIO -> {
                val media = mutableState.value.media.copy(speechModel = "")
                featurePrefs.saveMedia(media)
                viewModel.setOpenRouterSpeechModel("")
                mutableState.value = mutableState.value.copy(media = media, status = "Модель озвучивания снята")
            }
'''
new = '''            ModelCategory.SPEECH, ModelCategory.AUDIO -> {
                val media = mutableState.value.media.copy(speechModel = "", voice = "")
                featurePrefs.saveMedia(media)
                mutableState.value = mutableState.value.copy(media = media, status = "Модель озвучивания текста и документов снята")
            }
'''
if old not in text:
    raise SystemExit("Controller clear speech anchor missing")
text = text.replace(old, new, 1)
insert = '''
    fun assignReplySpeechModel(model: ModelInfo) {
        if (ModelCategory.SPEECH !in model.categories && ModelCategory.AUDIO !in model.categories) {
            mutableState.value = mutableState.value.copy(status = "Эта модель не поддерживает озвучивание")
            return
        }
        viewModel.setOpenRouterSpeechModel(model.id)
        mutableState.value = mutableState.value.copy(status = "${model.id} назначена для озвучивания ответов")
    }

    fun updateReplySpeechVoice(voice: String) {
        viewModel.setOpenRouterSpeechVoice(voice)
        mutableState.value = mutableState.value.copy(status = if (voice.isBlank()) "Голос ответов снят" else "Голос ответов сохранён")
    }

'''
anchor = '    fun clearBatchModel() {\n'
if anchor not in text:
    raise SystemExit("Controller insertion anchor missing")
text = text.replace(anchor, insert + anchor, 1)
write(path, text)

# Hub: add a dedicated reply speech settings page.
path = "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHub.kt"
text = read(path)
text = text.replace('private enum class HubPage { MODELS, ROUTING, TOOLS, JOBS, MEDIA, SHELL }', 'private enum class HubPage { MODELS, ROUTING, TOOLS, JOBS, MEDIA, REPLY_SPEECH, SHELL }')
old = '''            "speech", "tts" -> {
                requestedPage = HubPage.MEDIA
                requestedMediaSection = MediaSection.SPEECH
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
'''
new = old + '''            "reply-speech" -> {
                requestedPage = HubPage.REPLY_SPEECH
                requestedMediaSection = MediaSection.ALL
                open = true
                AsyncJobEvents.consumeHubRequest()
            }
'''
if old not in text:
    raise SystemExit("Hub request speech anchor missing")
text = text.replace(old, new, 1)

old_title = '''                                Text(
                                    if (settingsMode) {
                                        "OpenRouter: модели и настройки"
                                    } else {
                                        when (page) {
                                            HubPage.JOBS -> "Пакетные и фоновые задачи"
                                            HubPage.MEDIA -> when (initialMediaSection) {
                                                MediaSection.VIDEO -> "Создание видео"
                                                MediaSection.TRANSCRIPTION -> "Распознавание речи"
                                                MediaSection.SPEECH -> "Озвучивание текста"
                                                MediaSection.ALL -> "Медиа"
                                            }
                                            HubPage.SHELL -> "OpenRouter Shell"
                                            else -> "OpenRouter"
                                        }
                                    },
'''
new_title = '''                                Text(
                                    if (page == HubPage.REPLY_SPEECH) {
                                        "Озвучивание ответов"
                                    } else if (settingsMode) {
                                        "OpenRouter: модели и настройки"
                                    } else {
                                        when (page) {
                                            HubPage.JOBS -> "Пакетные и фоновые задачи"
                                            HubPage.MEDIA -> when (initialMediaSection) {
                                                MediaSection.VIDEO -> "Создание видео"
                                                MediaSection.TRANSCRIPTION -> "Распознавание речи"
                                                MediaSection.SPEECH -> "Озвучивание текста и документов"
                                                MediaSection.ALL -> "Медиа"
                                            }
                                            HubPage.SHELL -> "OpenRouter Shell"
                                            else -> "OpenRouter"
                                        }
                                    },
'''
if old_title not in text:
    raise SystemExit("Hub title anchor missing")
text = text.replace(old_title, new_title, 1)
old_sub = '                                    if (settingsMode) "Каталог, маршрутизация и работа с документами" else "Результат возвращается в текущий чат",\n'
new_sub = '                                    if (page == HubPage.REPLY_SPEECH) "Отдельная модель и голос для кнопки OR" else if (settingsMode) "Каталог, маршрутизация и работа с документами" else "Результат возвращается в текущий чат",\n'
if old_sub not in text:
    raise SystemExit("Hub subtitle anchor missing")
text = text.replace(old_sub, new_sub, 1)
old_when = '''                        HubPage.JOBS -> JobsPage(state, controller)
                        HubPage.MEDIA -> MediaPage(state, controller, initialMediaSection)
                        HubPage.SHELL -> ShellPage(state, controller)
'''
new_when = '''                        HubPage.JOBS -> JobsPage(state, controller)
                        HubPage.MEDIA -> MediaPage(state, controller, initialMediaSection)
                        HubPage.REPLY_SPEECH -> ReplySpeechPage(state, appState, controller)
                        HubPage.SHELL -> ShellPage(state, controller)
'''
if old_when not in text:
    raise SystemExit("Hub page when anchor missing")
text = text.replace(old_when, new_when, 1)

reply_page = r'''
@Composable
private fun ReplySpeechPage(
    state: OpenRouterHubState,
    appState: UiState,
    controller: OpenRouterHubController
) {
    val selected = state.catalog.firstOrNull { it.id == appState.openRouterSpeechModel }
    val voiceOptions = selected?.parameterValues("voice").orEmpty()
    var manualVoice by remember(appState.openRouterSpeechModel, appState.openRouterSpeechVoice) {
        mutableStateOf(appState.openRouterSpeechVoice)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Кнопка OR под ответами", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Эти настройки не влияют на режим «+ → Озвучить». Для ответов можно выбрать отдельную, в том числе бесплатную, модель.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            CategoryModelPicker(
                title = "Модель озвучивания ответов",
                current = appState.openRouterSpeechModel,
                models = state.catalog.filter { ModelCategory.SPEECH in it.categories || ModelCategory.AUDIO in it.categories },
                onSelect = controller::assignReplySpeechModel
            )
        }
        if (appState.openRouterSpeechModel.isNotBlank()) {
            item {
                Text("Голос", fontWeight = FontWeight.SemiBold)
                Text(
                    "Голоса зависят от модели. При смене модели Umnik не переносит старый голос на новую.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (voiceOptions.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(voiceOptions) { voice ->
                            FilterChip(
                                selected = appState.openRouterSpeechVoice == voice,
                                onClick = { controller.updateReplySpeechVoice(voice) },
                                label = { Text(voice, maxLines = 1) }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = manualVoice,
                    onValueChange = { manualVoice = it },
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
                    label = { Text("ID голоса") },
                    placeholder = { Text("Например: alloy, eve, en_paul_neutral") },
                    singleLine = true
                )
                FilledTonalButton(
                    onClick = { controller.updateReplySpeechVoice(manualVoice.trim()) },
                    enabled = manualVoice.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
                ) { Text("Сохранить голос") }
                if (appState.openRouterSpeechVoice.isNotBlank()) {
                    TextButton(
                        onClick = {
                            manualVoice = ""
                            controller.updateReplySpeechVoice("")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Снять выбор голоса") }
                }
            }
        }
    }
}

'''
anchor = '@Composable\nprivate fun ShellPage('
if anchor not in text:
    raise SystemExit("Hub reply page insertion anchor missing")
text = text.replace(anchor, reply_page + anchor, 1)
write(path, text)

# Settings: two separate entries instead of one coupled entry.
path = "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
text = read(path)
text = text.replace('    var openRouterSpeechExpanded by remember { mutableStateOf(false) }\n', '    var openRouterSpeechExpanded by remember { mutableStateOf(false) }\n    var openRouterDocumentSpeechExpanded by remember { mutableStateOf(false) }\n', 1)

old_card = '''            item {
                ExpandableSettingsCard(
                    title = "Озвучивание OpenRouter",
                    subtitle = state.openRouterSpeechModel.substringAfterLast('/').ifBlank { "Модель не выбрана" },
                    icon = Icons.Outlined.VolumeUp,
                    expanded = openRouterSpeechExpanded,
                    onToggle = { openRouterSpeechExpanded = !openRouterSpeechExpanded }
                ) {
                    Text(
                        "Эта модель используется кнопкой OR под ответами. Если модель не выбрана, кнопка остаётся неактивной.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("speech") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Выбрать модель озвучивания", fontWeight = FontWeight.Medium)
                            Text(
                                state.openRouterSpeechModel.ifBlank { "Не выбрана" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
'''
new_cards = '''            item {
                ExpandableSettingsCard(
                    title = "Озвучивание ответов OpenRouter",
                    subtitle = when {
                        state.openRouterSpeechModel.isBlank() -> "Модель не выбрана"
                        state.openRouterSpeechVoice.isBlank() -> "${state.openRouterSpeechModel.substringAfterLast('/')} · голос не выбран"
                        else -> "${state.openRouterSpeechModel.substringAfterLast('/')} · ${state.openRouterSpeechVoice}"
                    },
                    icon = Icons.Outlined.VolumeUp,
                    expanded = openRouterSpeechExpanded,
                    onToggle = { openRouterSpeechExpanded = !openRouterSpeechExpanded }
                ) {
                    Text(
                        "Используется только кнопкой OR под ответами.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("reply-speech") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Настроить модель и голос")
                    }
                }
            }

            item {
                ExpandableSettingsCard(
                    title = "Озвучивание текста и документов",
                    subtitle = "Отдельная модель и голос",
                    icon = Icons.Outlined.Description,
                    expanded = openRouterDocumentSpeechExpanded,
                    onToggle = { openRouterDocumentSpeechExpanded = !openRouterDocumentSpeechExpanded }
                ) {
                    Text(
                        "Используется режимом «+ → Озвучить» и не меняет озвучивание ответов.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { com.ayuemin.ymnik.AsyncJobEvents.requestHub("speech") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Открыть настройки озвучивания")
                    }
                }
            }
'''
if old_card not in text:
    raise SystemExit("YmnikApp speech settings card anchor missing")
text = text.replace(old_card, new_cards, 1)
text = text.replace('openRouterSpeechEnabled = state.openRouterSpeechModel.isNotBlank(),', 'openRouterSpeechEnabled = state.openRouterSpeechModel.isNotBlank() && state.openRouterSpeechVoice.isNotBlank(),', 1)
write(path, text)

# Guide: explain independent speech modes and voices.
path = "app/src/main/java/com/ayuemin/ymnik/help/UmnikUsageGuide.kt"
text = read(path)
old = '''## Озвучить
Эта функция делает аудиофайл из текста.

1. Назначьте модель **для озвучивания**.
2. Нажмите **+ → Озвучить**.
3. Введите текст или нажмите **Загрузить текстовый файл**.
4. Нажмите **Создать аудио**.

Готовую запись можно слушать прямо из сообщения чата. Скачивать её заранее не требуется.
'''
new = '''## Озвучить текст или документ
Режим **+ → Озвучить** делает отдельную аудиозапись из введённого текста или текстового файла. Для него используются **свои модель и голос**.

1. Нажмите **+ → Озвучить**.
2. Выберите Speech/Audio-модель.
3. Укажите совместимый с этой моделью **Voice / ID голоса**.
4. Введите текст или нажмите **Загрузить текстовый файл**.
5. Нажмите **Создать аудио**.

## Озвучить ответ кнопкой OR
Кнопка **OR** под ответом работает отдельно от режима выше. Она нужна для быстрого прослушивания ответа и не добавляет аудиофайл в чат.

Откройте **Настройки → Озвучивание ответов OpenRouter** и выберите отдельную модель и голос. Поэтому, например, ответы можно слушать бесплатной моделью, а большой документ озвучивать другой платной моделью.

**Важно:** у разных TTS-моделей разные голоса. После смены модели проверьте голос. Если модель или голос для ответов не выбраны, кнопка OR остаётся неактивной.
'''
if old not in text:
    raise SystemExit("Guide speech section anchor missing")
text = text.replace(old, new, 1)
write(path, text)

print("v1.10.6 patch applied")
