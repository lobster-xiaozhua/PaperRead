package io.github.lunalight.paperread.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.data.local.AppDatabase
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.model.ReaderPosition
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore
import io.github.lunalight.paperread.reader.FileBookTextStore
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        textStore = FileBookTextStore(context)
        importer = DefaultBookImporter(context, repository, textStore)
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
        val error = (result as PrepareResult.Unsupported).error
        assertEquals("pdf", error.extension)
        assertEquals("仅支持 TXT / EPUB 格式", error.userMessage)
        assertEquals(ImportStage.PRECHECK, error.stage)
    }

    @Test
    fun `epub files are accepted by prepare (M3 wired)`() = runTest {
        val uri = uriFor("novel.epub", gbkNovel, gbkCharset)
        // 内容虽是 TXT 文本，但扩展名为 epub：进入 EPUB 分支，因结构非法而 Failure
        // （此处仅验证不再走 Unsupported 拒绝路径）
        val result = importer.prepare(uri)
        assertTrue("期望 Failure（结构非法），实际 $result", result is PrepareResult.Failure)
        val error = (result as PrepareResult.Failure).error
        assertTrue("期望 ParseFailed，实际 $error", error is ImportError.ParseFailed)
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
    fun `imports utf-8 bom txt without bom char leaking into text`() = runTest {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val uri = Uri.parse("content://io.github.lunalight.paperread.test/bom.txt")
        shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(bom + gbkNovel.toByteArray(Charsets.UTF_8)))

        val pending = assertSuccess(importer.prepare(uri))

        assertEquals("UTF-8", pending.charsetName)
        assertEquals(4, pending.chapters.size)
        val first = textStore.readChapter(pending.dirName, pending.chapters[0])
        assertTrue(first.startsWith("第一章 荒原之始"))
        assertFalse(first.startsWith("\uFEFF"))
    }

    @Test
    fun `imports 5mb txt through streaming pipeline`() = runTest {
        // 约 5MB：强正则章节 + 每章约 600 字符，验证流式路径端到端可用
        val big = buildString {
            var i = 0
            while (length < 5_000_000) {
                i++
                append("第").append(i).append("章 大标题").append(i).append('\n')
                append("正文内容。".repeat(50)).append('\n')
            }
        }
        val uri = uriFor("长篇.txt", big, Charsets.UTF_8)
        val pending = assertSuccess(importer.prepare(uri))

        assertTrue("章节数应过五千，实际 ${pending.chapters.size}", pending.chapters.size > 5000)
        assertEquals(big.length.toLong(), pending.totalChars)
        // 首尾章节按字节偏移读回
        val first = textStore.readChapter(pending.dirName, pending.chapters[0])
        assertTrue(first.startsWith("第1章 大标题1"))
        val last = textStore.readChapter(pending.dirName, pending.chapters.last())
        assertTrue(last.contains("正文内容。"))
        assertFalse(last.contains("\uFFFD"))
        // 流式路径不残留临时文件
        assertFalse(File(textStore.bookDir(pending.dirName), "book.txt.tmp").exists())
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
    fun `reparseBook replaces chapters resets progress and clears bookmarks`() = runTest {
        val uri = uriFor("荒原之始.txt", gbkNovel, gbkCharset)
        val pending = assertSuccess(importer.prepare(uri))
        val id = importer.confirm(pending)
        repository.saveReaderPosition(id, ReaderPosition(2, 0, 0, 30), 60f)
        repository.addBookmark(
            Bookmark(
                bookId = id,
                chapterIndex = 2,
                paragraphIndex = 0,
                charOffset = 30,
                excerpt = "旧章节语义下的书签",
                createdAt = 1_000L,
            ),
        )

        val book = repository.getBook(id)!!
        val error = importer.reparseBook(book, SplitStrategy.CONSERVATIVE)

        assertNull(error)
        val updated = repository.getBook(id)!!
        assertEquals(4, updated.chapterCount)
        assertEquals(0, updated.lastChapterIndex)
        assertEquals(0f, updated.progressPercent, 0.01f)
        // 章节语义已变：书签必须清空，避免跳到错位内容
        assertTrue(repository.observeBookmarks(id).first().isEmpty())
        // 目录中的书籍文件被重写且可读
        val chapters = repository.getChapters(id)
        assertEquals(4, chapters.size)
        val text = textStore.readChapter(updated.dirName, chapters[0])
        assertTrue(text.startsWith("第一章 荒原之始"))
    }

    @Test
    fun `reparseBook reports reason when source file is missing`() = runTest {
        val uri = uriFor("荒原之始.txt", gbkNovel, gbkCharset)
        val pending = assertSuccess(importer.prepare(uri))
        val id = importer.confirm(pending)
        val book = repository.getBook(id)!!
        // 模拟源文件丢失
        File(textStore.bookDir(book.dirName), "source.txt").delete()

        val error = importer.reparseBook(book, SplitStrategy.CONSERVATIVE)

        assertNotNull(error)
        assertTrue("期望 SourceMissing，实际 $error", error is ImportError.SourceMissing)
        assertEquals("源文件已丢失，请删除后重新导入", error!!.userMessage)
        assertEquals(book.dirName, (error as ImportError.SourceMissing).dirName)
    }

    @Test
    fun `bom declared utf-8 with invalid bytes reports decode failure`() = runTest {
        // UTF-8 BOM 声明编码，但内容含非法 UTF-8 字节（0xFF / overlong 序列）：
        // 探测阶段即判编码失败，不进入流式容错路径（B7：编码失败可区分）
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val invalid = byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x80.toByte())
        val uri = Uri.parse("content://io.github.lunalight.paperread.test/broken.txt")
        shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(bom + invalid + "正文".toByteArray(Charsets.UTF_8)))

        val result = importer.prepare(uri)

        assertTrue("期望 Failure，实际 $result", result is PrepareResult.Failure)
        val error = (result as PrepareResult.Failure).error
        assertTrue("期望 DecodeFailed，实际 $error", error is ImportError.DecodeFailed)
        assertEquals("UTF-8", (error as ImportError.DecodeFailed).encoding)
        assertEquals(ImportStage.DETECTING, error.stage)
        // 临时目录已清理（books/ 下无残留）
        val booksRoot = textStore.bookDir(UUID.randomUUID().toString()).parentFile
        assertEquals("临时目录应已清理", 0, booksRoot?.listFiles()?.size ?: 0)
    }

    @Test
    fun `insufficient space maps to distinct message`() = runTest {
        // 直接验证结构化错误的用户话术两两可区分（B7 验收口径）
        val drm = ImportError.DrmEncrypted("encrypted")
        val space = ImportError.InsufficientSpace
        val decode = ImportError.DecodeFailed("UTF-8")
        val messages = setOf(drm.userMessage, space.userMessage, decode.userMessage)
        assertEquals("三类失败话术应两两不同", 3, messages.size)
    }

    @Test
    fun `unreadable uri reports read failure`() = runTest {
        // 未注册输入流的 URI：打开文件流失败 → ReadFailed（与解析失败可区分）
        val uri = Uri.parse("content://io.github.lunalight.paperread.test/missing.txt")

        val result = importer.prepare(uri)

        assertTrue("期望 Failure，实际 $result", result is PrepareResult.Failure)
        val error = (result as PrepareResult.Failure).error
        assertTrue("期望 ReadFailed，实际 $error", error is ImportError.ReadFailed)
        assertEquals(ImportStage.COPYING, error.stage)
    }

    // ---- EPUB e2e（Robolectric 下走 Android XmlPullParser 路径） ----

    private fun buildEpubZip(files: Map<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val epubContainerXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    private val epubOpf = """
        <?xml version="1.0" encoding="utf-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>荒原之始</dc:title>
            <dc:creator>测试作者</dc:creator>
            <dc:language>zh-CN</dc:language>
            <dc:identifier id="bookid">urn:uuid:test</dc:identifier>
          </metadata>
          <manifest>
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
            <item id="ch2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
            <item id="ch3" href="Text/ch3.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
          <spine toc="ncx">
            <itemref idref="ch1"/>
            <itemref idref="ch2"/>
            <itemref idref="ch3"/>
          </spine>
        </package>
    """.trimIndent()

    private val epubNcx = """
        <?xml version="1.0" encoding="utf-8"?>
        <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
          <head><meta name="dtb:uid" content="urn:uuid:test"/></head>
          <docTitle><text>荒原之始</text></docTitle>
          <navMap>
            <navPoint id="np1" playOrder="1">
              <navLabel><text>第一章 荒原</text></navLabel>
              <content src="Text/ch1.xhtml"/>
            </navPoint>
            <navPoint id="np2" playOrder="2">
              <navLabel><text>第二章 古城</text></navLabel>
              <content src="Text/ch2.xhtml"/>
            </navPoint>
            <navPoint id="np3" playOrder="3">
              <navLabel><text>第三章 远行</text></navLabel>
              <content src="Text/ch3.xhtml"/>
            </navPoint>
          </navMap>
        </ncx>
    """.trimIndent()

    private fun epubChapter(title: String, vararg paragraphs: String): String {
        val ps = paragraphs.joinToString("") { "<p>$it</p>" }
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head>
            <body><h1>$title</h1>$ps</body></html>
        """.trimIndent()
    }

    private fun standardEpubBytes(): ByteArray = buildEpubZip(
        mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to epubContainerXml,
            "OEBPS/content.opf" to epubOpf,
            "OEBPS/toc.ncx" to epubNcx,
            "OEBPS/Text/ch1.xhtml" to epubChapter("第一章 荒原", "少年提灯独行。", "风从北方来。"),
            "OEBPS/Text/ch2.xhtml" to epubChapter("第二章 古城", "城池在夜里醒来。"),
            "OEBPS/Text/ch3.xhtml" to epubChapter("第三章 远行", "他决定北上。"),
        ),
    )

    @Test
    fun `imports epub end to end with chapter titles and text`() = runTest {
        val uri = Uri.parse("content://io.github.lunalight.paperread.test/novel.epub")
        shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(standardEpubBytes()))

        val pending = assertSuccess(importer.prepare(uri))

        assertEquals(BookFormat.EPUB, pending.format)
        assertEquals("荒原之始", pending.title)
        assertEquals("测试作者", pending.author)
        assertEquals(3, pending.chapters.size)
        assertEquals(
            listOf("第一章 荒原", "第二章 古城", "第三章 远行"),
            pending.chapters.map { it.title },
        )

        val id = importer.confirm(pending)
        val book = repository.getBook(id)!!
        assertEquals(3, book.chapterCount)
        assertEquals("测试作者", book.author)
        val chapters = repository.getChapters(id)
        assertEquals(3, chapters.size)
        val first = textStore.readChapter(book.dirName, chapters[0])
        assertTrue(first.contains("少年提灯独行。"))
        assertTrue(first.contains("风从北方来。"))
    }

    @Test
    fun `rejects drm encrypted epub with clear message`() = runTest {
        val encryptionXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
                <EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes256-cbc"/>
                <CipherData><CipherReference URI="OEBPS/Text/ch1.xhtml"/></CipherData>
              </EncryptedData>
            </encryption>
        """.trimIndent()
        val bytes = buildEpubZip(
            mapOf(
                "META-INF/container.xml" to epubContainerXml,
                "META-INF/encryption.xml" to encryptionXml,
                "OEBPS/content.opf" to epubOpf,
                "OEBPS/toc.ncx" to epubNcx,
                "OEBPS/Text/ch1.xhtml" to epubChapter("第一章 荒原", "少年提灯独行。"),
                "OEBPS/Text/ch2.xhtml" to epubChapter("第二章 古城", "城池在夜里醒来。"),
                "OEBPS/Text/ch3.xhtml" to epubChapter("第三章 远行", "他决定北上。"),
            ),
        )
        val uri = Uri.parse("content://io.github.lunalight.paperread.test/drm.epub")
        shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(bytes))

        val result = importer.prepare(uri)
        assertTrue("期望 Failure，实际 $result", result is PrepareResult.Failure)
        val error = (result as PrepareResult.Failure).error
        assertTrue("期望 DrmEncrypted，实际 $error", error is ImportError.DrmEncrypted)
        assertTrue("DRM 文案应可区分", error.userMessage.contains("DRM"))
        assertEquals(ImportStage.PARSING, error.stage)
    }
}
