package io.github.lunalight.paperread.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.unit.Density

/**
 * 把章节段落按当前排版度量出每段高度，再用纯函数 [packPages] 装箱成等高页。
 *
 * 不参与持久化，进度仍由 [io.github.lunalight.paperread.domain.model.ReaderPosition] 承载。
 *
 * @param paragraphSpacingPx 段距(px)，计入每段高度以匹配渲染（每段底部 padding）
 * 度量用 [Paragraph]（与真实 [androidx.compose.foundation.text.BasicText] 同一排版引擎），
 * 因此分页结果与渲染一致。仅依赖 Compose 的 [Density] 与 [FontFamily.Resolver]，
 */
@Composable
fun rememberChapterPages(
    paragraphs: List<String>,
    textStyle: TextStyle,
    pageWidthPx: Int,
    pageHeightPx: Int,
    density: Density,
    fontFamilyResolver: FontFamily.Resolver,
    paragraphSpacingPx: Int,
): List<PageBreak> {
    return remember(
        paragraphs,
        textStyle,
        pageWidthPx,
        pageHeightPx,
        paragraphSpacingPx,
        density,
        fontFamilyResolver,
    ) {
        if (paragraphs.isEmpty() || pageWidthPx <= 0 || pageHeightPx <= 0) {
            emptyList()
        } else {
            // 渲染用两端对齐，度量须用同一对齐才能得到一致的行断与高度
            val measureStyle = textStyle.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Justify)
            val heights = paragraphs.map { text ->
                val intrinsics = ParagraphIntrinsics(
                    text = text,
                    style = measureStyle,
                    density = density,
                    fontFamilyResolver = fontFamilyResolver,
                )
                val paragraph = Paragraph(
                    paragraphIntrinsics = intrinsics,
                    width = pageWidthPx.toFloat(),
                    maxLines = Int.MAX_VALUE,
                    ellipsis = false,
                )
                (paragraph.height + paragraphSpacingPx).toInt().coerceAtLeast(1)
            }
            packPages(heights, pageHeightPx)
        }
    }
}
