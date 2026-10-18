package io.github.lunalight.paperread.transfer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度同步合并决策单测（AC-11.1 ~ AC-11.3，纯 JVM）：
 * last-write-wins、书签只增不删、单侧独有数据保留、双向不互相覆盖。
 */
class SyncMergerTest {

    private fun state(
        sha256: String = "sha-1",
        lastReadAt: Long? = 100L,
        chapter: Int = 0,
        paragraph: Int = 0,
        charOffset: Int = 0,
        bookmarks: List<SyncBookmark> = emptyList(),
    ) = BookSyncState(
        sha256 = sha256,
        title = "t-$sha256",
        lastChapterIndex = chapter,
        lastParagraphIndex = paragraph,
        lastScrollOffset = 0,
        lastCharOffset = charOffset,
        progressPercent = 0.2f,
        lastReadAt = lastReadAt,
        bookmarks = bookmarks,
    )

    private fun mark(chapter: Int, paragraph: Int = 0, offset: Int = 0, created: Long = 1L) =
        SyncBookmark(chapter, paragraph, offset, "摘录$chapter", created)

    // ---- 进度（AC-11.2） ----

    @Test
    fun `对端较新则覆盖本地进度`() {
        val result = SyncMerger.mergeBook(state(lastReadAt = 100L), state(lastReadAt = 200L))

        assertTrue(result.applyRemoteProgress)
        assertFalse(result.pushProgressToRemote)
    }

    @Test
    fun `本地较新则回推对端且不覆盖本地`() {
        val result = SyncMerger.mergeBook(state(lastReadAt = 300L), state(lastReadAt = 200L))

        assertFalse(result.applyRemoteProgress)
        assertTrue(result.pushProgressToRemote)
    }

    @Test
    fun `时间相同不互相覆盖`() {
        val result = SyncMerger.mergeBook(state(lastReadAt = 200L), state(lastReadAt = 200L))

        assertFalse(result.applyRemoteProgress)
        assertFalse(result.pushProgressToRemote)
    }

    @Test
    fun `本地无记录时接受对端进度`() {
        val result = SyncMerger.mergeBook(state(lastReadAt = null), state(lastReadAt = 50L))

        assertTrue(result.applyRemoteProgress)
    }

    @Test
    fun `对端无记录时不覆盖本地`() {
        val result = SyncMerger.mergeBook(state(lastReadAt = 50L), state(lastReadAt = null))

        assertFalse(result.applyRemoteProgress)
        assertTrue("本地有记录应回推", result.pushProgressToRemote)
    }

    // ---- 书签（AC-11.3：只增不删） ----

    @Test
    fun `对端独有书签并入本地`() {
        val result = SyncMerger.mergeBook(
            state(bookmarks = listOf(mark(0))),
            state(bookmarks = listOf(mark(1), mark(2))),
        )

        assertEquals(listOf(1, 2), result.addBookmarks.map { it.chapterIndex })
    }

    @Test
    fun `本地独有书签不会被删除且回推对端`() {
        val result = SyncMerger.mergeBook(
            state(bookmarks = listOf(mark(5))),
            state(bookmarks = emptyList()),
        )

        assertTrue("本地书签不得出现在待删除中（本接口无删除能力）", result.addBookmarks.isEmpty())
        assertEquals(listOf(5), result.pushBookmarks.map { it.chapterIndex })
    }

    @Test
    fun `同位置书签不重复添加`() {
        val same = mark(1, paragraph = 2, offset = 30)
        val result = SyncMerger.mergeBook(
            state(bookmarks = listOf(same.copy(excerpt = "本地摘录"))),
            state(bookmarks = listOf(same.copy(excerpt = "对端摘录"))),
        )

        assertTrue(result.addBookmarks.isEmpty())
        assertTrue(result.pushBookmarks.isEmpty())
    }

    // ---- 整库合并 ----

    @Test
    fun `整库统计与回推集合正确`() {
        val local = SyncEnvelope(
            deviceId = "me",
            books = listOf(
                state("a", lastReadAt = 100L, bookmarks = listOf(mark(1))), // 对端更新 → 本地覆盖
                state("b", lastReadAt = 500L, bookmarks = listOf(mark(2), mark(6))), // 本地更新 → 回推
                state("local-only", lastReadAt = 10L), // 仅本地，保留
            ),
        )
        val remote = SyncEnvelope(
            deviceId = "peer",
            books = listOf(
                state("a", lastReadAt = 300L, bookmarks = listOf(mark(1), mark(3))),
                state("b", lastReadAt = 200L, bookmarks = listOf(mark(2), mark(4))),
                state("remote-only", lastReadAt = 900L),
            ),
        )

        val report = SyncMerger.merge(local, remote)

        assertEquals(1, report.localOverwritten)
        // a：对端有 mark(1) 与 mark(3)，本地只有 mark(1) → 补 1 条；
        // b：对端有 mark(2) 与 mark(4)，本地只有 mark(2) → 再补 1 条。
        assertEquals("应补两端缺失的 2 条对端书签", 2, report.localBookmarksAdded)
        assertEquals(1, report.localOnlyBooks)
        assertEquals(1, report.remoteOnlyBooks)
        val pushed = report.pushBack.single()
        assertEquals("b", pushed.sha256)
        assertEquals("只推对端缺的书签", listOf(6), pushed.bookmarks.map { it.chapterIndex })
    }

