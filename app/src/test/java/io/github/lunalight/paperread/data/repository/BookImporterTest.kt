package io.github.lunalight.paperread.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.data.local.AppDatabase
import io.github.lunalight.paperread.domain.model.ReaderPosition
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookImporterTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: BookRepository
    private lateinit var textStore: BookTextStore
    private lateinit var importer: BookImporter

    private val gbkCharset: Charset = Charset.forName("GB18030")

    /** 四章 GBK 小说（无 BOM）——覆盖强正则切分。 */
    private val gbkNovel: String = """
        第一章 荒原之始
        少年提灯独行，身后是沉睡的城池。风从北方来，带着沙。
        第二章 古城灯火
        城池在夜里醒来，灯火次第亮起，像倒悬的星河。
        第三章 远行之约
        他决定北上，穿过荒原，去赴一个十年之约。
        第四章 雪落无声
        雪落下来的时候，荒原终于安静了。
    """.trimIndent()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = BookRepositoryImpl(
            database.bookDao(),
            database.chapterDao(),
            database.bookmarkDao(),
        )
        textStore = BookTextStore(context)
        importer = BookImporter(context, repository, textStore)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun uriFor(name: String, content: String, charset: Charset): Uri {
        val uri = Uri.parse("content://io.github.lunalight.paperread.test/$name")
        shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(content.toByteArray(charset)))
        return uri
    }

    private fun assertSuccess(result: PrepareResult): PendingImport {
        assertTrue("期望 Success，实际 $result", result is PrepareResult.Success)
        return (result as PrepareResult.Success).pending
    }

    @Test
    fun `imports GBK novel without mojibake`() = runTest {
        val uri = uriFor("荒原之始.txt", gbkNovel, gbkCharset)

        val result = importer.prepare(uri)
        val pending = assertSuccess(result)

        assertEquals("GB18030", pending.charsetName)
        assertEquals(4, pending.chapters.size)
        assertEquals("第一章 荒原之始", pending.chapters[0].title)
        assertTrue(pending.sha256.length == 64)
        assertTrue(pending.fileSize > 0)

        // 按字节偏移读回第一章：中文不得乱码
        val firstChapter = textStore.readChapter(pending.dirName, pending.chapters[0])
        assertTrue(firstChapter.startsWith("第一章 荒原之始"))
        assertTrue(firstChapter.contains("少年提灯独行"))
        assertFalse(firstChapter.contains("�"))

        // book.txt 已落盘
        assertTrue(File(textStore.textFile(pending.dirName).path).exists())
    }

    @Test
    fun `confirm saves book and chapters into repository`() = runTest {
        val uri = uriFor("荒原之始.txt", gbkNovel, gbkCharset)
        val pending = assertSuccess(importer.prepare(uri))

        val id = importer.confirm(pending)

        val book = repository.getBook(id)
        assertNotNull(book)
        assertEquals("荒原之始", book!!.title)
        assertEquals(4, book.chapterCount)
        val chapters = repository.getChapters(id)
        assertEquals(4, chapters.size)
        assertEquals(pending.chapters.first().byteStart, chapters.first().byteStart)
        assertEquals(pending.chapters.first().byteEnd, chapters.first().byteEnd)
    }

    @Test
    fun `duplicate import is detected by sha256`() = runTest {
        val uri = uriFor("荒原之始.txt", gbkNovel, gbkCharset)
        val pending = assertSuccess(importer.prepare(uri))
        importer.confirm(pending)

        // 同一内容再次导入（不同文件名同样应判重）
        val again = importer.prepare(uriFor("副本.txt", gbkNovel, gbkCharset))
        assertTrue("期望 Duplicate，实际 $again", again is PrepareResult.Duplicate)
        val existing = (again as PrepareResult.Duplicate).existing
        assertEquals("荒原之始", existing.title)
        // 重复导入不残留临时目录
        assertEquals(1, repository.observeBooks().first().size)
    }

    @Test
    fun `unsupported extension is rejected`() = runTest {
        val uri = uriFor("文档.pdf", gbkNovel, gbkCharset)
        val result = importer.prepare(uri)
        assertTrue("期望 Unsupported，实际 $result", result is PrepareResult.Unsupported)
    }

    @Test
    fun `epub reports upcoming support`() = runTest {
        val uri = uriFor("book.epub", gbkNovel, gbkCharset)
        val result = importer.prepare(uri)
        assertTrue(result is PrepareResult.Unsupported)
        assertTrue((result as PrepareResult.Unsupported).reason.contains("EPUB"))
    }

    @Test
    fun `reparse with aggressive strategy finds more chapters`() = runTest {
        // 短行标题文本：保守策略仅得 1 个强匹配回退分段；激进策略可按短行切开
        val text = """
            小引
            正文开始。

            一、荒原

            少年提灯独行。

            二、古城

            灯火次第亮起。

            三、远行

            他决定北上。
        """.trimIndent()
        val uri = uriFor("短篇.txt", text, Charsets.UTF_8)
        val pending = assertSuccess(importer.prepare(uri, SplitStrategy.CONSERVATIVE))

        val reparsed = importer.reparse(pending, "UTF-8", SplitStrategy.AGGRESSIVE)
        assertNotNull(reparsed)
        assertTrue(
            "激进策略章节数应多于保守策略",
            reparsed!!.chapters.size > pending.chapters.size,
        )
        assertEquals(pending.dirName, reparsed.dirName)
        assertTrue(reparsed.chapters.map { it.title }.containsAll(listOf("一、荒原", "二、古城", "三、远行")))
    }

    @Test
    fun `reparse switching charset decodes GBK as UTF-8 without crash`() = runTest {
        val uri = uriFor("荒原之始.txt", gbkNovel, gbkCharset)
        val pending = assertSuccess(importer.prepare(uri))

        // 故意切到错误编码：流水线应容错完成（供用户在结果页对比后选回）
        val reparsed = importer.reparse(pending, "UTF-8", SplitStrategy.CONSERVATIVE)
        assertNotNull(reparsed)
        assertEquals("UTF-8", reparsed!!.charsetName)
        assertTrue(reparsed.chapters.isNotEmpty())
    }

    @Test
    fun `cancel deletes temp directory`() = runTest {
        val uri = uriFor("荒原之始.txt", gbkNovel, gbkCharset)
        val pending = assertSuccess(importer.prepare(uri))
        assertTrue(textStore.bookDir(pending.dirName).exists())

        importer.cancel(pending)

        assertFalse(textStore.bookDir(pending.dirName).exists())
    }

    @Test
    fun `reparseBook replaces chapters and resets progress`() = runTest {
        val uri = uriFor("荒原之始.txt", gbkNovel, gbkCharset)
        val pending = assertSuccess(importer.prepare(uri))
        val id = importer.confirm(pending)
        repository.saveReaderPosition(id, ReaderPosition(2, 0, 0, 30), 60f)

        val book = repository.getBook(id)!!
        val ok = importer.reparseBook(book, SplitStrategy.CONSERVATIVE)

        assertTrue(ok)
        val updated = repository.getBook(id)!!
        assertEquals(4, updated.chapterCount)
        assertEquals(0, updated.lastChapterIndex)
        assertEquals(0f, updated.progressPercent, 0.01f)
        // 目录中的书籍文件被重写且可读
        val chapters = repository.getChapters(id)
        assertEquals(4, chapters.size)
        val text = textStore.readChapter(updated.dirName, chapters[0])
        assertTrue(text.startsWith("第一章 荒原之始"))
    }
}
