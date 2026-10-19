package io.github.lunalight.paperread.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [paginate] 纯算法单测（JVM，无 Android 依赖）：段边界切页 + 巨型段独占 + 恢复反查。
 */
class PaginationTest {

    @Test
    fun `多段同屏合并为一页`() {
        // 视口 100，三段各 20，段距 4 → 全部放得下（20+4+20+4+20=68 ≤ 100）
        val pages = paginate(listOf(20, 20, 20), availableHeight = 100, paragraphSpacing = 4)
        assertEquals(listOf(PageBound(0)), pages)
    }

    @Test
    fun `段高累计超屏后另起一页`() {
        // 视口 50，三段各 20，段距 4：第 3 段会让 20+4+20+4+20=68>50 → 拆成两页
        val pages = paginate(listOf(20, 20, 20), availableHeight = 50, paragraphSpacing = 4)
        assertEquals(listOf(PageBound(0), PageBound(2)), pages)
    }

    @Test
    fun `恰好填满也成单页，多一段才翻页`() {
        // 视口 44：20+4+20 = 44 恰好 → 单页；若再加一段则超
        val one = paginate(listOf(20, 20), availableHeight = 44, paragraphSpacing = 4)
        assertEquals(listOf(PageBound(0)), one)
        val two = paginate(listOf(20, 20, 20), availableHeight = 44, paragraphSpacing = 4)
        assertEquals(listOf(PageBound(0), PageBound(2)), two)
    }

    @Test
    fun `单段高于整屏仍独占一页不丢字`() {
        // 视口 30，单段高 100：不拦截（used==0），该段独占一页
        val pages = paginate(listOf(100), availableHeight = 30, paragraphSpacing = 4)
        assertEquals(listOf(PageBound(0)), pages)
    }

    @Test
    fun `巨型段与普通段混排各自成页`() {
        // 视口 50：首段 100 独占一页；后续两段 20+4+20=44 ≤ 50 同页
        val pages = paginate(listOf(100, 20, 20), availableHeight = 50, paragraphSpacing = 4)
        assertEquals(listOf(PageBound(0), PageBound(1)), pages)
    }

    @Test
    fun `空输入返回空页列表`() {
        assertEquals(emptyList<PageBound>(), paginate(emptyList(), 100, 4))
    }

    @Test
    fun `段距只在段间计入，不计入页首`() {
        // 视口 44：第一段 20（页首不加段距）+ 段距 4 + 第二段 20 = 44，恰好合成单页；
        // 若页首被误加段距（4 + 20 + 4 + 20 = 48）则会多切一页——以此锁定「页首不计段距」
        val one = paginate(listOf(20, 20), availableHeight = 44, paragraphSpacing = 4)
        assertEquals(listOf(PageBound(0)), one)
    }

    @Test
    fun `恢复位置反查到正确的页`() {
        // 三页：段 [0], [1,2,3], [4,5]
        val pages = listOf(PageBound(0), PageBound(1), PageBound(4))
        assertEquals(0, pageIndexForStartParagraph(pages, 0))
        assertEquals(1, pageIndexForStartParagraph(pages, 1))
        assertEquals(1, pageIndexForStartParagraph(pages, 3))
        assertEquals(2, pageIndexForStartParagraph(pages, 4))
        assertEquals(2, pageIndexForStartParagraph(pages, 99)) // 越界夹到最后
        assertEquals(0, pageIndexForStartParagraph(emptyList(), 5))
    }
}
