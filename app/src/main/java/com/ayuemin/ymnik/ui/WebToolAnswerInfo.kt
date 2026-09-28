package com.ayuemin.ymnik.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.InternetMode
import com.ayuemin.ymnik.model.WebFetchEngine
import com.ayuemin.ymnik.model.WebSearchEngine
import com.ayuemin.ymnik.model.WebSearchPreset

/**
 * Shows the web settings saved with this answer. The rows stay in one place so the sheet does
 * not jump when a tool was disabled. A dash means that the setting did not apply to this answer
 * or was not saved by an older Umnik version.
 */
@Composable
internal fun WebToolAnswerInfoRows(message: ChatMessage) {
    val serverWebEnabled = message.webSearchEnabled == true &&
        message.internetMode != InternetMode.BROWSER.name
    val hasSnapshot = !message.webSearchPreset.isNullOrBlank() ||
        !message.webSearchEngine.isNullOrBlank() ||
        !message.webFetchEngine.isNullOrBlank()

    val preset = if (serverWebEnabled) {
        message.webSearchPreset?.takeIf { it.isNotBlank() }?.let(::storedPresetLabel)
    } else null
    val search = if (serverWebEnabled) {
        message.webSearchEngine?.takeIf { it.isNotBlank() }?.let(::storedSearchEngineLabel)
    } else null
    val fetch = if (serverWebEnabled) {
        message.webFetchEngine?.takeIf { it.isNotBlank() }?.let(::storedFetchEngineLabel)
    } else null

    WebToolInfoRow("Уровень поиска", preset ?: "—")
    WebToolInfoRow("Search", search ?: "—")
    WebToolInfoRow("Fetch", fetch ?: "—")

    if (serverWebEnabled && !hasSnapshot) {
        Text(
            "Для этого старого ответа точные настройки Search и Fetch не сохранялись.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
        )
    }
}

private fun storedSearchEngineLabel(value: String): String =
    runCatching { WebSearchEngine.valueOf(value) }
        .map(::searchEngineLabelV2)
        .getOrDefault(value)

private fun storedFetchEngineLabel(value: String): String =
    runCatching { WebFetchEngine.valueOf(value) }
        .map(::fetchEngineLabelV2)
        .getOrDefault(value)

private fun storedPresetLabel(value: String): String = when (runCatching { WebSearchPreset.valueOf(value) }.getOrNull()) {
    WebSearchPreset.ON_DEMAND -> "По необходимости"
    WebSearchPreset.FAST -> "Быстрый"
    WebSearchPreset.NORMAL -> "Обычный"
    WebSearchPreset.DEEP -> "Глубокий"
    null -> value
}

@Composable
private fun WebToolInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp)
        )
        SelectionContainer(modifier = Modifier.weight(1f)) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
