package io.github.lunalight.paperread.transfer

import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 迁移接收编排单测（纯 JVM，fake 网关）：
 * 逐本导入、已存在不重复导入、单本失败不中断（AC-10.3）、
 * 回写按最后阅读时间（AC-10.4）。
 */
class MigrationProcessorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun snapshot(
        sha256: String,
        title: String,
        lastReadAt: Long? = 100L,
    ) = BookStateSnapshot(
        sha256 = sha256,
        title = title,
        author = "作者",
        format = "TXT",
        encoding = "UTF-8",
        fileSize = 1L,
        totalChars = 10L,
        chapterCount = 1,
        lastChapterIndex = 0,
        lastParagraphIndex = 0,
        lastScrollOffset = 0,
        lastCharOffset = 0,
        progressPercent = 0.1f,
        lastReadAt = lastReadAt,
        addedAt = 0L,
        bookmarks = listOf(
            BookmarkSnapshot(0, 0, 0, "摘录", 5L),
        ),
    )

    /** 可配置的 fake 网关：记录回写、可指定导入失败。 */
    private class FakeGateway(
        private val existing: Map<String, Book> = emptyMap(),
        private val failing: Set<String> = emptySet(),
    ) : MigrationImportGateway {

        val imported = mutableListOf<String>()
        val progressWritten = mutableListOf<String>()
        val bookmarksWritten = mutableListOf<String>()

        override suspend fun importFromSource(sourceFile: File, snapshot: BookStateSnapshot): ImportOutcome =
            if (snapshot.sha256 in failing) {
                ImportOutcome.Skipped("该 EPUB 含 DRM 加密，无法导入")
            } else {
                imported += snapshot.sha256
                ImportOutcome.Imported(book(id = 100L + imported.size, sha256 = snapshot.sha256, lastReadAt = null))
            }

        override suspend fun findBySha256(sha256: String): Book? = existing[sha256]

        override suspend fun writeProgress(book: Book, snapshot: BookStateSnapshot) {
            progressWritten += book.sha256
        }

        override suspend fun writeBookmarks(book: Book, snapshot: BookStateSnapshot) {
            bookmarksWritten += book.sha256
        }
    }

    private fun bundleOf(vararg snapshots: BookStateSnapshot): UnpackedBundle {
        val sources = snapshots.associate { it.sha256 to tmp.newFile("${it.sha256}.txt").apply { writeText("x") } }
        return UnpackedBundle(MigrationManifest(books = snapshots.toList()), sources)
    }

    @Test
    fun `逐本导入并回写进度与书签`() = runBlocking {
        val gateway = FakeGateway()
        val bundle = bundleOf(snapshot("a", "甲"), snapshot("b", "乙"))

        val report = MigrationProcessor(gateway).process(bundle)

        assertEquals(2, report.imported)
        assertEquals(0, report.duplicates)
        assertEquals(2, report.progressWritten)
        assertTrue(report.failed.isEmpty())
        assertEquals(listOf("a", "b"), gateway.imported)
        assertEquals(listOf("a", "b"), gateway.bookmarksWritten)
    }

    @Test
    fun `单本失败不中断整体并记入清单`() = runBlocking {
        val gateway = FakeGateway(failing = setOf("bad"))
        val bundle = bundleOf(snapshot("ok1", "正常一"), snapshot("bad", "坏书"), snapshot("ok2", "正常二"))

        val report = MigrationProcessor(gateway).process(bundle)

        assertEquals(2, report.imported)
        assertEquals(1, report.failed.size)
        assertEquals("坏书", report.failed[0].title)
        assertEquals("该 EPUB 含 DRM 加密，无法导入", report.failed[0].reason)
        assertEquals(3, report.total)
    }

    @Test
    fun `已存在的书不重复导入但按较新时间回写`() = runBlocking {
        val gateway = FakeGateway(existing = mapOf("a" to book(5, "a", lastReadAt = 50L)))
        val bundle = bundleOf(snapshot("a", "甲", lastReadAt = 100L))

        val report = MigrationProcessor(gateway).process(bundle)

        assertEquals(0, report.imported)
        assertEquals(1, report.duplicates)
        assertEquals(1, report.progressWritten)
        assertTrue(gateway.imported.isEmpty())
    }

    @Test
    fun `本地进度更新时不回写`() = runBlocking {
        val gateway = FakeGateway(existing = mapOf("a" to book(5, "a", lastReadAt = 900L)))
        val bundle = bundleOf(snapshot("a", "甲", lastReadAt = 100L))

        val report = MigrationProcessor(gateway).process(bundle)

        assertEquals(0, report.progressWritten)
        assertTrue(gateway.progressWritten.isEmpty())
        assertTrue(gateway.bookmarksWritten.isEmpty())
    }

    @Test
    fun `源文件缺失记失败而非静默丢弃`() = runBlocking {
        val gateway = FakeGateway()
        val bundle = UnpackedBundle(MigrationManifest(books = listOf(snapshot("ghost", "无源书"))), emptyMap())

        val report = MigrationProcessor(gateway).process(bundle)

        assertEquals(1, report.failed.size)
        assertTrue(report.failed[0].reason.contains("源文件缺失"))
        assertTrue(gateway.imported.isEmpty())
    }

    @Test
    fun `进度回调按本推进`() = runBlocking {
        val gateway = FakeGateway()
        val bundle = bundleOf(snapshot("a", "甲"), snapshot("b", "乙"))
        val calls = mutableListOf<Pair<Int, String>>()

        MigrationProcessor(gateway).process(bundle) { done, _, title ->
            calls += done to title
        }

        assertEquals(listOf(0 to "甲", 1 to "甲", 1 to "乙", 2 to "乙"), calls)
    }

    @Test
    fun `空清单返回空报告`() = runBlocking {
        val report = MigrationProcessor(FakeGateway()).process(UnpackedBundle(MigrationManifest(books = emptyList()), emptyMap()))

        assertEquals(0, report.total)
        assertNull(report.failed.firstOrNull())
    }

    private companion object {
        fun book(id: Long, sha256: String, lastReadAt: Long?) = Book(
            id = id,
            title = "t-$id",
            author = "a",
            format = BookFormat.TXT,
            encoding = "UTF-8",
            sha256 = sha256,
            fileSize = 1L,
            dirName = "d-$id",
            totalChars = 10L,
            chapterCount = 1,
            progressPercent = 0f,
            lastReadAt = lastReadAt,
            addedAt = 0L,
        )
    }
}
