package io.github.lunalight.paperread.transfer

import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.fakes.FakeBookRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度同步编排流程单测（T-004 / R-04 / G4，AC-11.1 ~ AC-11.3）。
 *
 * 经 [DefaultLanTransferManager] 双管理器真实路径（各自 HTTP 服务 + 配对 +
 * `GET /sync/state` / `POST /sync/apply`）覆盖同步编排：
 * 进度互写收敛、书签只增不删、对端回推的旧进度不得覆盖本地较新记录
 * （onSyncApply 的 [WriteBackDecider] 复核防线回归）、对端不可达失败。
 *
 * 合并决策与协调器失败分支的纯函数直测属 SyncMergerTest 领地，此处不重复。
 */
class SyncFlowTest : TransferFlowTestBase() {

    override val discoveryPort = 45473

    /** 一对已互配的双管理器（各持独立身份与仓储）。 */
    private class SyncPair(
        val managerA: DefaultLanTransferManager,
        val managerB: DefaultLanTransferManager,
        val identityA: DeviceIdentityStore,
        val identityB: DeviceIdentityStore,
        val repoA: FakeBookRepository,
        val repoB: FakeBookRepository,
    )

    /** 双管理器互配（展示端 [display] 生成配对码，输入侧 [input] 探测落定）。 */
    private fun pairManagers(
        display: DefaultLanTransferManager,
        input: DefaultLanTransferManager,
        displayIdentity: DeviceIdentityStore,
        inputIdentity: DeviceIdentityStore,
    ) {
        display.beginPairing()
        val code = display.pairing.value.displayCode!!
        input.pairWith(peerDevice(display, displayIdentity.deviceId(), "对端设备"), code)
        waitUntil { input.pairing.value.status == PairingGate.Status.PAIRED }
        waitUntil { display.pairing.value.status == PairingGate.Status.PAIRED }
    }

    /** 启动一对互配的管理器（A 作展示端，B 输入配对码完成双向配对）。 */
    private fun startSyncPair(discoveryPortB: Int): SyncPair {
        val identityA = freshIdentity("sync-a")
        val identityB = freshIdentity("sync-b")
        val repoA = FakeBookRepository()
        val repoB = FakeBookRepository()
        val managerA = createManager(identityStore = identityA, repository = repoA, discoveryPort = 45473)
        val managerB = createManager(
            identityStore = identityB,
            repository = repoB,
            discoveryPort = discoveryPortB,
        )
        managerA.startAndAwaitRunning()
        managerB.startAndAwaitRunning()
        pairManagers(managerA, managerB, identityA, identityB)
        return SyncPair(managerA, managerB, identityA, identityB, repoA, repoB)
    }

    @Test
    fun `双端同步后进度互写收敛（对端较新覆盖本地、本地较新回推对端）`() {
        val pair = startSyncPair(45474)
        val xA = book(id = 1, title = "书X", sha256 = "sync-x", lastReadAt = 100L)
            .copy(progressPercent = 0.1f)
        val yA = book(id = 2, title = "书Y", sha256 = "sync-y", lastReadAt = 500L)
            .copy(progressPercent = 0.5f)
        pair.repoA.emitBooks(listOf(xA, yA))
        val xB = book(id = 1, title = "书X", sha256 = "sync-x", lastReadAt = 900L)
            .copy(progressPercent = 0.9f)
        val yB = book(id = 2, title = "书Y", sha256 = "sync-y", lastReadAt = 200L)
            .copy(progressPercent = 0.2f)
        pair.repoB.emitBooks(listOf(xB, yB))

        pair.managerA.startSync(
            peerDevice(pair.managerB, pair.identityB.deviceId(), "设备B"),
        )

        waitUntil { pair.managerA.sync.value.report != null }

        // A 应用对端较新的 X（0.9）；A 较新的 Y（0.5）回推给 B 并由 B 落地
        assertNull(pair.managerA.sync.value.error)
        assertEquals(1, pair.managerA.sync.value.report!!.localOverwritten)
        assertFalse(pair.managerA.sync.value.active)
        val aSaved = pair.repoA.savedPositions.single()
        assertEquals(1L, aSaved.bookId)
        assertEquals(0.9f, aSaved.progressPercent, 0.001f)
        val bSaved = pair.repoB.savedPositions.single()
        assertEquals(2L, bSaved.bookId)
        assertEquals(0.5f, bSaved.progressPercent, 0.001f)
    }

