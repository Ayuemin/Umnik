package com.ayuemin.ymnik.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Common floating tool chrome shared by chat utilities and visually matched to Local Shell. */
@Composable
internal fun FloatingChatToolPanel(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    initialHeight: Dp = 430.dp,
    minHeight: Dp = 280.dp,
    maxHeightFraction: Float = 0.78f,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    var panelHeight by remember(title) { mutableStateOf(initialHeight) }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize().imePadding()
    ) {
        val maxPanelHeight = (maxHeight * maxHeightFraction).coerceAtLeast(minHeight)
        val effectiveHeight = panelHeight.coerceIn(minHeight, maxPanelHeight)

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, bottom = 78.dp)
                .height(effectiveHeight),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
            shadowElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .pointerInput(maxPanelHeight) {
                            detectVerticalDragGestures { _, dragAmount ->
                                val delta = with(density) { (-dragAmount).toDp() }
                                panelHeight = (panelHeight + delta).coerceIn(minHeight, maxPanelHeight)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    HorizontalDivider(
                        modifier = Modifier.width(46.dp),
                        thickness = 4.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    headerAction?.invoke()
                    IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Закрыть")
                    }
                }

                Spacer(Modifier.height(6.dp))
                content()
            }
        }
    }
}
