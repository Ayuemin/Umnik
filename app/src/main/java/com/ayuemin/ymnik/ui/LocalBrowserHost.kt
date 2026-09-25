package com.ayuemin.ymnik.ui

import android.view.View
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.ayuemin.ymnik.browser.LocalBrowserRuntime

@Composable
internal fun LocalBrowserHost() {
    val holder = remember { arrayOfNulls<WebView>(1) }
    val visible by LocalBrowserRuntime.userControlVisible.collectAsState()
    val activity by LocalBrowserRuntime.activity.collectAsState()

    BackHandler(enabled = visible) {
        LocalBrowserRuntime.hideUserControl()
    }

    Box(
        modifier = if (visible) Modifier.fillMaxSize() else Modifier.size(1.dp)
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).also { webView ->
                    holder[0] = webView
                    LocalBrowserRuntime.attach(webView)
                }
            },
            update = { webView ->
                webView.importantForAccessibility = if (visible) {
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                } else {
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
            },
            modifier = if (visible) {
                Modifier.fillMaxSize().padding(top = 56.dp)
            } else {
                Modifier.size(1.dp).graphicsLayer(alpha = 0f)
            }
        )

        if (visible) {
            Surface(
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Браузер · " + (activity?.host ?: "страница"),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = { LocalBrowserRuntime.hideUserControl() }) {
                        Text("В чат")
                    }
                    when (activity?.attentionKind) {
                        "CONFIRM_ACTION" -> {
                            TextButton(
                                onClick = {
                                    activity?.chatId?.let(LocalBrowserRuntime::cancelPendingUserAction)
                                }
                            ) { Text("Отмена") }
                            Button(
                                onClick = {
                                    activity?.chatId?.let(LocalBrowserRuntime::confirmPendingUserAction)
                                }
                            ) { Text("Подтвердить") }
                        }
                        "TAKEOVER" -> {
                            TextButton(
                                onClick = {
                                    activity?.chatId?.let(LocalBrowserRuntime::cancelPendingUserAction)
                                }
                            ) { Text("Отмена") }
                            Button(
                                onClick = {
                                    activity?.chatId?.let(LocalBrowserRuntime::finishUserControl)
                                }
                            ) { Text("Вернуть модели") }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            holder[0]?.let { webView ->
                LocalBrowserRuntime.detach(webView)
                runCatching { webView.stopLoading() }
                runCatching { webView.destroy() }
            }
            holder[0] = null
        }
    }
}
