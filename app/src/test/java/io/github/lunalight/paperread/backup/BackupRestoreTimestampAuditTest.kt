package io.github.lunalight.paperread.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.data.local.AppDatabase
import io.github.lunalight.paperread.data.repository.AndroidBookImportGateway
import io.github.lunalight.paperread.data.repository.BookRepositoryImpl
import io.github.lunalight.paperread.data.repository.BookStateWriter
import io.github.lunalight.paperread.data.repository.DefaultBookImporter
import io.github.lunalight.paperread.data.repository.PrepareResult
import io.github.lunalight.paperread.domain.model.ReaderPosition
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.FileBookTextStore
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * AC-14.6 / AC-14.1 的**时间戳保真**审计（真实 Room + 真实导入流水线）。
 *
 * 关注点：`BookStateWriter.writeProgress` 走 `BookRepository.saveReaderPosition`，
 * 而该方法的实现把 `lastReadAt` 写成 `System.currentTimeMillis()`（见
 * `BookRepositoryImpl.saveReaderPosition`）。若恢复时也照此写入，会产生两个后果：
 *
 * 1. 备份里的「上次阅读时间」不被还原（恢复后一律变成「刚刚」）——AC-14.1 的
 *    阅读进度保真只剩「位置」一半；
 * 2. **更严重**：恢复一次之后本地 `lastReadAt` 变成 now，导致此后恢复任何
 *    「时间戳较旧但确实是更新版本」的备份时，`WriteBackDecider` 判定本地更新，
 *    静默跳过回写——用户以为恢复了新进度，实际停留在旧进度。
 *
 * 这两条都以「用户可见的数据保真」为准，而不是以实现现状为准。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRestoreTimestampAuditTest {

    private lateinit var context: Context
    private lateinit var textStore: FileBookTextStore
    private val databases = mutableListOf<AppDatabase>()

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
        databases.forEach { it.close() }
        databases.clear()
        clearFileProviderCache()
        File(context.filesDir, "books").deleteRecursively()
        File(context.cacheDir, "backup").deleteRecursively()
    }

    @Ignore("已知缺陷 F-01（P2）：恢复未还原 lastReadAt，被 saveReaderPosition 写成 now；修复后移除本注解")
    @Test
    fun `恢复后应还原备份中的最后阅读时间`() = runTest {
        val originalReadAt = 1_600_000_000_000L
        val zip = File(context.cacheDir, "stamp.zip")

        // ---- 源库：一本读到第 2 章的书，lastReadAt = 2020 年 ----
        val sourceDb = newDatabase()
        val sourceRepo = repositoryOf(sourceDb)
        val sourceId = importTxt(sourceRepo, "时间戳书", "第一章\n内容一\n第二章\n内容二\n")
        sourceRepo.saveReaderPosition(sourceId, ReaderPosition(1, 0, 0, 3), 0.5f)
        // 把 lastReadAt 钉成历史值（saveReaderPosition 只会写 now）
        pinReadAt(sourceDb, sourceId, originalReadAt)
        assertEquals(originalReadAt, sourceRepo.getBook(sourceId)!!.lastReadAt)

        BackupExporter(sourceRepo, textStore).export(zip, password = null)

        // ---- 目标库：空库恢复 ----
        val targetDb = newDatabase()
        val targetRepo = repositoryOf(targetDb)
        val importer = BackupImporter(
            bookRepository = targetRepo,
            gateway = AndroidBookImportGateway(
                context,
                DefaultBookImporter(context, targetRepo, textStore),
                targetRepo,
            ),
            stateWriter = BookStateWriter(targetRepo),
            workDir = File(context.cacheDir, "backup/work"),
        )

        val result = importer.restore(zip) as BackupRestoreResult.Done
        assertEquals(1, result.report.imported)
        assertEquals(1, result.report.progressWritten)

        val restored = targetRepo.observeBooks().first().single()
        assertEquals("阅读位置应还原", 1, restored.lastChapterIndex)
        assertEquals(
            "最后阅读时间应还原为备份值（AC-14.1 阅读进度保真）",
            originalReadAt,
            restored.lastReadAt,
        )
    }

    @Ignore("已知缺陷 F-01（P2）：恢复把 lastReadAt 写成 now，使后续恢复较新备份被判为「旧进度」而静默跳过回写；修复后移除本注解")
    @Test
    fun `连续恢复先旧后新两个备份时新备份的进度必须生效`() = runTest {
        val olderZip = File(context.cacheDir, "older.zip")
        val newerZip = File(context.cacheDir, "newer.zip")
        val base = 1_600_000_000_000L

        // ---- 旧备份：读到第 1 章，lastReadAt = base ----
        val oldDb = newDatabase()
        val oldRepo = repositoryOf(oldDb)
        val oldId = importTxt(oldRepo, "同一本书", "第一章\n内容一\n第二章\n内容二\n第三章\n内容三\n")
        oldRepo.saveReaderPosition(oldId, ReaderPosition(0, 0, 0, 0), 0.2f)
        pinReadAt(oldDb, oldId, base)
        BackupExporter(oldRepo, textStore).export(olderZip, password = null)

        // ---- 新备份：同一本书读到第 2 章，lastReadAt = base + 10 天 ----
        val newDb = newDatabase()
        val newRepo = repositoryOf(newDb)
        val newId = importTxt(newRepo, "同一本书", "第一章\n内容一\n第二章\n内容二\n第三章\n内容三\n")
        newRepo.saveReaderPosition(newId, ReaderPosition(2, 0, 0, 3), 0.9f)
        pinReadAt(newDb, newId, base + 10L * 24 * 3600 * 1000)
        BackupExporter(newRepo, textStore).export(newerZip, password = null)

        // ---- 恢复机：先恢复旧备份，再恢复新备份 ----
        val targetDb = newDatabase()
        val targetRepo = repositoryOf(targetDb)
        val importer = BackupImporter(
            bookRepository = targetRepo,
            gateway = AndroidBookImportGateway(
                context,
                DefaultBookImporter(context, targetRepo, textStore),
                targetRepo,
            ),
            stateWriter = BookStateWriter(targetRepo),
            workDir = File(context.cacheDir, "backup/work"),
        )

        val first = importer.restore(olderZip) as BackupRestoreResult.Done
        assertEquals(1, first.report.progressWritten)

        val second = importer.restore(newerZip) as BackupRestoreResult.Done
        assertEquals("同一本书应按 SHA-256 命中", 1, second.report.duplicates)
        assertEquals(
            "新备份的进度较新，必须回写（AC-14.6）",
            1,
            second.report.progressWritten,
        )

        val restored = targetRepo.observeBooks().first().single()
        assertEquals(
            "恢复后应停在新备份的阅读位置（第 3 章）",
            2,
            restored.lastChapterIndex,
        )
        assertEquals(0.9f, restored.progressPercent, 0.0001f)
        assertTrue(restored.lastReadAt != null)
    }

    // ---- 工具 ----

    private fun newDatabase(): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { databases += it }

    private fun repositoryOf(database: AppDatabase): BookRepository = BookRepositoryImpl(
        database.bookDao(),
        database.chapterDao(),
        database.bookmarkDao(),
    )

    private suspend fun importTxt(repo: BookRepository, title: String, content: String): Long {
        val importer = DefaultBookImporter(context, repo, textStore)
        val uri = Uri.parse("content://paperread.test/$title.txt")
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)),
        )
        val result = importer.prepare(uri)
        assertTrue("应导入成功：$result", result is PrepareResult.Success)
        return importer.confirm((result as PrepareResult.Success).pending)
    }

    /** 把 lastReadAt 钉成历史值（`saveReaderPosition` 会写成 now，不能用来构造旧时间）。 */
    private suspend fun pinReadAt(database: AppDatabase, bookId: Long, readAt: Long) {
        val entity = database.bookDao().getById(bookId)!!
        database.bookDao().update(entity.copy(lastReadAt = readAt))
    }

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
