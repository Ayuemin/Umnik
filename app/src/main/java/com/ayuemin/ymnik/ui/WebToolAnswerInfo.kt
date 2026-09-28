package com.ayuemin.ymnik.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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

/**
 * OpenRouter Chat Completions currently gives Umnik the aggregate request usage/cost,
 * but not a reliable per-server-tool invocation flag that we can persist as fact.
 * Therefore this block deliberately labels Search/Fetch values as settings, not as proof
 * that either tool was actually called for the answer.
 */
@Composable
internal fun WebToolAnswerInfoRows(message: ChatMessage) {
    if (message.webSearchEnabled != true || message.internetMode == InternetMode.BROWSER.name) return

    val context = LocalContext.current
    val tools = remember(message.id) {
        OpenRouterFeaturePrefs(context.applicationContext).tools()
    }

    WebToolInfoRow("Search", "Настроено сейчас · ${searchEngineLabelV2(tools.webSearchEngine)}")
    WebToolInfoRow("Fetch", "Настроено сейчас · ${fetchEngineLabelV2(tools.webFetchEngine)}")
    Text(
        "Search и Fetch здесь показывают текущую настройку движков. Это не подтверждение, что OpenRouter фактически вызвал каждый server tool в данном ответе.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
    )
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