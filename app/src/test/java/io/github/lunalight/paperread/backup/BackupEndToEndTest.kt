package io.github.lunalight.paperread.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.data.local.AppDatabase
import io.github.lunalight.paperread.data.repository.AndroidBookImportGateway
import io.github.lunalight.paperread.data.repository.BookImporter
import io.github.lunalight.paperread.data.repository.BookRepositoryImpl
import io.github.lunalight.paperread.data.repository.BookStateWriter
import io.github.lunalight.paperread.data.repository.DefaultBookImporter
import io.github.lunalight.paperread.data.repository.PrepareResult
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.model.ReaderPosition
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.FileBookTextStore
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 备份端到端验收（US-14 最有价值的一条）：**导出 → 删库 → 恢复 → 数据比对**。
 *
 * 全程真实组件：Room 数据库、`FileBookTextStore`、`DefaultBookImporter`
 * （`PreparePipeline` 真解析 + `book.txt` 真落盘）、`AndroidBookImportGateway`
 * （FileProvider 真读写）、`BackupExporter` / `BackupImporter` / `BackupCrypto`。
 * 只有「用户点按钮」被测试代码替代，因此这条用例绿 ≡ AC-14.1 / 14.4 / 14.5 /
 * 14.6 / 14.9 在真实数据通路上成立。
 *
 * 同时证明两件事：
 * - `book.txt` 不进备份包，但恢复后**确实被流水线重建**（AC-14.1 的口径）；
 * - 第二次恢复全部命中 SHA-256（AC-14.4 的幂等性）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupEndToEndTest {

    private lateinit var context: Context
    private lateinit var textStore: FileBookTextStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        textStore = FileBookTextStore(context)
        clearFileProviderCache()
        File(context.filesDir, "books").deleteRecursively()
        File(context.cacheDir, "backup").deleteRecursively()
    }

    @After
    fun tearDown() {
        clearFileProviderCache()
        File(context.filesDir, "books").deleteRecursively()
        File(context.cacheDir, "backup").deleteRecursively()
    }

    @Test
    fun `导出删库恢复后书籍进度与书签全等`() = runTest {
        // 导出后仍要用于比对的快照（db1 关闭后不能再查）
        var booksBefore: List<io.github.lunalight.paperread.domain.model.Book> = emptyList()
        var bookmarksBefore: Map<String, List<Bookmark>> = emptyMap()
        val zip = File(context.cacheDir, "backup-e2e.zip")

        // ---- 1. 建库并导入两本真实 TXT（走真实导入流水线） ----
        val db1 = newDatabase()
        try {
            val repo1 = repositoryOf(db1)
            val importer1 = DefaultBookImporter(context, repo1, textStore)
            val firstId = importTxt(
                importer = importer1,
                title = "第一本",
                content = """
                    第一章 起点
                    少年提灯独行，身后是沉睡的城池。
                    第二章 转折
                    城池在夜里醒来，灯火次第亮起。
                """.trimIndent(),
            )
            val secondId = importTxt(
                importer = importer1,
                title = "第二本",
                content = """
                    第一章 独行
                    风从北方来，带着沙。
                    第二章 相逢
                    他决定北上，去赴一个十年之约。
                """.trimIndent(),
            )
            repo1.saveReaderPosition(firstId, ReaderPosition(1, 2, 3, 40), 0.35f)
            repo1.saveReaderPosition(secondId, ReaderPosition(0, 1, 0, 12), 0.8f)
            repo1.addBookmark(
                Bookmark(
                    bookId = firstId,
                    chapterIndex = 1,
                    paragraphIndex = 0,
                    charOffset = 40,
                    excerpt = "城池在夜里醒来",
                    createdAt = 900L,
                    note = "好句",
                    updatedAt = 901L,
                ),
            )

            booksBefore = repo1.observeBooks().first()
            assertEquals(2, booksBefore.size)
            bookmarksBefore = booksBefore.associate { book ->
                book.sha256 to repo1.observeBookmarks(book.id).first()
            }

            // ---- 2. 导出（加密，覆盖 AC-14.2 的真实数据通路） ----
            val password = "correct-horse-battery".toCharArray()
            val manifest = BackupExporter(repo1, textStore).export(zip, password)
            assertEquals(2, manifest.bookCount)
            assertEquals(2, manifest.progressCount)
            assertEquals(1, manifest.bookmarkCount)
            assertTrue(manifest.encrypted)
            ZipFile(zip).use { archive ->
                val names = archive.entries().asSequence().map { it.name }.toList()
                assertTrue(names.contains("manifest.json"))
                assertTrue(names.contains("crypto.json"))
                assertFalse("book.txt 是派生文件，不得进包：$names", names.any { it.contains("book.txt") })
            }
        } finally {
            db1.close()
        }

        // ---- 3. 删库：关闭数据库 + 删除书籍目录（模拟换机 / 重装） ----
        File(context.filesDir, "books").deleteRecursively()
        assertTrue("删库后不应再有任何书籍目录", File(context.filesDir, "books").listFiles().isNullOrEmpty())

        // ---- 4. 从备份恢复到全新数据库 ----
        val db2 = newDatabase()
        try {
            val repo2 = repositoryOf(db2)
            val importer2 = DefaultBookImporter(context, repo2, textStore)
            val gateway = AndroidBookImportGateway(context, importer2, repo2)
            val backupImporter = BackupImporter(
                bookRepository = repo2,
                gateway = gateway,
                stateWriter = BookStateWriter(repo2),
                workDir = File(context.cacheDir, "backup/work"),
            )
            val password = "correct-horse-battery".toCharArray()

            val inspection = backupImporter.inspect(zip, password)
            assertTrue("应可预览：$inspection", inspection is BackupInspection.Ready)
            assertEquals(
                setOf("第一本", "第二本"),
                (inspection as BackupInspection.Ready).bookTitles.toSet(),
            )

            val result = backupImporter.restore(zip, password)
            assertTrue("应恢复完成：$result", result is BackupRestoreResult.Done)
            val report = (result as BackupRestoreResult.Done).report
            assertEquals(2, report.imported)
            assertEquals(0, report.duplicates)
            assertEquals(2, report.progressWritten)
            assertTrue(report.failed.isEmpty())

            // ---- 5. 数据比对：书籍 / 进度 / 书签全等 ----
            val booksAfter = repo2.observeBooks().first()
            assertEquals(2, booksAfter.size)
            // 重新取导出前的快照（db1 已关闭，用备份里声明的源哈希做键比对）
            booksAfter.forEach { restored ->
                val before = booksBefore.single { it.sha256 == restored.sha256 }
                assertEquals("书名应保真", before.title, restored.title)
                assertEquals("作者应保真", before.author, restored.author)
                assertEquals("章节数应一致", before.chapterCount, restored.chapterCount)
                assertEquals("阅读章节应回写", before.lastChapterIndex, restored.lastChapterIndex)
                assertEquals("阅读段落应回写", before.lastParagraphIndex, restored.lastParagraphIndex)
                assertEquals("字符偏移应回写", before.lastCharOffset, restored.lastCharOffset)
                assertEquals("进度百分比应回写", before.progressPercent, restored.progressPercent, 0.0001f)
                assertTrue("恢复后 book.txt 应由流水线重建", textStore.textFile(restored.dirName).length() > 0)

                val beforeMarks = bookmarksBefore.getValue(restored.sha256)
                val afterMarks = repo2.observeBookmarks(restored.id).first()
                assertEquals(beforeMarks.size, afterMarks.size)
                beforeMarks.zip(afterMarks).forEach { (expected, actual) ->
                    assertEquals(expected.chapterIndex, actual.chapterIndex)
                    assertEquals(expected.paragraphIndex, actual.paragraphIndex)
                    assertEquals(expected.charOffset, actual.charOffset)
                    assertEquals(expected.excerpt, actual.excerpt)
                    assertEquals(expected.createdAt, actual.createdAt)
                    assertEquals(expected.note, actual.note)
                    assertEquals(expected.updatedAt, actual.updatedAt)
                }
            }

            // ---- 6. 幂等：再恢复一次全部命中 SHA-256 ----
            val second = backupImporter.restore(zip, password)
            assertTrue(second is BackupRestoreResult.Done)
            val secondReport = (second as BackupRestoreResult.Done).report
            assertEquals(0, secondReport.imported)
            assertEquals(2, secondReport.duplicates)
            assertEquals(2, repo2.observeBooks().first().size)
        } finally {
            db2.close()
        }
    }

    // ---- 工具 ----

    private fun newDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun repositoryOf(database: AppDatabase): BookRepository = BookRepositoryImpl(
        database.bookDao(),
        database.chapterDao(),
        database.bookmarkDao(),
    )

    private suspend fun importTxt(importer: BookImporter, title: String, content: String): Long {
        val uri = Uri.parse("content://paperread.test/$title.txt")
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)),
        )
        val result = importer.prepare(uri)
        assertTrue("《$title》应导入成功：$result", result is PrepareResult.Success)
        return importer.confirm((result as PrepareResult.Success).pending)
    }

    /** FileProvider 的 PathStrategy 按 authority 静态缓存（绑定首个 cacheDir）。 */
    private fun clearFileProviderCache() {
        runCatching {
            val cache = androidx.core.content.FileProvider::class.java
                .getDeclaredField("sCache")
                .apply { isAccessible = true }
                .get(null)
            (cache as? HashMap<*, *>)?.clear()
        }
    }
}
