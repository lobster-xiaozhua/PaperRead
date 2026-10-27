package io.github.lunalight.paperread.reader.txt

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InlineChapterScanner] 单测（纯 JVM）。
 *
 * 覆盖：行内 `第X章`、重复书名 + 括号序号、误判防护（命中不足 / 密度过高）、
 * 字符串入口与流式入口的一致性（跨块解码 + 字节偏移）。
 */
class InlineChapterScannerTest {

    /** 700 字的正文段，句末标点收尾（保证书名前缀能重新起算）。 */
    private fun body(chars: Int = 700): String = "这是一段正文。".repeat(chars / 7)

    @Test
    fun `单行文本内的第X章被识别为章节边界`() {
        val text = buildString {
            append("楔子内容，随便写点什么。")
            for (i in 1..5) {
                append("第${i}章")
                append("正文".repeat(400))
                append("。")
            }
        }

        val headings = InlineChapterScanner.scan(text)

        assertEquals("5 个行内标题", 5, headings.size)
        assertEquals(
            listOf("第1章", "第2章", "第3章", "第4章", "第5章"),
            headings.map { it.title },
        )
        // 边界必须指向「第」字本身
        assertEquals(text.indexOf("第1章").toLong(), headings.first().charStart)
        assertEquals(
            "第1章",
            text.substring(headings.first().charStart.toInt(), headings.first().charStart.toInt() + 3),
        )
    }

    @Test
    fun `重复书名加括号序号被识别为一组章节`() {
        val ordinals = listOf("一", "二", "三", "四", "五", "六", "七", "八")
        val text = buildString {
            append("作者的话：随便写写。")
            for (o in ordinals) {
                append("神秘园（$o）")
                append(body())
            }
        }

        val headings = InlineChapterScanner.scan(text)

        assertEquals("8 个系列标题", 8, headings.size)
        assertEquals("神秘园（一）", headings.first().title)
        assertEquals("神秘园（八）", headings.last().title)
        assertEquals(text.indexOf("神秘园（一）").toLong(), headings.first().charStart)
        assertTrue(
            "边界必须单调递增",
            headings.zipWithNext().all { (a, b) -> b.charStart > a.charStart },
        )
    }

    @Test
    fun `书名号包裹的系列标题同样被识别`() {
        // `《》` 是词边界字符，会先清空当前词；靠「上一个完整词」快照才能认出书名。
        val ordinals = listOf("一", "二", "三", "四", "五", "六")
        val text = buildString {
            append("开篇。")
            for (o in ordinals) {
                append("《神秘园》（$o）")
                append(body())
            }
        }

        val headings = InlineChapterScanner.scan(text)

        assertEquals("6 个系列标题", 6, headings.size)
        assertEquals("神秘园（一）", headings.first().title)
        // 边界指向书名首字（`《` 之后），左书名号留在上一章末尾
        assertEquals(text.indexOf("《神秘园》（一）") + 1L, headings.first().charStart)
    }

    @Test
    fun `命中不足三条时判定为正文提及`() {
        val text = "他翻到第三章，愣了一下。" + body() + "又想起第五章说过的话。"

        assertTrue("只有两处命中，不足以成目录", InlineChapterScanner.scan(text).isEmpty())
    }

    @Test
    fun `命中密度过高时判定为误判`() {
        // 几十字里塞 10 个「第X章」：平均章长远低于 MIN_CHAPTER_CHARS
        val text = buildString {
            for (i in 1..10) {
                append("第${i}章")
                append("短")
            }
        }

        assertTrue(InlineChapterScanner.scan(text).isEmpty())
    }

    @Test
    fun `第X回等其它单位字同样识别`() {
        val text = buildString {
            for (i in 1..4) {
                append("第${i}回")
                append(body())
            }
        }

        val headings = InlineChapterScanner.scan(text)
        assertEquals(4, headings.size)
        assertEquals("第1回", headings.first().title)
    }

    @Test
    fun `流式扫描与字符串扫描结果一致`() {
        val text = buildString {
            append("开头随便说说。")
            for (i in 1..6) {
                append("第${i}章")
                append(body(900))
            }
            // 远多于一个读块（64KB）的内容，验证跨块解码与代理对字节计数
            append("🌈".repeat(200))
            append("正文填充。".repeat(10_000))
        }
        assertTrue("样本需大于一个读块", text.toByteArray(Charsets.UTF_8).size > 64 * 1024)

        val fromString = InlineChapterScanner.scan(text)
        val fromStream = InlineChapterScanner.scan(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

        assertEquals(fromString, fromStream)
        assertEquals(6, fromString.size)
    }

    @Test
    fun `流式扫描的字节偏移可按 UTF-8 切片还原标题`() {
        val text = buildString {
            append("前言。")
            for (i in 1..4) {
                append("第${i}章")
                append(body())
            }
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        val headings = InlineChapterScanner.scan(ByteArrayInputStream(bytes))

        val first = headings.first()
        val tail = String(bytes, first.byteStart.toInt(), bytes.size - first.byteStart.toInt(), Charsets.UTF_8)
        assertTrue("字节偏移应落在标题起点", tail.startsWith("第1章"))
    }
}
