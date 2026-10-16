package io.github.lunalight.paperread.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightScheme = lightColorScheme(
    primary = PaperGreen,
    onPrimary = PaperWhite,
    primaryContainer = PaperGreenContainer,
    onPrimaryContainer = Ink,
    secondary = PaperBrown,
    onSecondary = PaperWhite,
    secondaryContainer = PaperBrownLight,
    onSecondaryContainer = Ink,
    tertiary = PaperBlue,
    onTertiary = PaperWhite,
    background = PaperWhite,
    onBackground = Ink,
    surface = PaperWhite,
    onSurface = Ink,
    surfaceVariant = SurfaceLight,
    onSurfaceVariant = OutlineLight,
)

private val DarkScheme = darkColorScheme(
    primary = PaperGreenLight,
    onPrimary = Ink,
    primaryContainer = PaperGreen,
    onPrimaryContainer = PaperWhite,
    secondary = PaperBrownLight,
    onSecondary = Ink,
    tertiary = PaperBlueLight,
    onTertiary = Ink,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = PaperBrownLight,
)

/**
 * 纸阅应用主题。
 *
 * 注意：阅读区配色不走 Material 主题，而使用 [ReaderTheme] 预设，
 * 保证任何系统/应用主题下阅读体验一致。
 */
@Composable
fun PaperReadTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = Typography(),
        content = content,
    )
}
