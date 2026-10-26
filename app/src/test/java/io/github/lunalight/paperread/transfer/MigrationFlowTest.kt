package io.github.lunalight.paperread.transfer

import io.github.lunalight.paperread.data.repository.ImportError
import io.github.lunalight.paperread.data.repository.PrepareResult
import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.fakes.FakeBookRepository
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import java.io.File
import java.net.ServerSocket
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换机迁移编排流程单测（T-004 / R-04 / G4，AC-10.1 ~ AC-10.4）。
 *
 * 经 [DefaultLanTransferManager] 真实路径（`POST /bundle` → 落盘 → 解包 →
 * 逐本导入 → AC-10.4 回写）覆盖接收侧分支（重复导入 / DRM 失败清单 /
 * 取消迁移 / 迁移包损坏 / busy 拒绝）与发送侧分支（书架为空 / 目标不可达），
 * 并以 A→B 双管理器跑完整端到端迁移。
 *
 * 打包 / 解包 / 合并决策的纯函数直测属 MigrationBundleTest / MigrationProcessorTest
 * / SyncMergerTest 领地，此处只固化编排层（G4「编排层零直测」缺口）。
 */
class MigrationFlowTest : TransferFlowTestBase() {

    override val discoveryPort = 45471

    private fun pendingImport(sha256: String, title: String) = PendingImport(
        dirName = "dir-$sha256",
        displayName = "$title.txt",
        title = title,
        author = "未知",
        format = io.github.lunalight.paperread.domain.model.BookFormat.TXT,
        charsetName = "UTF-8",
        strategy = SplitStrategy.CONSERVATIVE,
        sha256 = sha256,
        fileSize = 16L,
        totalChars = 16L,
        chapters = emptyList(),
    )

    /** 构造真实迁移包（manifest + sources，与生产 pack 同一编解码）。 */
    private fun bundleZip(
        snapshots: List<BookStateSnapshot>,
        sources: Map<String, File> = emptyMap(),
    ): File = File.createTempFile("migrate", ".zip").apply {
        deleteOnExit()
        MigrationBundleCodec.pack(MigrationManifest(books = snapshots), sources, this)
    }

    /** 在 fake 文本库中落一本真实源文件（发送侧打包用）。 */
    private fun prepareSource(book: Book, content: String = "正文内容"): File {
        val source = textStore.sourceFile(book)
        source.parentFile?.mkdirs()
        source.writeText(content)
        return source
    }

    // ---- 接收侧（AC-10.2 / AC-10.3 / AC-10.4） ----

