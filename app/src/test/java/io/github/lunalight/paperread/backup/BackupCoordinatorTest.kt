package io.github.lunalight.paperread.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.data.repository.BookStateWriter
import io.github.lunalight.paperread.fakes.FakeBookTextStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 备份编排层单测（T-015 / T-016）：SAF Uri ↔ 缓存临时文件的搬运、状态流转、
 * 口令生命周期。
 *
 * 纯逻辑（打包 / 解包 / 加密）由 Exporter / Importer / Crypto 三个测试类覆盖；
 * 这里只验证「设置页按一个按钮到文件真正落地」的编排是否正确：
 * 导入导出成功、加密包先要口令、错口令原地重试、非备份文件给出明确错误，
 * 以及**调用方传入的口令数组在流程结束时被 zero-fill**（AC-14.2）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var repository: MemoryBookRepository
    private lateinit var textStore: FakeBookTextStore
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: BackupCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        textStore = FakeBookTextStore().apply { booksRoot = tmp.newFolder("books") }
        repository = MemoryBookRepository()
        seedBook(repository, textStore, dirName = "dir-a", title = "书一", content = "书一正文")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        coordinator = BackupCoordinator(
            context = context,
            exporter = BackupExporter(repository, textStore),
            importer = BackupImporter(
                bookRepository = repository,
                gateway = FakeImportGateway(),
                stateWriter = BookStateWriter(repository),
                workDir = File(context.cacheDir, "backup/work"),
            ),
            scope = scope,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        File(context.cacheDir, "backup").deleteRecursively()
    }

    @Test
    fun `预览后导出到SAF目标并把口令数组清零`() {
        coordinator.loadExportPreview()
        waitUntil { coordinator.state.value.exportPreview != null }
        val preview = coordinator.state.value.exportPreview!!
        assertEquals(1, preview.bookCount)
        assertEquals(1, preview.progressCount)

        val uri = Uri.parse("content://paperread.test/out.zip")
        val sink = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, sink)
        val password = "correct-horse-battery".toCharArray()

        coordinator.export(uri, password)

        waitUntil {
            coordinator.state.value.message != null || coordinator.state.value.error != null
        }
        assertNull("不应报错：${coordinator.state.value.error}", coordinator.state.value.error)
        assertEquals(context.getString(R.string.backup_export_done), coordinator.state.value.message)
        assertTrue("流程结束必须 zero-fill 口令", password.all { it == '\u0000' })

        val entries = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(sink.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries += entry.name
            }
        }
        assertTrue("导出应含 manifest：$entries", entries.contains("manifest.json"))
        assertTrue(entries.contains("state/dir-a.json"))
        assertFalse("不得导出派生的 book.txt", entries.any { it.contains("book.txt") })
    }

    @Test
    fun `打开备份显示预览后恢复并出报告`() {
        val zip = exportToFile(password = null)
        val uri = openUri(zip)

        coordinator.openBackup(uri)

        waitUntil { coordinator.state.value.importPreview != null || coordinator.state.value.error != null }
        val preview = coordinator.state.value.importPreview
        assertNotNull("应读到备份：${coordinator.state.value.error}", preview)
        assertEquals(listOf("书一"), preview!!.bookTitles)
        assertFalse(preview.passwordRequired)

        coordinator.restore(null)

        waitUntil { coordinator.state.value.report != null || coordinator.state.value.error != null }
        val report = coordinator.state.value.report
        assertNotNull("应出结果报告：${coordinator.state.value.error}", report)
        // 恢复目标是同一仓储，SHA-256 命中 → 不重复导入（AC-14.4 的幂等性）；
        // 本地与备份的 lastReadAt 同为 100 → WriteBackDecider 判定「不比本地新」，
        // 不回写（AC-14.6：旧进度不覆盖新进度，时间相等亦不覆盖）
        assertEquals(0, report!!.imported)
        assertEquals(1, report.duplicates)
        assertEquals(0, report.progressWritten)
        assertNull("完成后预览应关闭", coordinator.state.value.importPreview)
    }

    @Test
    fun `加密备份先要口令且错口令可原地重试`() {
        val password = "correct-horse-battery".toCharArray()
        val zip = exportToFile(password)
        val uri = openUri(zip)

        coordinator.openBackup(uri)
        waitUntil { coordinator.state.value.importPreview != null }
        assertTrue(coordinator.state.value.importPreview!!.passwordRequired)

        coordinator.restore("wrong-password".toCharArray())
        waitUntil { coordinator.state.value.wrongPassword }
        assertNull("口令错误不应关闭对话框", coordinator.state.value.error)
        assertNull("口令错误不应产生报告", coordinator.state.value.report)
        assertNotNull("口令错误后仍保留预览", coordinator.state.value.importPreview)

        coordinator.restore(password)
        waitUntil { coordinator.state.value.report != null }
        // 同仓储命中 → 重复而非导入；时间相等不回写
        assertEquals(0, coordinator.state.value.report!!.imported)
        assertEquals(1, coordinator.state.value.report!!.duplicates)
        assertFalse(coordinator.state.value.wrongPassword)
    }

    @Test
    fun `非备份文件给出明确错误`() {
        val uri = Uri.parse("content://paperread.test/not-a-backup.zip")
        shadowOf(context.contentResolver).registerInputStream(
            uri,
            ByteArrayInputStream("这不是 zip".toByteArray()),
        )

        coordinator.openBackup(uri)

        waitUntil { coordinator.state.value.error != null }
        assertEquals(
            context.getString(R.string.backup_error_not_a_backup),
            coordinator.state.value.error,
        )
        assertNull(coordinator.state.value.importPreview)
        assertFalse(coordinator.state.value.busy)
    }

    // ---- 工具 ----

    private fun exportToFile(password: CharArray?): File = kotlinx.coroutines.runBlocking {
        val zip = File(tmp.root, "coordinator-${System.nanoTime()}.zip")
        BackupExporter(repository, textStore).export(zip, password)
        zip
    }

    private fun openUri(file: File): Uri =
        Uri.parse("content://paperread.test/${file.name}").also { uri ->
            shadowOf(context.contentResolver).registerInputStream(uri, FileInputStream(file))
        }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        error("等待条件超时（${timeoutMs}ms），当前状态：${coordinator.state.value}")
    }
}
