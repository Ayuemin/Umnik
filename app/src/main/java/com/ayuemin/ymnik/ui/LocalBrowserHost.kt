package com.ayuemin.ymnik.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ayuemin.ymnik.browser.LocalBrowserRuntime

@Composable
internal fun LocalBrowserHost() {
    val holder = remember { arrayOfNulls<WebView>(1) }

    AndroidView(
        factory = { context ->
            WebView(context).also { webView ->
                holder[0] = webView
                LocalBrowserRuntime.attach(webView)
            }
        },
        modifier = Modifier
            .size(1.dp)
            .graphicsLayer(alpha = 0f)
    )

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
