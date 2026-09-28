package com.ayuemin.ymnik.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ayuemin.ymnik.ChatViewModel
import com.ayuemin.ymnik.model.ServerToolSettings
import com.ayuemin.ymnik.model.WebFetchEngine
import com.ayuemin.ymnik.model.WebSearchEngine
import com.ayuemin.ymnik.model.WebSearchPreset

/**
 * Compact web-tools settings page. Search and Fetch intentionally live in separate
 * expandable sections: they solve different jobs and may use different engines/costs.
 */
@Composable
internal fun OpenRouterToolsPageV2(
    tools: ServerToolSettings,
    controller: OpenRouterHubController,
    viewModel: ChatViewModel
) {
    var searchExpanded by remember { mutableStateOf(false) }
    var fetchExpanded by remember { mutableStateOf(false) }
    var otherExpanded by remember { mutableStateOf(false) }
    var defaultSearchEnabled by remember { mutableStateOf(viewModel.defaultWebSearchEnabled()) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                "Веб-инструменты",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Поиск находит страницы, Fetch читает известный URL, Browser работает с интерактивным сайтом.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp)
            )
        }

        item {
            WebToolSectionHeader(
                title = "Поиск",
                subtitle = buildString {
                    append(if (defaultSearchEnabled) "Для новых чатов: включён" else "Для новых чатов: выключен")
                    append(" · ").append(searchEngineLabelV2(tools.webSearchEngine))
                },
                expanded = searchExpanded,
                onClick = { searchExpanded = !searchExpanded }
            )
        }
        if (searchExpanded) {
            item {
                WebToolPanel {
                    WebToolToggleRow(
                        title = "Поиск в новых чатах",
                        subtitle = "Стартовое значение. После создания каждый чат хранит свой режим интернета отдельно.",
                        checked = defaultSearchEnabled,
                        onCheckedChange = {
                            defaultSearchEnabled = it
                            viewModel.setDefaultWebSearchEnabled(it)
                        }
                    )
                    WebToolCaption("Уровень поиска по умолчанию")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(WebSearchPreset.entries) { preset ->
                            FilterChip(
                                selected = tools.webSearchPreset == preset,
                                onClick = { controller.updateTools(tools.copy(webSearchPreset = preset)) },
                                label = { Text(searchPresetLabelV2(preset)) }
                            )
                        }
                    }
                    WebToolCaption("Движок поиска")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(WebSearchEngine.entries) { engine ->
                            FilterChip(
                                selected = tools.webSearchEngine == engine,
                                onClick = { controller.updateTools(tools.copy(webSearchEngine = engine)) },
                                label = { Text(searchEngineLabelV2(engine)) }
                            )
                        }
                    }
                    Text(
                        "Движок общий для чатов, где серверный поиск OpenRouter включён.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            WebToolSectionHeader(
                title = "Fetch · чтение страниц",
                subtitle = "Движок: ${fetchEngineLabelV2(tools.webFetchEngine)}",
                expanded = fetchExpanded,
                onClick = { fetchExpanded = !fetchExpanded }
            )
        }
        if (fetchExpanded) {
            item {
                WebToolPanel {
                    Text(
                        "Fetch читает содержимое известной или найденной страницы без запуска Local Browser. В режимах «Только поиск» и «Автоматически» модель получает его вместе с серверным поиском OpenRouter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    WebToolCaption("Движок Fetch")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(WebFetchEngine.entries) { engine ->
                            FilterChip(
                                selected = tools.webFetchEngine == engine,
                                onClick = { controller.updateTools(tools.copy(webFetchEngine = engine)) },
                                label = { Text(fetchEngineLabelV2(engine)) }
                            )
                        }
                    }
                    Text(
                        when (tools.webFetchEngine) {
                            WebFetchEngine.AUTO -> "Auto разрешает OpenRouter выбрать подходящий способ чтения страницы."
                            WebFetchEngine.NATIVE -> "Native использует встроенный Fetch выбранного провайдера, если он поддерживается."
                            WebFetchEngine.OPENROUTER -> "OpenRouter использует собственный direct Fetch."
                            WebFetchEngine.EXA -> "Exa используется как отдельный Fetch-движок."
                            WebFetchEngine.PARALLEL -> "Parallel используется как отдельный Fetch-движок."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Тарифы веб-движков могут меняться; актуальную стоимость лучше сверять в OpenRouter.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            WebToolSectionHeader(
                title = "Другие инструменты",
                subtitle = listOfNotNull(
                    "Дата/время".takeIf { tools.datetime },
                    "Изображения".takeIf { tools.imageGeneration },
                    "Fusion".takeIf { tools.fusion }
                ).joinToString(" · ").ifBlank { "Дополнительные server tools" },
                expanded = otherExpanded,
                onClick = { otherExpanded = !otherExpanded }
            )
        }
        if (otherExpanded) {
            item {
                WebToolPanel {
                    WebToolToggleRow(
                        "Текущие дата и время",
                        "Разрешает модели запросить актуальные дату и время как server tool.",
                        tools.datetime
                    ) { controller.updateTools(tools.copy(datetime = it)) }
                    WebToolToggleRow(
                        "Создание изображений как инструмент",
                        "Позволяет совместимой модели вызвать генерацию изображения в ходе разговора.",
                        tools.imageGeneration
                    ) { controller.updateTools(tools.copy(imageGeneration = it)) }
                    WebToolToggleRow(
                        "Fusion",
                        "Продвинутый режим OpenRouter для объединения работы нескольких инструментов.",
                        tools.fusion
                    ) { controller.updateTools(tools.copy(fusion = it)) }
                }
            }
        }
    }
}

@Composable
private fun WebToolSectionHeader(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (expanded) "Свернуть" else "Развернуть",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun WebToolPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun WebToolCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp)
    )
}

@Composable
private fun WebToolToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

internal fun searchEngineLabelV2(value: WebSearchEngine): String = when (value) {
    WebSearchEngine.AUTO -> "Auto"
    WebSearchEngine.NATIVE -> "Native"
    WebSearchEngine.EXA -> "Exa"
    WebSearchEngine.PARALLEL -> "Parallel"
    WebSearchEngine.PERPLEXITY -> "Perplexity"
}

internal fun fetchEngineLabelV2(value: WebFetchEngine): String = when (value) {
    WebFetchEngine.AUTO -> "Auto"
    WebFetchEngine.NATIVE -> "Native"
    WebFetchEngine.OPENROUTER -> "OpenRouter"
    WebFetchEngine.EXA -> "Exa"
    WebFetchEngine.PARALLEL -> "Parallel"
}

private fun searchPresetLabelV2(value: WebSearchPreset): String = when (value) {
    WebSearchPreset.ON_DEMAND -> "По необходимости"
    WebSearchPreset.FAST -> "Быстрый"
    WebSearchPreset.NORMAL -> "Обычный"
    WebSearchPreset.DEEP -> "Глубокий"
}
