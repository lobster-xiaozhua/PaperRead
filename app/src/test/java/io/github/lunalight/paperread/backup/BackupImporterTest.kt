package io.github.lunalight.paperread.backup

import io.github.lunalight.paperread.data.repository.BookStateWriter
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.fakes.FakeBookTextStore
import io.github.lunalight.paperread.fakes.testBook
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 备份恢复单测（T-016 / AC-14.3 ~ AC-14.9）。
 *
 * 逐条固化：
 * - AC-14.3 高版本拒绝；
 * - AC-14.4 走导入网关 + SHA-256 命中跳过；
 * - AC-14.5 单本失败不中断、结束有失败清单；
 * - AC-14.6 旧进度不覆盖新进度（WriteBackDecider）；
 * - AC-14.7 口令错误可重试；
 * - AC-14.9 字体缺失进入报告；
 * - 包完整性（条目缺失 / 哈希不符 / manifest 摘要被改）逐项有断言。
 */
class BackupImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var sourceRepo: MemoryBookRepository
    private lateinit var sourceStore: FakeBookTextStore

    @Before
    fun setUp() {
        sourceStore = FakeBookTextStore().apply { booksRoot = tmp.newFolder("books") }
        sourceRepo = MemoryBookRepository()
    }

    // ---- AC-14.8 / AC-14.4：预览 + 恢复 ----

    @Test
    fun `明文备份预览书目并完整恢复书籍进度与书签`() = runTest {
        val source = seedBook(
            repository = sourceRepo,
            textStore = sourceStore,
            dirName = "dir-a",
            title = "第一本",
            content = "第一本正文",
            lastReadAt = 100L,
            progressPercent = 0.42f,
            lastChapterIndex = 3,
            lastParagraphIndex = 4,
            lastCharOffset = 50,
            bookmarks = listOf(
                Bookmark(
                    bookId = 0L,
                    chapterIndex = 3,
                    paragraphIndex = 4,
                    charOffset = 50,
                    excerpt = "精彩段落",
                    createdAt = 777L,
                    note = "备注",
                    updatedAt = 8L,
                ),
            ),
        )
        val zip = exportBackup()

        val restoreRepo = MemoryBookRepository()
        val gateway = FakeImportGateway()
        val importer = newImporter(restoreRepo, gateway)

        val inspection = importer.inspect(zip)
        assertTrue("预览应可读：$inspection", inspection is BackupInspection.Ready)
        inspection as BackupInspection.Ready
        assertEquals(listOf("第一本"), inspection.bookTitles)
        assertEquals(0, inspection.missingEntries)
        assertTrue(inspection.fontFallbacks.isEmpty())
        assertFalse(inspection.manifest.encrypted)

        val result = importer.restore(zip)
        assertTrue("恢复应完成：$result", result is BackupRestoreResult.Done)
        val report = (result as BackupRestoreResult.Done).report
        assertEquals(1, report.imported)
        assertEquals(0, report.duplicates)
        assertEquals(1, report.progressWritten)
        assertTrue(report.failed.isEmpty())

        val position = restoreRepo.savedPositions.single()
        assertEquals(3, position.position.chapterIndex)
        assertEquals(4, position.position.paragraphIndex)
        assertEquals(50, position.position.charOffset)
        assertEquals(0.42f, position.progressPercent, 0.0001f)
        val bookmark = restoreRepo.addedBookmarks.single()
        assertEquals("精彩段落", bookmark.excerpt)
        assertEquals("备注", bookmark.note)
        assertEquals(8L, bookmark.updatedAt)
        // 元数据提示：恢复保留原书名（导入流水线的显示名是临时文件名）
        assertEquals(source.title, gateway.importedHints.single()?.title)
    }

    @Test
    fun `加密备份未给口令时只要求口令且不暴露书名`() = runTest {
        seedBook(sourceRepo, sourceStore, dirName = "dir-a", title = "秘密书名")
        val password = "correct-horse-battery".toCharArray()
        val zip = exportBackup(password)

        val importer = newImporter(MemoryBookRepository(), FakeImportGateway())
        val inspection = importer.inspect(zip)

        assertTrue(inspection is BackupInspection.PasswordRequired)
        val manifest = (inspection as BackupInspection.PasswordRequired).manifest
        assertTrue(manifest.encrypted)
        assertNull(manifest.books.single().title)
    }

    // ---- AC-14.7：口令错误可重试 ----

    @Test
    fun `口令错误可原地重试且不产生任何写入`() = runTest {
        seedBook(sourceRepo, sourceStore, dirName = "dir-a", title = "加密书")
        val password = "correct-horse-battery".toCharArray()
        val zip = exportBackup(password)

        val restoreRepo = MemoryBookRepository()
        val gateway = FakeImportGateway()
        val importer = newImporter(restoreRepo, gateway)
        val wrong = "wrong-password".toCharArray()

        assertTrue(importer.inspect(zip, wrong) is BackupInspection.WrongPassword)
        assertTrue(importer.restore(zip, wrong) is BackupRestoreResult.WrongPassword)
        assertTrue("口令错误时不得导入任何书", gateway.importedFiles.isEmpty())
        assertTrue(restoreRepo.savedPositions.isEmpty())

        val retry = importer.restore(zip, password)
        assertTrue("正确口令应可重试成功：$retry", retry is BackupRestoreResult.Done)
        assertEquals(1, (retry as BackupRestoreResult.Done).report.imported)
    }

    // ---- AC-14.3：版本过新 ----

    @Test
    fun `高版本备份被明确拒绝`() = runTest {
        val zip = File(tmp.root, "future.zip")
        val manifest = BackupManifest(
            version = BACKUP_VERSION + 1,
            bookCount = 0,
            books = emptyList(),
            contentDigest = backupContentDigest(emptyList()),
        )
        writeZip(zip) { out ->
            out.putNextEntry(ZipEntry("manifest.json"))
            out.write(
                backupJson.encodeToString(BackupManifest.serializer(), manifest)
                    .toByteArray(Charsets.UTF_8),
            )
            out.closeEntry()
        }

        val importer = newImporter(MemoryBookRepository(), FakeImportGateway())
        val inspection = importer.inspect(zip)
        assertTrue(inspection is BackupInspection.Invalid)
        assertEquals(
            BackupInvalidReason.VERSION_TOO_NEW,
            (inspection as BackupInspection.Invalid).reason,
        )

        val result = importer.restore(zip)
        assertTrue(result is BackupRestoreResult.Rejected)
        assertEquals(
            BackupInvalidReason.VERSION_TOO_NEW,
            (result as BackupRestoreResult.Rejected).reason,
        )
    }

    @Test
    fun `不是备份的文件被拒绝`() = runTest {
        val bogus = File(tmp.root, "bogus.zip").apply { writeText("这不是 zip") }
        val importer = newImporter(MemoryBookRepository(), FakeImportGateway())

        val inspection = importer.inspect(bogus)
        assertTrue(inspection is BackupInspection.Invalid)
        assertEquals(
            BackupInvalidReason.NOT_A_BACKUP,
            (inspection as BackupInspection.Invalid).reason,
        )
    }

    @Test
    fun `manifest 内容摘要被篡改时拒绝打开`() = runTest {
        seedBook(sourceRepo, sourceStore, dirName = "dir-a", title = "书")
        val zip = exportBackup()

        rewriteZip(zip) { name, bytes ->
            if (name != "manifest.json") {
                bytes
            } else {
                val text = bytes.toString(Charsets.UTF_8)
                val tampered = text.replace(Regex("\"contentDigest\":\"[0-9a-f]+\""), "\"contentDigest\":\"deadbeef\"")
                tampered.toByteArray(Charsets.UTF_8)
            }
        }

        val inspection = newImporter(MemoryBookRepository(), FakeImportGateway()).inspect(zip)
        assertTrue(inspection is BackupInspection.Invalid)
        assertEquals(
            BackupInvalidReason.CORRUPT,
            (inspection as BackupInspection.Invalid).reason,
        )
    }

    // ---- AC-14.5：单本失败不中断 ----

    @Test
    fun `单本源文件条目缺失只记失败不中断整体`() = runTest {
        seedBook(sourceRepo, sourceStore, dirName = "dir-a", title = "书一")
        seedBook(sourceRepo, sourceStore, dirName = "dir-b", title = "书二")
        seedBook(sourceRepo, sourceStore, dirName = "dir-c", title = "书三")
        val zip = exportBackup()

        rewriteZip(zip, drop = setOf("books/dir-b/source.txt"))

        val restoreRepo = MemoryBookRepository()
        val gateway = FakeImportGateway()
        val importer = newImporter(restoreRepo, gateway)

        val inspection = importer.inspect(zip) as BackupInspection.Ready
        assertEquals("预览应提示条目缺失", 1, inspection.missingEntries)

        val result = importer.restore(zip) as BackupRestoreResult.Done
        assertEquals(2, result.report.imported)
        val failure = result.report.failed.single()
        assertEquals("书二", failure.title)
        assertTrue("失败原因应说明条目缺失：${failure.reason}", failure.reason.contains("缺少源文件条目"))
    }

    @Test
    fun `单本哈希不符时该书记失败其余照常恢复`() = runTest {
        seedBook(sourceRepo, sourceStore, dirName = "dir-a", title = "好书")
        seedBook(sourceRepo, sourceStore, dirName = "dir-b", title = "坏书")
        val zip = exportBackup()

        rewriteZip(zip) { name, bytes ->
            if (name == "books/dir-b/source.txt") "被篡改的内容".toByteArray(Charsets.UTF_8) else bytes
        }

        val result = newImporter(MemoryBookRepository(), FakeImportGateway())
            .restore(zip) as BackupRestoreResult.Done

        assertEquals(1, result.report.imported)
        val failure = result.report.failed.single()
        assertEquals("坏书", failure.title)
        assertTrue(failure.reason.contains("内容哈希不匹配"))
    }

    @Test
    fun `阅读状态条目缺失记失败`() = runTest {
        seedBook(sourceRepo, sourceStore, dirName = "dir-a", title = "书一")
        val zip = exportBackup()
        rewriteZip(zip, drop = setOf("state/dir-a.json"))

        val result = newImporter(MemoryBookRepository(), FakeImportGateway())
            .restore(zip) as BackupRestoreResult.Done

        assertEquals(0, result.report.imported)
        assertTrue(result.report.failed.single().reason.contains("阅读记录"))
    }

    // ---- AC-14.4 / AC-14.6：去重与回写裁决 ----

    @Test
    fun `SHA256命中时跳过导入并按较新时间回写`() = runTest {
        val source = seedBook(
            repository = sourceRepo,
            textStore = sourceStore,
            dirName = "dir-a",
            title = "已存在",
            lastReadAt = 500L,
        )
        val zip = exportBackup()

        val restoreRepo = MemoryBookRepository()
        val existing = source.copy(id = 42L, lastReadAt = 100L)
        restoreRepo.seed(existing)
        val gateway = FakeImportGateway()
        val importer = newImporter(restoreRepo, gateway)

        val result = importer.restore(zip) as BackupRestoreResult.Done

        assertEquals(0, result.report.imported)
        assertEquals(1, result.report.duplicates)
        assertEquals(1, result.report.progressWritten)
        assertTrue("命中即跳过导入", gateway.importedFiles.isEmpty())
        assertEquals(42L, restoreRepo.savedPositions.single().bookId)
        assertEquals(listOf(42L), restoreRepo.clearedBookmarkBookIds)
    }

    @Test
    fun `旧进度不覆盖新进度`() = runTest {
        seedBook(
            repository = sourceRepo,
            textStore = sourceStore,
            dirName = "dir-a",
            title = "本地较新",
            lastReadAt = 100L,
        )
        val zip = exportBackup()

        val restoreRepo = MemoryBookRepository()
        restoreRepo.seed(
            testBook(
                id = 9L,
                title = "本地较新",
                sha256 = sourceRepo.books().single().sha256,
                dirName = "local-dir",
                lastReadAt = 900L,
            ),
        )

        val result = newImporter(restoreRepo, FakeImportGateway())
            .restore(zip) as BackupRestoreResult.Done

        assertEquals(1, result.report.duplicates)
        assertEquals(0, result.report.progressWritten)
        assertTrue("本地较新时不得回写", restoreRepo.savedPositions.isEmpty())
        assertTrue(restoreRepo.clearedBookmarkBookIds.isEmpty())
    }

    // ---- AC-14.9：字体缺失 ----

    @Test
    fun `字体缺失进入预览与结果报告`() = runTest {
        seedBook(sourceRepo, sourceStore, dirName = "dir-a", title = "书")
        val zip = exportBackup(
            fontReferences = listOf(
                BackupFontReference("serif", "衬线", builtIn = true),
                BackupFontReference("custom-missing", "思源宋体", builtIn = false),
            ),
        )

        val importer = newImporter(
            repository = MemoryBookRepository(),
            gateway = FakeImportGateway(),
            fontAvailability = { name -> name != "custom-missing" },
        )
        val inspection = importer.inspect(zip) as BackupInspection.Ready
        assertEquals(listOf("思源宋体"), inspection.fontFallbacks)

        val result = importer.restore(zip) as BackupRestoreResult.Done
        assertEquals(listOf("思源宋体"), result.report.fontFallbacks)
        assertEquals("字体缺失不影响书籍恢复", 1, result.report.imported)
    }

    // ---- 取消 ----

    @Test
    fun `取消恢复不留下临时目录`() = runTest {
        repeat(3) { index ->
            seedBook(sourceRepo, sourceStore, dirName = "dir-$index", title = "书$index")
        }
        val zip = exportBackup()
        val workDir = File(tmp.root, "work-cancel")
        val importer = BackupImporter(
            bookRepository = MemoryBookRepository(),
            gateway = FakeImportGateway(),
            stateWriter = BookStateWriter(MemoryBookRepository()),
            workDir = workDir,
        )

        var thrown: CancellationException? = null
        try {
            importer.restore(zip, shouldContinue = { false })
        } catch (e: CancellationException) {
            thrown = e
        }

        assertNotNull("应响应取消", thrown)
        assertTrue(
            "取消后临时目录应清空：${workDir.listFiles()?.toList()}",
            workDir.listFiles().isNullOrEmpty(),
        )
    }

    // ---- 加密恢复（AC-14.2 → 端到端可用） ----

    @Test
    fun `加密备份用正确口令可完整恢复`() = runTest {
        seedBook(sourceRepo, sourceStore, dirName = "dir-a", title = "加密书", content = "加密正文")
        val password = "correct-horse-battery".toCharArray()
        val zip = exportBackup(password)

        val result = newImporter(MemoryBookRepository(), FakeImportGateway())
            .restore(zip, password) as BackupRestoreResult.Done

        assertEquals(1, result.report.imported)
        assertEquals(1, result.report.progressWritten)
    }

    // ---- 工具 ----

    private fun newImporter(
        repository: MemoryBookRepository,
        gateway: FakeImportGateway,
        fontAvailability: FontAvailability = FontAvailability.BuiltIn,
    ): BackupImporter = BackupImporter(
        bookRepository = repository,
        gateway = gateway,
        stateWriter = BookStateWriter(repository),
        workDir = File(tmp.root, "work"),
        fontAvailability = fontAvailability,
    )

    private suspend fun exportBackup(
        password: CharArray? = null,
        fontReferences: List<BackupFontReference> = emptyList(),
    ): File {
        val exporter = BackupExporter(
            bookRepository = sourceRepo,
            bookTextStore = sourceStore,
            fontReferencesProvider = { fontReferences },
        )
        val target = File(tmp.root, "backup-${System.nanoTime()}.zip")
        exporter.export(target, password)
        return target
    }

    private fun writeZip(file: File, write: (ZipOutputStream) -> Unit) {
        ZipOutputStream(file.outputStream().buffered()).use(write)
    }

    /** 重写 zip（丢弃条目 / 替换条目字节），用于构造损坏样本。 */
    private fun rewriteZip(
        file: File,
        drop: Set<String> = emptySet(),
        transform: (name: String, bytes: ByteArray) -> ByteArray = { _, bytes -> bytes },
    ) {
        val rewritten = File(file.parentFile, "rewritten-${System.nanoTime()}.zip")
        ZipFile(file).use { zip ->
            ZipOutputStream(rewritten.outputStream().buffered()).use { out ->
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    if (entry.name in drop) return@forEach
                    val bytes = zip.getInputStream(entry).readBytes()
                    val mapped = transform(entry.name, bytes)
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(mapped)
                    out.closeEntry()
                }
            }
        }
        file.delete()
        assertTrue(rewritten.renameTo(file) || rewritten.copyTo(file, overwrite = true).exists())
        rewritten.delete()
    }
}
