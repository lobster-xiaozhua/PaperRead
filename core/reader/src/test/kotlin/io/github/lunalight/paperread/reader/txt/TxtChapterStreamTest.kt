package io.github.lunalight.paperread.reader.txt

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * 流式章节状态机测试：
 *  - 语义钉死（与 ChapterSplitterTest 同源的期望标题/章节数）；
 *  - 跨 chunk 大小不变性（1/2/3/5/7/13/64/1024/默认）——流式正确性的核心保障；
 *  - 区间连续 + 双偏移自洽 + 按字节偏移读回章节文本 round-trip；
 *  - 跨编码（UTF-8 / GB18030 / UTF-16LE）多字节字符跨块边界；
 *  - 取消传播、进度单调性、空输入、BOM、超长行、大文件冒烟。
 */
class TxtChapterStreamTest {

    private data class Fixture(
        val name: String,
        val text: String,
        val expectedTitles: List<String>? = null,
    )

    private val novel4 = """
        开头引子。
        第一章 荒原之始
        少年提灯独行。
        第二章 古城灯火
        城池在夜里醒来。
        第三章 远行之约
        他决定北上。
    """.trimIndent()

    private val tocPage = """
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

    private val chapterN = """
        Prologue text.
        Chapter 1 The Lamp
        Body one.
        Chapter 2 The City
        Body two.
        Chapter 3 The Road
        Body three.
    """.trimIndent()

    private val specialHeadings = """
        序章 火与雪
        Body zero.
        楔子
        Body half.
        番外篇 灯下
        Body extra.
        后记
        Body end.
    """.trimIndent()

    private val aggressiveShort = """
        正文开始。

        小标题一

        内容甲内容甲。

        小标题二

        内容乙内容乙。

        小标题三

        内容丙内容丙。
    """.trimIndent()

    private val mixed = """
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

    private val crlfText = "第一章 甲\r\n内容\r第二章 乙\r\n内容\r第三章 丙\r\n内容"

    private val beyond200Lines: String = buildString {
        for (i in 1..250) appendLine("填充行$i。")
        appendLine("目录")
        appendLine("第一章 甲")
        appendLine("第二章 乙")
        appendLine("第三章 丙")
        appendLine("第一章 甲")
        appendLine("正文一。")
        appendLine("第二章 乙")
        appendLine("正文二。")
        appendLine("第三章 丙")
        appendLine("正文三。")
    }

    private val chainFailThenSecondToc = """
        目录
        普通行
        目录
        第一章 甲
        第二章 乙
        第三章 丙
        第一章 甲
        正文开始。
        第二章 乙
        正文继续。
        第三章 丙
        正文结束。
    """.trimIndent()

    private val tocAtEof = "目录\n第一章 甲\n第二章 乙\n第三章 丙\n"

    private val repeatedTitleBelowThree = "目录\n第一章 甲\n第一章 甲\n第一章 甲\n正文开始。\n更多正文。"

