package io.github.lunalight.paperread.diag

import io.github.lunalight.paperread.data.repository.ImportError
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
 * [DiagnosticsExporter] 单测（T-021 · AC-19.2 / AC-19.3 / AC-19.5）。
 *
 * 三件事必须钉死：
 * 1. **条目白名单**：包内只有 `summary.txt` / crashes 目录下的 .log / `import-errors.json`；
 * 2. **无正文**：书架里的 `book.txt`、书签备注、书名一个字节都进不了包；
 * 3. **脱敏**：即使崩溃日志里带出私有目录路径 / 书籍目录名 / UUID，
 *    打包时也被替换成 `<files>` / `<book>` / `<id>`。
 */
class DiagnosticsExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var crashLogs: CrashLogStore
    private lateinit var importErrors: ImportErrorLog
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
        crashLogs = CrashLogStore(
            dir = File(filesDir, CrashLogStore.DIR_NAME),
            device = DeviceInfo("Xiaomi", "Mi 9", 29),
            appVersion = "1.2.0",
            versionCode = 4,
            clock = { now },
        )
        importErrors = ImportErrorLog(File(filesDir, "${CrashLogStore.DIR_NAME}/import-errors.json"))
    }

    private fun exporter() = DiagnosticsExporter(
        crashLogs = crashLogs,
        importErrors = importErrors,
        device = DeviceInfo("Xiaomi", "Mi 9", 29),
        appVersion = "1.2.0",
        versionCode = 4,
        filesDir = filesDir,
        clock = { now },
    )

    /** 把 zip 读成「条目名 → 文本」——断言白名单与内容都靠它。 */
    private fun unzip(zip: File): Map<String, String> = ZipFile(zip).use { archive ->
        archive.entries().asSequence().associate { entry ->
            entry.name to archive.getInputStream(entry).bufferedReader().readText()
        }
    }

    // ---- 白名单 ----

    @Test
    fun `没有崩溃与导入失败时只有摘要`() = runTest {
        val target = File(tmp.root, "diag.zip")
        exporter().export(target)

        val entries = unzip(target)
        assertEquals(setOf(DiagnosticsExporter.ENTRY_SUMMARY), entries.keys)
        // 白名单是上界：任何条目都必须在声明清单里
        assertTrue(DiagnosticsExporter.STATIC_ENTRIES.containsAll(entries.keys))
    }

    @Test
    fun `条目不超出白名单`() = runTest {
        now = 1_700_000_000_000L
        crashLogs.record(Thread.currentThread(), IllegalStateException("boom"))
        now = 1_700_000_001_000L
        crashLogs.record(Thread.currentThread(), IllegalStateException("boom-2"))
        importErrors.record(ImportError.ParseFailed("zip 损坏"))

        val target = File(tmp.root, "diag.zip")
        exporter().export(target)

        val names = unzip(target).keys
        assertEquals(4, names.size)
        assertTrue(names.contains(DiagnosticsExporter.ENTRY_SUMMARY))
        assertTrue(names.contains(DiagnosticsExporter.ENTRY_IMPORT_ERRORS))
        names.filter { it != DiagnosticsExporter.ENTRY_SUMMARY }
            .filter { it != DiagnosticsExporter.ENTRY_IMPORT_ERRORS }
            .forEach { name ->
                assertTrue("崩溃条目必须在 crashes/ 下：$name", name.startsWith(DiagnosticsExporter.CRASH_DIR))
                assertTrue("崩溃条目必须是 .log：$name", name.endsWith(".log"))
            }
    }

    // ---- 摘要内容（AC-19.3） ----

    @Test
    fun `摘要包含版本 设备 API 崩溃条数与导入失败`() = runTest {
        crashLogs.record(Thread.currentThread(), IllegalStateException("boom"))
        importErrors.record(ImportError.UnsupportedFormat("pdf"))

        val target = File(tmp.root, "diag.zip")
        exporter().export(target)

        val summary = unzip(target).getValue(DiagnosticsExporter.ENTRY_SUMMARY)
        assertTrue(summary, summary.contains("应用版本: 1.2.0 (4)"))
        assertTrue(summary, summary.contains("设备: Xiaomi Mi 9 (API 29)"))
        assertTrue(summary, summary.contains("崩溃记录: 1 条"))
        assertTrue(summary, summary.contains("导入失败: 1 条"))
        assertTrue(summary, summary.contains("仅支持 TXT / EPUB 格式"))
        assertTrue("必须声明数据边界", summary.contains("不含书籍正文"))
    }

    @Test
    fun `摘要先于导出可单独获取 供确认对话框使用`() {
        crashLogs.record(Thread.currentThread(), IllegalStateException("boom"))
        val summary = exporter().summary()

        assertEquals("1.2.0", summary.appVersion)
        assertEquals(4, summary.versionCode)
        assertEquals("Xiaomi Mi 9 (API 29)", summary.device)
        assertEquals(1, summary.crashCount)
        assertEquals(0, summary.importErrorCount)
        // summary() 是纯读：不在任何位置生成 zip
        assertFalse("summary() 不得落盘", File(tmp.root, "diag.zip").exists())
    }

    // ---- 无正文（AC-19.5） ----

    @Test
    fun `导出包不含书籍正文 书名与书签备注`() = runTest {
        // 造一个「真实」书架目录：正文 + 书名 + 书签备注
        val booksDir = File(filesDir, "books/机密书名").apply { mkdirs() }
        File(booksDir, "book.txt").writeText("这是绝不能出现在诊断包里的正文内容")
        File(booksDir, "source.txt").writeText("机密书名")
        val bookmarkNote = "这是书签备注：主角在第 3 章说了什么"

        val target = File(tmp.root, "diag.zip")
        exporter().export(target)

        val all = unzip(target).values.joinToString("\n")
        assertFalse("不得包含正文", all.contains("绝不能出现在诊断包里的正文内容"))
        assertFalse("不得包含书名", all.contains("机密书名"))
        assertFalse("不得包含书签备注", all.contains(bookmarkNote))
        assertFalse("不得包含 book.txt 条目", unzip(target).keys.any { it.contains("book.txt") })
        assertFalse("不得包含 source.txt 条目", unzip(target).keys.any { it.contains("source.txt") })
    }

    // ---- 脱敏（AC-19.5「路径中的书名」） ----

    @Test
    fun `崩溃日志里的私有路径与书籍目录名被脱敏`() = runTest {
        crashLogs.record(
            Thread.currentThread(),
            IllegalStateException(
                "无法打开 ${filesDir.absolutePath}/books/机密书名/book.txt " +
                    "(dir=3f2504e0-4f89-11d3-9a0c-0305e82c3301)",
            ),
        )

        val target = File(tmp.root, "diag.zip")
        exporter().export(target)

        val crashText = unzip(target).entries.first { it.key.startsWith(DiagnosticsExporter.CRASH_DIR) }.value
        assertFalse("私有目录前缀必须被替换：$crashText", crashText.contains(filesDir.absolutePath))
        assertFalse("书籍目录名必须被抹掉：$crashText", crashText.contains("机密书名"))
        assertFalse(
            "UUID 属于可关联标识，必须被替换：$crashText",
            crashText.contains("3f2504e0-4f89-11d3-9a0c-0305e82c3301"),
        )
        assertTrue("保留结构便于排障：$crashText", crashText.contains("<files>/books/<book>/book.txt"))
        assertTrue(crashText, crashText.contains("<id>"))
    }

    @Test
    fun `脱敏规则独立可测`() {
        val input = "/data/user/0/app/files/books/某本书/book.txt 3f2504e0-4f89-11d3-9a0c-0305e82c3301"
        val output = DiagnosticsRedactor.redact(input, "/data/user/0/app/files")
        assertEquals("<files>/books/<book>/book.txt <id>", output)
        // 没有 filesDir 时也要抹掉 books 段与 UUID
        assertEquals(
            "/x/books/<book> <id>",
            DiagnosticsRedactor.redact("/x/books/某本书 3f2504e0-4f89-11d3-9a0c-0305e82c3301", null),
        )
    }

    // ---- 失败语义 ----

    @Test
    fun `导出失败时不留下半成品文件`() = runTest {
        // 父路径是普通文件 → 目录建不出来、文件流也打不开
        val occupied = File(tmp.root, "occupied").apply { writeText("file") }
        val target = File(occupied, "diag.zip")

        val thrown = runCatching { exporter().export(target) }.exceptionOrNull()

        assertTrue("应抛出异常给调用方：$thrown", thrown != null)
        assertFalse("失败后不得留下半成品", target.exists())
    }
}