    @Test
    fun `书签只增不删且差集互推`() {
        val pair = startSyncPair(45475)
        val xA = book(id = 1, title = "书X", sha256 = "bm-x", lastReadAt = 200L)
        val xB = book(id = 1, title = "书X", sha256 = "bm-x", lastReadAt = 100L)
        pair.repoA.emitBooks(listOf(xA))
        pair.repoB.emitBooks(listOf(xB))
        val bmSame = Bookmark(
            bookId = 1L,
            chapterIndex = 1,
            paragraphIndex = 1,
            charOffset = 0,
            excerpt = "共有书签",
            createdAt = 50L,
        )
        val bmA = Bookmark(
            bookId = 1L,
            chapterIndex = 3,
            paragraphIndex = 3,
            charOffset = 0,
            excerpt = "A 的签",
            createdAt = 100L,
        )
        val bmB = Bookmark(
            bookId = 1L,
            chapterIndex = 2,
            paragraphIndex = 2,
            charOffset = 0,
            excerpt = "B 的签",
            createdAt = 200L,
        )
        pair.repoA.emitBookmarks(listOf(bmSame, bmA))
        pair.repoB.emitBookmarks(listOf(bmSame, bmB))

        pair.managerA.startSync(
            peerDevice(pair.managerB, pair.identityB.deviceId(), "设备B"),
        )

        waitUntil { pair.managerA.sync.value.report != null }

        // A 得到 B 独有的书签；A 独有的书签回推给 B
        assertEquals("B 的签", pair.repoA.addedBookmarks.single().excerpt)
        assertEquals("A 的签", pair.repoB.addedBookmarks.single().excerpt)
        // 只增不删：任何一侧都不得删除 / 清空书签（AC-11.3）
        assertTrue(pair.repoA.deletedBookmarkIds.isEmpty())
        assertTrue(pair.repoB.deletedBookmarkIds.isEmpty())
        assertTrue(pair.repoA.clearedBookmarkBookIds.isEmpty())
        assertTrue(pair.repoB.clearedBookmarkBookIds.isEmpty())
        // 进度按 AC-11.2：A 较新，A 不应用对端；B 收到 A 的回推后落地
        assertTrue(pair.repoA.savedPositions.isEmpty())
        val bSaved = pair.repoB.savedPositions.single()
        assertEquals(1L, bSaved.bookId)
        assertEquals(0f, bSaved.progressPercent, 0.001f)
    }

    @Test
    fun `对端回推的旧进度不覆盖本地较新进度（AC-11 2 回归防线）`() {
        val pair = startSyncPair(45476)
        // A 较新（200）；B 较旧（100）但带独有书签——B 的回推条目会因书签
        // 差异携带旧进度（SyncMerger.pushBack 条件是「有差异」而非「进度较新」）
        val xA = book(id = 1, title = "书X", sha256 = "stale-x", lastReadAt = 200L)
            .copy(progressPercent = 0.8f)
        val xB = book(id = 1, title = "书X", sha256 = "stale-x", lastReadAt = 100L)
            .copy(progressPercent = 0.1f)
        pair.repoA.emitBooks(listOf(xA))
        pair.repoB.emitBooks(listOf(xB))
        pair.repoB.emitBookmarks(
            listOf(
                Bookmark(
                    bookId = 1L,
                    chapterIndex = 2,
                    paragraphIndex = 2,
                    charOffset = 0,
                    excerpt = "B 独有",
                    createdAt = 200L,
                ),
            ),
        )

        // B 同步：B 应用 A 的较新进度，同时把「旧进度 + 独有书签」回推给 A
        pair.managerB.startSync(
            peerDevice(pair.managerA, pair.identityA.deviceId(), "设备A"),
        )

        waitUntil { pair.managerB.sync.value.report != null }
        waitUntil { pair.repoA.addedBookmarks.isNotEmpty() }

        // 防线：A 的较新进度（0.8）不得被 B 的旧进度（0.1）覆盖
        assertTrue(
            "旧进度回推不得落地：${pair.repoA.savedPositions}",
            pair.repoA.savedPositions.isEmpty(),
        )
        // 书签差集仍正常合并（只拦截进度，不拦截书签）
        assertEquals("B 独有", pair.repoA.addedBookmarks.single().excerpt)
        // B 侧正常应用了 A 的较新进度
        val bSaved = pair.repoB.savedPositions.single()
        assertEquals(1L, bSaved.bookId)
        assertEquals(0.8f, bSaved.progressPercent, 0.001f)
    }