    private val fixtures = listOf(
        Fixture("novel4", novel4, listOf("开头", "第一章 荒原之始", "第二章 古城灯火", "第三章 远行之约")),
        Fixture("tocPage", tocPage, listOf("开头", "第一章 荒原之始", "第二章 古城灯火", "第三章 远行之约")),
        Fixture("chapterN", chapterN, listOf("开头", "Chapter 1 The Lamp", "Chapter 2 The City", "Chapter 3 The Road")),
        Fixture("specialHeadings", specialHeadings, listOf("序章 火与雪", "楔子", "番外篇 灯下", "后记")),
        // 保守策略不启用短行启发式：全文不足一段 → 强制分段单段（激进语义见专用测试）
        Fixture("aggressiveShort", aggressiveShort, listOf("第 1 段")),
        Fixture("mixed", mixed, listOf("Chapter 1 Lamp", "第二章 城", "序章", "第三章 路", "第四章 终点")),
        Fixture("crlf", crlfText, listOf("第一章 甲", "第二章 乙", "第三章 丙")),
        Fixture("empty", "", emptyList()),
        Fixture("tocWithSpaces", "目 录\n第一章 甲\n第二章 乙\n第三章 丙\n第一章 甲\n正文开始。\n第二章 乙\n正文继续。\n第三章 丙\n正文结束。",
            listOf("开头", "第一章 甲", "第二章 乙", "第三章 丙")),
        Fixture("beyond200Lines", beyond200Lines,
            listOf("开头", "第一章 甲", "第二章 乙", "第三章 丙", "第一章 甲", "第二章 乙", "第三章 丙")),
        Fixture("chainFailThenSecondToc", chainFailThenSecondToc,
            listOf("开头", "第一章 甲", "第二章 乙", "第三章 丙")),
        Fixture("tocAtEof", tocAtEof, listOf("第 1 段")),
        Fixture("repeatedTitleBelowThree", repeatedTitleBelowThree, listOf("第 1 段")),
        Fixture("onlyNewlines", "\n\n\n", listOf("第 1 段")),
        Fixture("singleLineNoNewline", "第一章 甲", listOf("第 1 段")),
    )

    private class RunResult(
        val entries: List<ChapterIndexEntry>,
        val totalChars: Long,
        val out: ByteArrayOutputStream,
    )

    private fun run(
        bytes: ByteArray,
        charset: Charset = Charsets.UTF_8,
        strategy: SplitStrategy = SplitStrategy.CONSERVATIVE,
        chunkSize: Int = TxtChapterStream.DEFAULT_CHUNK_SIZE,
        bomLength: Int = 0,
        maxBufferChars: Int = TxtChapterStream.DEFAULT_MAX_BUFFER_CHARS,
        totalBytes: Long = bytes.size.toLong(),
        shouldContinue: () -> Boolean = { true },
        onProgress: (Float) -> Unit = {},
    ): RunResult {
        val out = ByteArrayOutputStream()
        val result = TxtChapterStream(charset, strategy, bomLength, chunkSize, maxBufferChars)
            .process(
                input = ByteArrayInputStream(bytes),
                out = out,
                totalBytes = totalBytes,
                shouldContinue = shouldContinue,
                onProgress = onProgress,
            )
        return RunResult(result.entries, result.totalChars, out)
    }

    // ---- 语义 + 跨 chunk 不变性 ----

    @Test
    fun `fixtures produce expected chapters regardless of chunk size`() {
        fixtures.forEach { fixture ->
            val bytes = fixture.text.toByteArray(Charsets.UTF_8)
            val base = run(bytes)
            fixture.expectedTitles?.let { expected ->
                assertEquals(fixture.name, expected, base.entries.map { it.title })
            }
            // 输出即标准化全文（UTF-8）
            assertEquals(
                fixture.name,
                ChapterSplitter.normalizeLineEndings(fixture.text),
                base.out.toString(Charsets.UTF_8.name()),
            )
            assertCoverage(fixture.name, base)
            for (chunk in intArrayOf(1, 2, 3, 5, 7, 13, 64, 1024)) {
                val varied = run(bytes, chunkSize = chunk)
                assertEquals("chunk=$chunk 结果不一致: ${fixture.name}", base.entries, varied.entries)
                assertCoverage("${fixture.name}@chunk=$chunk", varied)
            }
        }
    }

    @Test
    fun `aggressive strategy is chunk invariant on short line fixture`() {
        val bytes = aggressiveShort.toByteArray(Charsets.UTF_8)
        val base = run(bytes, strategy = SplitStrategy.AGGRESSIVE)
        // 与批量 findShortLineOffsets 一致：空行包围且 ≤15 字的短行皆视为标题
        // （启发式已知的粗粒度：内容行同样短时也会入章，v1.0 起即此行为）
        assertEquals(
            listOf("正文开始。", "小标题一", "内容甲内容甲。", "小标题二", "内容乙内容乙。", "小标题三", "内容丙内容丙。"),
            base.entries.map { it.title },
        )
        for (chunk in intArrayOf(1, 3, 7, 64, 4096)) {
            val varied = run(bytes, strategy = SplitStrategy.AGGRESSIVE, chunkSize = chunk)
            assertEquals("chunk=$chunk", base.entries, varied.entries)
        }
    }

