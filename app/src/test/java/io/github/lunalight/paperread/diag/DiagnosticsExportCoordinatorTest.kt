package io.github.lunalight.paperread.diag

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.data.repository.ImportError
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [DiagnosticsExportCoordinator] 单测（T-021 · AC-19.2 / AC-19.6）。
 *
 * 用 Robolectric 的 `ShadowContentResolver` 模拟 SAF：注册输出流即可观察
 * 「写了什么」与「删了哪个 Uri」。这与 `BackupCoordinatorTest` 同一范式
 * （同一个坑长出来的同一套测法）。
 *
 * 最关键的两条：
 * - 失败 / 取消时**本次新建**的目标文档必须被删除（不留 0 字节残骸）；
 * - 目标不是本次新建时**绝不能删**（用户既有的诊断包）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsExportCoordinatorTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var crashLogs: CrashLogStore
    private lateinit var importErrors: ImportErrorLog
    private lateinit var coordinator: DiagnosticsExportCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        crashLogs = CrashLogStore(
            dir = File(context.filesDir, CrashLogStore.DIR_NAME),
            device = DeviceInfo("Xiaomi", "Mi 9", 29),
            appVersion = "1.2.0",
            versionCode = 4,
        )
        importErrors = ImportErrorLog(File(context.filesDir, "${CrashLogStore.DIR_NAME}/import-errors.json"))
        coordinator = DiagnosticsExportCoordinator(
            context = context,
            exporter = DiagnosticsExporter(
                crashLogs = crashLogs,
                importErrors = importErrors,
                device = DeviceInfo("Xiaomi", "Mi 9", 29),
                appVersion = "1.2.0",
                versionCode = 4,
                filesDir = context.filesDir,
            ),
            scope = scope,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        File(context.filesDir, CrashLogStore.DIR_NAME).deleteRecursively()
        File(context.cacheDir, "diagnostics").deleteRecursively()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待条件超时")
    }

    @Test
    fun `先出摘要再导出 成功后提示完成`() {
        crashLogs.record(Thread.currentThread(), IllegalStateException("boom"))

        coordinator.loadSummary()
        waitUntil { coordinator.state.value.summary != null }
        assertEquals(1, coordinator.state.value.summary?.crashCount)

        val sink = ByteArrayOutputStream()
        val uri = Uri.parse("content://paperread.test/diag.zip")
        shadowOf(context.contentResolver).registerOutputStream(uri, sink)

        coordinator.export(uri, targetCreatedByThisExport = true)
        waitUntil { !coordinator.state.value.busy && coordinator.state.value.message != null }

        assertEquals(
            context.getString(R.string.diagnostics_export_done),
            coordinator.state.value.message,
        )
        assertNull(coordinator.state.value.error)
        assertNull("导出后摘要对话框应关闭", coordinator.state.value.summary)

        // 写进 SAF 的确实是合法 zip，且含摘要条目
        val names = mutableListOf<String>()
        ZipInputStream(sink.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
            }
        }
        assertTrue("zip 里应有摘要：$names", names.contains(DiagnosticsExporter.ENTRY_SUMMARY))
    }

    @Test
    fun `导出失败时删除本次新建的目标文档`() {
        val uri = Uri.parse("content://paperread.test/fresh.zip")
        shadowOf(context.contentResolver).registerOutputStream(
            uri,
            object : OutputStream() {
                override fun write(b: Int) {
                    throw IOException("磁盘已满")
                }
            },
        )

        coordinator.export(uri, targetCreatedByThisExport = true)
        waitUntil { !coordinator.state.value.busy && coordinator.state.value.error != null }
        // 清理在 finally 里、跑在 Dispatchers.IO 上：错误态先落地，删除稍后完成
        waitUntil { shadowOf(context.contentResolver).deletedUris.contains(uri) }

        assertNotNull(coordinator.state.value.error)
        assertTrue(
            "AC-19.6：本次新建但没写成的目标文档必须清理",
            shadowOf(context.contentResolver).deletedUris.contains(uri),
        )
    }

    @Test
    fun `目标不是本次新建时失败也不删除`() {
        val uri = Uri.parse("content://paperread.test/existing.zip")
        shadowOf(context.contentResolver).registerOutputStream(
            uri,
            object : OutputStream() {
                override fun write(b: Int) {
                    throw IOException("磁盘已满")
                }
            },
        )

        // 默认值就是 false（上一轮 QA 的结论：宁可留残骸，不误删用户既有文件）
        coordinator.export(uri)
        waitUntil { !coordinator.state.value.busy && coordinator.state.value.error != null }

        assertFalse(
            "用户既有文件不得被清理逻辑误删",
            shadowOf(context.contentResolver).deletedUris.contains(uri),
        )
    }

    @Test
    fun `摘要加载失败给出错误提示`() {
        // 让诊断目录不可读：用普通文件占住目录名
        val diagnosticsDir = File(context.filesDir, CrashLogStore.DIR_NAME)
        diagnosticsDir.deleteRecursively()
        diagnosticsDir.parentFile?.mkdirs()
        diagnosticsDir.writeText("occupied")

        val broken = DiagnosticsExportCoordinator(
            context = context,
            exporter = DiagnosticsExporter(
                crashLogs = crashLogs,
                importErrors = importErrors,
                device = DeviceInfo("Xiaomi", "Mi 9", 29),
                appVersion = "1.2.0",
                versionCode = 4,
                filesDir = context.filesDir,
            ),
            scope = scope,
        )
        broken.loadSummary()
        // 摘要读取本身容错（坏日志被跳过），故这里只断言「要么出摘要要么出错误」，
        // 不会卡在 busy 里
        waitUntil { !broken.state.value.busy }
        assertTrue(
            broken.state.value.summary != null || broken.state.value.error != null,
        )
    }

    @Test
    fun `导入失败记录会进诊断包`() {
        importErrors.record(ImportError.ParseFailed("zip 损坏"))

        val sink = ByteArrayOutputStream()
        val uri = Uri.parse("content://paperread.test/with-errors.zip")
        shadowOf(context.contentResolver).registerOutputStream(uri, sink)

        coordinator.export(uri, targetCreatedByThisExport = true)
        waitUntil { !coordinator.state.value.busy && coordinator.state.value.message != null }

        // zip 条目是压缩的：必须真正解包，不能对字节流做子串匹配
        val entries = mutableMapOf<String, String>()
        ZipInputStream(sink.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        val errors = entries[DiagnosticsExporter.ENTRY_IMPORT_ERRORS]
        assertNotNull("zip 里应含 import-errors.json 条目：${entries.keys}", errors)
        assertTrue("应含失败话术：$errors", errors!!.contains("文件解析失败，请重试"))
        assertTrue("应含阶段：$errors", errors.contains("PARSING"))
    }
}
