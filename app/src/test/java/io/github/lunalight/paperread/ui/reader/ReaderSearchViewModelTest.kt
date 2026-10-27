package io.github.lunalight.paperread.ui.reader

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ReaderViewModel 的搜索行为单测（US-15，纯 JVM）。
 *
 * 覆盖点与 AC 对照：
 * - AC-15.4 流式：结果逐条追加，扫描未结束就能看到首批；
 * - AC-15.5 取消：回到发起前位置、搜索期间不落库、取消后不再追加结果；
 * - AC-15.2 门槛与防抖：< 2 字不扫描、300ms 内连打只扫最后一次；
 * - AC-15.3 选结果：跳章 + 定位段落 + 高亮；
 * - AC-15.7 无命中：results 空 + searching=false（面板据此显示空态）；
 * - AC-15.8 协议：ViewModel 只认 [BookSearchSource]，测试直接注入 fake 实现
 *   （等价于 v2.1 换个实现，VM 一行不改）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderSearchViewModelTest {

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

    /**
     * 可控的搜索源 fake：先吐 [initialHits]，然后挂起等待取消。
     *
     * 「挂起不完成」是关键——真实实现要扫完整本书才结束，用完成态 fake
     * 会把「流式」与「取消」两类断言都测成假绿。
     */
    private class FakeSearchSource(
        private val initialHits: List<SearchHit> = emptyList(),
        private val complete: Boolean = false,
    ) : BookSearchSource {

        val queries = mutableListOf<String>()
        var cancelled = false

        override fun search(query: String): Flow<SearchHit> = flow {
            queries += query
            try {
                initialHits.forEach { emit(it) }
                if (!complete) awaitCancellation()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
    }

    private var fakeSource = FakeSearchSource()

    private fun newViewModel(): ReaderViewModel = ReaderViewModel(
        bookId = 1L,
        bookRepository = repository,
        bookTextStore = textStore,
        settingsStore = settingsStore,
        speechController = speechController,
        searchSourceFactory = { _, _ -> fakeSource },
        searchDispatcher = dispatcher,
    )

    private fun hit(chapter: Int, paragraph: Int, query: String = "灵剑") = SearchHit(
        bookId = 1L,
        chapterIndex = chapter,
        paragraphIndex = paragraph,
        charOffset = chapter * 1000 + paragraph,
        chapterTitle = "第 ${chapter + 1} 章",
        snippet = "…$query…",
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeBookRepository().apply {
            this.book = this@ReaderSearchViewModelTest.book
            this.chapters = this@ReaderSearchViewModelTest.chapters
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

    // ---- 入口与状态（AC-15.1） ----

    @Test
    fun `打开搜索面板记录起点并重置上一轮状态`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.openSearch()

        val state = viewModel.uiState.value
        assertTrue(state.searchVisible)
        assertEquals("", state.searchQuery)
        assertTrue(state.searchResults.isEmpty())
        assertFalse(state.searching)
        assertNull(state.searchHighlight)
    }

    @Test
    fun `加载中不打开搜索面板`() = runTest(dispatcher) {
        val viewModel = newViewModel() // 未 advanceUntilIdle：仍处于 loading

        viewModel.openSearch()

        assertFalse(viewModel.uiState.value.searchVisible)
    }

    @Test
    fun `打开搜索会收起目录弹层`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.showToc()

        viewModel.openSearch()

        assertFalse(viewModel.uiState.value.tocVisible)
    }

    // ---- 关键词门槛与防抖（AC-15.2） ----

    @Test
    fun `短于两个字不发起扫描`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openSearch()

        viewModel.onSearchQueryChange("灵")
        advanceUntilIdle()

        assertTrue("1 个字不扫描", fakeSource.queries.isEmpty())
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
        assertFalse(viewModel.uiState.value.searching)
    }

    @Test
    fun `防抖窗口内连打只扫最后一次输入`() = runTest(dispatcher) {
        fakeSource = FakeSearchSource(initialHits = listOf(hit(0, 0)), complete = true)
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openSearch()

        viewModel.onSearchQueryChange("灵剑")
        advanceTimeBy(120)
        viewModel.onSearchQueryChange("灵剑出")
        advanceTimeBy(120)
        viewModel.onSearchQueryChange("灵剑出世")
        advanceUntilIdle()

        assertEquals(listOf("灵剑出世"), fakeSource.queries)
    }

    @Test
    fun `防抖满 300ms 才发起扫描`() = runTest(dispatcher) {
        fakeSource = FakeSearchSource(initialHits = listOf(hit(0, 0)), complete = true)
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openSearch()

        viewModel.onSearchQueryChange("灵剑")
        advanceTimeBy(299)
        assertTrue("不到 300ms 不扫描", fakeSource.queries.isEmpty())

        advanceTimeBy(2)
        advanceUntilIdle()
        assertEquals(listOf("灵剑"), fakeSource.queries)
    }

    @Test
    fun `清空关键词取消在途扫描并清结果`() = runTest(dispatcher) {
        fakeSource = FakeSearchSource(initialHits = listOf(hit(0, 0), hit(1, 0)))
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openSearch()
        viewModel.onSearchQueryChange("灵剑")
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.searchResults.size)

        viewModel.onSearchQueryChange("灵")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
        assertFalse(viewModel.uiState.value.searching)
        assertTrue("在途扫描被取消", fakeSource.cancelled)
    }

    // ---- 流式与结果（AC-15.4 / AC-15.7） ----

    @Test
    fun `首批结果在扫描结束前就到达`() = runTest(dispatcher) {
        fakeSource = FakeSearchSource(initialHits = listOf(hit(0, 1), hit(2, 3)))
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openSearch()

        viewModel.onSearchQueryChange("灵剑")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("扫描仍挂起即可见两条结果", 2, state.searchResults.size)
        assertTrue("扫描尚未结束", state.searching)
        assertEquals(listOf(0, 2), state.searchResults.map { it.chapterIndex })

        viewModel.cancelSearch() // 收尾：别把挂起的扫描留给下一个用例
        advanceUntilIdle()
    }

    @Test
    fun `扫描结束且无命中时退出搜索中状态`() = runTest(dispatcher) {
        fakeSource = FakeSearchSource(initialHits = emptyList(), complete = true)
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openSearch()

        viewModel.onSearchQueryChange("不存在的词")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("AC-15.7 空态数据来源", state.searchResults.isEmpty())
        assertFalse(state.searching)
    }

    @Test
    fun `新一轮搜索清空上一轮结果`() = runTest(dispatcher) {
        fakeSource = FakeSearchSource(initialHits = listOf(hit(0, 0)))
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openSearch()
        viewModel.onSearchQueryChange("灵剑")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.searchResults.size)

        val second = FakeSearchSource(initialHits = listOf(hit(1, 2), hit(2, 0)))
        fakeSource = second
        viewModel.onSearchQueryChange("灵剑出")
        advanceUntilIdle()

        assertEquals(
            "结果只含本轮命中，不残留上一轮",
            listOf(1, 2),
            viewModel.uiState.value.searchResults.map { it.chapterIndex },
        )
        viewModel.cancelSearch()
        advanceUntilIdle()
    }

    // ---- 结果点击（AC-15.3） ----

    @Test
    fun `点击结果跳章定位并保留高亮`() = runTest(dispatcher) {
        fakeSource = FakeSearchSource(initialHits = listOf(hit(2, 1)))
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openSearch()
        viewModel.onSearchQueryChange("灵剑")
        advanceUntilIdle()

        viewModel.selectSearchHit(viewModel.uiState.value.searchResults.single())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("跳到命中章", 2, state.chapterIndex)
        assertEquals("定位到命中段", 1, state.initialParagraph)
        assertFalse("面板关闭（用户要看见正文）", state.searchVisible)
        assertEquals(
            SearchHighlightTarget(chapterIndex = 2, paragraphIndex = 1, query = "灵剑"),
            state.searchHighlight,
        )
    }

    @Test
    fun `点击结果后恢复正常进度保存`() = runTest(dispatcher) {
        fakeSource = FakeSearchSource(initialHits = listOf(hit(2, 1)))
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openSearch()
        viewModel.onSearchQueryChange("灵剑")
        advanceUntilIdle()
        viewModel.selectSearchHit(viewModel.uiState.value.searchResults.single())
        advanceUntilIdle()
        val savesAfterJump = repository.savedPositions.size
        assertEquals("跳转本身立即落库", 1, savesAfterJump)

        viewModel.onScrollPosition(2)
        advanceTimeBy(600)
        advanceUntilIdle()

        assertTrue("跳转后滚动照常保存", repository.savedPositions.size > savesAfterJump)
    }

    // ---- 取消（AC-15.5） ----

    @Test
    fun `取消搜索回到发起前的位置`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        // 起点：第 2 章第 2 段（恢复位置）
        assertEquals(1, viewModel.uiState.value.chapterIndex)
        assertEquals(2, viewModel.uiState.value.currentParagraph)
        val originPercent = viewModel.uiState.value.progressPercent

        viewModel.openSearch()
        // 搜索期间界面可能被程序性跳走（模拟：用户点结果后重新打开搜索再取消）
        viewModel.goToChapter(0, 0)
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.chapterIndex)

        viewModel.cancelSearch()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("回到发起搜索前的章", 1, state.chapterIndex)
        assertEquals("回到发起搜索前的段", 2, state.currentParagraph)
        assertEquals("进度回到搜索前数值", originPercent, state.progressPercent, 1e-4f)
        assertFalse(state.searchVisible)
        assertNull(state.searchHighlight)
    }

    @Test
    fun `取消搜索不改动已保存进度`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.onScrollPosition(2)
        advanceTimeBy(600)
        advanceUntilIdle()
        val savesBeforeSearch = repository.savedPositions.size
        assertEquals(1, savesBeforeSearch)

        viewModel.openSearch()
        // 搜索行为期间的所有滚动上报都应被抑制
        viewModel.onScrollPosition(0)
        viewModel.onScrollPosition(3)
        advanceTimeBy(1200)
        advanceUntilIdle()
        viewModel.cancelSearch()
        advanceUntilIdle()

        assertEquals("取消搜索不产生任何进度写入", savesBeforeSearch, repository.savedPositions.size)
    }

    @Test
    fun `取消搜索后不再追加在途结果`() = runTest(dispatcher) {
        fakeSource = FakeSearchSource(initialHits = listOf(hit(0, 0), hit(1, 0)))
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openSearch()
        viewModel.onSearchQueryChange("灵剑")
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.searchResults.size)

        viewModel.cancelSearch()
        advanceUntilIdle()

        assertTrue("在途扫描被取消", fakeSource.cancelled)
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
        assertFalse(viewModel.uiState.value.searching)
    }

    @Test
    fun `下滑关闭面板与取消同语义`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openSearch()

        viewModel.dismissSearch()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.searchVisible)
        assertTrue(viewModel.uiState.value.searchResults.isEmpty())
    }

    @Test
    fun `翻页模式下的位置上报同样被抑制`() = runTest(dispatcher) {
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.openSearch()

        viewModel.onPagePosition(1)
        advanceTimeBy(1200)
        advanceUntilIdle()

        assertTrue("翻页上报也不得落库（AC-15.5）", repository.savedPositions.isEmpty())
    }
}
