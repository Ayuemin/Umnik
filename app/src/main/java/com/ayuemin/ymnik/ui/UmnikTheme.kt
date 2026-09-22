package com.ayuemin.ymnik.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.ayuemin.ymnik.model.ThemeChoice

@Composable
fun UmnikTheme(choice: ThemeChoice, customColor: Int, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current

    val baseColors = when (choice) {
        ThemeChoice.DYNAMIC -> when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
            dark -> graphiteDark()
            else -> graphiteLight()
        }
        ThemeChoice.CUSTOM -> if (dark) customDark(Color(customColor)) else customLight(Color(customColor))
        ThemeChoice.GRAPHITE -> if (dark) graphiteDark() else graphiteLight()
        ThemeChoice.OCEAN -> if (dark) oceanDark() else oceanLight()
        ThemeChoice.FOREST -> if (dark) forestDark() else forestLight()
        ThemeChoice.AMBER -> if (dark) amberDark() else amberLight()
    }

    val colors = harmonizeVisiblePalette(baseColors, dark)

    MaterialTheme(
        colorScheme = colors,
        shapes = UmnikShapes,
        content = content
    )
}

private fun harmonizeVisiblePalette(source: ColorScheme, dark: Boolean): ColorScheme {
    val primary = source.primary
    return if (dark) {
        source.copy(
            background = lerp(primary, Color.Black, 0.95f),
            onBackground = lerp(primary, Color.White, 0.68f),
            surface = lerp(primary, Color.Black, 0.94f),
            onSurface = lerp(primary, Color.White, 0.68f),
            surfaceVariant = lerp(primary, Color.Black, 0.76f),
            onSurfaceVariant = lerp(primary, Color.White, 0.50f),
            outline = lerp(primary, Color.Gray, 0.44f),
            outlineVariant = lerp(primary, Color.Black, 0.50f),
            surfaceContainerLowest = lerp(primary, Color.Black, 0.96f),
            surfaceContainerLow = lerp(primary, Color.Black, 0.90f),
            surfaceContainer = lerp(primary, Color.Black, 0.84f),
            surfaceContainerHigh = lerp(primary, Color.Black, 0.78f),
            surfaceContainerHighest = lerp(primary, Color.Black, 0.70f),
            scrim = lerp(primary, Color.Black, 0.84f)
        )
    } else {
        source.copy(
            background = lerp(primary, Color.White, 0.985f),
            onBackground = lerp(primary, Color.Black, 0.72f),
            surface = lerp(primary, Color.White, 0.985f),
            onSurface = lerp(primary, Color.Black, 0.72f),
            surfaceVariant = lerp(primary, Color.White, 0.88f),
            onSurfaceVariant = lerp(primary, Color.Black, 0.57f),
            outline = lerp(primary, Color.Gray, 0.54f),
            outlineVariant = lerp(primary, Color.White, 0.66f),
            surfaceContainerLowest = lerp(primary, Color.White, 0.995f),
            surfaceContainerLow = lerp(primary, Color.White, 0.96f),
            surfaceContainer = lerp(primary, Color.White, 0.92f),
            surfaceContainerHigh = lerp(primary, Color.White, 0.87f),
            surfaceContainerHighest = lerp(primary, Color.White, 0.82f),
            scrim = lerp(primary, Color.Black, 0.82f)
        )
    }
}

private val UmnikShapes = Shapes(
    extraSmall = RoundedCornerShape(18.dp),
    small = RoundedCornerShape(18.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

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
    secondaryContainer = lerp(primary, Color.White, 0.86f),
    onSecondaryContainer = lerp(primary, Color.Black, 0.76f),
    tertiary = tertiary,
    onTertiary = Color.White,
    tertiaryContainer = lerp(tertiary, Color.White, 0.84f),
    onTertiaryContainer = lerp(tertiary, Color.Black, 0.76f),
    background = lerp(primary, Color.White, 0.985f),
    onBackground = lerp(primary, Color.Black, 0.86f),
    surface = lerp(primary, Color.White, 0.985f),
    onSurface = lerp(primary, Color.Black, 0.86f),
    surfaceTint = primary,
    surfaceVariant = lerp(primary, Color.White, 0.90f),
    onSurfaceVariant = lerp(primary, Color.Black, 0.70f),
    outline = lerp(primary, Color.Gray, 0.68f),
    outlineVariant = lerp(primary, Color.White, 0.72f),
    surfaceContainerLowest = lerp(primary, Color.White, 0.995f),
    surfaceContainerLow = lerp(primary, Color.White, 0.965f),
    surfaceContainer = lerp(primary, Color.White, 0.94f),
    surfaceContainerHigh = lerp(primary, Color.White, 0.91f),
    surfaceContainerHighest = lerp(primary, Color.White, 0.87f),
    scrim = lerp(primary, Color.Black, 0.82f)
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
    secondaryContainer = lerp(primary, Color.Black, 0.62f),
    onSecondaryContainer = lerp(primary, Color.White, 0.84f),
    tertiary = tertiary,
    onTertiary = Color(0xFF17181A),
    tertiaryContainer = lerp(tertiary, Color.Black, 0.60f),
    onTertiaryContainer = lerp(tertiary, Color.White, 0.84f),
    background = lerp(primary, Color.Black, 0.91f),
    onBackground = lerp(primary, Color.White, 0.88f),
    surface = lerp(primary, Color.Black, 0.91f),
    onSurface = lerp(primary, Color.White, 0.88f),
    surfaceTint = primary,
    surfaceVariant = lerp(primary, Color.Black, 0.72f),
    onSurfaceVariant = lerp(primary, Color.White, 0.73f),
    outline = lerp(primary, Color.Gray, 0.58f),
    outlineVariant = lerp(primary, Color.Black, 0.50f),
    surfaceContainerLowest = lerp(primary, Color.Black, 0.91f),
    surfaceContainerLow = lerp(primary, Color.Black, 0.86f),
    surfaceContainer = lerp(primary, Color.Black, 0.82f),
    surfaceContainerHigh = lerp(primary, Color.Black, 0.77f),
    surfaceContainerHighest = lerp(primary, Color.Black, 0.72f),
    scrim = lerp(primary, Color.Black, 0.82f)
)

private fun customLight(primary: Color) = baseLight(
    primary = primary,
    primaryContainer = lerp(primary, Color.White, 0.82f),
    onPrimaryContainer = lerp(primary, Color.Black, 0.78f),
    secondary = lerp(primary, Color.Gray, 0.42f),
    tertiary = lerp(primary, Color(0xFF7D5260), 0.38f)
)

private fun customDark(primary: Color): ColorScheme {
    val bright = lerp(primary, Color.White, 0.46f)
    return baseDark(
        primary = bright,
        primaryContainer = lerp(primary, Color.Black, 0.34f),
        onPrimaryContainer = lerp(primary, Color.White, 0.82f),
        secondary = lerp(primary, Color.White, 0.56f),
        tertiary = lerp(primary, Color(0xFFFFD8E4), 0.45f)
    )
}

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
