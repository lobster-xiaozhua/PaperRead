package io.github.lunalight.paperread.ui.bookshelf

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import io.github.lunalight.paperread.domain.model.Book

/**
 * 程序化封面：由书名哈希决定渐变色对与首字，无需图片资源、零网络。
 *
 * 色板取低饱和「书页 / 水墨」系，保证任意组合在浅色 UI 上不刺眼。
 */
object CoverGenerator {

    data class CoverStyle(val start: Color, val end: Color, val glyph: String) {
        fun brush(): Brush = Brush.linearGradient(listOf(start, end))
    }

    private val PALETTES: List<Pair<Color, Color>> = listOf(
        Color(0xFF8E6E4E) to Color(0xFFC9A87C), // 牛皮纸
        Color(0xFF4E6E5D) to Color(0xFF8FAE94), // 苔绿
        Color(0xFF3E5470) to Color(0xFF7C93B0), // 黛蓝
        Color(0xFF6E4E57) to Color(0xFFA87C88), // 藕荷
        Color(0xFF5D5E6E) to Color(0xFF9092A0), // 石墨
        Color(0xFF7A6240) to Color(0xFFB89B6A), // 秋褐
        Color(0xFF46607A) to Color(0xFF6E97B8), // 远山
        Color(0xFF6B5B7A) to Color(0xFF9C8BA8), // 暮紫
    )

    /**
     * @param fallbackGlyph 书名首字为空时的兜底字形。由调用方从 `strings.xml` 传入
     *   （T-022：本类不依赖 Android 资源，保持「零图片、零联网、零资源」的纯算法性）。
     */
    fun styleFor(book: Book, fallbackGlyph: String): CoverStyle =
        styleFor(book.title, book.dirName, fallbackGlyph)

    fun styleFor(title: String, seed: String, fallbackGlyph: String): CoverStyle {
        val hash = (title.hashCode() * 31 + seed.hashCode()) and 0x7FFFFFFF
        val (start, end) = PALETTES[hash % PALETTES.size]
        val glyph = title.trim().firstOrNull()?.toString() ?: fallbackGlyph
        return CoverStyle(start, end, glyph)
    }
}
