package io.github.lunalight.paperread.reader.txt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterSplitterTest {

    private fun coverageOk(text: String, refs: List<ChapterRef>): Boolean {
        if (refs.isEmpty()) return text.isEmpty()
        if (refs.first().charStart != 0 || refs.last().charEnd != text.length) return false
        return refs.zipWithNext().all { (a, b) -> a.charEnd == b.charStart }
    }

    @Test
    fun `splits on 第X章 headings`() {
        val text = """
            开头引子。
            第一章 荒原之始
            少年提灯独行。
            第二章 古城灯火
            城池在夜里醒来。
            第三章 远行之约
            他决定北上。
        """.trimIndent()
        val refs = ChapterSplitter.split(text)
        assertTrue(coverageOk(text, refs))
        assertEquals(4, refs.size)
        assertEquals("开头", refs[0].title)
        assertEquals("第一章 荒原之始", refs[1].title)
        assertEquals("第二章 古城灯火", refs[2].title)
        assertEquals("第三章 远行之约", refs[3].title)
    }

    @Test
    fun `splits on Chapter N headings`() {
        val text = """
            Prologue text.
            Chapter 1 The Lamp
            Body one.
            Chapter 2 The City
            Body two.
            Chapter 3 The Road
            Body three.
        """.trimIndent()
        val refs = ChapterSplitter.split(text)
        assertTrue(coverageOk(text, refs))
        assertEquals(4, refs.size)
        assertEquals("Chapter 1 The Lamp", refs[1].title)
    }

    @Test
    fun `splits on special headings 序章 番外 后记`() {
        val text = """
            序章 火与雪
            Body zero.
            楔子
            Body half.
            番外篇 灯下
            Body extra.
            后记
            Body end.
        """.trimIndent()
        val refs = ChapterSplitter.split(text)
        assertTrue(coverageOk(text, refs))
        assertTrue(refs.map { it.title }.containsAll(listOf("序章 火与雪", "楔子", "番外篇 灯下", "后记")))
    }

    @Test
    fun `skips table of contents page`() {
        val text = """
            目录
            第一章 荒原之始
            第二章 古城灯火
            第三章 远行之约
            第一章 荒原之始
            少年提灯独行，走了很久。
            第二章 古城灯火
            城池在夜里醒来，灯火次第亮起。
            第三章 远行之约
            他决定北上，穿过荒原。
        """.trimIndent()
        val refs = ChapterSplitter.split(text)
        assertTrue(coverageOk(text, refs))
        // 目录页整体归入「开头」章，正文首章标题不被吞
        assertEquals(4, refs.size)
        assertEquals("开头", refs[0].title)
        assertTrue(text.substring(refs[0].charStart, refs[0].charEnd).contains("目录"))
        assertEquals("第一章 荒原之始", refs[1].title)
        assertEquals("第二章 古城灯火", refs[2].title)
        assertEquals("第三章 远行之约", refs[3].title)
        assertTrue(
            text.substring(refs[1].charStart, refs[1].charEnd).startsWith("第一章 荒原之始"),
        )
    }

    @Test
    fun `conservative falls back to forced segments when fewer than 3 strong matches`() {
        val text = "第一章 only\n" + "很长的正文。".repeat(600) // 仅 1 个强匹配，总长 > 3000
        val refs = ChapterSplitter.split(text, SplitStrategy.CONSERVATIVE)
        assertTrue(coverageOk(text, refs))
        assertTrue("应触发强制分段", refs.size >= 2)
        assertTrue(refs.all { it.title.startsWith("第 ") && it.title.endsWith(" 段") })
    }

    @Test
    fun `aggressive splits short lines surrounded by blank lines`() {
        val text = """
            正文开始。

            小标题一

            内容甲内容甲。

            小标题二

            内容乙内容乙。

            小标题三

            内容丙内容丙。
        """.trimIndent()
        val refs = ChapterSplitter.split(text, SplitStrategy.AGGRESSIVE)
        assertTrue(coverageOk(text, refs))
        assertTrue(refs.map { it.title }.containsAll(listOf("小标题一", "小标题二", "小标题三")))
    }

    @Test
    fun `conservative does not use heuristic short lines`() {
        val text = """
            正文开始。

            小标题一

            内容甲内容甲。

            小标题二

            内容乙内容乙。

            小标题三

            内容丙内容丙。
        """.trimIndent()
        val refs = ChapterSplitter.split(text, SplitStrategy.CONSERVATIVE)
        assertTrue(coverageOk(text, refs))
        // 保守策略下不识别短行标题，走强制分段
        assertTrue(refs.none { it.title == "小标题一" })
    }

    @Test
    fun `forced segmentation produces continuous coverage`() {
        val text = "啊".repeat(9500) // 无任何章节特征
        val refs = ChapterSplitter.split(text, SplitStrategy.CONSERVATIVE)
        assertTrue(coverageOk(text, refs))
        assertEquals(4, refs.size) // 3000×3 + 500
        assertTrue(refs.all { it.title.matches(Regex("第 \\d+ 段")) })
    }

    @Test
    fun `normalizeLineEndings converts CRLF and CR`() {
        assertEquals("a\nb\nc", ChapterSplitter.normalizeLineEndings("a\r\nb\rc"))
        assertEquals("a\n\nb", ChapterSplitter.normalizeLineEndings("a\r\n\r\nb"))
    }

    @Test
    fun `empty text yields no chapters`() {
        assertEquals(emptyList<ChapterRef>(), ChapterSplitter.split(""))
    }

    @Test
    fun `mixed patterns are merged and sorted`() {
        val text = """
            Chapter 1 Lamp
            Body.
            第二章 城
            Body.
            序章
            Body.
            第三章 路
            Body.
            第四章 终点
            Body.
        """.trimIndent()
        val refs = ChapterSplitter.split(text)
        assertTrue(coverageOk(text, refs))
        val titles = refs.map { it.title }
        assertEquals(listOf("Chapter 1 Lamp", "第二章 城", "序章", "第三章 路", "第四章 终点"), titles)
        // index 递增
        assertEquals(refs.indices.toList(), refs.map { it.index })
    }

    @Test
    fun `text before first heading becomes 开头 chapter`() {
        val text = """
            卷首语一段。
            第一章 甲
            内容。
            第二章 乙
            内容。
            第三章 丙
            内容。
        """.trimIndent()
        val refs = ChapterSplitter.split(text)
        assertEquals("开头", refs[0].title)
        assertTrue(refs[0].charEnd <= refs[1].charStart)
        assertTrue(text.substring(refs[0].charStart, refs[0].charEnd).contains("卷首语"))
    }
}
