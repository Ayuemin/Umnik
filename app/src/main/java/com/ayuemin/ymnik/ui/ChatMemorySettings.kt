package com.ayuemin.ymnik.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.model.ChatContextMode
import com.ayuemin.ymnik.model.ChatMemoryGlobalSettings
import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.UiState
import java.util.Locale

@Composable
fun ChatContextSettingsSection(chat: ChatSession, state: UiState, vm: ChatViewModel) {
    var expanded by remember(chat.id) { mutableStateOf(false) }
    val overrideMode = vm.chatContextModeOverride(chat.id)
    val effectiveMode = vm.chatContextMode(chat.id)
    val defaultMode = vm.chatMemorySettings().defaultContextMode
    val stats = vm.chatMemoryStats(chat.id)

    MemorySettingsExpander(
        title = "Контекст чата",
        subtitle = if (overrideMode == null) {
            "По умолчанию · ${modeLabel(effectiveMode)}"
        } else {
            modeLabel(effectiveMode)
        },
        expanded = expanded,
        onToggle = { expanded = !expanded },
        info = "Режим определяет, сколько старой переписки отправляется модели. Исходная история чата всегда остаётся на телефоне."
    )
    if (!expanded) return

    ContextModeChoice(
        selected = overrideMode == null,
        title = "По умолчанию: ${modeLabel(defaultMode)}",
        description = "Следовать общему режиму из Настройки → Память и контекст. Если общий режим изменится, этот чат изменится вместе с ним."
    ) { vm.setChatContextMode(chat.id, null) }
    ContextModeChoice(
        selected = overrideMode == ChatContextMode.AUTO,
        title = "Баланс",
        description = "До порога используется полная история, затем Umnik держит свежие пары, компактный конспект и найденные старые фрагменты в общем бюджете."
    ) { vm.setChatContextMode(chat.id, ChatContextMode.AUTO) }
    ContextModeChoice(
        selected = overrideMode == ChatContextMode.FULL,
        title = "Всегда полный",
        description = "Отправлять максимум исходной истории, который помещается в контекст выбранной модели. Долговременная память не используется."
    ) { vm.setChatContextMode(chat.id, ChatContextMode.FULL) }
    ContextModeChoice(
        selected = overrideMode == ChatContextMode.ECONOMY,
        title = "Эконом",
        description = "Раньше включает гибридную память и использует меньший бюджет истории и памяти."
    ) { vm.setChatContextMode(chat.id, ChatContextMode.ECONOMY) }

    Text(
        "Память: ${stats.checkpoints} checkpoint · ${stats.chunks} фрагм. · ${formatMemoryBytes(stats.bytes)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledTonalButton(
            onClick = { vm.rebuildChatMemory(chat.id) },
            enabled = !state.isLoading && !state.requestActive && effectiveMode != ChatContextMode.FULL,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Перестроить")
        }
        UmnikInfoHint(
            title = "Перестроить память",
            text = "Заново создаёт служебные конспекты и индекс старой переписки этого чата. Исходная переписка не меняется."
        )
        FilledTonalButton(
            onClick = { vm.clearChatMemory(chat.id) },
            enabled = !state.isLoading && !state.requestActive,
            modifier = Modifier.weight(1f)
        ) {
            Text("Очистить память")
        }
        UmnikInfoHint(
            title = "Очистить память",
            text = "Удаляет только служебные checkpoint-конспекты, embeddings и карточку состояния этого чата. Переписка и файлы остаются."
        )
    }
}

@Composable
private fun ContextModeChoice(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(title) },
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        UmnikInfoHint(title = title, text = description)
    }
}

