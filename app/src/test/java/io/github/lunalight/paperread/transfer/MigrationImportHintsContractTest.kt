package io.github.lunalight.paperread.transfer

import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.data.repository.PrepareResult
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移导入的**隐式契约**：`hints = null`（T-015 / R-13 回归保护网）。
 *
 * `BookImportGateway.importFromFile(file, hints)` 的 `hints` 只覆盖「书名 / 作者」。
 * 备份恢复需要它（AC-14.1 保留原书名），迁移**必须不传**——否则迁移的书名会从
 * 「显示名推导」变成「快照书名」，与 AC-10.2 既有行为不一致，属于静默行为漂移。
 *
 * 这条契约原本只写在 `TransferMigrationController` 的注释里，没有任何用例保护：
 * 把 `importFromFile(sourceFile)` 改成传 `ImportHints(snapshot.title, …)`，
 * 迁移相关 28 个用例（MigrationProcessorTest 7 / MigrationFlowTest 9 /
 * TransferServerFlowTest 12）**全绿**。本类补上这条约束：
 * `confirm` 收到的书名必须是导入流水线解析出的书名，而不是迁移快照里的书名。
 */
class MigrationImportHintsContractTest : TransferFlowTestBase() {

    override val discoveryPort: Int = 45479

    @Test
    fun `迁移导入不得用快照书名覆盖解析得到的书名`() {
        val sha = "migrate-hints-sha"
        val parsedTitle = "解析得到的书名"
        val snapshotTitle = "快照里的书名"

        importer.prepareResult = PrepareResult.Success(
            PendingImport(
                dirName = "dir-$sha",
                displayName = "$sha.txt",
                title = parsedTitle,
                author = "解析作者",
                format = BookFormat.TXT,
                charsetName = "UTF-8",
                strategy = SplitStrategy.CONSERVATIVE,
                sha256 = sha,
                fileSize = 16L,
                totalChars = 16L,
                chapters = emptyList(),
            ),
        )
        repository.book = book(id = 1, title = parsedTitle, sha256 = sha)

        val manager = createManager()
        manager.startAndAwaitRunning()
        val secret = manager.pairAsSender(DeviceIdentityStore(context))

        val zip = bundleZip(
            snapshots = listOf(snapshot(sha256 = sha, title = snapshotTitle, lastReadAt = 100L)),
            sources = mapOf(sha to tempFile("source").apply { writeText("迁移正文") }),
        )

        val result = clientFor(secret).uploadBundle(
            loopbackHost,
            manager.httpPort(),
            zip,
            "paperread-migrate.zip",
        )

        waitUntil { manager.migration.value.report != null }
        assertTrue("上传应成功：$result", result is UploadResult.Success)
        val report = manager.migration.value.report!!
        assertEquals(1, report.imported)
        assertTrue(report.failed.isEmpty())

        val confirmed = importer.confirmed.single()
        assertEquals(
            "迁移必须沿用导入流水线解析的书名（不得传 hints）",
            parsedTitle,
            confirmed.title,
        )
        assertEquals("解析作者", confirmed.author)
    }

    // ---- 工具 ----

    private fun bundleZip(
        snapshots: List<BookStateSnapshot>,
        sources: Map<String, File>,
    ): File = File.createTempFile("migrate-hints", ".zip").apply {
        deleteOnExit()
        MigrationBundleCodec.pack(MigrationManifest(books = snapshots), sources, this)
    }
}
