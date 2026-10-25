package io.github.lunalight.paperread.reader.txt

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChapterIndexWriterTest {

    private fun write(text: String, refs: List<ChapterRef>): Triple<List<ChapterIndexEntry>, ByteArray, Long> {
        val out = ByteArrayOutputStream()
        val entries = ChapterIndexWriter(out).write(text, refs)
        return Triple(entries, out.toByteArray(), out.size().toLong())
    }

    @Test
    fun `round trip byte offsets map back to original chunks`() {
        val text = "开头无标题段落。\n第一章 荒原\n少年提灯独行。\n第二章 古城\n灯火次第亮起。\n"
        val b1 = text.indexOf("第一章")
        val b2 = text.indexOf("第二章")
        val refs = listOf(
            ChapterRef(0, "开头", 0, b1),
            ChapterRef(1, "第一章 荒原", b1, b2),
            ChapterRef(2, "第二章 古城", b2, text.length),
        )
        val (entries, bytes, total) = write(text, refs)
        assertEquals(3, entries.size)
        // total 为 UTF-8 字节数，不是字符数
        assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), total)
        entries.forEachIndexed { i, entry ->
            val chunk = String(bytes, entry.byteStart.toInt(), (entry.byteEnd - entry.byteStart).toInt(), Charsets.UTF_8)
            assertEquals(text.substring(refs[i].charStart, refs[i].charEnd), chunk)
        }
    }

    @Test
    fun `byte offsets account for multi byte UTF-8`() {
        // "啊" 3 字节；两段各 2 个字符 = 各 6 字节，全文 12 字节
        val text = "啊啊啊啊"
        val refs = listOf(
            ChapterRef(0, "a", 0, 2),
            ChapterRef(1, "b", 2, 4),
        )
        val (entries, bytes, total) = write(text, refs)
        assertEquals(12L, total)
        assertEquals(0L, entries[0].byteStart)
        assertEquals(6L, entries[0].byteEnd)
        assertEquals(6L, entries[1].byteStart)
        assertEquals(12L, entries[1].byteEnd)
        assertArrayEquals("啊啊啊啊".toByteArray(Charsets.UTF_8), bytes)
    }

    @Test
    fun `cumulativeChars accumulates before each chapter`() {
        val text = "一二三四五六"
        val refs = listOf(
            ChapterRef(0, "a", 0, 2),
            ChapterRef(1, "b", 2, 5),
            ChapterRef(2, "c", 5, 6),
        )
        val (entries, _, _) = write(text, refs)
        assertEquals(listOf(0L, 2L, 5L), entries.map { it.cumulativeChars })
        assertEquals(listOf(2L, 3L, 1L), entries.map { it.charCount })
    }

    @Test
    fun `rejects refs with gap`() {
        val text = "一二三四五"
        val refs = listOf(
            ChapterRef(0, "a", 0, 2),
            ChapterRef(1, "b", 3, 5), // 缺 [2,3)
        )
        assertThrows(IllegalArgumentException::class.java) { write(text, refs) }
    }

    @Test
    fun `rejects refs not starting at zero`() {
        val text = "一二三四五"
        val refs = listOf(ChapterRef(0, "a", 1, 5))
        assertThrows(IllegalArgumentException::class.java) { write(text, refs) }
    }

    @Test
    fun `rejects refs not covering the end`() {
        val text = "一二三四五"
        val refs = listOf(ChapterRef(0, "a", 0, 3))
        assertThrows(IllegalArgumentException::class.java) { write(text, refs) }
    }

    @Test
    fun `empty refs write nothing`() {
        val (entries, bytes, total) = write("任意文本", emptyList())
        assertEquals(emptyList<ChapterIndexEntry>(), entries)
        assertEquals(0L, total)
        assertArrayEquals(ByteArray(0), bytes)
    }
}
