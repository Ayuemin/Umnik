package com.ayuemin.ymnik.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

internal val UmnikPanelShape = RoundedCornerShape(22.dp)
internal val UmnikItemShape = RoundedCornerShape(16.dp)
internal val UmnikFieldShape = RoundedCornerShape(28.dp)
internal val UmnikPanelPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)

@Composable
internal fun UmnikCircleAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(44.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(22.dp),
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
        }
    }
}

@Composable
internal fun UmnikPanel(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val border = BorderStroke(
        1.dp,
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.78f)
    )
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = UmnikPanelShape,
            color = container,
            border = border,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            content = content
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = UmnikPanelShape,
            color = container,
            border = border,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            content = content
        )
    }
}

@Composable
internal fun umnikDividerColor(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
