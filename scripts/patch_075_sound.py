from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"anchor not found: {label}")
    return text.replace(old, new, 1)

# Models.kt
p = Path('app/src/main/java/com/ayuemin/ymnik/model/Models.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(s, '''enum class AnswerSoundChoice {
    DEFAULT,
    SOFT,
    BRIGHT,
    DOUBLE
}''', '''enum class AnswerSoundChoice {
    DEFAULT,
    CUSTOM,
    SOFT,
    BRIGHT,
    DOUBLE
}''', 'sound custom enum')
s = replace_once(s, '''data class StorageStats(
    val generatedBytes: Long = 0L,
    val exportBytes: Long = 0L,
    val skillBytes: Long = 0L,
    val projectBytes: Long = 0L,
    val chatBytes: Long = 0L
) {
    val totalBytes: Long
        get() = generatedBytes + exportBytes + skillBytes + projectBytes + chatBytes
}''', '''data class StorageStats(
    val generatedBytes: Long = 0L,
    val exportBytes: Long = 0L,
    val skillBytes: Long = 0L,
    val projectBytes: Long = 0L,
    val chatBytes: Long = 0L,
    val soundBytes: Long = 0L
) {
    val totalBytes: Long
        get() = generatedBytes + exportBytes + skillBytes + projectBytes + chatBytes + soundBytes
}''', 'storage sound bytes')
s = replace_once(s, '''    val answerSoundChoice: AnswerSoundChoice = AnswerSoundChoice.DEFAULT,
    val answerSoundVolume: Int = 28,
    val themeChoice: ThemeChoice = ThemeChoice.DYNAMIC,''', '''    val answerSoundChoice: AnswerSoundChoice = AnswerSoundChoice.DEFAULT,
    val answerSoundVolume: Int = 28,
    val answerSoundCustomPath: String? = null,
    val answerSoundCustomName: String? = null,
    val themeChoice: ThemeChoice = ThemeChoice.DYNAMIC,''', 'sound state fields')
p.write_text(s, encoding='utf-8')

# StorageRepository.kt
p = Path('app/src/main/java/com/ayuemin/ymnik/data/StorageRepository.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(s, '''    private val chatFilesRoot = File(context.filesDir, "chat_files").apply { mkdirs() }
    private val chatsFile = File(File(context.filesDir, "chats"), "chats.json")''', '''    private val chatFilesRoot = File(context.filesDir, "chat_files").apply { mkdirs() }
    private val soundsRoot = File(context.filesDir, "sounds").apply { mkdirs() }
    private val chatsFile = File(File(context.filesDir, "chats"), "chats.json")''', 'sounds root')
s = replace_once(s, '''        collect(exportsRoot, "Экспорт", true, items)
        collect(skillsRoot, "Навыки", false, items, skipName = "skills.json")''', '''        collect(exportsRoot, "Экспорт", true, items)
        collect(soundsRoot, "Звуки", true, items)
        collect(skillsRoot, "Навыки", false, items, skipName = "skills.json")''', 'collect sounds')
s = replace_once(s, '''        projectBytes = sizeOf(projectsRoot),
        chatBytes = (if (chatsFile.exists()) chatsFile.length() else 0L) + sizeOf(chatFilesRoot)
    )''', '''        projectBytes = sizeOf(projectsRoot),
        chatBytes = (if (chatsFile.exists()) chatsFile.length() else 0L) + sizeOf(chatFilesRoot),
        soundBytes = sizeOf(soundsRoot)
    )''', 'sound stats')
s = replace_once(s, '''        if (!isInside(target, generatedRoot) && !isInside(target, exportsRoot)) return false''', '''        if (!isInside(target, generatedRoot) && !isInside(target, exportsRoot) && !isInside(target, soundsRoot)) return false''', 'delete sounds')
s = replace_once(s, '''        "yaml", "yml" -> "application/yaml"
        else -> "application/octet-stream"''', '''        "yaml", "yml" -> "application/yaml"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"''', 'sound mimes')
p.write_text(s, encoding='utf-8')

