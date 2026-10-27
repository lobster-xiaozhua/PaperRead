package io.github.lunalight.paperread.data.search

import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.domain.search.SearchHit
import io.github.lunalight.paperread.reader.BookTextStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

/**
 * [CurrentBookSearchSource] 单测（AC-15.2 / AC-15.4 / AC-15.5 / AC-15.7，纯 JVM）。
 *
 * 覆盖重点：
 * - **流式顺序**：先章先出、同章按段落升序（AC-15.4）；
 * - **取消即停**：`take(n)` 取消后不再读下一章、不再 emit 剩余命中（AC-15.5 / 15.10）；
 * - 命中判定：大小写不敏感、中文精确、同段多次命中合并 + `matchCount`；
 * - 边界：首章 / 末章 / 空书 / 空章 / 单字关键词 / 无命中（AC-15.7 的空态数据来源）；
 * - `charOffset` 为全书字符偏移（`Chapter.charStart` 坐标系），可直接定位。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentBookSearchSourceTest {

    // ---- 夹具 ----

    /** 记录读取顺序的内存 [BookTextStore]：readChapter 一律记账，用于验证「取消了就不读」。 */
    private class RecordingTextStore(
        private val texts: Map<Int, String>,
    ) : BookTextStore {

        val readChapterIndexes = mutableListOf<Int>()

        override fun bookDir(dirName: String): File = File("/tmp/fake-books/$dirName")

        override fun textFile(dirName: String): File = File(bookDir(dirName), "book.txt")

        override fun sourceFile(book: Book): File =
            File(bookDir(book.dirName), "source.${book.format.extension}")

        override fun readChapter(dirName: String, chapter: Chapter): String {
            readChapterIndexes += chapter.index
            return texts[chapter.index] ?: ""
        }

        override fun hasText(dirName: String): Boolean = true

        override fun readSourceText(book: Book, charset: Charset): String = ""

        override fun deleteBookDir(dirName: String) {
            // no-op：搜索不触碰目录
        }
    }

    private fun chapter(index: Int, title: String = "第 ${index + 1} 章", charStart: Long = index * 1000L) =
        Chapter(
            id = index.toLong(),
            bookId = 1L,
            index = index,
            title = title,
            charStart = charStart,
            charEnd = charStart + 1000,
            byteStart = charStart,
            byteEnd = charStart + 1000,
            charCount = 1000,
            cumulativeChars = charStart,
        )

    private fun source(chapters: List<Chapter>, texts: Map<Int, String>): Pair<CurrentBookSearchSource, RecordingTextStore> {
        val store = RecordingTextStore(texts)
        return CurrentBookSearchSource(bookId = 7L, chapters = chapters, textStore = store, dirName = "dir") to store
    }

    // ---- 流式顺序（AC-15.4） ----

    @Test
    fun `先命中的章节先输出`() = runTest {
        val chapters = listOf(chapter(0), chapter(1), chapter(2))
        val (search, _) = source(
            chapters,
            mapOf(
                0 to "第一章没有关键词",
                1 to "第一章之后出现了 灵剑 两个字\n第二段也有 灵剑",
                2 to "末章同样有 灵剑",
            ),
        )

        val hits = search.search("灵剑").toList()

        assertEquals(listOf(1, 1, 2), hits.map { it.chapterIndex })
        assertEquals("同章按段落升序", listOf(0, 1), hits.filter { it.chapterIndex == 1 }.map { it.paragraphIndex })
    }

    @Test
    fun `流式产出不等待全书扫描完成`() = runTest {
        // 第 0 章就命中：take(1) 拿到的第一条必须来自第 0 章——证明实现是
        // 「命中即 emit」而不是「扫完全书再一次性返回」（后者 take(1) 也会返回
        // 全书第一条，但 readChapterIndexes 会读满 3 章）。
        val chapters = listOf(chapter(0), chapter(1), chapter(2))
        val (search, store) = source(
            chapters,
            mapOf(0 to "开头就命中 灵剑", 1 to "灵剑", 2 to "灵剑"),
        )

        val first = search.search("灵剑").take(1).toList().single()

        assertEquals(0, first.chapterIndex)
        assertEquals("拿到首批结果时只读了第 0 章", listOf(0), store.readChapterIndexes)
    }

    // ---- 取消（AC-15.5 / AC-15.10） ----

    @Test
    fun `取消后不再读下一章`() = runTest {
        val chapters = (0..4).map { chapter(it) }
        val (search, store) = source(chapters, (0..4).associateWith { "每章都有 灵剑" })

        search.search("灵剑").take(1).toList()

        assertEquals("取消后不应再打开后续章节的文件", listOf(0), store.readChapterIndexes)
    }

    @Test
    fun `取消后不再输出本章剩余命中`() = runTest {
        val chapters = listOf(chapter(0), chapter(1))
        val (search, store) = source(
            chapters,
            mapOf(0 to "灵剑一\n灵剑二\n灵剑三", 1 to "灵剑四"),
        )

        val taken = search.search("灵剑").take(2).toList()

        assertEquals(2, taken.size)
        assertEquals(listOf(0, 0), taken.map { it.chapterIndex })
        assertEquals("取消发生在第 0 章内，第 1 章一次都没读", listOf(0), store.readChapterIndexes)
    }

    // ---- 命中判定 ----

    @Test
    fun `大小写不敏感`() = runTest {
        val (search, _) = source(listOf(chapter(0)), mapOf(0 to "Harry Potter 与 HARRY 的魔法"))

        val hits = search.search("harry").toList()

        assertEquals(1, hits.size)
        assertEquals("同一段里的两种大小写都算命中", 2, hits.single().matchCount)
    }

    @Test
    fun `中文关键词精确命中并按段合并计数`() = runTest {
        val (search, _) = source(
            listOf(chapter(0)),
            mapOf(0 to "天不生我李淳罡，剑道万古如长夜。\n李淳罡又说了一句。"),
        )

        val hits = search.search("李淳罡").toList()

        assertEquals(2, hits.size)
        assertEquals(listOf(0, 1), hits.map { it.paragraphIndex })
        assertTrue("每条命中都是整段摘要", hits.all { it.snippet.contains("李淳罡") })
    }

    @Test
    fun `同段多次命中合并为一条并记录次数`() = runTest {
        val (search, _) = source(listOf(chapter(0)), mapOf(0 to "剑来，剑来，还是剑来"))

        val hit = search.search("剑来").toList().single()

        assertEquals(3, hit.matchCount)
    }

    @Test
    fun `命中次数不重叠计数`() = runTest {
        val (search, _) = source(listOf(chapter(0)), mapOf(0 to "哈哈哈"))

        val hit = search.search("哈哈").toList().single()

        assertEquals("aaa 里 aa 只算一次（不重叠）", 1, hit.matchCount)
    }

    // ---- 定位字段 ----

    @Test
    fun `charOffset 为全书字符偏移`() = runTest {
        // 第 2 章 charStart = 2000，段首在第 6 个字符（"标题行\n"），命中词再偏 1
        val (search, _) = source(
            listOf(chapter(0), chapter(1), chapter(2, charStart = 2000L)),
            mapOf(2 to "标题行\n  灵剑出世"),
        )

        val hit = search.search("灵剑").toList().single()

        // 行首偏移 4（"标题行\n" 占 4 个字符）+ 段前导空白 2 = 6，命中词在段内 0
        assertEquals(2000 + 6, hit.charOffset)
    }

    @Test
    fun `摘要超长时两端补省略号`() = runTest {
        val text = "前置".repeat(40) + "灵剑" + "后置".repeat(80)
        val (search, _) = source(listOf(chapter(0)), mapOf(0 to text))

        val hit = search.search("灵剑").toList().single()

        assertTrue(hit.snippet.startsWith("…"))
        assertTrue(hit.snippet.endsWith("…"))
        assertTrue(hit.snippet.contains("灵剑"))
        assertEquals(
            "摘要长度 = 前 20 + 命中词 2 + 后 60 + 两个省略号",
            SearchText.SNIPPET_LEAD + 2 + SearchText.SNIPPET_TRAIL + 2,
            hit.snippet.length,
        )
    }

    @Test
    fun `短段摘要不补省略号`() = runTest {
        val (search, _) = source(listOf(chapter(0)), mapOf(0 to "灵剑"))

        val hit = search.search("灵剑").toList().single()

        assertEquals("灵剑", hit.snippet)
    }

    // ---- 空态与边界（AC-15.7） ----

    @Test
    fun `无命中返回空流`() = runTest {
        val (search, store) = source(listOf(chapter(0), chapter(1)), mapOf(0 to "无关内容", 1 to "还是无关"))

        val hits = search.search("灵剑").toList()

        assertTrue(hits.isEmpty())
        assertEquals("无命中也必须扫完（否则永远发现不了后面的命中）", listOf(0, 1), store.readChapterIndexes)
    }

    @Test
    fun `单字关键词不扫描`() = runTest {
        val (search, store) = source(listOf(chapter(0)), mapOf(0 to "灵剑"))

        val hits = search.search("灵").toList()

        assertTrue(hits.isEmpty())
        assertTrue("短于门槛的关键词一次都不该读盘", store.readChapterIndexes.isEmpty())
    }

    @Test
    fun `空白关键词不扫描`() = runTest {
        val (search, store) = source(listOf(chapter(0)), mapOf(0 to "灵剑"))

        assertTrue(search.search("   ").toList().isEmpty())
        assertTrue(store.readChapterIndexes.isEmpty())
    }

    @Test
    fun `关键词两端空白被裁剪`() = runTest {
        val (search, _) = source(listOf(chapter(0)), mapOf(0 to "灵剑出世"))

        assertEquals(1, search.search("  灵剑  ").toList().size)
    }

    @Test
    fun `空书返回空流`() = runTest {
        val (search, store) = source(emptyList(), emptyMap())

        assertTrue(search.search("灵剑").toList().isEmpty())
        assertTrue(store.readChapterIndexes.isEmpty())
    }

    @Test
    fun `空章节被跳过且不影响后续章`() = runTest {
        val (search, _) = source(
            listOf(chapter(0), chapter(1), chapter(2)),
            mapOf(0 to "", 2 to "灵剑在末章"),
        )

        val hits = search.search("灵剑").toList()

        assertEquals(listOf(2), hits.map { it.chapterIndex })
    }

    @Test
    fun `末章命中且 chapterTitle 透传`() = runTest {
        val chapters = listOf(chapter(0), chapter(1, title = "第十章 大结局"))
        val (search, _) = source(chapters, mapOf(1 to "灵剑归鞘"))

        val hit = search.search("灵剑").toList().single()

        assertEquals(1, hit.chapterIndex)
        assertEquals("第十章 大结局", hit.chapterTitle)
        assertEquals("bookId 透传当前书", 7L, hit.bookId)
    }

    @Test
    fun `首章命中`() = runTest {
        val (search, _) = source(listOf(chapter(0), chapter(1)), mapOf(0 to "灵剑", 1 to "无"))

        assertEquals(0, search.search("灵剑").toList().single().chapterIndex)
    }

    // ---- SearchHit 契约 ----

    @Test
    fun `同段落位置键相同且默认为一次命中`() {
        val hit = SearchHit(
            bookId = 1L,
            chapterIndex = 2,
            paragraphIndex = 3,
            charOffset = 400,
            chapterTitle = "第三章",
            snippet = "摘要",
        )

        assertEquals("默认 matchCount = 1", 1, hit.matchCount)
        assertEquals("2:3", hit.positionKey)
        assertFalse("摘要不含省略号（调用方未越界）", hit.snippet.contains("…"))
    }

    // ---- SearchText 纯函数边界 ----

    @Test
    fun `countMatches 处理空串与越界`() {
        assertEquals(0, SearchText.countMatches("", "剑"))
        assertEquals(0, SearchText.countMatches("剑来", ""))
        assertEquals(0, SearchText.countMatches("剑来", "刀"))
        assertEquals(1, SearchText.countMatches("剑", "剑"))
    }

    @Test
    fun `snippet 命中在段首时不加前缀省略号`() {
        val text = "灵剑" + "后".repeat(100)

        val snippet = SearchText.snippet(text, matchStart = 0, matchLength = 2)

        assertFalse(snippet.startsWith("…"))
        assertTrue("尾部超界补省略号", snippet.endsWith("…"))
    }

    @Test
    fun `snippet 命中在段尾时不加后缀省略号`() {
        val text = "前".repeat(100) + "灵剑"

        val snippet = SearchText.snippet(text, matchStart = 100, matchLength = 2)

        assertTrue(snippet.startsWith("…"))
        assertFalse(snippet.endsWith("…"))
    }

    @Test
    fun `snippet 把换行压成空格`() {
        val snippet = SearchText.snippet("前\n灵剑\n后", matchStart = 2, matchLength = 2)

        assertFalse("摘要按单行展示，不应带换行", snippet.contains('\n'))
        assertTrue(snippet.contains("灵剑"))
    }

    @Test
    fun `scanChapter 对无关键词的章节快速返回空`() {
        val hits = SearchText.scanChapter(
            bookId = 1L,
            needle = "灵剑",
            chapter = chapter(0),
            raw = "整章都没有那个词，长度也不短，但就是不命中。".repeat(10),
        )

        assertTrue(hits.isEmpty())
    }
}
