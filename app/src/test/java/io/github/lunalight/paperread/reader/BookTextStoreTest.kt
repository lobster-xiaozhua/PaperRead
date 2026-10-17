package io.github.lunalight.paperread.reader

import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.reader.txt.ChapterIndexWriter
import io.github.lunalight.paperread.reader.txt.ChapterRef
import java.io.File
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookTextStoreTest {

    private lateinit var store: BookTextStore
    private lateinit var book: Book

    @Before
    fun setUp() {
        store = FileBookTextStore(ApplicationProvider.getApplicationContext())
        book = Book(
            title = "测试书",
            author = "未知",
            format = BookFormat.TXT,
            encoding = "UTF-8",
            sha256 = "x",
            fileSize = 100,
            dirName = "test-dir",
            totalChars = 50,
            chapterCount = 2,
            addedAt = 1L,
        )
    }

    private fun writeBookFile(text: String, refs: List<ChapterRef>): List<Chapter> {
        store.bookDir(book.dirName).mkdirs()
        val entries = File(store.textFile(book.dirName).path).outputStream().use { out ->
            ChapterIndexWriter(out).write(text, refs)
        }
        return entries.map { entry ->
            Chapter(
                bookId = book.id,
                index = entry.index,
                title = entry.title,
                charStart = entry.charStart,
                charEnd = entry.charEnd,
                byteStart = entry.byteStart,
                byteEnd = entry.byteEnd,
                charCount = entry.charCount,
                cumulativeChars = entry.cumulativeChars,
            )
        }
    }

    @Test
    fun `readChapter locates text by byte offsets`() {
        val text = "第一章 荒原\n少年提灯独行。\n第二章 古城\n灯火次第亮起。\n"
        val b = text.indexOf("第二章")
        val chapters = writeBookFile(text, listOf(
            ChapterRef(0, "第一章 荒原", 0, b),
            ChapterRef(1, "第二章 古城", b, text.length),
        ))

        val first = store.readChapter(book.dirName, chapters[0])
        val second = store.readChapter(book.dirName, chapters[1])
        assertEquals("第一章 荒原\n少年提灯独行。\n", first)
        assertEquals("第二章 古城\n灯火次第亮起。\n", second)
        assertEquals(text, first + second)
    }

    @Test
    fun `readChapter handles GBK-decoded multi byte content`() {
        // 模拟 GB18030 小说转存后的 UTF-8 book.txt
        val text = "一二三四五六七八九十"
        val chapters = writeBookFile(text, listOf(
            ChapterRef(0, "a", 0, 5),
            ChapterRef(1, "b", 5, 10),
        ))
        assertEquals("一二三四五", store.readChapter(book.dirName, chapters[0]))
        assertEquals("六七八九十", store.readChapter(book.dirName, chapters[1]))
    }

    @Test
    fun `readChapter returns empty for missing file`() {
        val chapter = Chapter(
            bookId = 1, index = 0, title = "x", charStart = 0, charEnd = 10,
            byteStart = 0, byteEnd = 30, charCount = 10, cumulativeChars = 0,
        )
        assertEquals("", store.readChapter("no-such-dir", chapter))
    }

    @Test
    fun `readSourceText decodes with given charset`() {
        val dir = store.bookDir(book.dirName)
        dir.mkdirs()
        val content = "第一章 荒原"
        File(dir, "source.txt").writeBytes(content.toByteArray(Charset.forName("GB18030")))

        val decoded = store.readSourceText(book, Charset.forName("GB18030"))
        assertEquals(content, decoded)
    }

    @Test
    fun `deleteBookDir removes directory recursively`() {
        val dir = store.bookDir(book.dirName)
        dir.mkdirs()
        File(dir, "book.txt").writeText("内容")
        File(dir, "source.txt").writeText("原文")
        assertTrue(dir.exists())

        store.deleteBookDir(book.dirName)

        assertFalse(dir.exists())
        assertFalse(store.textFile(book.dirName).exists())
    }
}
