package io.github.lunalight.paperread.transfer

import io.github.lunalight.paperread.domain.model.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「备注更新」在**落地侧**的验证（AC-17.7 的下半段）。
 *
 * `BookmarkCompatTest` 只证明了合并器（[SyncMerger]）算得对——算出
 * `BookMergeResult.updateNotes` 不等于本地真的改了列。这里守的是从合并结果到
 * [LocalSyncGateway.updateNotes] 的这一跳：
 * - 有备注更新时必须调用 `updateNotes`（而不是 `addBookmarks`，否则会插重复行）；
 * - 只有新增时必须**不**调用 `updateNotes`（避免对不存在的行做无谓更新）；
 * - 本地较新时既不更新也不新增，只把本地那条回推对端。
 *
 * 为什么用假网关而不是真仓储：`TransferSyncController` 的落地实现依赖 Room，
 * 属 instrumented 口径；纯 JVM 侧能验证的是「协调器按合并结论发出哪一类调用」，
 * 这也正是回归最容易断的一环（`updateNotes` 曾整段缺失，`/sync/apply` 静默丢备注）。
 */
class SyncNoteApplyTest {

    private class RecordingGateway(
        private val initial: SyncEnvelope,
        private val remote: SyncEnvelope,
    ) : LocalSyncGateway {

        val progressApplied = mutableListOf<String>()
        val bookmarksAdded = mutableListOf<String>()
        val notesUpdated = mutableListOf<Pair<String, String>>()

        var pushed: SyncEnvelope? = null

        override suspend fun snapshot(deviceId: String): SyncEnvelope = initial

        override suspend fun applyProgress(state: BookSyncState) {
            progressApplied += state.sha256
        }

        override suspend fun addBookmarks(state: BookSyncState, bookmarks: List<SyncBookmark>) {
            bookmarks.forEach { bookmarksAdded += "${state.sha256}:${it.positionKey}" }
        }

        override suspend fun updateNotes(state: BookSyncState, bookmarks: List<SyncBookmark>) {
            bookmarks.forEach { notesUpdated += state.sha256 to "${it.positionKey}=${it.noteOrEmpty}" }
        }

        fun asRemote(): RemoteSyncGateway = object : RemoteSyncGateway {
            override fun fetchState(device: DiscoveredDevice): SyncEnvelope = remote

            override fun pushState(device: DiscoveredDevice, envelope: SyncEnvelope): Boolean {
                pushed = envelope
                return true
            }
        }
    }

    private val device = DiscoveredDevice(
        deviceId = "peer",
        deviceName = "对端",
        host = "192.168.1.20",
        httpPort = 45473,
        lastSeenAt = 1L,
    )

    private fun mark(
        paragraphIndex: Int = 2,
        note: String? = null,
        updatedAt: Long? = null,
    ) = SyncBookmark(
        chapterIndex = 1,
        paragraphIndex = paragraphIndex,
        charOffset = 30,
        excerpt = "摘录",
        createdAt = 100L,
        note = note,
        updatedAt = updatedAt,
    )

    private fun state(bookmarks: List<SyncBookmark>, lastReadAt: Long = 100L, sha: String = "sha-1") =
        BookSyncState(
            sha256 = sha,
            title = "书一",
            lastChapterIndex = 1,
            lastParagraphIndex = 2,
            lastScrollOffset = 0,
            lastCharOffset = 30,
            progressPercent = 12.5f,
            lastReadAt = lastReadAt,
            bookmarks = bookmarks,
        )

    private fun envelope(book: BookSyncState) =
        SyncEnvelope(version = SYNC_PROTOCOL_VERSION, deviceId = "me", books = listOf(book))

