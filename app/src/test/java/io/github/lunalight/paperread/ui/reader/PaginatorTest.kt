package io.github.lunalight.paperread.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginatorTest {

    @Test
    fun `空章节返回空页列表`() {
        assertTrue(packPages(emptyList(), 100).isEmpty())
    }

    @Test
    fun `所有段落在单页内返回仅一页且起始段为0`() {
        val pages = packPages(listOf(50, 50, 50), 200)
        assertEquals(listOf(PageBreak(0)), pages)
    }

    @Test
    fun `恰好铺满单页返回仅一页`() {
        val pages = packPages(listOf(100, 100, 100), 300)
        assertEquals(1, pages.size)
        assertEquals(0, pages[0].startParagraph)
    }

    @Test
    fun `每段略超半页时每段独占一页`() {
        val pages = packPages(listOf(60, 60, 60), 100)
        assertEquals(listOf(PageBreak(0), PageBreak(1), PageBreak(2)), pages)
    }

    @Test
    fun `中间段落放不下则另起新页`() {
        // 页高 250：100+100=200 放得下；+100=300>250 -> 第 3 段另起新页。
        // 即共 2 页：页 0 装段 0+1，页 1 从段 2 开始。
        val pages = packPages(listOf(100, 100, 100), 250)
        assertEquals(listOf(PageBreak(0), PageBreak(2)), pages)
    }

    @Test
    fun `单段超过页高时独占一页且偏移为0`() {
        val pages = packPages(listOf(500), 100)
        assertEquals(listOf(PageBreak(0, 0)), pages)
    }

    @Test
    fun `多页填充且边界正确`() {
        // 页高 100：段 [40,40,40,40] -> 页0(40+40=80)，页1(40+40=80)
        val pages = packPages(listOf(40, 40, 40, 40), 100)
        assertEquals(listOf(PageBreak(0), PageBreak(2)), pages)
    }

    @Test
    fun `pageIndexForPosition 反查正确`() {
        val pages = listOf(PageBreak(0), PageBreak(2), PageBreak(5))
        assertEquals(0, pageIndexForPosition(pages, 0))
        assertEquals(0, pageIndexForPosition(pages, 1))
        assertEquals(1, pageIndexForPosition(pages, 2))
        assertEquals(1, pageIndexForPosition(pages, 4))
        assertEquals(2, pageIndexForPosition(pages, 5))
        assertEquals(2, pageIndexForPosition(pages, 99))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `页高非正抛异常`() {
        packPages(listOf(10), 0)
    }
}