@Composable
fun ChatMemoryGlobalSettingsSection(state: UiState, vm: ChatViewModel) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val initial = vm.chatMemorySettings()
    val embeddingModel = state.embeddingModel
    var defaultMode by remember(initial.defaultContextMode) { mutableStateOf(initial.defaultContextMode) }
    var autoThreshold by remember(initial.autoThresholdTokens) { mutableStateOf(initial.autoThresholdTokens.toString()) }
    var economyThreshold by remember(initial.economyThresholdTokens) { mutableStateOf(initial.economyThresholdTokens.toString()) }
    var autoBudget by remember(initial.autoContextBudgetTokens) { mutableStateOf(initial.autoContextBudgetTokens.toString()) }
    var economyBudget by remember(initial.economyContextBudgetTokens) { mutableStateOf(initial.economyContextBudgetTokens.toString()) }
    var autoRecentPairs by remember(initial.autoRecentMessages) { mutableStateOf((initial.autoRecentMessages / 2).toString()) }
    var economyRecentPairs by remember(initial.economyRecentMessages) { mutableStateOf((initial.economyRecentMessages / 2).toString()) }
    var autoTopK by remember(initial.autoTopK) { mutableStateOf(initial.autoTopK.toString()) }
    var economyTopK by remember(initial.economyTopK) { mutableStateOf(initial.economyTopK.toString()) }
    var checkpointTokens by remember(initial.checkpointTokens) { mutableStateOf(initial.checkpointTokens.toString()) }
    var chunkTokens by remember(initial.chunkTokens) { mutableStateOf(initial.chunkTokens.toString()) }
    var chunkOverlap by remember(initial.chunkOverlapTokens) { mutableStateOf(initial.chunkOverlapTokens.toString()) }
    var neighborChunks by remember(initial.neighborChunks) { mutableStateOf(initial.neighborChunks.toString()) }
    var minimumScore by remember(initial.minimumScore) { mutableStateOf(String.format(Locale.US, "%.2f", initial.minimumScore)) }
    var stateCardMaxChars by remember(initial.stateCardMaxChars) { mutableStateOf(initial.stateCardMaxChars.toString()) }

    val catalog = remember(context, vm) { OpenRouterHubController(context, vm) }
    val catalogState by catalog.state.collectAsState()
    DisposableEffect(catalog) { onDispose { catalog.close() } }
    LaunchedEffect(expanded) {
        if (expanded && catalogState.catalog.isEmpty() && !catalogState.loading) catalog.refreshCatalog()
    }
    val selectedEmbeddingInfo = catalogState.catalog.firstOrNull { it.id == embeddingModel }
    val detectedEmbeddingContext = selectedEmbeddingInfo?.contextLength
        ?: initial.embeddingContextTokens
    val requestedChunk = chunkTokens.toIntOrNull() ?: initial.chunkTokens
    val effectiveChunk = adaptiveChunkTarget(requestedChunk, detectedEmbeddingContext)

    UmnikPanel {
        ExpandableSettingsHeader(
            icon = Icons.Outlined.History,
            title = "Память и контекст",
            subtitle = "Гибридная память длинных чатов · ${formatMemoryBytes(vm.totalChatMemoryBytes())}",
            expanded = expanded,
            onToggle = { expanded = !expanded },
            info = "Umnik не удаляет старую переписку. После заданного порога старые завершённые ходы индексируются общей Embeddings-моделью, а системная модель делает компактный конспект. Обе модели задаются один раз в Настройки → Модели."
        )
        if (!expanded) return@UmnikPanel
        Column(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {

            Text("Режим контекста по умолчанию", fontWeight = FontWeight.SemiBold)
            Text(
                "Используется чатами, где не задан индивидуальный режим.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = defaultMode == ChatContextMode.AUTO,
                    onClick = { defaultMode = ChatContextMode.AUTO },
                    label = { Text("Баланс") }
                )
                FilterChip(
                    selected = defaultMode == ChatContextMode.FULL,
                    onClick = { defaultMode = ChatContextMode.FULL },
                    label = { Text("Полный") }
                )
                FilterChip(
                    selected = defaultMode == ChatContextMode.ECONOMY,
                    onClick = { defaultMode = ChatContextMode.ECONOMY },
                    label = { Text("Эконом") }
                )
            }

            UmnikInlineExpander(
                title = "Расширенные параметры памяти",
                expanded = advanced,
                onToggle = { advanced = !advanced }
            )
            if (advanced) {
                when {
                    catalogState.loading -> Text(
                        "Обновляю сведения о выбранной модели…",
                        style = MaterialTheme.typography.bodySmall
                    )
                    detectedEmbeddingContext != null -> Text(
                        "Окно Embeddings-модели: $detectedEmbeddingContext токенов. Рабочий фрагмент: до $effectiveChunk токенов.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Text(
                        "OpenRouter не сообщил лимит этой модели. Umnik использует сохранённые безопасные параметры.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                NumericMemoryField("Баланс: включить память после, токенов", autoThreshold) { autoThreshold = it }
                NumericMemoryField("Эконом: включить память после, токенов", economyThreshold) { economyThreshold = it }
                NumericMemoryField("Баланс: бюджет истории и памяти, токенов", autoBudget) { autoBudget = it }
                NumericMemoryField("Эконом: бюджет истории и памяти, токенов", economyBudget) { economyBudget = it }
                NumericMemoryField("Баланс: последних пар диалога", autoRecentPairs) { autoRecentPairs = it }
                NumericMemoryField("Эконом: последних пар диалога", economyRecentPairs) { economyRecentPairs = it }
                NumericMemoryField("Баланс: максимум найденных фрагментов", autoTopK) { autoTopK = it }
                NumericMemoryField("Эконом: максимум найденных фрагментов", economyTopK) { economyTopK = it }
                NumericMemoryField("Размер checkpoint, токенов", checkpointTokens) { checkpointTokens = it }
                NumericMemoryField("Желаемый размер фрагмента поиска, токенов", chunkTokens) { chunkTokens = it }
                NumericMemoryField("Перекрытие соседних фрагментов, токенов", chunkOverlap) { chunkOverlap = it }
                NumericMemoryField("Соседних фрагментов с каждой стороны (0–1)", neighborChunks) { neighborChunks = it }
                OutlinedTextField(
                    value = minimumScore,
                    onValueChange = { minimumScore = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Минимальная близость embeddings") },
                    singleLine = true
                )
                NumericMemoryField("Максимум общего конспекта, знаков", stateCardMaxChars) { stateCardMaxChars = it }
            }

            FilledTonalButton(
                onClick = {
                    val catalogLimit = catalogState.catalog.firstOrNull { it.id == embeddingModel }?.contextLength
                    val savedLimit = catalogLimit
                        ?: initial.embeddingContextTokens
                    vm.saveChatMemorySettings(
                        ChatMemoryGlobalSettings(
                            defaultContextMode = defaultMode,
                            autoThresholdTokens = autoThreshold.toIntOrNull() ?: initial.autoThresholdTokens,
                            economyThresholdTokens = economyThreshold.toIntOrNull() ?: initial.economyThresholdTokens,
                            autoContextBudgetTokens = autoBudget.toIntOrNull() ?: initial.autoContextBudgetTokens,
                            economyContextBudgetTokens = economyBudget.toIntOrNull() ?: initial.economyContextBudgetTokens,
                            autoRecentMessages = (autoRecentPairs.toIntOrNull()?.times(2)) ?: initial.autoRecentMessages,
                            economyRecentMessages = (economyRecentPairs.toIntOrNull()?.times(2)) ?: initial.economyRecentMessages,
                            autoTopK = autoTopK.toIntOrNull() ?: initial.autoTopK,
                            economyTopK = economyTopK.toIntOrNull() ?: initial.economyTopK,
                            topK = initial.topK,
                            checkpointTokens = checkpointTokens.toIntOrNull() ?: initial.checkpointTokens,
                            chunkTokens = chunkTokens.toIntOrNull() ?: initial.chunkTokens,
                            chunkOverlapTokens = chunkOverlap.toIntOrNull() ?: initial.chunkOverlapTokens,
                            neighborChunks = neighborChunks.toIntOrNull() ?: initial.neighborChunks,
                            embeddingContextTokens = savedLimit,
                            minimumScore = minimumScore.toDoubleOrNull() ?: initial.minimumScore,
                            stateCardMaxChars = stateCardMaxChars.toIntOrNull() ?: initial.stateCardMaxChars
                        )
                    )
                },
                enabled = embeddingModel.isNotBlank() && !state.isLoading && !state.requestActive,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить настройки памяти") }

            FilledTonalButton(
                onClick = { confirmClear = true },
                enabled = !state.isLoading && !state.requestActive,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Очистить память всех чатов")
                Spacer(Modifier.width(6.dp))
                UmnikInfoHint(
                    title = "Очистить память всех чатов",
                    text = "Удаляет только служебные конспекты, embeddings и карточки состояния всех чатов. Переписка, файлы и сами чаты не удаляются."
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить служебную память?") },
            text = { Text("Переписка и файлы не удалятся. Checkpoint-конспекты, embeddings и карточки состояния будут удалены и при необходимости построятся заново.") },
            confirmButton = {
                TextButton(onClick = { vm.clearAllChatMemory(); confirmClear = false }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } }
        )
    }
}

@Composable
private fun NumericMemoryField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> onValue(next.filter { it.isDigit() }) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true
    )
}

@Composable
private fun MemorySettingsExpander(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    info: String? = null
) {
    UmnikPanel {
        ExpandableSettingsHeader(
            icon = Icons.Outlined.History,
            title = title,
            subtitle = subtitle,
            expanded = expanded,
            onToggle = onToggle,
            info = info
        )
    }
}

private fun adaptiveChunkTarget(requestedTokens: Int, embeddingContextTokens: Int?): Int {
    val requested = requestedTokens.coerceIn(128, 4_000)
    val safe = embeddingContextTokens
        ?.takeIf { it >= 128 }
        ?.let { (it * 3 / 4).coerceAtLeast(96) }
    return minOf(requested, safe ?: requested).coerceAtLeast(96)
}

private fun modeLabel(mode: ChatContextMode): String = when (mode) {
    ChatContextMode.AUTO -> "Баланс"
    ChatContextMode.FULL -> "Всегда полный"
    ChatContextMode.ECONOMY -> "Эконом"
}

private fun formatMemoryBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes Б"
    bytes < 1024L * 1024L -> "%.1f КБ".format(Locale.getDefault(), bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f МБ".format(Locale.getDefault(), bytes / 1024.0 / 1024.0)
    else -> "%.2f ГБ".format(Locale.getDefault(), bytes / 1024.0 / 1024.0 / 1024.0)
}