    @Test
    fun `双方一致时无任何变更`() {
        val book = state("a", lastReadAt = 100L, bookmarks = listOf(mark(1)))
        val report = SyncMerger.merge(
            SyncEnvelope(deviceId = "me", books = listOf(book)),
            SyncEnvelope(deviceId = "peer", books = listOf(book)),
        )

        assertEquals(0, report.totalChanges)
        assertEquals(0, report.pushBack.size)
        assertEquals(0, report.localOnlyBooks)
        assertEquals(0, report.remoteOnlyBooks)
    }

    @Test
    fun `对端独有书籍只计数不导入`() {
        val report = SyncMerger.merge(
            SyncEnvelope(deviceId = "me", books = emptyList()),
            SyncEnvelope(deviceId = "peer", books = listOf(state("new"))),
        )

        assertEquals(1, report.remoteOnlyBooks)
        assertEquals(0, report.localOverwritten)
    }

    // ---- 协调器 ----

    private class FakeLocal(initial: SyncEnvelope) : LocalSyncGateway {
        private val envelope = initial
        val applied = mutableListOf<String>()
        val added = mutableListOf<Pair<String, Int>>()
        var failSnapshot = false

        override suspend fun snapshot(deviceId: String): SyncEnvelope {
            if (failSnapshot) error("磁盘读失败")
            return envelope
        }

        override suspend fun applyProgress(state: BookSyncState) {
            applied += state.sha256
        }

        override suspend fun addBookmarks(state: BookSyncState, bookmarks: List<SyncBookmark>) {
            bookmarks.forEach { added += state.sha256 to it.chapterIndex }
        }
    }

    private class FakeRemote(
        private val remote: SyncEnvelope?,
        private val pushOk: Boolean = true,
    ) : RemoteSyncGateway {
        var pushed: SyncEnvelope? = null

        override fun fetchState(device: DiscoveredDevice): SyncEnvelope? = remote

        override fun pushState(device: DiscoveredDevice, envelope: SyncEnvelope): Boolean {
            pushed = envelope
            return pushOk
        }
    }

    private val device = DiscoveredDevice("peer", "对端", "127.0.0.1", 1, 0L)

    @Test
    fun `协调器应用对端较新进度并回推本地较新进度`() = runBlocking {
        val local = FakeLocal(
            SyncEnvelope(
                deviceId = "me",
                books = listOf(state("a", lastReadAt = 100L), state("b", lastReadAt = 500L)),
            ),
        )
        val remote = FakeRemote(
            SyncEnvelope(
                deviceId = "peer",
                books = listOf(state("a", lastReadAt = 300L), state("b", lastReadAt = 200L)),
            ),
        )

        val outcome = ProgressSyncCoordinator(local, remote).sync(device, "me").getOrThrow()

        assertEquals(listOf("a"), local.applied)
        assertEquals(listOf("b"), remote.pushed!!.books.map { it.sha256 })
        assertEquals(1, outcome.pushed)
        assertEquals(1, outcome.report.localOverwritten)
    }

    @Test
    fun `无法获取对端状态时返回失败`() = runBlocking {
        val result = ProgressSyncCoordinator(FakeLocal(SyncEnvelope(deviceId = "me", books = emptyList())), FakeRemote(null))
            .sync(device, "me")

        assertTrue(result.isFailure)
        assertEquals("无法获取对端阅读状态", result.exceptionOrNull()!!.message)
    }

    @Test
    fun `读取本机状态失败时返回失败`() = runBlocking {
        val local = FakeLocal(SyncEnvelope(deviceId = "me", books = emptyList())).apply { failSnapshot = true }
        val result = ProgressSyncCoordinator(local, FakeRemote(SyncEnvelope(deviceId = "peer", books = emptyList())))
            .sync(device, "me")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("读取本机阅读状态失败"))
    }

    @Test
    fun `仅本地有更新时不发起回推请求以外的一切都正常`() = runBlocking {
        val local = FakeLocal(SyncEnvelope(deviceId = "me", books = listOf(state("a", lastReadAt = 900L))))
        val remote = FakeRemote(SyncEnvelope(deviceId = "peer", books = listOf(state("a", lastReadAt = 100L))))

        val outcome = ProgressSyncCoordinator(local, remote).sync(device, "me").getOrThrow()

        assertNotNull(remote.pushed)
        assertEquals(0, outcome.report.totalChanges)
        assertTrue(local.applied.isEmpty())
    }

    @Test
    fun `无变更时不发送回推`() = runBlocking {
        val same = state("a", lastReadAt = 100L)
        val local = FakeLocal(SyncEnvelope(deviceId = "me", books = listOf(same)))
        val remote = FakeRemote(SyncEnvelope(deviceId = "peer", books = listOf(same)))

        ProgressSyncCoordinator(local, remote).sync(device, "me").getOrThrow()

        assertNull(remote.pushed)
    }
}