# ChatViewModel.kt
p = Path('app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(s, '''import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri''', '''import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.provider.OpenableColumns''', 'media imports')
s = replace_once(s, '''            answerSoundChoice = runCatching {
                AnswerSoundChoice.valueOf(
                    prefs.getString("answer_sound_choice", AnswerSoundChoice.DEFAULT.name)
                        ?: AnswerSoundChoice.DEFAULT.name
                )
            }.getOrDefault(AnswerSoundChoice.DEFAULT),
            answerSoundVolume = prefs.getInt("answer_sound_volume", 28).coerceIn(0, 100),''', '''            answerSoundChoice = runCatching {
                AnswerSoundChoice.valueOf(
                    prefs.getString("answer_sound_choice", AnswerSoundChoice.DEFAULT.name)
                        ?: AnswerSoundChoice.DEFAULT.name
                )
            }.getOrDefault(AnswerSoundChoice.DEFAULT).let {
                if (it == AnswerSoundChoice.CUSTOM) it else AnswerSoundChoice.DEFAULT
            },
            answerSoundVolume = prefs.getInt("answer_sound_volume", 28).coerceIn(0, 100),
            answerSoundCustomPath = prefs.getString("answer_sound_custom_path", null),
            answerSoundCustomName = prefs.getString("answer_sound_custom_name", null),''', 'sound init')
s = replace_once(s, '''    fun setAnswerSoundChoice(choice: AnswerSoundChoice) {
        prefs.edit().putString("answer_sound_choice", choice.name).apply()
        _state.value = _state.value.copy(answerSoundChoice = choice)
        playReadySound()
    }

    fun setAnswerSoundVolume(volume: Int) {''', '''    fun setAnswerSoundChoice(choice: AnswerSoundChoice) {
        val normalized = if (choice == AnswerSoundChoice.CUSTOM) choice else AnswerSoundChoice.DEFAULT
        prefs.edit().putString("answer_sound_choice", normalized.name).apply()
        _state.value = _state.value.copy(answerSoundChoice = normalized)
        playReadySound()
    }

    fun selectAnswerSound(file: StoredFile) {
        if (file.category != "Звуки" || !File(file.localPath).isFile) return
        prefs.edit()
            .putString("answer_sound_choice", AnswerSoundChoice.CUSTOM.name)
            .putString("answer_sound_custom_path", file.localPath)
            .putString("answer_sound_custom_name", file.name)
            .apply()
        _state.value = _state.value.copy(
            answerSoundChoice = AnswerSoundChoice.CUSTOM,
            answerSoundCustomPath = file.localPath,
            answerSoundCustomName = file.name
        )
        playReadySound()
    }

    fun importAnswerSound(uri: Uri) {
        runCatching {
            val resolver = context.contentResolver
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }.orEmpty().ifBlank { "sound_${System.currentTimeMillis()}" }
            val safeName = displayName
                .replace(Regex("[^\\p{L}\\p{N}._ ()-]"), "_")
                .take(120)
                .ifBlank { "sound_${System.currentTimeMillis()}" }
            val dir = File(context.filesDir, "sounds").apply { mkdirs() }
            val target = File(dir, "${UUID.randomUUID()}_$safeName")
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > 20L * 1024L * 1024L) throw IllegalArgumentException("Звуковой файл больше 20 МБ")
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IllegalArgumentException("Не удалось прочитать выбранный звук")
            if (target.length() == 0L) {
                target.delete()
                throw IllegalArgumentException("Выбран пустой звуковой файл")
            }
            target
        }.onSuccess { target ->
            val displayName = target.name.substringAfter('_', target.name)
            prefs.edit()
                .putString("answer_sound_choice", AnswerSoundChoice.CUSTOM.name)
                .putString("answer_sound_custom_path", target.absolutePath)
                .putString("answer_sound_custom_name", displayName)
                .apply()
            _state.value = _state.value.copy(
                answerSoundChoice = AnswerSoundChoice.CUSTOM,
                answerSoundCustomPath = target.absolutePath,
                answerSoundCustomName = displayName,
                storedFiles = storageRepository.list(),
                storageStats = storageRepository.stats(),
                status = "Звук «$displayName» сохранён в Umnik"
            )
            playReadySound()
        }.onFailure {
            _state.value = _state.value.copy(status = it.message ?: "Не удалось добавить звук")
        }
    }

    fun setAnswerSoundVolume(volume: Int) {''', 'sound import methods')
s = replace_once(s, '''        if (!storageRepository.delete(file.localPath)) {
            _state.value = _state.value.copy(status = "Не удалось удалить файл")
            return
        }
        removeFileReferences(setOf(file.localPath))
        _state.value = _state.value.copy(
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            status = "Файл удалён"
        )''', '''        if (!storageRepository.delete(file.localPath)) {
            _state.value = _state.value.copy(status = "Не удалось удалить файл")
            return
        }
        removeFileReferences(setOf(file.localPath))
        val removedSelectedSound = file.localPath == _state.value.answerSoundCustomPath
        if (removedSelectedSound) {
            prefs.edit()
                .putString("answer_sound_choice", AnswerSoundChoice.DEFAULT.name)
                .remove("answer_sound_custom_path")
                .remove("answer_sound_custom_name")
                .apply()
        }
        _state.value = _state.value.copy(
            storedFiles = storageRepository.list(),
            storageStats = storageRepository.stats(),
            answerSoundChoice = if (removedSelectedSound) AnswerSoundChoice.DEFAULT else _state.value.answerSoundChoice,
            answerSoundCustomPath = if (removedSelectedSound) null else _state.value.answerSoundCustomPath,
            answerSoundCustomName = if (removedSelectedSound) null else _state.value.answerSoundCustomName,
            status = if (removedSelectedSound) "Звук удалён. Выбран основной сигнал" else "Файл удалён"
        )''', 'delete selected sound')
old_sound = '''    private fun playReadySound() {
        val state = _state.value
        if (!state.answerSoundEnabled) return
        runCatching {
            val tone = ToneGenerator(
                AudioManager.STREAM_NOTIFICATION,
                state.answerSoundVolume.coerceIn(0, 100)
            )
            val handler = Handler(Looper.getMainLooper())
            when (state.answerSoundChoice) {
                AnswerSoundChoice.DEFAULT -> {
                    tone.startTone(ToneGenerator.TONE_PROP_ACK, 90)
                    handler.postDelayed({ runCatching { tone.release() } }, 180)
                }
                AnswerSoundChoice.SOFT -> {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 70)
                    handler.postDelayed({ runCatching { tone.release() } }, 160)
                }
                AnswerSoundChoice.BRIGHT -> {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 90)
                    handler.postDelayed({ runCatching { tone.release() } }, 180)
                }
                AnswerSoundChoice.DOUBLE -> {
                    tone.startTone(ToneGenerator.TONE_PROP_ACK, 55)
                    handler.postDelayed({ runCatching { tone.startTone(ToneGenerator.TONE_PROP_ACK, 55) } }, 105)
                    handler.postDelayed({ runCatching { tone.release() } }, 260)
                }
            }
        }
    }'''
new_sound = '''    private fun playReadySound() {
        val state = _state.value
        if (!state.answerSoundEnabled) return

        if (state.answerSoundChoice == AnswerSoundChoice.CUSTOM) {
            val custom = state.answerSoundCustomPath?.let(::File)
            if (custom?.isFile == true) {
                runCatching {
                    val volume = state.answerSoundVolume.coerceIn(0, 100) / 100f
                    val player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        setDataSource(custom.absolutePath)
                        setVolume(volume, volume)
                        setOnPreparedListener { it.start() }
                        setOnCompletionListener { it.release() }
                        setOnErrorListener { mp, _, _ -> mp.release(); true }
                        prepareAsync()
                    }
                    return
                }
            }
        }

        runCatching {
            val tone = ToneGenerator(
                AudioManager.STREAM_NOTIFICATION,
                state.answerSoundVolume.coerceIn(0, 100)
            )
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 90)
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { tone.release() } }, 180)
        }
    }'''
s = replace_once(s, old_sound, new_sound, 'play imported sound')
p.write_text(s, encoding='utf-8')

# YmnikApp.kt
p = Path('app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(s, 'import com.ayuemin.ymnik.ChatViewModel\n', 'import com.ayuemin.ymnik.BuildConfig\nimport com.ayuemin.ymnik.ChatViewModel\n', 'BuildConfig import')
s = replace_once(s, '''    var reasoningExpanded by remember { mutableStateOf(false) }
    var profileExpanded by remember { mutableStateOf(false) }''', '''    var reasoningExpanded by remember { mutableStateOf(false) }
    var soundExpanded by remember { mutableStateOf(false) }
    var profileExpanded by remember { mutableStateOf(false) }''', 'sound expanded state')
s = replace_once(s, '''    val themes = ThemeChoice.entries
    val profileScopes = UserProfileScope.entries

    Column(Modifier.fillMaxSize()) {''', '''    val themes = ThemeChoice.entries
    val profileScopes = UserProfileScope.entries
    val importedSounds = state.storedFiles.filter { it.category == "Звуки" }
    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importAnswerSound)
    }

    Column(Modifier.fillMaxSize()) {''', 'sound picker')
old_card = '''            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Звук готового ответа", fontWeight = FontWeight.Medium)
                                Text(
                                    "Сигнал после завершения ответа модели",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = state.answerSoundEnabled, onCheckedChange = vm::setAnswerSoundEnabled)
                        }
                        if (state.answerSoundEnabled) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AnswerSoundChoice.entries.forEach { choice ->
                                    FilterChip(
                                        selected = state.answerSoundChoice == choice,
                                        onClick = { vm.setAnswerSoundChoice(choice) },
                                        label = { Text(answerSoundLabel(choice)) },
                                        leadingIcon = if (state.answerSoundChoice == choice) {
                                            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Громкость", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text("${state.answerSoundVolume}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Slider(
                                value = state.answerSoundVolume.toFloat(),
                                onValueChange = { vm.setAnswerSoundVolume(it.toInt()) },
                                valueRange = 0f..100f
                            )
                        }
                    }
                }
            }'''
new_card = '''            item {
                ExpandableSettingsCard(
                    title = "Звук готового ответа",
                    subtitle = when {
                        !state.answerSoundEnabled -> "Выключен"
                        state.answerSoundChoice == AnswerSoundChoice.CUSTOM -> state.answerSoundCustomName ?: "Свой звук"
                        else -> "Основной сигнал"
                    },
                    icon = Icons.Outlined.VolumeUp,
                    expanded = soundExpanded,
                    onToggle = { soundExpanded = !soundExpanded }
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Уведомлять после ответа", modifier = Modifier.weight(1f))
                        Switch(checked = state.answerSoundEnabled, onCheckedChange = vm::setAnswerSoundEnabled)
                    }
                    if (state.answerSoundEnabled) {
                        Spacer(Modifier.height(10.dp))
                        FilterChip(
                            selected = state.answerSoundChoice == AnswerSoundChoice.DEFAULT,
                            onClick = { vm.setAnswerSoundChoice(AnswerSoundChoice.DEFAULT) },
                            label = { Text("Основной") },
                            leadingIcon = if (state.answerSoundChoice == AnswerSoundChoice.DEFAULT) {
                                { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                        if (importedSounds.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(importedSounds, key = { it.id }) { sound ->
                                    FilterChip(
                                        selected = state.answerSoundCustomPath == sound.localPath && state.answerSoundChoice == AnswerSoundChoice.CUSTOM,
                                        onClick = { vm.selectAnswerSound(sound) },
                                        label = { Text(sound.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = if (state.answerSoundCustomPath == sound.localPath && state.answerSoundChoice == AnswerSoundChoice.CUSTOM) {
                                            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = { soundPicker.launch(arrayOf("audio/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("Добавить звук с телефона")
                        }
                        Text(
                            "Выбранный файл копируется в память Umnik и остаётся доступным, пока вы не удалите его в «Хранилище Umnik».",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 7.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Громкость", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("${state.answerSoundVolume}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(
                            value = state.answerSoundVolume.toFloat(),
                            onValueChange = { vm.setAnswerSoundVolume(it.toInt()) },
                            valueRange = 0f..100f
                        )
                    }
                }
            }'''
s = replace_once(s, old_card, new_card, 'sound settings collapsible')
# App version at bottom after API key card.
anchor = '''            item {
                ExpandableSettingsCard(
                    title = "API-ключ OpenRouter",'''
idx = s.find(anchor)
if idx < 0:
    raise SystemExit('api card anchor not found')
end_marker = '''            }
        }
    }

    if (storageOpen)'''
end_idx = s.find(end_marker, idx)
if end_idx < 0:
    raise SystemExit('settings end anchor not found')
insert = '''            }

            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Umnik", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Версия ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
'''
s = s[:end_idx] + insert + s[end_idx + len('            }\n'):]
# Keep helper exhaustive though old tones are no longer shown in UI.
s = replace_once(s, '''private fun answerSoundLabel(choice: AnswerSoundChoice): String = when (choice) {
    AnswerSoundChoice.DEFAULT -> "Основной"
    AnswerSoundChoice.SOFT -> "Мягкий"
    AnswerSoundChoice.BRIGHT -> "Ясный"
    AnswerSoundChoice.DOUBLE -> "Двойной"
}''', '''private fun answerSoundLabel(choice: AnswerSoundChoice): String = when (choice) {
    AnswerSoundChoice.DEFAULT -> "Основной"
    AnswerSoundChoice.CUSTOM -> "Свой звук"
    AnswerSoundChoice.SOFT -> "Мягкий"
    AnswerSoundChoice.BRIGHT -> "Ясный"
    AnswerSoundChoice.DOUBLE -> "Двойной"
}''', 'sound label custom')
p.write_text(s, encoding='utf-8')

# build.gradle.kts
p = Path('app/build.gradle.kts')
s = p.read_text(encoding='utf-8')
s = s.replace('// Umnik v0.7.4', '// Umnik v0.7.5', 1)
s = s.replace('versionCode = 21', 'versionCode = 22', 1)
s = s.replace('versionName = "0.7.4"', 'versionName = "0.7.5"', 1)
p.write_text(s, encoding='utf-8')

# CHANGELOG.md
p = Path('CHANGELOG.md')
s = p.read_text(encoding='utf-8')
entry = '''## v0.7.5 - 2026-09-11

- Звук готового ответа теперь можно выбрать из аудиофайлов телефона; файл копируется в постоянное хранилище Umnik.
- Импортированные звуки отображаются в «Хранилище Umnik» как обычные удаляемые файлы категории «Звуки»; при удалении активного звука приложение автоматически возвращается к основному сигналу.
- Настройка звука вместе с выбором и громкостью стала сворачиваемой.
- Внизу настроек добавлена краткая информация о приложении и текущей версии.

'''
s = replace_once(s, '## Unreleased\n\n', '## Unreleased\n\n' + entry, 'changelog')
p.write_text(s, encoding='utf-8')