    @Test
    fun `conservative does not use heuristic short lines`() {
        val base = run(aggressiveShort.toByteArray(Charsets.UTF_8))
        assertTrue(base.entries.none { it.title == "小标题一" })
        assertTrue(base.entries.all { it.title.startsWith("第 ") && it.title.endsWith(" 段") })
    }

    @Test
    fun `forced segmentation covers long newline-free text`() {
        val text = "啊".repeat(9500)
        val base = run(text.toByteArray(Charsets.UTF_8))
        assertEquals(4, base.entries.size)
        assertTrue(base.entries.all { it.title.matches(Regex("第 \\d+ 段")) })
        for (chunk in intArrayOf(1, 7, 3000, 6000, 65536)) {
            val varied = run(text.toByteArray(Charsets.UTF_8), chunkSize = chunk)
            assertEquals("chunk=$chunk", base.entries, varied.entries)
        }
    }

    @Test
    fun `forced segmentation snaps backward then hard cuts like batch`() {
        // 复刻批量 splitForced 三段语义：目标位 3000 前最后一个换行 → 向后吸附切到其后一位；
        // 第二段目标位之前唯一的换行已不 > 段首 → 硬切；末段不足一段自然收尾
        val text = "第一章 only\n" + "很长的正文。".repeat(600)
        val nl = text.indexOf('\n').toLong()
        val base = run(text.toByteArray(Charsets.UTF_8))
        assertEquals(listOf(0L, nl + 1, nl + 1 + 3000), base.entries.map { it.charStart })
        assertTrue(base.entries.all { it.title.matches(Regex("第 \\d+ 段")) })
        assertCoverage("backward-snap", base)
        for (chunk in intArrayOf(1, 13, 128, 3000, 4096)) {
            val varied = run(text.toByteArray(Charsets.UTF_8), chunkSize = chunk)
            assertEquals("chunk=$chunk", base.entries, varied.entries)
        }
    }

    @Test
    fun `forced segmentation snaps forward to newline within window`() {
        // 目标位 3000 之后 50 字符处有换行：向前吸附切在其后一位（批量 indexOf 语义）
        val text = "啊".repeat(3050) + "\n" + "啊".repeat(3050) // 6101 字，唯一换行在 3050
        val base = run(text.toByteArray(Charsets.UTF_8))
        assertEquals(listOf(0L, 3051L, 6051L), base.entries.map { it.charStart })
        assertCoverage("forward-snap", base)
        for (chunk in intArrayOf(1, 7, 512, 65536)) {
            val varied = run(text.toByteArray(Charsets.UTF_8), chunkSize = chunk)
            assertEquals("chunk=$chunk", base.entries, varied.entries)
        }
    }

    // ---- 跨编码 ----

    @Test
    fun `decodes gbk multibyte chars across chunk boundaries`() {
        val gbk = Charset.forName("GB18030")
        val text = """
            第一章 荒原之始
            少年提灯独行，身后是沉睡的城池。风从北方来，带着沙。
            第二章 古城灯火
            城池在夜里醒来，灯火次第亮起，像倒悬的星河。
            第三章 远行之约
            他决定北上，穿过荒原，去赴一个十年之约。
            第四章 雪落无声
            雪落下来的时候，荒原终于安静了。
        """.trimIndent()
        val bytes = text.toByteArray(gbk)
        val base = run(bytes, charset = gbk, chunkSize = 3)
        assertEquals(
            listOf("第一章 荒原之始", "第二章 古城灯火", "第三章 远行之约", "第四章 雪落无声"),
            base.entries.map { it.title },
        )
        val normalized = ChapterSplitter.normalizeLineEndings(text)
        assertEquals(normalized, base.out.toString(Charsets.UTF_8.name()))
        assertFalse(base.out.toString(Charsets.UTF_8.name()).contains("\uFFFD"))
        assertCoverage("gbk", base)
        assertRoundTrip("gbk", base, normalized)
        for (chunk in intArrayOf(1, 2, 5, 17, 4096)) {
            val varied = run(bytes, charset = gbk, chunkSize = chunk)
            assertEquals("chunk=$chunk", base.entries, varied.entries)
        }
    }

