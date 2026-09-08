package com.ayuemin.ymnik.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.ayuemin.ymnik.model.ThemeChoice

@Composable
fun UmnikTheme(choice: ThemeChoice, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current

    val colors = when (choice) {
        ThemeChoice.DYNAMIC -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
            dark -> graphiteDark()
            else -> graphiteLight()
        }
        ThemeChoice.GRAPHITE -> if (dark) graphiteDark() else graphiteLight()
        ThemeChoice.OCEAN -> if (dark) oceanDark() else oceanLight()
        ThemeChoice.FOREST -> if (dark) forestDark() else forestLight()
        ThemeChoice.AMBER -> if (dark) amberDark() else amberLight()
    }

    MaterialTheme(colorScheme = colors, content = content)
}

private fun baseLight(
    primary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    tertiary: Color
): ColorScheme = lightColorScheme(
    primary = primary,
    onPrimary = Color.White,
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E1DD),
    onSecondaryContainer = Color(0xFF241F1C),
    tertiary = tertiary,
    onTertiary = Color.White,
    background = Color(0xFFF9F9FA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF9F9FA),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE2E2E6),
    onSurfaceVariant = Color(0xFF45464B),
    outline = Color(0xFF76777C)
)

private fun baseDark(
    primary: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color,
    secondary: Color,
    tertiary: Color
): ColorScheme = darkColorScheme(
    primary = primary,
    onPrimary = Color(0xFF17181A),
    primaryContainer = primaryContainer,
    onPrimaryContainer = onPrimaryContainer,
    secondary = secondary,
    onSecondary = Color(0xFF17181A),
    secondaryContainer = Color(0xFF403A36),
    onSecondaryContainer = Color(0xFFE6E1DD),
    tertiary = tertiary,
    onTertiary = Color(0xFF17181A),
    background = Color(0xFF111315),
    onBackground = Color(0xFFE3E3E6),
    surface = Color(0xFF111315),
    onSurface = Color(0xFFE3E3E6),
    surfaceVariant = Color(0xFF44464B),
    onSurfaceVariant = Color(0xFFC5C6CA),
    outline = Color(0xFF8F9095)
)

private fun graphiteLight() = baseLight(
    primary = Color(0xFF55575C),
    primaryContainer = Color(0xFFE1E2E6),
    onPrimaryContainer = Color(0xFF1A1C1E),
    secondary = Color(0xFF6A5F58),
    tertiary = Color(0xFF5F626A)
)

private fun graphiteDark() = baseDark(
    primary = Color(0xFFC4C6CC),
    primaryContainer = Color(0xFF3E4045),
    onPrimaryContainer = Color(0xFFE1E2E6),
    secondary = Color(0xFFD4C3B8),
    tertiary = Color(0xFFC7CAD3)
)

private fun oceanLight() = baseLight(
    primary = Color(0xFF245FA6),
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF526579),
    tertiary = Color(0xFF5A5D8A)
)

private fun oceanDark() = baseDark(
    primary = Color(0xFFA8C8FF),
    primaryContainer = Color(0xFF0B477F),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFB9C9DB),
    tertiary = Color(0xFFC2C2F0)
)

private fun forestLight() = baseLight(
    primary = Color(0xFF3F6650),
    primaryContainer = Color(0xFFC3EBCF),
    onPrimaryContainer = Color(0xFF002111),
    secondary = Color(0xFF52645A),
    tertiary = Color(0xFF3D6670)
)

private fun forestDark() = baseDark(
    primary = Color(0xFFA7D0B4),
    primaryContainer = Color(0xFF274E39),
    onPrimaryContainer = Color(0xFFC3EBCF),
    secondary = Color(0xFFBACBBF),
    tertiary = Color(0xFFA4CDD7)
)

private fun amberLight() = baseLight(
    primary = Color(0xFF8A5B00),
    primaryContainer = Color(0xFFFFDEA4),
    onPrimaryContainer = Color(0xFF2B1700),
    secondary = Color(0xFF705D3F),
    tertiary = Color(0xFF53643E)
)

private fun amberDark() = baseDark(
    primary = Color(0xFFFFB951),
    primaryContainer = Color(0xFF684200),
    onPrimaryContainer = Color(0xFFFFDEA4),
    secondary = Color(0xFFDEC3A0),
    tertiary = Color(0xFFB9CC9F)
)