    /**
     * AC-17.7 的**落地侧**回归：同位置书签备注较新者胜，且不得产生重复条目。
     *
     * 覆盖的正是「`/sync/apply` 曾静默丢对端备注」的那个分支——修复前
     * `applyRemote` 只补本地缺的书签，同位置已有书签时对端的新备注被直接丢弃
     * （本地列表看起来「什么都没发生」）。本用例的判定点是 A 的
     * `updatedBookmarkNotes`：只有 noteUpdates 分支真正落地才会有记录。
     *
     * 方向：B 持较新备注并发起同步 → B 把自己的那条回推给 A（`POST /sync/apply`），
     * A 走 applyRemote 的 updateNotes 分支就地改列。
     */
    @Test
    fun `同位置备注较新者胜且不产生重复书签（sync apply 落地侧）`() {
        val pair = startSyncPair(45477)
        val xA = book(id = 1, title = "书N", sha256 = "note-x", lastReadAt = 100L)
        val xB = book(id = 1, title = "书N", sha256 = "note-x", lastReadAt = 100L)
        pair.repoA.emitBooks(listOf(xA))
        pair.repoB.emitBooks(listOf(xB))
        // 同一位置（章节 + 段落 + 字符偏移完全一致），只有备注与时间不同
        pair.repoA.emitBookmarks(
            listOf(
                Bookmark(
                    id = 11L,
                    bookId = 1L,
                    chapterIndex = 1,
                    paragraphIndex = 4,
                    charOffset = 12,
                    excerpt = "同一段",
                    createdAt = 50L,
                    note = "旧备注",
                    updatedAt = 100L,
                ),
            ),
        )
        pair.repoB.emitBookmarks(
            listOf(
                Bookmark(
                    id = 21L,
                    bookId = 1L,
                    chapterIndex = 1,
                    paragraphIndex = 4,
                    charOffset = 12,
                    excerpt = "同一段",
                    createdAt = 50L,
                    note = "对端新备注",
                    updatedAt = 900L,
                ),
            ),
        )

        pair.managerB.startSync(
            peerDevice(pair.managerA, pair.identityA.deviceId(), "设备A"),
        )

        waitUntil { pair.managerB.sync.value.report != null }
        waitUntil { pair.repoA.updatedBookmarkNotes.isNotEmpty() }

        val applied = pair.repoA.updatedBookmarkNotes.single()
        assertEquals("落到本地原有那条书签上", 11L, applied.id)
        assertEquals("对端新备注", applied.note)
        assertEquals(900L, applied.updatedAt)
        assertTrue(
            "备注差异不得产生重复书签：${pair.repoA.addedBookmarks}",
            pair.repoA.addedBookmarks.isEmpty(),
        )
        assertTrue("只增不删：不得删除任何书签", pair.repoA.deletedBookmarkIds.isEmpty())
        assertTrue(pair.repoA.clearedBookmarkBookIds.isEmpty())
    }

    @Test
    fun `同步对端不可达时失败并复位状态`() {
        val manager = createManager()
        manager.startAndAwaitRunning()
        val deadPort = java.net.ServerSocket(0).use { it.localPort }

        manager.startSync(DiscoveredDevice("peer-dead", "不可达", loopbackHost, deadPort, 0L))

        waitUntil { manager.sync.value.error != null }
        assertEquals("无法获取对端阅读状态", manager.sync.value.error)
        assertFalse(manager.sync.value.active)
        assertNull(manager.sync.value.report)
    }
}