    @Test
    fun `重复导入命中已存在书籍时跳过导入并按 AC-10 4 回写较新进度`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)
        val existing = book(id = 7, title = "旧书架", sha256 = "dup-sha", lastReadAt = 100L)
        repository.emitBooks(listOf(existing))
        val mark = BookmarkSnapshot(
            chapterIndex = 2,
            paragraphIndex = 3,
            charOffset = 40,
            excerpt = "标记一",
            createdAt = 555L,
        )
        val zip = bundleZip(
            listOf(
                snapshot(
                    sha256 = "dup-sha",
                    title = "旧书架",
                    lastReadAt = 500L,
                    progressPercent = 0.5f,
                    bookmarks = listOf(mark),
                ),
            ),
        )

        val result = clientFor(secret).uploadBundle(
            loopbackHost,
            manager.httpPort(),
            zip,
            "paperread-migrate.zip",
        )

        waitUntil { manager.migration.value.report != null }
        assertTrue("$result", result is io.github.lunalight.paperread.transfer.UploadResult.Success)
        val report = manager.migration.value.report!!
        assertEquals(0, report.imported)
        assertEquals(1, report.duplicates)
        assertEquals(1, report.progressWritten)
        assertTrue(report.failed.isEmpty())
        // 回写：进度替换 + 书签替换式写入（AC-10.4 与进度同一决策单元）
        val saved = repository.savedPositions.single()
        assertEquals(7L, saved.bookId)
        assertEquals(0.5f, saved.progressPercent, 0.001f)
        assertEquals(listOf(7L), repository.clearedBookmarkBookIds)
        val added = repository.addedBookmarks.single()
        assertEquals(7L, added.bookId)
        assertEquals("标记一", added.excerpt)
        // 导入流水线未被调用（命中即跳过，不重复导入）
        assertTrue(importer.prepareCalls.isEmpty())
    }

    @Test
    fun `重复导入但本地较新时不回写进度`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)
        repository.emitBooks(
            listOf(book(id = 9, title = "新书架", sha256 = "local-new", lastReadAt = 900L)),
        )
        val zip = bundleZip(
            listOf(snapshot(sha256 = "local-new", title = "新书架", lastReadAt = 100L)),
        )

        clientFor(secret).uploadBundle(loopbackHost, manager.httpPort(), zip, "m.zip")

        waitUntil { manager.migration.value.report != null }
        val report = manager.migration.value.report!!
        assertEquals(1, report.duplicates)
        assertEquals(0, report.progressWritten)
        assertTrue("本地较新不得被旧快照覆盖", repository.savedPositions.isEmpty())
        assertTrue(repository.clearedBookmarkBookIds.isEmpty())
    }

    @Test
    fun `DRM 书籍记入失败清单且不中断整体迁移`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)
        val imported = book(id = 1, title = "好书", sha256 = "ok-sha", lastReadAt = null)
        repository.book = imported
        importer.prepareResultQueue = ArrayDeque(
            listOf(
                PrepareResult.Success(pendingImport("ok-sha", "好书")),
                PrepareResult.Failure(ImportError.DrmEncrypted("encrypted")),
            ),
        )
        val sources = mapOf(
            "ok-sha" to File.createTempFile("ok_src", ".txt").apply { writeText("ok"); deleteOnExit() },
            "drm-sha" to File.createTempFile("drm_src", ".epub").apply { writeText("drm"); deleteOnExit() },
        )
        val zip = bundleZip(
            listOf(
                snapshot(sha256 = "ok-sha", title = "好书", lastReadAt = 100L),
                snapshot(sha256 = "drm-sha", title = "坏书", lastReadAt = 100L),
            ),
            sources,
        )

        clientFor(secret).uploadBundle(loopbackHost, manager.httpPort(), zip, "m.zip")

        waitUntil { manager.migration.value.report != null }
        val report = manager.migration.value.report!!
        assertEquals(1, report.imported)
        assertEquals(1, report.progressWritten)
        val failure = report.failed.single()
        assertEquals("坏书", failure.title)
        assertEquals("该 EPUB 含 DRM 加密，无法导入", failure.reason)
        assertFalse(manager.migration.value.active)
        assertNull(manager.migration.value.error)
    }

    @Test
    fun `取消迁移时提示已取消并清理解包目录`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)
        // 挂起导入流水线：迁移停在「导入书籍」相位，等待取消
        importer.prepareGate = CompletableDeferred()
        val zip = bundleZip(
            listOf(snapshot(sha256 = "cancel-sha", title = "待导入", lastReadAt = 100L)),
            mapOf(
                "cancel-sha" to File.createTempFile("cancel", ".txt")
                    .apply { writeText("cancel"); deleteOnExit() },
            ),
        )

        clientFor(secret).uploadBundle(loopbackHost, manager.httpPort(), zip, "m.zip")

        waitUntil {
            manager.migration.value.active &&
                manager.migration.value.phaseLabel == "导入书籍"
        }
        manager.cancelMigration()

        waitUntil { manager.migration.value.error != null }
        assertEquals("已取消迁移", manager.migration.value.error)
        // 半成品清理：解包目录与收到的 zip 均不残留
        assertFalse(File(context.cacheDir, "transfer/unpack").exists())
        assertFalse(File(context.cacheDir, "transfer/incoming.bundle.zip").exists())
    }

    @Test
    fun `迁移包损坏时提示且不产生报告`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)
        val garbage = File.createTempFile("broken", ".zip").apply {
            writeText("这不是一个 zip")
            deleteOnExit()
        }

        clientFor(secret).uploadBundle(loopbackHost, manager.httpPort(), garbage, "m.zip")

        waitUntil { manager.migration.value.error != null }
        assertEquals("迁移包损坏或版本不兼容", manager.migration.value.error)
        assertNull(manager.migration.value.report)
        assertFalse(manager.migration.value.active)
        assertFalse("损坏包应清理", File(context.cacheDir, "transfer/incoming.bundle.zip").exists())
    }

    @Test
    fun `发送迁移期间拒绝收到的迁移包（busy）`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val identity = DeviceIdentityStore(context)
        val secret = manager.pairAsSender(identity)
        repository.emitBooks(listOf(book(id = 1, title = "书一", sha256 = "sha-1")))
        // 把发送侧协程稳定挂在「收集书库」相位（目标不可达也无妨：
        // 协程永远到不了上传那一步），使 SEND 状态窗口不再依赖竞态
        repository.booksGate = CompletableDeferred()

        manager.startMigration(peerDevice(manager, "peer-x", "对端X"))

        waitUntil {
            manager.migration.value.active &&
                manager.migration.value.direction == MigrationDirection.SEND
        }

        val zip = bundleZip(listOf(snapshot(sha256 = "sha-new", title = "新书", lastReadAt = 1L)))
        val result = clientFor(secret).uploadBundle(
            loopbackHost,
            manager.httpPort(),
            zip,
            "m.zip",
        )

        assertTrue("发送中应拒绝接收：$result", result is UploadResult.Failure)
        assertEquals("对端返回 500", (result as UploadResult.Failure).message)
        // 回归：spill 进度回调不得把 SEND 状态踩成 RECEIVE（否则 busy 拒绝
        // 永远不生效，且发送中 UI 被接收进度覆盖）
        assertTrue(manager.migration.value.active)
        assertEquals(MigrationDirection.SEND, manager.migration.value.direction)
        // 收到的字节不落盘（busy 分支直接删除 spill）
        assertFalse(File(context.cacheDir, "transfer/incoming.bundle.zip").exists())
        assertNull(manager.migration.value.report)

        // 收尾：取消挂起的门释放协程（走取消分支），再取消迁移任务
        repository.booksGate?.cancel()
        manager.cancelMigration()
        waitUntil { manager.migration.value.error != null }
        assertEquals("已取消迁移", manager.migration.value.error)
    }

    // ---- 发送侧（AC-10.1） ----

    @Test
    fun `书架为空时迁移直接失败`() {
        val manager = createManager()
        manager.startAndAwaitRunning()

        manager.startMigration(
            DiscoveredDevice("peer-x", "对端X", loopbackHost, manager.httpPort(), 0L),
        )

        waitUntil { manager.migration.value.error != null }
        assertEquals("书架为空，没有可迁移的书籍", manager.migration.value.error)
        assertFalse(manager.migration.value.active)
    }

    @Test
    fun `目标不可达时迁移发送失败`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        repository.emitBooks(listOf(book(id = 1, title = "书一", sha256 = "sha-1")))
        // 先占后放：拿一个必然无人监听的端口
        val deadPort = ServerSocket(0).use { it.localPort }

        manager.startMigration(
            DiscoveredDevice("peer-x", "对端X", loopbackHost, deadPort, 0L),
        )

        waitUntil { manager.migration.value.error != null }
        assertTrue(
            "应提示发送失败：${manager.migration.value.error}",
            manager.migration.value.error!!.startsWith("迁移包发送失败"),
        )
        assertFalse(manager.migration.value.active)
    }

    @Test
    fun `A 到 B 双管理器端到端迁移完成并回写`() {
        val identityA = freshIdentity("mig-a")
        val identityB = freshIdentity("mig-b")
        val repoA = FakeBookRepository()
        val repoB = FakeBookRepository()
        val managerA = createManager(identityStore = identityA, repository = repoA, discoveryPort = 45481)
        val managerB = createManager(identityStore = identityB, repository = repoB, discoveryPort = 45482)
        managerA.startAndAwaitRunning()
        managerB.startAndAwaitRunning()

        // B 展示配对码，A 输入完成配对（真实 pairWith 路径）
        managerB.beginPairing()
        val code = managerB.pairing.value.displayCode!!
        managerA.pairWith(peerDevice(managerB, identityB.deviceId(), "设备B"), code)
        waitUntil { managerA.pairing.value.status == PairingGate.Status.PAIRED }
        waitUntil { managerB.pairing.value.status == PairingGate.Status.PAIRED }

        // A 的书库：两本真实源文件；均带最后阅读时间（触发 AC-10.4 回写）
        val bookA1 = book(id = 1, title = "书一", sha256 = "e2e-1", lastReadAt = 100L)
        val bookA2 = book(
            id = 2,
            title = "书二",
            sha256 = "e2e-2",
            lastReadAt = 200L,
            dirName = "dir-2",
        ).copy(lastChapterIndex = 4, lastParagraphIndex = 5, progressPercent = 0.42f)
        repoA.emitBooks(listOf(bookA1, bookA2))
        prepareSource(bookA1, "书一正文")
        prepareSource(bookA2, "书二正文")
        repoA.emitBookmarks(
            listOf(
                io.github.lunalight.paperread.domain.model.Bookmark(
                    bookId = 2L,
                    chapterIndex = 1,
                    paragraphIndex = 2,
                    charOffset = 30,
                    excerpt = "书二的书签",
                    createdAt = 777L,
                ),
            ),
        )

        // B 侧导入结局：两本都成功（入库后按快照回写）
        repoB.book = book(id = 1, title = "导入的书", sha256 = "e2e-1", lastReadAt = null)
        importer.prepareResult = PrepareResult.Success(pendingImport("e2e-1", "导入的书"))

        managerA.startMigration(peerDevice(managerB, identityB.deviceId(), "设备B"))

        waitUntil { managerB.migration.value.report != null }
        waitUntil { !managerA.migration.value.active }

        // 发送端：完成且无错误
        assertNull(managerA.migration.value.error)
        assertEquals("已发送", managerA.migration.value.phaseLabel)
        // 接收端：两本新导入 + 两本回写（新入库书籍 lastReadAt 为空 → 全部回写）
        val report = managerB.migration.value.report!!
        assertEquals(2, report.imported)
        assertEquals(0, report.duplicates)
        assertEquals(2, report.progressWritten)
        assertTrue(report.failed.isEmpty())
        assertEquals(2, repoB.savedPositions.size)
        assertEquals(1, repoB.addedBookmarks.size)
        assertEquals("书二的书签", repoB.addedBookmarks.single().excerpt)
        // 导入流水线被真实驱动（FileProvider → prepare → confirm → 回写）
        assertEquals(2, importer.prepareCalls.size)
        assertEquals(2, importer.confirmed.size)
    }
}
