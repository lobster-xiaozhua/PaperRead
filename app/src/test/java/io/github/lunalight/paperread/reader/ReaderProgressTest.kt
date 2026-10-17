package io.github.lunalight.paperread.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ReaderProgress 纯函数单测（JVM，无 Android 依赖）。
 */
class ReaderProgressTest {

    @Test
    fun `scanParagraphs 空文本`() {
        val scan = ReaderProgress.scanParagraphs("")
        assertEquals(emptyList<String>(), scan.texts)
        assertEquals(emptyList<Int>(), scan.starts)
    }

    @Test
    fun `scanParagraphs 无空行时原始起点即渲染长度累加`() {
        val scan = ReaderProgress.scanParagraphs("ab\ncde")
        assertEquals(listOf("ab", "cde"), scan.texts)
        // 0, "ab"+\n = 3
        assertEquals(listOf(0, 3), scan.starts)
    }

    @Test
    fun `scanParagraphs 空行被过滤但起点仍按原文累加`() {
        // "a"(0) + \n(1) → 空行占 1 + \n → "c" 起点 3
        val scan = ReaderProgress.scanParagraphs("a\n\nc")
        assertEquals(listOf("a", "c"), scan.texts)
        assertEquals(listOf(0, 3), scan.starts)
    }

    @Test
    fun `scanParagraphs 段首前导空白计入原始起点`() {
        // 行 0 "  a" 段首起点 2；行 1 "   x" 起点 4 + 前导 3 = 7
        val scan = ReaderProgress.scanParagraphs("  a\n   x")
        assertEquals(listOf("a", "x"), scan.texts)
        assertEquals(listOf(2, 7), scan.starts)
    }

    @Test
    fun `scanParagraphs 尾随空白不影响后续段起点`() {
        // "a  "(0,3) + \n → "b" 起点 4
        val scan = ReaderProgress.scanParagraphs("a  \nb")
        assertEquals(listOf("a", "b"), scan.texts)
        assertEquals(listOf(0, 4), scan.starts)
    }

    @Test
    fun `chapterCharOffset 取段落原始起点`() {
        val starts = listOf(0, 3, 7)
        assertEquals(0, ReaderProgress.chapterCharOffset(starts, 0))
        assertEquals(3, ReaderProgress.chapterCharOffset(starts, 1))
        assertEquals(7, ReaderProgress.chapterCharOffset(starts, 2))
    }

    @Test
    fun `chapterCharOffset 空表或越界夹取`() {
        assertEquals(0, ReaderProgress.chapterCharOffset(emptyList(), 3))
        val starts = listOf(0, 3, 7)
        assertEquals(0, ReaderProgress.chapterCharOffset(starts, -3))
        assertEquals(7, ReaderProgress.chapterCharOffset(starts, 99))
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

    // ---- B4 进度口径统一：与切分器原文偏移同一坐标系 ----

    @Test
    fun `B4 长书首尾进度误差在万分之一以内`() {
        // 模拟一章原文：每段 99 字符 + '\n'，共 100 段 → 总长 9999
        val raw = (0 until 100).joinToString("\n") { "段".repeat(99) }
        assertEquals(9999, raw.length)
        val scan = ReaderProgress.scanParagraphs(raw)
        assertEquals(100, scan.texts.size)
        // 首段起点 0、末段起点 9900，与手算原文偏移一致
        assertEquals(0, scan.starts.first())
        assertEquals(9900, scan.starts.last())
        // 全书 = 本章，读到末段起点 = 9900/9999 ≈ 99.01%，误差 0
        assertEquals(
            99.01f,
            ReaderProgress.percentOf(0, ReaderProgress.chapterCharOffset(scan.starts, 99), 9999),
            0.001f,
        )
    }

    @Test
    fun `B4 空行与前导空白下偏移仍等于原文位置`() {
        // 章原文：3 空行 + "  甲" + 空行 + "乙"，总长 9
        // 偏移：3 空行占 4 → "  甲" 段首起点 5；+3+1+1 → "乙" 起点 8
        val raw = "\n\n\n  甲\n\n乙"
        val scan = ReaderProgress.scanParagraphs(raw)
        assertEquals(listOf("甲", "乙"), scan.texts)
        assertEquals(listOf(5, 8), scan.starts)
        // 进度以原始起点计算：8 / 9 ≈ 88.9%（trim 口径会把两段都算成 0 偏移）
        assertEquals(
            88.9f,
            ReaderProgress.percentOf(0, ReaderProgress.chapterCharOffset(scan.starts, 1), 9),
            0.05f,
        )
    }
}
