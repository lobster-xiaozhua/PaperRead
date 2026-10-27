package io.github.lunalight.paperread.ui.reader.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命中词高亮纯逻辑单测（AC-15.3，纯 JVM：`AnnotatedString` 不依赖 Android 运行时）。
 *
 * 之所以不写成 Robolectric 渲染用例：这里要验的是「哪几段字符被标成什么样式」，
 * 属于数据断言；渲染是否画出来由 ui.smoke 承载，两者职责分开。
 */
class SearchHighlighterTest {

    private val accent = Color(0xFF3366FF)

    @Test
    fun `匹配区间忽略大小写`() {
        val ranges = SearchHighlighter.matchRanges("Harry and HARRY", "harry")

        assertEquals(2, ranges.size)
        assertEquals(0..4, ranges[0])
        assertEquals("第二次命中在句末（下标 10 起）", 10..14, ranges[1])
    }

    @Test
    fun `不重叠匹配`() {
        val ranges = SearchHighlighter.matchRanges("哈哈哈", "哈哈")

        assertEquals("aaa 里 aa 只算一次", 1, ranges.size)
        assertEquals(0..1, ranges[0])
    }

    @Test
    fun `空关键词或空文本无区间`() {
        assertTrue(SearchHighlighter.matchRanges("正文", "").isEmpty())
        assertTrue(SearchHighlighter.matchRanges("", "灵剑").isEmpty())
    }

    @Test
    fun `无命中时原文不变且无样式`() {
        val annotated = SearchHighlighter.highlight("第一章 风起", "灵剑", accent)

        assertEquals("第一章 风起", annotated.text)
        assertTrue(annotated.spanStyles.isEmpty())
    }

    @Test
    fun `命中处加上 accent 底色与粗体`() {
        val annotated = SearchHighlighter.highlight("天不生我李淳罡，剑来", "李淳罡", accent)

        assertEquals("字面文本必须与原段落完全一致（否则正文会串字）", "天不生我李淳罡，剑来", annotated.text)
        val span = annotated.spanStyles.single()
        assertEquals(4, span.start)
        assertEquals(7, span.end)
        assertEquals(accent.copy(alpha = SearchHighlighter.BACKGROUND_ALPHA), span.item.background)
        assertEquals(FontWeight.SemiBold, span.item.fontWeight)
    }

    @Test
    fun `摘要模式可以取消粗体`() {
        val annotated = SearchHighlighter.highlight("灵剑出世", "灵剑", accent, bold = false)

        assertNull("摘要列表里不加粗，避免行高跳动", annotated.spanStyles.single().item.fontWeight)
    }

    @Test
    fun `多次命中全部上色`() {
        val annotated = SearchHighlighter.highlight("剑来，剑来，还是剑来", "剑来", accent)

        assertEquals(3, annotated.spanStyles.size)
        assertEquals(listOf(0, 3, 8), annotated.spanStyles.map { it.start })
    }

    @Test
    fun `annotate 对空关键词原样返回`() {
        val annotated = SearchHighlighter.annotate("正文段落", null, accent)

        assertEquals("正文段落", annotated.text)
        assertTrue(annotated.spanStyles.isEmpty())
    }

    @Test
    fun `annotate 有关键词时等价于 highlight`() {
        val annotated = SearchHighlighter.annotate("灵剑出世", "灵剑", accent)

        assertEquals("灵剑出世", annotated.text)
        assertEquals(1, annotated.spanStyles.size)
    }

    @Test
    fun `命中在段首与段尾都能标注`() {
        val head = SearchHighlighter.highlight("灵剑在此", "灵剑", accent)
        val tail = SearchHighlighter.highlight("在此灵剑", "灵剑", accent)

        assertEquals(0, head.spanStyles.single().start)
        assertEquals(2, tail.spanStyles.single().start)
        assertEquals(4, tail.spanStyles.single().end)
    }
}