    @Test
    fun `decodes utf-16le with bom and odd chunk sizes`() {
        val utf16le = Charset.forName("UTF-16LE")
        val text = novel4
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val bytes = bom + text.toByteArray(utf16le)
        val base = run(bytes, charset = utf16le, bomLength = 2, chunkSize = 5)
        assertEquals(
            listOf("开头", "第一章 荒原之始", "第二章 古城灯火", "第三章 远行之约"),
            base.entries.map { it.title },
        )
        val normalized = ChapterSplitter.normalizeLineEndings(text)
        assertEquals(normalized, base.out.toString(Charsets.UTF_8.name()))
        assertCoverage("utf16", base)
        assertRoundTrip("utf16", base, normalized)
        for (chunk in intArrayOf(1, 3, 9, 512)) {
            val varied = run(bytes, charset = utf16le, bomLength = 2, chunkSize = chunk)
            assertEquals("chunk=$chunk", base.entries, varied.entries)
        }
    }

    @Test
    fun `skips utf-8 bom`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + novel4.toByteArray(Charsets.UTF_8)
        val base = run(bytes, bomLength = 3)
        assertFalse(base.out.toString(Charsets.UTF_8.name()).startsWith("\uFEFF"))
        assertEquals(
            listOf("开头", "第一章 荒原之始", "第二章 古城灯火", "第三章 远行之约"),
            base.entries.map { it.title },
        )
        assertCoverage("bom", base)
        assertRoundTrip("bom", base, ChapterSplitter.normalizeLineEndings(novel4))
    }

    @Test
    fun `bom only file yields empty result`() {
        val base = run(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bomLength = 3)
        assertTrue(base.entries.isEmpty())
        assertEquals(0L, base.totalChars)
        assertEquals(0, base.out.size())
    }

    @Test
    fun `empty input yields no entries`() {
        val base = run(ByteArray(0))
        assertTrue(base.entries.isEmpty())
        assertEquals(0L, base.totalChars)
        assertEquals(0, base.out.size())
    }

    // ---- 取消与进度 ----

    @Test
    fun `cancellation mid-stream propagates and stops early`() {
        val big = buildBigText(2_000_000)
        val bytes = big.toByteArray(Charsets.UTF_8)
        var checks = 0
        val failure = try {
            run(bytes, chunkSize = 64 * 1024, shouldContinue = { checks++ < 2 })
            null
        } catch (e: CancellationException) {
            e
        }
        assertNotNull("应抛出 CancellationException", failure)
        assertTrue("应在少量数据块后取消，实际检查 $checks 次", checks <= 3)
    }

    @Test
    fun `progress is monotonic and reaches one when total known`() {
        val bytes = buildBigText(500_000).toByteArray(Charsets.UTF_8)
        val fractions = mutableListOf<Float>()
        run(bytes, chunkSize = 64 * 1024, onProgress = { fractions.add(it) })
        assertTrue(fractions.isNotEmpty())
        fractions.zipWithNext().forEach { (a, b) ->
            assertTrue("进度回调非单调: $a > $b", a <= b)
        }
        assertEquals(1f, fractions.last(), 1e-6f)
    }

    @Test
    fun `unknown total size reports no progress but completes`() {
        val fractions = mutableListOf<Float>()
        val base = run(novel4.toByteArray(Charsets.UTF_8), totalBytes = 0, onProgress = { fractions.add(it) })
        assertTrue(fractions.isEmpty())
        assertEquals(4, base.entries.size)
    }

    // ---- 超长行 ----

    @Test
    fun `oversized line is flushed and preserved as body text`() {
        val long = "x".repeat(5000)
        val text = "第一章 甲\n$long\n第二章 乙\n内容。\n第三章 丙\n内容。"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val base = run(bytes, maxBufferChars = 1000, chunkSize = 128)
        assertEquals(
            listOf("第一章 甲", "第二章 乙", "第三章 丙"),
            base.entries.map { it.title },
        )
        assertCoverage("oversized", base)
        assertTrue(base.out.toString(Charsets.UTF_8.name()).contains(long))
        for (chunk in intArrayOf(1, 7, 1000, 8192)) {
            val varied = run(bytes, maxBufferChars = 1000, chunkSize = chunk)
            assertEquals("chunk=$chunk", base.entries, varied.entries)
        }
    }

    // ---- 大文件冒烟 ----

    @Test
    fun `handles 20mb novel with bounded buffer`() {
        val text = buildBigText(20_000_000)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val base = run(bytes)
        assertCoverage("big", base)
        assertTrue("章节数应过万，实际 ${base.entries.size}", base.entries.size > 30_000)
        val normalized = ChapterSplitter.normalizeLineEndings(text)
        val decoded = base.out.toString(Charsets.UTF_8.name())
        // 全量比对用 assertTrue：避免失败时格式化 20MB 差异信息导致 OOM
        assertTrue("输出长度不符：期望 ${normalized.length}，实际 ${decoded.length}", decoded.length == normalized.length)
        assertTrue("输出与标准化全文不一致", decoded == normalized)
        // 抽查首/中/尾章节的字节偏移可读回正确文本
        for (i in listOf(0, base.entries.size / 2, base.entries.size - 1)) {
            val e = base.entries[i]
            val slice = base.out.toByteArray().copyOfRange(e.byteStart.toInt(), e.byteEnd.toInt())
            assertEquals(
                "章节 $i 读回不一致",
                normalized.substring(e.charStart.toInt(), e.charEnd.toInt()),
                String(slice, Charsets.UTF_8),
            )
        }
    }

    // ---- 断言辅助 ----

    private fun assertCoverage(name: String, result: RunResult) {
        val entries = result.entries
        if (entries.isEmpty()) {
            assertEquals(name, 0L, result.totalChars)
            assertEquals(name, 0, result.out.size())
            return
        }
        assertEquals(name, 0L, entries.first().charStart)
        assertEquals(name, result.totalChars, entries.last().charEnd)
        assertEquals(name, 0L, entries.first().byteStart)
        assertEquals(name, result.out.size().toLong(), entries.last().byteEnd)
        entries.zipWithNext().forEach { (a, b) ->
            assertEquals(name, a.charEnd, b.charStart)
            assertEquals(name, a.byteEnd, b.byteStart)
        }
        var cumulative = 0L
        entries.forEachIndexed { i, e ->
            assertEquals(name, i, e.index)
            assertEquals(name, cumulative, e.cumulativeChars)
            assertEquals(name, e.charEnd - e.charStart, e.charCount)
            assertTrue(name, e.charCount > 0)
            cumulative += e.charCount
        }
        assertEquals(name, result.totalChars, cumulative)
    }

    private fun assertRoundTrip(name: String, result: RunResult, normalized: String) {
        val bytes = result.out.toByteArray()
        result.entries.forEach { e ->
            val slice = bytes.copyOfRange(e.byteStart.toInt(), e.byteEnd.toInt())
            assertEquals(
                name,
                normalized.substring(e.charStart.toInt(), e.charEnd.toInt()),
                String(slice, Charsets.UTF_8),
            )
        }
    }

    private fun buildBigText(targetChars: Int): String = buildString {
        var i = 0
        while (length < targetChars) {
            i++
            append("第").append(i).append("章 标题").append(i).append('\n')
            append("正文内容。".repeat(60)).append('\n')
        }
    }
}