    @Test
    fun `对端备注较新时走 updateNotes 而不是新增`() {
        val gateway = RecordingGateway(
            initial = envelope(state(listOf(mark(note = null, updatedAt = null)))),
            remote = envelope(state(listOf(mark(note = "对端新备注", updatedAt = 500L)))),
        )
        val coordinator = ProgressSyncCoordinator(gateway, gateway.asRemote())

        val result = runSync(coordinator)

        assertTrue("同步本身应成功", result.isSuccess)
        assertTrue("不得插入新书签（会撞去重键）", gateway.bookmarksAdded.isEmpty())
        assertEquals(1, gateway.notesUpdated.size)
        assertEquals("sha-1" to "1:2:30=对端新备注", gateway.notesUpdated.single())
    }

    @Test
    fun `对端只有新书签时不触碰备注更新`() {
        val gateway = RecordingGateway(
            initial = envelope(state(listOf(mark(paragraphIndex = 2)))),
            remote = envelope(
                state(listOf(mark(paragraphIndex = 2), mark(paragraphIndex = 9, note = "别处的备注", updatedAt = 500L))),
            ),
        )
        val coordinator = ProgressSyncCoordinator(gateway, gateway.asRemote())

        runSync(coordinator)

        assertEquals(1, gateway.bookmarksAdded.size)
        assertTrue("新增与改备注必须分开", gateway.notesUpdated.isEmpty())
    }

    @Test
    fun `本地备注较新时只回推不改本地`() {
        val gateway = RecordingGateway(
            initial = envelope(state(listOf(mark(note = "本地新备注", updatedAt = 900L)))),
            remote = envelope(state(listOf(mark(note = "对端旧备注", updatedAt = 500L)))),
        )
        val coordinator = ProgressSyncCoordinator(gateway, gateway.asRemote())

        runSync(coordinator)

        assertTrue(gateway.notesUpdated.isEmpty())
        assertTrue(gateway.bookmarksAdded.isEmpty())
        val pushed = gateway.pushed
        assertNotNull("本地较新时必须回推对端", pushed)
        assertEquals("本地新备注", pushed!!.books.single().bookmarks.single().note)
    }

    /** [ProgressSyncCoordinator.sync] 是 suspend：测试里用 `runBlocking` 包一层即可。 */
    private fun runSync(coordinator: ProgressSyncCoordinator) =
        kotlinx.coroutines.runBlocking { coordinator.sync(device, selfDeviceId = "me") }

    @Test
    fun `备注更新后书签总数不变`() {
        // 端到端语义：同位置同键 → 合并后本地仍是 1 条（不因备注差异变 2 条）
        val local = state(listOf(mark(note = null, updatedAt = null)))
        val remote = state(listOf(mark(note = "备注", updatedAt = 500L)))

        val merged = SyncMerger.mergeBook(local, remote)

        assertEquals(0, merged.addBookmarks.size)
        assertEquals(1, merged.updateNotes.size)
        assertEquals(0, merged.pushBookmarks.size)
        assertEquals("只增不删：没有删除动作", 0, merged.pushBookmarks.count { it.note.isNullOrEmpty() })
        // 本地最终形态 = 原有 1 条 + 就地改备注，不会出现第 2 条
        val finalKeys = (local.bookmarks + merged.addBookmarks).map { it.positionKey }
        assertEquals(listOf("1:2:30"), finalKeys)
        assertTrue(finalKeys.size == 1)
    }

    @Test
    fun `本地书签构造时不带备注也参与合并`() {
        // AC-17.6 的落地形态：v1→v2 迁移后的存量行 note = "" / updatedAt = 0
        val legacyLocal = Bookmark(
            id = 3L,
            bookId = 1L,
            chapterIndex = 1,
            paragraphIndex = 2,
            charOffset = 30,
            excerpt = "摘录",
            createdAt = 100L,
        )
        val sync = SyncBookmark.from(legacyLocal)

        assertEquals("", legacyLocal.note)
        assertEquals(0L, legacyLocal.updatedAt)
        assertEquals("1:2:30", sync.positionKey)
        assertEquals(0L, sync.noteTimestamp)
    }
}
