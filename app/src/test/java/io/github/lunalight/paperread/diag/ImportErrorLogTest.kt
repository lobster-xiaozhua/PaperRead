package io.github.lunalight.paperread.diag

import io.github.lunalight.paperread.data.repository.ImportError
import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [ImportErrorLog] 单测（T-021 · AC-19.4「环形 5 条」/ AC-19.5「只记结构化字段」）。
 *
 * 纯 JVM：不碰 Android，也不碰导入流水线（流水线侧另有
 * `ImportErrorRecordingTest` 验证「失败真的会被记进来」）。
 */
class ImportErrorLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_700_000_000_000L

    private fun log(maxEntries: Int = ImportErrorLog.DEFAULT_MAX_ENTRIES): ImportErrorLog =
        ImportErrorLog(
            file = File(tmp.root, "diagnostics/${ImportErrorLog.FILE_NAME}"),
            maxEntries = maxEntries,
            clock = { now },
        )

    private val sample = listOf(
        ImportError.UnsupportedFormat("pdf"),
        ImportError.InsufficientSpace,
        ImportError.ReadFailed("权限失效"),
        ImportError.ParseFailed("zip 损坏"),
        ImportError.DecodeFailed("UTF-16LE"),
        ImportError.DrmEncrypted("encrypted"),
        ImportError.SourceMissing("dir-x"),
    )

    @Test
    fun `记录保留三要素`() {
        val log = log()
        log.record(ImportError.ParseFailed("zip 损坏"))

        val entry = log.entries().single()
        assertEquals("PARSING", entry.stage)
        assertEquals("zip 损坏", entry.cause)
        assertEquals("文件解析失败，请重试", entry.userMessage)
        assertEquals(now, entry.timestamp)
    }

    @Test
    fun `环形只保留最近五条 最新在前`() {
        val log = log()
        sample.forEach { error ->
            now += 1
            log.record(error)
        }

        val entries = log.entries()
        assertEquals(ImportErrorLog.DEFAULT_MAX_ENTRIES, entries.size)
        // 最新的一条是最后写入的 SourceMissing，最旧的两条应被淘汰
        assertEquals("PARSING", entries.first().stage)
        assertEquals("dir=dir-x", entries.first().cause)
        assertTrue(
            "最早的 UnsupportedFormat 应被淘汰",
            entries.none { it.stage == "PRECHECK" && it.userMessage.contains("TXT") },
        )
        // 时间戳单调（最新在前）
        assertEquals(entries.map { it.timestamp }.sortedDescending(), entries.map { it.timestamp })
    }

    @Test
    fun `超过上限后旧记录不再回来`() {
        val log = log(maxEntries = 2)
        log.record(ImportError.InsufficientSpace)
        log.record(ImportError.UnsupportedFormat("pdf"))
        log.record(ImportError.ReadFailed("x"))

        assertEquals(2, log.entries().size)
        assertEquals("COPYING", log.entries().first().stage)
    }

    @Test
    fun `落盘后重新读取仍一致`() {
        val file = File(tmp.root, "diagnostics/${ImportErrorLog.FILE_NAME}")
        val first = ImportErrorLog(file, clock = { now })
        first.record(ImportError.DrmEncrypted("drm"))

        val second = ImportErrorLog(file, clock = { now })
        assertEquals(first.entries(), second.entries())
    }

    @Test
    fun `损坏的日志文件不会让记录路径崩溃`() {
        val file = File(tmp.root, "diagnostics/${ImportErrorLog.FILE_NAME}")
        file.parentFile!!.mkdirs()
        file.writeText("{ this is not json")

        val log = ImportErrorLog(file, clock = { now })
        assertTrue("损坏文件应读成空列表", log.entries().isEmpty())

        log.record(ImportError.ParseFailed("after-corrupt"))
        assertEquals(1, log.entries().size)
    }

    @Test
    fun `写不进去也不抛`() {
        // 把「目录」指向普通文件：parentFile 存在但不是目录 → 写盘失败
        val occupied = File(tmp.root, "occupied")
        occupied.writeText("file")
        val log = ImportErrorLog(File(occupied, "import-errors.json"), clock = { now })

        log.record(ImportError.ParseFailed("io"))
        assertTrue(log.entries().isEmpty())
    }

    @Test
    fun `导出 JSON 可被解析且字段齐全`() {
        val log = log()
        log.record(ImportError.UnsupportedFormat("pdf"))

        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString(ListSerializer(ImportErrorEntry.serializer()), log.exportJson())

        assertEquals(1, decoded.size)
        assertEquals("PRECHECK", decoded[0].stage)
        assertEquals("extension=pdf", decoded[0].cause)
        assertEquals("仅支持 TXT / EPUB 格式", decoded[0].userMessage)
    }

    @Test
    fun `没有记录时导出空数组`() {
        assertEquals("[]", log().exportJson())
    }

    @Test
    fun `clear 清空记录`() {
        val log = log()
        log.record(ImportError.InsufficientSpace)
        log.clear()
        assertTrue(log.entries().isEmpty())
        assertNull(log.entries().firstOrNull())
    }
}
