package io.github.lunalight.paperread.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ReaderProgress 纯函数单测（JVM，无 Android 依赖）。
 */
class ReaderProgressTest {

    @Test
    fun `paragraphCharStarts 空列表返回单个 0`() {
        assertEquals(listOf(0), ReaderProgress.paragraphCharStarts(emptyList()))
    }

    @Test
    fun `paragraphCharStarts 单段`() {
        assertEquals(listOf(0, 4), ReaderProgress.paragraphCharStarts(listOf("abc")))
    }

    @Test
    fun `paragraphCharStarts 累计含段间分隔符`() {
        val starts = ReaderProgress.paragraphCharStarts(listOf("ab", "cde"))
        // 0, "ab"+\n = 3, +"cde"+\n = 7
        assertEquals(listOf(0, 3, 7), starts)
    }

    @Test
    fun `chapterCharOffset 空列表或首段为 0`() {
        assertEquals(0, ReaderProgress.chapterCharOffset(emptyList(), 0))
        assertEquals(0, ReaderProgress.chapterCharOffset(listOf("ab", "cde"), 0))
        assertEquals(0, ReaderProgress.chapterCharOffset(listOf("ab", "cde"), -3))
    }

    @Test
    fun `chapterCharOffset 为前序段落长度加一分隔符`() {
        assertEquals(3, ReaderProgress.chapterCharOffset(listOf("ab", "cde"), 1))
        assertEquals(7, ReaderProgress.chapterCharOffset(listOf("ab", "cde"), 2))
    }

    @Test
    fun `chapterCharOffset 越界夹到章总字符数`() {
        val paragraphs = listOf("ab", "cde")
        assertEquals(7, ReaderProgress.chapterCharOffset(paragraphs, 99))
        assertEquals(
            ReaderProgress.paragraphCharStarts(paragraphs).last(),
            ReaderProgress.chapterCharOffset(paragraphs, 99),
        )
    }

    @Test
    fun `percentOf 总字符为 0 时返回 0`() {
        assertEquals(0f, ReaderProgress.percentOf(0, 0, 0), 0.001f)
    }

    @Test
    fun `percentOf 基本比例`() {
        assertEquals(0f, ReaderProgress.percentOf(0, 0, 100), 0.001f)
        assertEquals(50f, ReaderProgress.percentOf(50, 0, 100), 0.001f)
        assertEquals(100f, ReaderProgress.percentOf(100, 0, 100), 0.001f)
    }

    @Test
    fun `percentOf 字符加权而非章节百分比`() {
        // 两章：第 1 章 900 字符、第 2 章 100 字符；读完第 1 章 = 90%，而非 50%
        assertEquals(90f, ReaderProgress.percentOf(900, 0, 1000), 0.001f)
        assertEquals(95f, ReaderProgress.percentOf(900, 50, 1000), 0.001f)
    }

    @Test
    fun `percentOf 夹到 0 至 100`() {
        assertEquals(100f, ReaderProgress.percentOf(150, 50, 100), 0.001f)
        assertEquals(0f, ReaderProgress.percentOf(0, 0, 100), 0.001f)
    }
}
