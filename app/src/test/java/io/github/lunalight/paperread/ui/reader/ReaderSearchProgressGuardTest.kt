package io.github.lunalight.paperread.ui.reader

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.domain.search.BookSearchSource
import io.github.lunalight.paperread.domain.search.SearchHit
import io.github.lunalight.paperread.fakes.FakeBookRepository
import io.github.lunalight.paperread.fakes.FakeBookTextStore
import io.github.lunalight.paperread.fakes.FakeSettingsStore
import io.github.lunalight.paperread.fakes.FakeSpeechController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 搜索期间的进度落库防线（AC-15.5 深度校验）。
 *
 * 既有 `ReaderSearchViewModelTest` 覆盖了「滚动上报被抑制」与「取消不落库」，
 * 但漏掉了两条更容易出事的出口：
 * 1. **搜索中直接退出阅读器**——`onCleared()` 会用 appScope 兜底写 `pendingSave`；
 * 2. **搜索中宿主进 ON_STOP**——`flushPendingSave()` 更激进，连防抖都不等。
 *
 * 这两条都不会读 `suppressProgressSave`，它们的安全性完全依赖
 * 「抑制期间 `pendingSave` 根本不会被写入」这一不变量。本文件把该不变量
 * 钉成断言：守住了，取消不改进度就是结构性的；守不住，用户搜到一半按返回键
 * 就会把临时浏览位置写成阅读进度。
 *
 * 另含 AC-15.8 的反向验证：注入一个「多书命中」的 [BookSearchSource] 实现
 * （模拟 v2.1 书架级搜索），确认 ViewModel 层不需要任何改动即可消费。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSearchProgressGuardTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var repository: FakeBookRepository
    private lateinit var textStore: FakeBookTextStore
    private lateinit var settingsStore: FakeSettingsStore
    private lateinit var speechController: FakeSpeechController

    private val book = Book(
        id = 1L,
        title = "测试书",
        author = "未知",
        format = BookFormat.TXT,
        encoding = "UTF-8",
        sha256 = "abc",
        fileSize = 1000L,
        dirName = "dir-1",
        totalChars = 3000L,
        chapterCount = 3,
        lastChapterIndex = 1,
        lastParagraphIndex = 2,
        lastScrollOffset = 0,
        lastCharOffset = 6,
        lastReadAt = 123L,
        addedAt = 100L,
    )

    private val chapters = (0..2).map { index ->
        Chapter(
            id = index.toLong(),
            bookId = 1L,
            index = index,
            title = "第 ${index + 1} 章",
            charStart = index * 1000L,
            charEnd = index * 1000L + 1000,
            byteStart = index * 2000L,
            byteEnd = index * 2000L + 2000,
            charCount = 1000L,
            cumulativeChars = index * 1000L,
        )
    }

    /** 立刻吐完即结束的源：用于把「扫描生命周期」从断言里排除干净。 */
    private class InstantSource(private val hits: List<SearchHit>) : BookSearchSource {
        override fun search(query: String): Flow<SearchHit> = flow { hits.forEach { emit(it) } }
    }

    /** 挂起不结束的源：收集方取消时才退出，用于观察「在途扫描是否被取消」。 */
    private class SuspendingSource : BookSearchSource {
        var cancelled = false

        override fun search(query: String): Flow<SearchHit> = flow {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
    }

    private var source: BookSearchSource = InstantSource(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeBookRepository().apply {
            book = this@ReaderSearchProgressGuardTest.book
            chapters = this@ReaderSearchProgressGuardTest.chapters
        }
        textStore = FakeBookTextStore().apply {
            chapterText = { _, _ -> "一段\n二段\n三段\n四段" }
        }
        settingsStore = FakeSettingsStore()
        speechController = FakeSpeechController()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 把 ViewModel 放进真实的 [ViewModelStore]，以便走**框架路径**触发 `onCleared()`。
     *
     * 不用反射调 protected 方法：`ViewModelStore.clear()` 正是 Activity 销毁时
     * 框架做的事，测这条路径才能证明「用户按返回键退出阅读器」时的真实行为。
     * `appScope` 注入测试调度器，保证 onCleared 内部的兜底写入可被
     * `advanceUntilIdle()` 确定性驱动（默认 Dispatchers.IO 会变成不可等待的竞态）。
     */
    private fun ownedViewModel(store: ViewModelStore): ReaderViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ReaderViewModel(
                    bookId = 1L,
                    bookRepository = repository,
                    bookTextStore = textStore,
                    settingsStore = settingsStore,
                    speechController = speechController,
                    appScope = CoroutineScope(dispatcher),
                    searchSourceFactory = { _, _ -> source },
                    searchDispatcher = dispatcher,
                ) as T
        }
        return ViewModelProvider(store, factory)[ReaderViewModel::class.java]
    }

    private fun hit(chapter: Int, paragraph: Int, bookId: Long = 1L) = SearchHit(
        bookId = bookId,
        chapterIndex = chapter,
        paragraphIndex = paragraph,
        charOffset = chapter * 1000 + paragraph,
        chapterTitle = "第 ${chapter + 1} 章",
        snippet = "…灵剑…",
    )

    // ---- AC-15.5：退出阅读器的兜底写盘不得带走临时位置 ----

    @Test
    fun `搜索中退出阅读器不写临时位置`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val viewModel = ownedViewModel(store)
        advanceUntilIdle()
        viewModel.openSearch()
        // 搜索期间界面可能因布局变化上报滚动（临时浏览）
        viewModel.onScrollPosition(0, 240)
        viewModel.onScrollPosition(3, 60)
        advanceTimeBy(1200)
        advanceUntilIdle()

        store.clear() // = 用户按返回键退出阅读器
        advanceUntilIdle()

        assertTrue(
            "搜索中退出：onCleared 的 appScope 兜底写盘不得写出临时位置",
            repository.savedPositions.isEmpty(),
        )
    }

    @Test
    fun `搜索中进后台立即落库也不写临时位置`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val viewModel = ownedViewModel(store)
        advanceUntilIdle()
        viewModel.openSearch()
        viewModel.onScrollPosition(2, 0)

        // ON_STOP 走 flushPendingSave：它连 500ms 防抖都不等，是最激进的写盘口
        viewModel.flushPendingSave()
        advanceUntilIdle()

        assertTrue(
            "flushPendingSave 不读 suppressProgressSave，安全性靠 pendingSave 未被写入",
            repository.savedPositions.isEmpty(),
        )
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `搜索前已排队的进度仍会在退出时落地`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val viewModel = ownedViewModel(store)
        advanceUntilIdle()
        // 先正常滚动一次但不等防抖：pendingSave 里是**真实**阅读位置
        viewModel.onScrollPosition(2, 0)
        viewModel.openSearch()

        store.clear()
        advanceUntilIdle()

        assertEquals("真实阅读位置不该被搜索抑制误伤", 1, repository.savedPositions.size)
        assertEquals(2, repository.savedPositions.single().position.paragraphIndex)
    }

    @Test
    fun `取消搜索后滚动恢复落库`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val viewModel = ownedViewModel(store)
        advanceUntilIdle()
        viewModel.openSearch()
        viewModel.cancelSearch()
        advanceUntilIdle()

        viewModel.onScrollPosition(1, 0)
        advanceTimeBy(600)
        advanceUntilIdle()

        assertEquals("抑制开关必须在取消后复位", 1, repository.savedPositions.size)
        store.clear()
        advanceUntilIdle()
    }

    // ---- AC-15.8 反向验证：换实现不改 VM/UI 协议 ----

    @Test
    fun `注入多书命中实现后结果与跳转照常工作`() = runTest(dispatcher) {
        // v2.1 书架级实现的形态：一条流里混着不同书的命中（bookId 各不相同）
        source = InstantSource(
            listOf(
                hit(chapter = 1, paragraph = 2, bookId = 1L),
                hit(chapter = 0, paragraph = 0, bookId = 42L),
                hit(chapter = 2, paragraph = 1, bookId = 7L),
            ),
        )
        val store = ViewModelStore()
        val viewModel = ownedViewModel(store)
        advanceUntilIdle()
        viewModel.openSearch()

        viewModel.onSearchQueryChange("灵剑")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("跨书命中全部入列（协议层不丢 bookId）", 3, state.searchResults.size)
        assertEquals(listOf(1L, 42L, 7L), state.searchResults.map { it.bookId })
        assertFalse("扫描已自然结束", state.searching)

        // 点击外部书的命中：ViewModel 用的是 chapterIndex，不认 bookId
        viewModel.selectSearchHit(state.searchResults[2])
        advanceUntilIdle()

        val after = viewModel.uiState.value
        assertEquals("v2.1 只需换实现即可复用跳转链路", 2, after.chapterIndex)
        assertEquals(1, after.initialParagraph)
        assertEquals(
            SearchHighlightTarget(chapterIndex = 2, paragraphIndex = 1, query = "灵剑"),
            after.searchHighlight,
        )
        store.clear()
        advanceUntilIdle()
    }

    @Test
    fun `多书命中在取消后清空且不改进度`() = runTest(dispatcher) {
        source = InstantSource(listOf(hit(chapter = 0, paragraph = 0, bookId = 42L)))
        val store = ViewModelStore()
        val viewModel = ownedViewModel(store)
        advanceUntilIdle()
        viewModel.openSearch()
        viewModel.onSearchQueryChange("灵剑")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.searchResults.size)

        viewModel.cancelSearch()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
        assertTrue("取消后不得留下任何进度写入", repository.savedPositions.isEmpty())
        assertEquals("回到发起前章节", 1, viewModel.uiState.value.chapterIndex)
        store.clear()
        advanceUntilIdle()
    }

    // ---- AC-15.10：离开页面取消在途扫描 ----

    @Test
    fun `离开阅读器取消在途扫描`() = runTest(dispatcher) {
        val suspending = SuspendingSource()
        source = suspending
        val store = ViewModelStore()
        val viewModel = ownedViewModel(store)
        advanceUntilIdle()
        viewModel.openSearch()
        viewModel.onSearchQueryChange("灵剑")
        advanceUntilIdle()
        assertTrue("前提：扫描仍在进行中", viewModel.uiState.value.searching)

        store.clear() // = 页面销毁（返回书架 / 旋转重建）
        advanceUntilIdle()

        assertTrue("AC-15.10：离开阅读器必须取消在途扫描，不得泄漏协程", suspending.cancelled)
        // 刻意不断言 searching == false：onCleared 先 cancel 再 ++searchToken，
        // 收集协程的 finally 因令牌已过期而不再写状态（「销毁后不再有任何结果写
        // 状态」是设计意图，且此时已无观察者）。这里只钉住「协程确实被取消」。
    }
}
