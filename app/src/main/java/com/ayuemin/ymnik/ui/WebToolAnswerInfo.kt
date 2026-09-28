package com.ayuemin.ymnik.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ayuemin.ymnik.data.OpenRouterFeaturePrefs
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.InternetMode
import com.ayuemin.ymnik.model.WebFetchEngine
import com.ayuemin.ymnik.model.WebSearchEngine
import com.ayuemin.ymnik.model.WebSearchPreset

/**
 * OpenRouter Chat Completions gives Umnik aggregate request usage/cost, but not a reliable
 * per-server-tool invocation flag. New answers therefore persist the request settings that
 * were actually placed into the final payload; this is still not proof that every tool ran.
 */
@Composable
internal fun WebToolAnswerInfoRows(message: ChatMessage) {
    if (message.webSearchEnabled != true || message.internetMode == InternetMode.BROWSER.name) return

    val hasSnapshot = !message.webSearchPreset.isNullOrBlank() ||
        !message.webSearchEngine.isNullOrBlank() ||
        !message.webFetchEngine.isNullOrBlank()

    if (hasSnapshot) {
        message.webSearchPreset?.takeIf { it.isNotBlank() }?.let {
            WebToolInfoRow("Уровень", storedPresetLabel(it))
        }
        message.webSearchEngine?.takeIf { it.isNotBlank() }?.let {
            WebToolInfoRow("Search", "В этом ответе · ${storedSearchEngineLabel(it)}")
        }
        message.webFetchEngine?.takeIf { it.isNotBlank() }?.let {
            WebToolInfoRow("Fetch", "В этом ответе · ${storedFetchEngineLabel(it)}")
        }
        Text(
            "Это сохранённые настройки запроса. Они не подтверждают, что OpenRouter фактически вызвал каждый server tool.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
        )
    } else {
        val context = LocalContext.current
        val tools = remember(message.id) {
            OpenRouterFeaturePrefs(context.applicationContext).tools()
        }
        WebToolInfoRow("Search", "Настроено сейчас · ${searchEngineLabelV2(tools.webSearchEngine)}")
        WebToolInfoRow("Fetch", "Настроено сейчас · ${fetchEngineLabelV2(tools.webFetchEngine)}")
        Text(
            "Для этого старого ответа снимок движков не сохранялся, поэтому показана текущая настройка. Это не подтверждение вызова server tools.",
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
    WebSearchPreset.ON_DEMAND -> "По запросу"
    WebSearchPreset.FAST -> "Быстро"
    WebSearchPreset.NORMAL -> "Обычно"
    WebSearchPreset.DEEP -> "Глубоко"
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
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1.4f)
        )
    }
}
