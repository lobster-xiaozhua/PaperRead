package io.github.lunalight.paperread.backup

import io.github.lunalight.paperread.data.repository.BookStateWriter
import io.github.lunalight.paperread.fakes.FakeBookTextStore
import io.github.lunalight.paperread.fakes.testBook
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 备份「云同步行为」单测（US-22）：验证 [BackupExporter.export] 的 `includeSources`
 * 与 [BackupImporter.restore] 的 `progressOnly` 两个新开关的语义，且不破坏既有默认行为。
 */
class BackupSyncBehaviorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var textStore: FakeBookTextStore
    private lateinit var repository: MemoryBookRepository

    @Before
    fun setUp() {
        textStore = FakeBookTextStore().apply { booksRoot = tmp.newFolder("books") }
        repository = MemoryBookRepository()
    }

    // ---- includeSources=false ----

    @Test
    fun `export includeSources=false 不写源文件条目且manifest标sourceMissing`() = runTest {
        seedBook(repository, textStore, dirName = "dir-a", title = "书一", content = "书一正文")
        val outFile = File(tmp.root, "status-only.zip")

        val manifest = BackupExporter(repository, textStore).export(
            outFile,
            "password123".toCharArray(),
            includeSources = false,
        )

        ZipFile(outFile).use { zip ->
            val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            assertTrue("仅状态快照不得含源文件条目：$names", names.none { it.startsWith("books/") })
            assertTrue("仍应有状态条目", names.any { it.startsWith("state/") })
        }
        assertTrue("条目应标记 sourceMissing", manifest.books.single().sourceMissing)
    }

    @Test
    fun `export 默认includeSources true 仍写源文件条目`() = runTest {
        seedBook(repository, textStore, dirName = "dir-a", title = "书一", content = "书一正文")
        val outFile = File(tmp.root, "full.zip")

        val manifest = BackupExporter(repository, textStore).export(outFile, "password123".toCharArray())

        ZipFile(outFile).use { zip ->
            assertTrue("默认应含源文件条目", zip.getEntry("books/dir-a/source.txt") != null)
        }
        assertFalse("默认 sourceMissing 应为 false", manifest.books.single().sourceMissing)
    }

    // ---- restore progressOnly=true ----

    @Test
    fun `progressOnly 本机有同sha书时写入进度与书签`() = runTest {
        // 来源库导出「仅状态」快照（sourceMissing=true）
        val source = seedBook(
            repository,
            textStore,
            dirName = "dir-a",
            title = "书X",
            content = "正文X",
            lastReadAt = 100L,
        )
        val snapshot = File(tmp.root, "snap.zip")
        BackupExporter(repository, textStore).export(
            snapshot,
            "password123".toCharArray(),
            includeSources = false,
        )

        // 目标库有同一本书（sha 相同，但本机进度更旧）
        val target = MemoryBookRepository()
        target.seed(
            testBook(
                id = 7,
                title = "书X",
                sha256 = source.sha256,
                dirName = "dir-local",
                lastReadAt = 50L,
                progressPercent = 0.1f,
            ),
        )
        val importer = BackupImporter(
            bookRepository = target,
            gateway = FakeImportGateway(),
            stateWriter = BookStateWriter(target),
            workDir = File(tmp.root, "work"),
        )

        val result = importer.restore(
            snapshot,
            "password123".toCharArray(),
            progressOnly = true,
        ) as BackupRestoreResult.Done

        assertEquals(1, result.report.progressWritten)
        assertEquals(1, target.savedPositions.size)
    }

    @Test
    fun `progressOnly 本机无此书时记失败而非中断`() = runTest {
        // 快照来自一本本机没有的书
        seedBook(repository, textStore, dirName = "dir-a", title = "书X", content = "正文X")
        val snapshot = File(tmp.root, "snap.zip")
        BackupExporter(repository, textStore).export(
            snapshot,
            "password123".toCharArray(),
            includeSources = false,
        )

        val target = MemoryBookRepository() // 空库，找不到 sha
        val importer = BackupImporter(
            bookRepository = target,
            gateway = FakeImportGateway(),
            stateWriter = BookStateWriter(target),
            workDir = File(tmp.root, "work"),
        )

        val result = importer.restore(
            snapshot,
            "password123".toCharArray(),
            progressOnly = true,
        ) as BackupRestoreResult.Done

        assertEquals(0, result.report.progressWritten)
        assertEquals(1, result.report.failed.size)
        assertTrue(
            "失败原因应说明本机无此书：${result.report.failed.single().reason}",
            result.report.failed.single().reason.contains("本机无此书，未同步进度"),
        )
    }

    @Test
    fun `progressOnly false 时源文件缺失仍按原行为记失败`() = runTest {
        seedBook(repository, textStore, dirName = "dir-a", title = "书X", content = "正文X")
        val snapshot = File(tmp.root, "snap.zip")
        BackupExporter(repository, textStore).export(
            snapshot,
            "password123".toCharArray(),
            includeSources = false,
        )
        val target = MemoryBookRepository()
        val importer = BackupImporter(
            bookRepository = target,
            gateway = FakeImportGateway(),
            stateWriter = BookStateWriter(target),
            workDir = File(tmp.root, "work"),
        )

        // 默认 progressOnly=false：sourceMissing 的书应记「源文件缺失」失败
        val result = importer.restore(snapshot, "password123".toCharArray()) as BackupRestoreResult.Done

        assertEquals(1, result.report.failed.size)
        assertTrue(
            "默认行为应记源文件缺失：${result.report.failed.single().reason}",
            result.report.failed.single().reason.contains("源文件缺失"),
        )
    }
}
