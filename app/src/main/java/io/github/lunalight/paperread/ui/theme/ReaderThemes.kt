package io.github.lunalight.paperread.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 阅读器主题预设。
 *
 * 与 Material 主题解耦：阅读区只认这里的四个色 token，
 * 保证任何系统/应用主题下阅读体验一致。
 */
@Immutable
data class ReaderTheme(
    val id: String,
    val displayName: String,
    val background: Color,
    val onBackground: Color,
    val secondary: Color,
    val accent: Color,
    val isDark: Boolean,
)

object ReaderThemes {

    val presets: List<ReaderTheme> = listOf(
        ReaderTheme(
            id = "paper",
            displayName = "纸白",
            background = Color(0xFFFFFBFF),
            onBackground = Color(0xFF1A1C1E),
            secondary = Color(0xFF6F6B75),
            accent = Color(0xFF2F5D50),
            isDark = false,
        ),
        ReaderTheme(
            id = "beige",
            displayName = "米黄",
            background = Color(0xFFF6EBD7),
            onBackground = Color(0xFF453A2B),
            secondary = Color(0xFF8A7B63),
            accent = Color(0xFF8A6D3B),
            isDark = false,
        ),
        ReaderTheme(
            id = "moss",
            displayName = "苔绿",
            background = Color(0xFFDCE8DC),
            onBackground = Color(0xFF24352A),
            secondary = Color(0xFF5F7267),
            accent = Color(0xFF2F5D50),
            isDark = false,
        ),
        ReaderTheme(
            id = "indigo",
            displayName = "黛蓝",
            background = Color(0xFFDFE7F0),
            onBackground = Color(0xFF1F2A38),
            secondary = Color(0xFF5E6B7E),
            accent = Color(0xFF3E5C76),
            isDark = false,
        ),
        ReaderTheme(
            id = "ink",
            displayName = "墨黑",
            background = Color(0xFF121316),
            onBackground = Color(0xFFC8C4BD),
            secondary = Color(0xFF77736C),
            accent = Color(0xFFA7C8BC),
            isDark = true,
        ),
    )

    fun byId(id: String): ReaderTheme =
        presets.firstOrNull { it.id == id } ?: presets.first()
}
