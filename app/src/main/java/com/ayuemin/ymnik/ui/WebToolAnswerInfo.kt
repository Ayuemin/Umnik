package com.ayuemin.ymnik.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ayuemin.ymnik.data.OpenRouterFeaturePrefs
import com.ayuemin.ymnik.model.ChatMessage
import com.ayuemin.ymnik.model.InternetMode

/**
 * OpenRouter Chat Completions currently gives Umnik the aggregate request usage/cost,
 * but not a reliable per-server-tool invocation flag that we can persist as fact.
 * Therefore this block deliberately labels Search/Fetch values as the current settings,
 * not as proof that either tool was actually called for an older answer.
 */
@Composable
internal fun WebToolAnswerInfoRows(message: ChatMessage) {
    if (message.webSearchEnabled != true || message.internetMode == InternetMode.BROWSER.name) return

    val context = LocalContext.current
    val tools = remember(message.id) {
        OpenRouterFeaturePrefs(context.applicationContext).tools()
    }

    AnswerInfoRow("Search", "Настроено сейчас · ${searchEngineLabelV2(tools.webSearchEngine)}")
    AnswerInfoRow("Fetch", "Настроено сейчас · ${fetchEngineLabelV2(tools.webFetchEngine)}")
    Text(
        "Search и Fetch здесь показывают текущую настройку движков. Это не подтверждение, что OpenRouter фактически вызвал каждый server tool в данном ответе.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
    )
}
