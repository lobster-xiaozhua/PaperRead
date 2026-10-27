package io.github.lunalight.paperread.ui.reader.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * 命中词高亮（AC-15.3：点击搜索结果后正文该段落的高亮词要可见）。
 *
 * 刻意做成「纯函数对象」而不是可组合函数：
 * - 匹配逻辑（找区间、忽略大小写）与渲染无关，可在**纯 JVM** 单测里逐条断言，
 *   不需要 Robolectric 渲染树（`AnnotatedString` / `SpanStyle` 本身不依赖 Android）；
 * - 搜索面板（结果摘要）与正文（定位段落）共用同一份匹配口径，不会出现
 *   「面板里高亮了、正文里没高亮」的两套规则。
 */
object SearchHighlighter {

    /** 高亮底色透明度：够显眼又不至于盖住正文色（深色主题下同样可读）。 */
    const val BACKGROUND_ALPHA: Float = 0.32f

    /**
     * 求 [query] 在 [text] 中的全部命中区间（忽略大小写、不重叠、升序）。
     *
     * 空关键词返回空列表（调用方可能传空串：搜索框刚被清空时正文还没重新渲染）。
     */
    fun matchRanges(text: String, query: String): List<IntRange> {
        if (query.isEmpty() || text.isEmpty()) return emptyList()
        val ranges = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val index = text.indexOf(query, from, ignoreCase = true)
            if (index < 0) return ranges
            ranges += index until (index + query.length)
            from = index + query.length
            if (from >= text.length) return ranges
        }
    }

    /**
     * 正文渲染便利入口：有关键词就上色，没有则原样返回（零 span）。
     *
     * 两条渲染路径（滚动 / 翻页）与双页跨页都调这一个函数，保证「同一次搜索
     * 在三种阅读形态下高亮一致」。
     *
     * @param query 该段需要高亮的关键词；`null` / 空串 = 本段不参与高亮
     */
    fun annotate(paragraph: String, query: String?, background: Color): AnnotatedString =
        if (query.isNullOrEmpty()) AnnotatedString(paragraph) else highlight(paragraph, query, background)

    /**
     * 把 [text] 中所有 [query] 命中处标成高亮，返回可直接交给 `Text` 的
     * [AnnotatedString]；无命中时等价于原文（返回同样的字面文本，零 span）。
     *
     * @param background 高亮底色（通常取主题 accent + 透明度）
     * @param bold 是否加粗（正文默认加粗，摘要处可关掉以免行高跳动）
     */
    fun highlight(
        text: String,
        query: String,
        background: Color,
        bold: Boolean = true,
    ): AnnotatedString {
        val ranges = matchRanges(text, query)
        if (ranges.isEmpty()) return AnnotatedString(text)
        val style = SpanStyle(
            background = background.copy(alpha = BACKGROUND_ALPHA),
            fontWeight = if (bold) FontWeight.SemiBold else null,
        )
        return buildAnnotatedString {
            var cursor = 0
            ranges.forEach { range ->
                if (range.first > cursor) append(text.substring(cursor, range.first))
                withStyle(style) { append(text.substring(range.first, range.last + 1)) }
                cursor = range.last + 1
            }
            if (cursor < text.length) append(text.substring(cursor))
        }
    }
}
