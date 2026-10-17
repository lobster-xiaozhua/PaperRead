package io.github.lunalight.paperread.ui.reader

import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.fakes.FakeBookRepository
import io.github.lunalight.paperread.fakes.FakeBookTextStore
import io.github.lunalight.paperread.fakes.FakeSettingsStore
import io.github.lunalight.paperread.reader.ReaderProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * ReaderViewModel 单测（纯 JVM，无 Robolectric）：
 * 防抖落库 / 切章立即落库 / 恢复 / 书签 / 设置流。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var repository: FakeBookRepository
    private lateinit var textStore: FakeBookTextStore
    private lateinit var settingsStore: FakeSettingsStore
    private lateinit var viewModel: ReaderViewModel

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
        lastScrollOffset = 15,
        lastCharOffset = 6,
        progressPercent = 0f,
        lastReadAt = 123L,
        addedAt = 100L,
    )

    private val chapters = listOf(
        chapter(index = 0, cumulative = 0L, charStart = 0L, byteStart = 0L),
        chapter(index = 1, cumulative = 1000L, charStart = 1000L, byteStart = 2000L),
        chapter(index = 2, cumulative = 2000L, charStart = 2000L, byteStart = 4000L),
    )

    private fun chapter(index: Int, cumulative: Long, charStart: Long, byteStart: Long) = Chapter(
        id = index.toLong(),
        bookId = 1L,
        index = index,
        title = "第 ${index + 1} 章",
        charStart = charStart,
        charEnd = charStart + 1000,
        byteStart = byteStart,
        byteEnd = byteStart + 2000,
        charCount = 1000L,
        cumulativeChars = cumulative,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeBookRepository().apply {
            this.book = this@ReaderViewModelTest.book
            this.chapters = this@ReaderViewModelTest.chapters
        }
        textStore = FakeBookTextStore().apply {
            // 四段，每段 2 字符：章内偏移表 = [0, 3, 6, 9, 12]
            chapterText = { _, _ -> "一段\n二段\n三段\n四段" }
        }
        settingsStore = FakeSettingsStore()
        viewModel = ReaderViewModel(
            bookId = 1L,
            bookRepository = repository,
            bookTextStore = textStore,
            settingsStore = settingsStore,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `加载后恢复章段与像素偏移并计算进度`() = runTest(dispatcher) {
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("加载完成", state.loading)
        assertNull(state.error)
        assertEquals(book, state.book)
        assertEquals(3, state.chapters.size)
        assertEquals("恢复章下标", 1, state.chapterIndex)
        assertEquals(listOf("一段", "二段", "三段", "四段"), state.paragraphs)
        assertEquals("恢复段下标", 2, state.initialParagraph)
        assertEquals("恢复像素偏移", 15, state.initialScrollOffset)
        assertEquals(
            ReaderProgress.percentOf(1000L, ReaderProgress.chapterCharOffset(state.paragraphs, 2), 3000L),
            state.progressPercent,
            1e-4f,
        )
    }

    @Test
    fun `越界的恢复位置被夹取到有效范围`() = runTest(dispatcher) {
        repository.book = book.copy(lastChapterIndex = 99, lastParagraphIndex = 99)
        viewModel = ReaderViewModel(1L, repository, textStore, settingsStore)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("章下标夹到末章", 2, state.chapterIndex)
        assertEquals("段下标夹到末段", 3, state.initialParagraph)
    }

    @Test
    fun `书籍不存在时进入错误态`() = runTest(dispatcher) {
        repository.book = null
        viewModel = ReaderViewModel(1L, repository, textStore, settingsStore)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals("书籍不存在或已删除", state.error)
    }

    @Test
    fun `章节为空时进入错误态`() = runTest(dispatcher) {
        repository.chapters = emptyList()
        viewModel = ReaderViewModel(1L, repository, textStore, settingsStore)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals("章节为空，请尝试重新解析", state.error)
    }

    @Test
    fun `滚动位置防抖 500ms 只落库最后一次`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.onScrollPosition(3)
        advanceTimeBy(300)
        assertTrue("未到 500ms 不落库", repository.savedPositions.isEmpty())

        viewModel.onScrollPosition(2)
        advanceTimeBy(400)
        assertTrue("重新计时后仍未到点", repository.savedPositions.isEmpty())

        advanceTimeBy(150)
        assertEquals("只落库一次", 1, repository.savedPositions.size)
        val saved = repository.savedPositions.single()
        assertEquals("落库最后位置", 2, saved.position.paragraphIndex)
        assertEquals("像素偏移默认 0", 0, saved.position.scrollOffset)
        assertEquals(1L, saved.bookId)
        assertEquals("进度随位置更新", 1, viewModel.uiState.value.chapterIndex)
    }

    @Test
    fun `切章立即落库并取消防抖`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.onScrollPosition(3)
        advanceTimeBy(100) // 防抖计时中
        viewModel.nextChapter() // 加载后停在第 2 章（index=1）→ 第 3 章（index=2）
        advanceUntilIdle()

        assertEquals("防抖被取消，仅切章一次落库", 1, repository.savedPositions.size)
        val saved = repository.savedPositions.single()
        assertEquals("新章下标", 2, saved.position.chapterIndex)
        assertEquals("章首段", 0, saved.position.paragraphIndex)
        assertEquals("读了新章文本", chapters[2], textStore.readChapterCalls.last().second)
        val state = viewModel.uiState.value
        assertEquals(2, state.chapterIndex)
        assertEquals(0, state.currentParagraph)
        assertEquals(false, state.tocVisible)
    }

    @Test
    fun `边界章不重复落库`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.goToChapter(0)
        advanceUntilIdle()
        viewModel.previousChapter() // 已在第一章：应被忽略
        advanceUntilIdle()

        assertEquals("仅一次切章落库", 1, repository.savedPositions.size)
        assertEquals(0, repository.savedPositions.single().position.chapterIndex)
        assertEquals(0, viewModel.uiState.value.chapterIndex)
    }

    @Test
    fun `书签增删与书签流更新`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.onScrollPosition(2)
        advanceUntilIdle() // 防抖落库一次（与书签无关）
        viewModel.addBookmark()
        advanceUntilIdle()

        val added = repository.addedBookmarks.single()
        assertEquals("书签锚在当前章", 1, added.chapterIndex)
        assertEquals("书签锚在当前段", 2, added.paragraphIndex)
        assertEquals("摘录取自当前段", "三段", added.excerpt)
        assertEquals(
            "字符偏移与进度口径一致",
            ReaderProgress.chapterCharOffset(viewModel.uiState.value.paragraphs, 2),
            added.charOffset,
        )
        assertEquals(1L, added.bookId)
        assertEquals("已添加书签", viewModel.uiState.value.message)

        // 仓储流推送 → UI 状态同步（倒序由仓储保证）
        val stored = added.copy(id = 7L)
        repository.emitBookmarks(listOf(stored))
        advanceUntilIdle()
        assertEquals(listOf(stored), viewModel.uiState.value.bookmarks)

        viewModel.deleteBookmark(7L)
        advanceUntilIdle()
        assertEquals(listOf(7L), repository.deletedBookmarkIds)

        viewModel.dismissMessage()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `跳转书签恢复章与段`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.goToBookmark(
            Bookmark(
                id = 7L,
                bookId = 1L,
                chapterIndex = 2,
                paragraphIndex = 1,
                charOffset = 0,
                excerpt = "摘录",
                createdAt = 1L,
            ),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.chapterIndex)
        assertEquals(1, state.currentParagraph)
        val saved = repository.savedPositions.last()
        assertEquals(2, saved.position.chapterIndex)
        assertEquals(1, saved.position.paragraphIndex)
    }

    @Test
    fun `设置更新经 VM 写入并回流到状态`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.updateSettings { it.copy(fontSizeSp = 22) }
        advanceUntilIdle()

        assertEquals(22, settingsStore.current().fontSizeSp)
        assertEquals(22, viewModel.uiState.value.settings.fontSizeSp)
    }

    @Test
    fun `工具栏与目录面板开关`() = runTest(dispatcher) {
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.chromeVisible)
        viewModel.toggleChrome()
        assertFalse(viewModel.uiState.value.chromeVisible)

        viewModel.showToc()
        assertTrue(viewModel.uiState.value.tocVisible)
        assertTrue("打开目录时工具栏可见", viewModel.uiState.value.chromeVisible)
        viewModel.hideToc()
        assertFalse(viewModel.uiState.value.tocVisible)

        viewModel.showSettings()
        assertTrue(viewModel.uiState.value.settingsVisible)
        viewModel.hideSettings()
        assertFalse(viewModel.uiState.value.settingsVisible)
    }

    @Test
    fun `错误可被消除`() = runTest(dispatcher) {
        repository.book = null
        viewModel = ReaderViewModel(1L, repository, textStore, settingsStore)
        advanceUntilIdle()
        assertEquals("书籍不存在或已删除", viewModel.uiState.value.error)

        viewModel.dismissError()
        assertNull(viewModel.uiState.value.error)
    }
}
