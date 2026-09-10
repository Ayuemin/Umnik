from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"anchor not found: {label}")
    return text.replace(old, new, 1)

# Models.kt
p = Path('app/src/main/java/com/ayuemin/ymnik/model/Models.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(s, '''enum class ReasoningEffort(val apiValue: String) {
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh")
}

enum class ThemeChoice {''', '''enum class ReasoningEffort(val apiValue: String) {
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh")
}

enum class AnswerSoundChoice {
    DEFAULT,
    SOFT,
    BRIGHT,
    DOUBLE
}

enum class ThemeChoice {''', 'sound enum')
s = replace_once(s, '''    val answerSoundEnabled: Boolean = true,
    val themeChoice: ThemeChoice = ThemeChoice.DYNAMIC,''', '''    val answerSoundEnabled: Boolean = true,
    val answerSoundChoice: AnswerSoundChoice = AnswerSoundChoice.DEFAULT,
    val answerSoundVolume: Int = 28,
    val themeChoice: ThemeChoice = ThemeChoice.DYNAMIC,''', 'sound state')
p.write_text(s, encoding='utf-8')

# ChatViewModel.kt
p = Path('app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(s, 'import com.ayuemin.ymnik.model.ChatFile\n', 'import com.ayuemin.ymnik.model.AnswerSoundChoice\nimport com.ayuemin.ymnik.model.ChatFile\n', 'vm sound import')
s = replace_once(s, '''            answerSoundEnabled = prefs.getBoolean("answer_sound", true),
            themeChoice = runCatching {''', '''            answerSoundEnabled = prefs.getBoolean("answer_sound", true),
            answerSoundChoice = runCatching {
                AnswerSoundChoice.valueOf(
                    prefs.getString("answer_sound_choice", AnswerSoundChoice.DEFAULT.name)
                        ?: AnswerSoundChoice.DEFAULT.name
                )
            }.getOrDefault(AnswerSoundChoice.DEFAULT),
            answerSoundVolume = prefs.getInt("answer_sound_volume", 28).coerceIn(0, 100),
            themeChoice = runCatching {''', 'vm sound init')
s = replace_once(s, '''    fun setAnswerSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("answer_sound", enabled).apply()
        _state.value = _state.value.copy(answerSoundEnabled = enabled)
        if (enabled) playReadySound()
    }

    fun setThemeChoice(choice: ThemeChoice) {''', '''    fun setAnswerSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("answer_sound", enabled).apply()
        _state.value = _state.value.copy(answerSoundEnabled = enabled)
        if (enabled) playReadySound()
    }

    fun setAnswerSoundChoice(choice: AnswerSoundChoice) {
        prefs.edit().putString("answer_sound_choice", choice.name).apply()
        _state.value = _state.value.copy(answerSoundChoice = choice)
        playReadySound()
    }

    fun setAnswerSoundVolume(volume: Int) {
        val clean = volume.coerceIn(0, 100)
        prefs.edit().putInt("answer_sound_volume", clean).apply()
        _state.value = _state.value.copy(answerSoundVolume = clean)
    }

    fun setThemeChoice(choice: ThemeChoice) {''', 'vm sound setters')
old_sound = '''    private fun playReadySound() {
        if (!_state.value.answerSoundEnabled) return
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 28)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 90)
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { tone.release() }
            }, 180)
        }
    }'''
new_sound = '''    private fun playReadySound() {
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
s = replace_once(s, old_sound, new_sound, 'play ready sound')
p.write_text(s, encoding='utf-8')

# YmnikApp.kt
p = Path('app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt')
s = p.read_text(encoding='utf-8')
s = replace_once(s, 'import androidx.compose.foundation.Image as ComposeImage\n', 'import androidx.compose.foundation.Image as ComposeImage\nimport androidx.compose.foundation.horizontalScroll\nimport androidx.compose.foundation.rememberScrollState\n', 'scroll imports')
s = replace_once(s, 'import androidx.compose.material3.Scaffold\n', 'import androidx.compose.material3.Scaffold\nimport androidx.compose.material3.Slider\n', 'slider import')
s = replace_once(s, 'import com.ayuemin.ymnik.model.ChatMessage\n', 'import com.ayuemin.ymnik.model.AnswerSoundChoice\nimport com.ayuemin.ymnik.model.ChatMessage\n', 'ui sound import')

s = replace_once(s, '''    val currentChatFiles = state.chats.firstOrNull { it.id == state.currentChatId }?.chatFiles.orEmpty()
''', '''    val currentChat = state.chats.firstOrNull { it.id == state.currentChatId }
    val currentChatFiles = currentChat?.chatFiles.orEmpty()
    val currentProjectSkillIds = currentChat?.projectId
        ?.let { projectId -> state.projects.firstOrNull { it.id == projectId }?.skillIds }
        .orEmpty()
    val activeSkillCount = (state.activeSkillIds + currentProjectSkillIds).size
''', 'composer active skills')

old_leading = '''                leadingIcon = {
                    IconButton(
                        onClick = { actionsOpen = true },
                        enabled = !state.isLoading
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "Добавить и инструменты",
                            tint = if (state.webSearchEnabled || state.reasoningEnabled || state.mode == ChatMode.IMAGE) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                },'''
new_leading = '''                leadingIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        IconButton(
                            onClick = { actionsOpen = true },
                            enabled = !state.isLoading,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = "Добавить и инструменты",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (activeSkillCount > 0) {
                            ComposerInlineIndicator(
                                icon = Icons.Outlined.Extension,
                                description = "Активные навыки: $activeSkillCount",
                                count = activeSkillCount
                            )
                        }
                        if (state.reasoningEnabled) {
                            ComposerInlineIndicator(
                                icon = Icons.Outlined.Psychology,
                                description = "Размышление включено"
                            )
                        }
                        if (state.webSearchEnabled) {
                            ComposerInlineIndicator(
                                icon = Icons.Outlined.Language,
                                description = "Поиск в сети включён"
                            )
                        }
                    }
                },'''
s = replace_once(s, old_leading, new_leading, 'composer indicators')

# Compact action buttons under messages.
old_user_actions = '''                IconButton(onClick = { copyText(context, message.text) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Копировать своё сообщение")
                }
                if (onRetry != null) {
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Спросить ещё раз")
                    }
                }'''
new_user_actions = '''                CompactMessageAction(
                    icon = Icons.Outlined.ContentCopy,
                    description = "Копировать своё сообщение",
                    onClick = { copyText(context, message.text) }
                )
                if (onRetry != null) {
                    CompactMessageAction(
                        icon = Icons.Outlined.Refresh,
                        description = "Спросить ещё раз",
                        onClick = onRetry
                    )
                }'''
s = replace_once(s, old_user_actions, new_user_actions, 'user message actions')
old_assistant_actions = '''                IconButton(onClick = { copyText(context, message.text) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "Копировать ответ")
                }
                IconButton(onClick = { shareText(context, message.text) }) {
                    Icon(Icons.Outlined.Share, contentDescription = "Поделиться")
                }
                if (message.text.isNotBlank()) {
                    IconButton(onClick = { tts.toggle(message.id, message.text) }) {
                        Icon(
                            if (tts.speakingMessageId == message.id) Icons.Outlined.StopCircle else Icons.Outlined.VolumeUp,
                            contentDescription = if (tts.speakingMessageId == message.id) "Остановить озвучку" else "Озвучить"
                        )
                    }
                    IconButton(onClick = onExportText) {
                        Icon(Icons.Outlined.Download, contentDescription = "Сохранить ответ файлом")
                    }
                }'''
new_assistant_actions = '''                CompactMessageAction(
                    icon = Icons.Outlined.ContentCopy,
                    description = "Копировать ответ",
                    onClick = { copyText(context, message.text) }
                )
                CompactMessageAction(
                    icon = Icons.Outlined.Share,
                    description = "Поделиться",
                    onClick = { shareText(context, message.text) }
                )
                if (message.text.isNotBlank()) {
                    CompactMessageAction(
                        icon = if (tts.speakingMessageId == message.id) Icons.Outlined.StopCircle else Icons.Outlined.VolumeUp,
                        description = if (tts.speakingMessageId == message.id) "Остановить озвучку" else "Озвучить",
                        active = tts.speakingMessageId == message.id,
                        onClick = { tts.toggle(message.id, message.text) }
                    )
                    CompactMessageAction(
                        icon = Icons.Outlined.Download,
                        description = "Сохранить ответ файлом",
                        onClick = onExportText
                    )
                }'''
s = replace_once(s, old_assistant_actions, new_assistant_actions, 'assistant message actions')

# Skill button wording.
s = replace_once(s, '''label = { Text(if (skill.id in state.activeSkillIds) "Подключён" else "Подключить") },''', '''label = {
                                    Text(
                                        if (skill.id in state.activeSkillIds)
                                            "Подключён к каждому чату"
                                        else
                                            "Подключить к каждому чату"
                                    )
                                },''', 'skill global wording')

# Sound settings card.
old_sound_card = '''            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Звук готового ответа", fontWeight = FontWeight.Medium)
                            Text(
                                "Короткий сигнал после ответа модели",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = state.answerSoundEnabled, onCheckedChange = vm::setAnswerSoundEnabled)
                    }
                }
            }'''
new_sound_card = '''            item {
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
s = replace_once(s, old_sound_card, new_sound_card, 'sound settings card')

# Replace MarkdownText implementation with table-aware version.
start = s.find('@Composable\nprivate fun MarkdownText(')
end = s.find('private fun markdownInline(', start)
if start < 0 or end < 0:
    raise SystemExit('markdown function anchors not found')
new_markdown = r'''@Composable
private fun MarkdownText(text: String, color: androidx.compose.ui.graphics.Color) {
    val lines = remember(text) { text.replace("\r\n", "\n").split("\n") }
    val paragraph = mutableListOf<String>()

    @Composable
    fun paragraphBlock(raw: String) {
        if (raw.isBlank()) return
        SelectionContainer {
            Text(
                text = markdownInline(raw),
                color = color,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    @Composable
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            paragraphBlock(paragraph.joinToString("\n").trim())
            paragraph.clear()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var index = 0
        while (index < lines.size) {
            val rawLine = lines[index]
            val line = rawLine.trimEnd()
            val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line.trimStart())
            val unordered = Regex("^\\s*[-+*]\\s+(.+)$").matchEntire(line)
            val ordered = Regex("^\\s*(\\d+)[.)]\\s+(.+)$").matchEntire(line)
            val quote = Regex("^\\s*>\\s?(.*)$").matchEntire(line)
            val horizontalRule = Regex("^\\s*((-{3,})|(\\*{3,})|(_{3,}))\\s*$").matches(line)
            val possibleHeader = markdownTableCells(line)
            val tableStart = possibleHeader.size >= 2 && index + 1 < lines.size &&
                isMarkdownTableSeparator(lines[index + 1], possibleHeader.size)

            when {
                tableStart -> {
                    flushParagraph()
                    val rows = mutableListOf(possibleHeader)
                    index += 2 // skip header separator
                    while (index < lines.size) {
                        val cells = markdownTableCells(lines[index])
                        if (cells.size != possibleHeader.size) break
                        rows += cells
                        index++
                    }
                    MarkdownTable(rows = rows, color = color)
                    continue
                }
                line.isBlank() -> flushParagraph()
                heading != null -> {
                    flushParagraph()
                    val level = heading.groupValues[1].length
                    val style = when (level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    SelectionContainer {
                        Text(
                            text = markdownInline(heading.groupValues[2]),
                            color = color,
                            style = style,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                horizontalRule -> {
                    flushParagraph()
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                }
                unordered != null -> {
                    flushParagraph()
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text("•", color = color, modifier = Modifier.width(20.dp))
                        SelectionContainer {
                            Text(
                                markdownInline(unordered.groupValues[1]),
                                color = color,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                ordered != null -> {
                    flushParagraph()
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text("${ordered.groupValues[1]}.", color = color, modifier = Modifier.width(30.dp))
                        SelectionContainer {
                            Text(
                                markdownInline(ordered.groupValues[2]),
                                color = color,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                quote != null -> {
                    flushParagraph()
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text("│", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            SelectionContainer {
                                Text(
                                    markdownInline(quote.groupValues[1]),
                                    color = color,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
                else -> paragraph += line
            }
            index++
        }
        flushParagraph()
    }
}

private fun markdownTableCells(line: String): List<String> {
    val trimmed = line.trim()
    if (!trimmed.contains('|')) return emptyList()
    val body = trimmed.removePrefix("|").removeSuffix("|")
    val cells = body.split('|').map { it.trim() }
    return if (cells.size >= 2) cells else emptyList()
}

private fun isMarkdownTableSeparator(line: String, columns: Int): Boolean {
    val cells = markdownTableCells(line)
    if (cells.size != columns) return false
    return cells.all { Regex("^:?-{3,}:?$").matches(it.replace(" ", "")) }
}

@Composable
private fun MarkdownTable(rows: List<List<String>>, color: androidx.compose.ui.graphics.Color) {
    if (rows.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
        ) {
            rows.forEachIndexed { rowIndex, row ->
                Row {
                    row.forEach { cell ->
                        SelectionContainer {
                            Text(
                                text = markdownInline(cell),
                                modifier = Modifier.width(160.dp).padding(horizontal = 9.dp, vertical = 8.dp),
                                color = color,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
                if (rowIndex < rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = if (rowIndex == 0) 0.45f else 0.22f))
                }
            }
        }
    }
}

'''
s = s[:start] + new_markdown + s[end:]

# Insert compact helper functions before WorkingStopIcon.
anchor = '@Composable\nprivate fun WorkingStopIcon() {'
helpers = '''@Composable
private fun ComposerInlineIndicator(
    icon: ImageVector,
    description: String,
    count: Int? = null
) {
    Row(
        modifier = Modifier.padding(horizontal = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        if (count != null && count > 1) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun CompactMessageAction(
    icon: ImageVector,
    description: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

'''
if anchor not in s:
    raise SystemExit('working stop anchor not found')
s = s.replace(anchor, helpers + anchor, 1)

# Add sound label helper before formatDate.
anchor = 'private fun formatDate(timestamp: Long): String =\n'
helper = '''private fun answerSoundLabel(choice: AnswerSoundChoice): String = when (choice) {
    AnswerSoundChoice.DEFAULT -> "Основной"
    AnswerSoundChoice.SOFT -> "Мягкий"
    AnswerSoundChoice.BRIGHT -> "Ясный"
    AnswerSoundChoice.DOUBLE -> "Двойной"
}

'''
if anchor not in s:
    raise SystemExit('formatDate anchor not found')
s = s.replace(anchor, helper + anchor, 1)
p.write_text(s, encoding='utf-8')

# Version bump.
p = Path('app/build.gradle.kts')
s = p.read_text(encoding='utf-8')
s = s.replace('// Umnik v0.7.3', '// Umnik v0.7.4', 1) if '// Umnik v0.7.3' in s else s.replace('// Umnik v0.7.2', '// Umnik v0.7.4', 1)
s = replace_once(s, 'versionCode = 20', 'versionCode = 21', 'version code') if 'versionCode = 20' in s else replace_once(s, 'versionCode = 19', 'versionCode = 21', 'version code fallback')
s = replace_once(s, 'versionName = "0.7.3"', 'versionName = "0.7.4"', 'version name') if 'versionName = "0.7.3"' in s else replace_once(s, 'versionName = "0.7.2"', 'versionName = "0.7.4"', 'version name fallback')
p.write_text(s, encoding='utf-8')

# Changelog.
p = Path('CHANGELOG.md')
s = p.read_text(encoding='utf-8')
entry = '''## v0.7.4 - 2026-09-11

- Значки действий под сообщениями уменьшены и приведены к более компактному стилю нового интерфейса.
- Markdown-таблицы теперь распознаются и отображаются как прокручиваемые таблицы вместо строк с символами `|`.
- Для глобальных навыков уточнены состояния: «Подключить к каждому чату» и «Подключён к каждому чату».
- В поле ввода появились компактные индикаторы активных навыков, размышления и поиска в сети.
- Настройка звука ответа расширена: основной сигнал остаётся доступен всегда, добавлены ещё варианты и регулировка громкости.

'''
s = replace_once(s, '## Unreleased\n\n', '## Unreleased\n\n' + entry, 'changelog unreleased')
p.write_text(s, encoding='utf-8')

print('Umnik 0.7.4 patch applied')
