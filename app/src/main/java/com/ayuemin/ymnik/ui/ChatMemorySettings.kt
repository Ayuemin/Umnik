package com.ayuemin.ymnik.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.model.ChatContextMode
import com.ayuemin.ymnik.model.ChatMemoryGlobalSettings
import com.ayuemin.ymnik.model.ChatSession
import com.ayuemin.ymnik.model.ModelCategory
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
        onToggle = { expanded = !expanded }
    )
    if (!expanded) return

    Text(
        "Режим определяет, сколько старой переписки отправляется модели. Исходная история чата всегда остаётся на телефоне.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(
            onClick = { vm.rebuildChatMemory(chat.id) },
            enabled = !state.isLoading && !state.requestActive && effectiveMode != ChatContextMode.FULL,
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Перестроить")
        }
        TextButton(
            onClick = { vm.clearChatMemory(chat.id) },
            enabled = !state.isLoading && !state.requestActive,
            modifier = Modifier.weight(1f)
        ) {
            Text("Очистить память")
        }
    }
    Text(
        "Очистка памяти не удаляет переписку. При удалении самого чата его checkpoint-конспекты, embeddings и карточка состояния удаляются автоматически.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ContextModeChoice(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        FilterChip(selected = selected, onClick = onClick, label = { Text(title) })
        Text(
            description,
            modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ChatMemoryGlobalSettingsSection(state: UiState, vm: ChatViewModel) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val initial = vm.chatMemorySettings()
    var embeddingModel by remember(initial.embeddingModelId) { mutableStateOf(initial.embeddingModelId) }
    var summaryModel by remember(initial.summaryModelId) { mutableStateOf(initial.summaryModelId) }
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
    var embeddingMenu by remember { mutableStateOf(false) }
    var summaryMenu by remember { mutableStateOf(false) }

    val catalog = remember(context, vm) { OpenRouterHubController(context, vm) }
    val catalogState by catalog.state.collectAsState()
    DisposableEffect(catalog) { onDispose { catalog.close() } }
    LaunchedEffect(expanded) {
        if (expanded && catalogState.catalog.isEmpty() && !catalogState.loading) catalog.refreshCatalog()
    }
    val embeddingChoices = (listOf(embeddingModel, ChatMemoryGlobalSettings.DEFAULT_EMBEDDING_MODEL) +
        catalogState.catalog.filter { ModelCategory.EMBEDDINGS in it.categories }.map { it.id })
        .filter(String::isNotBlank).distinct()
    val textChoices = (listOf(summaryModel, state.currentChatTextModel.orEmpty(), state.textModel, "openrouter/auto") +
        state.availableTextModels.filter { ModelCategory.TEXT in it.categories && !it.isBatch }.map { it.id })
        .filter(String::isNotBlank).distinct()
    val selectedEmbeddingInfo = catalogState.catalog.firstOrNull { it.id == embeddingModel }
    val detectedEmbeddingContext = selectedEmbeddingInfo?.contextLength
        ?: initial.embeddingContextTokens.takeIf { initial.embeddingModelId == embeddingModel }
    val requestedChunk = chunkTokens.toIntOrNull() ?: initial.chunkTokens
    val effectiveChunk = adaptiveChunkTarget(requestedChunk, detectedEmbeddingContext)

    ElevatedCard(Modifier.fillMaxWidth()) {
        ExpandableSettingsHeader(
            icon = Icons.Outlined.History,
            title = "Память и контекст",
            subtitle = "Гибридная память длинных чатов · ${formatMemoryBytes(vm.totalChatMemoryBytes())}",
            expanded = expanded,
            onToggle = { expanded = !expanded }
        )
        if (!expanded) return@ElevatedCard
        Column(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {

            Text(
                "Umnik не удаляет старую переписку. После порога старые завершённые ходы индексируются один раз, а модели отправляются свежий хвост, краткая карточка состояния и только релевантные старые фрагменты.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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

            Text("Embedding-модель", fontWeight = FontWeight.SemiBold)
            Box {
                FilledTonalButton(
                    onClick = { embeddingMenu = true },
                    enabled = !state.isLoading && !state.requestActive,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(embeddingModel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                DropdownMenu(expanded = embeddingMenu, onDismissRequest = { embeddingMenu = false }) {
                    embeddingChoices.forEach { id ->
                        DropdownMenuItem(
                            text = { Text(id, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            onClick = { embeddingModel = id; embeddingMenu = false }
                        )
                    }
                }
            }
            when {
                catalogState.loading -> Text(
                    "Обновляю список Embeddings OpenRouter…",
                    style = MaterialTheme.typography.bodySmall
                )
                detectedEmbeddingContext != null -> Text(
                    "Окно выбранной Embedding-модели: $detectedEmbeddingContext токенов. Рабочий фрагмент: до $effectiveChunk токенов с запасом для токенизации.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Text(
                    "OpenRouter не сообщил лимит этой модели. Umnik использует заданный размер фрагмента; при выборе модели из каталога лимит сохраняется автоматически.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text("Модель конспекта", fontWeight = FontWeight.SemiBold)
            Box {
                FilledTonalButton(
                    onClick = { summaryMenu = true },
                    enabled = !state.isLoading && !state.requestActive,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(summaryModel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                DropdownMenu(expanded = summaryMenu, onDismissRequest = { summaryMenu = false }) {
                    textChoices.forEach { id ->
                        DropdownMenuItem(
                            text = { Text(id, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            onClick = { summaryModel = id; summaryMenu = false }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = summaryModel,
                onValueChange = { summaryModel = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Model ID конспекта") },
                supportingText = { Text("Обычная текстовая модель OpenRouter; можно указать ID вручную.") },
                singleLine = true
            )

            NumericMemoryField("Баланс: включить память после, токенов", autoThreshold) { autoThreshold = it }
            NumericMemoryField("Эконом: включить память после, токенов", economyThreshold) { economyThreshold = it }
            NumericMemoryField("Баланс: бюджет истории и памяти, токенов", autoBudget) { autoBudget = it }
            NumericMemoryField("Эконом: бюджет истории и памяти, токенов", economyBudget) { economyBudget = it }
            NumericMemoryField("Баланс: последних пар диалога", autoRecentPairs) { autoRecentPairs = it }
            NumericMemoryField("Эконом: последних пар диалога", economyRecentPairs) { economyRecentPairs = it }
            NumericMemoryField("Баланс: максимум найденных фрагментов", autoTopK) { autoTopK = it }
            NumericMemoryField("Эконом: максимум найденных фрагментов", economyTopK) { economyTopK = it }

            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                Text(if (advanced) "Скрыть дополнительные параметры" else "Дополнительные параметры")
            }
            if (advanced) {
                NumericMemoryField("Размер checkpoint, токенов", checkpointTokens) { checkpointTokens = it }
                NumericMemoryField("Желаемый размер фрагмента поиска, токенов", chunkTokens) { chunkTokens = it }
                NumericMemoryField("Перекрытие соседних фрагментов, токенов", chunkOverlap) { chunkOverlap = it }
                NumericMemoryField("Соседних фрагментов с каждой стороны (0–1)", neighborChunks) { neighborChunks = it }
                Text(
                    if (detectedEmbeddingContext != null) {
                        "Адаптивный предел сейчас: $effectiveChunk токенов. Больший заданный размер автоматически уменьшается под окно выбранной Embedding-модели."
                    } else {
                        "Если каталог сообщает окно Embedding-модели, Umnik автоматически ограничивает размер фрагмента примерно 75% этого окна."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = minimumScore,
                    onValueChange = { minimumScore = it.filter { c -> c.isDigit() || c == '.' || c == '-' } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Минимальная близость embeddings") },
                    singleLine = true
                )
                NumericMemoryField("Максимум карточки состояния, знаков", stateCardMaxChars) { stateCardMaxChars = it }
                Text(
                    "Хранилище памяти не ограничивается Umnik по размеру и находится отдельно от обычных файлов и базы знаний.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FilledTonalButton(
                onClick = {
                    val catalogLimit = catalogState.catalog.firstOrNull { it.id == embeddingModel }?.contextLength
                    val savedLimit = catalogLimit
                        ?: initial.embeddingContextTokens.takeIf { initial.embeddingModelId == embeddingModel }
                    vm.saveChatMemorySettings(
                        ChatMemoryGlobalSettings(
                            embeddingModelId = embeddingModel,
                            summaryModelId = summaryModel,
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
                enabled = embeddingModel.isNotBlank() && summaryModel.isNotBlank() && !state.isLoading && !state.requestActive,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Сохранить настройки памяти") }

            TextButton(
                onClick = { confirmClear = true },
                enabled = !state.isLoading && !state.requestActive,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Очистить память всех чатов")
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
    onToggle: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        ExpandableSettingsHeader(
            icon = Icons.Outlined.History,
            title = title,
            subtitle = subtitle,
            expanded = expanded,
            onToggle = onToggle
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
