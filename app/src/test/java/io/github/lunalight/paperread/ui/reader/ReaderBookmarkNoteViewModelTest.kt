package io.github.lunalight.paperread.ui.reader

import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.fakes.FakeBookRepository
import io.github.lunalight.paperread.fakes.FakeBookTextStore
import io.github.lunalight.paperread.fakes.FakeSettingsStore
import io.github.lunalight.paperread.fakes.FakeSpeechController
import io.github.lunalight.paperread.ui.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 书签备注的 ViewModel 行为单测（US-17 / AC-17.1 / AC-17.3，纯 JVM）。
 *
 * 覆盖：添加书签后进入备注编辑、500 字上限裁剪、计数器、保存走
 * `updateBookmarkNote`（带 updatedAt）、取消不落库、删除时关闭编辑态。
 * 领域层的规范化（trim / 截断）在 `Bookmark.normalizeNote`，这里按落库值断言。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderBookmarkNoteViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var repository: FakeBookRepository
    private lateinit var textStore: FakeBookTextStore
    private lateinit var settingsStore: FakeSettingsStore
    private lateinit var speechController: FakeSpeechController
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
        lastChapterIndex = 0,
        lastParagraphIndex = 0,
        lastScrollOffset = 0,
        lastCharOffset = 0,
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

    private fun legacyBookmark(
        id: Long = 7L,
        note: String = "",
        updatedAt: Long = 0L,
    ) = Bookmark(
        id = id,
        bookId = 1L,
        chapterIndex = 0,
        paragraphIndex = 1,
        charOffset = 3,
        excerpt = "二段",
        createdAt = 100L,
        note = note,
        updatedAt = updatedAt,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeBookRepository().apply {
            this.book = this@ReaderBookmarkNoteViewModelTest.book
            this.chapters = this@ReaderBookmarkNoteViewModelTest.chapters
        }
        textStore = FakeBookTextStore().apply {
            chapterText = { _, _ -> "一段\n二段\n三段\n四段" }
        }
        settingsStore = FakeSettingsStore()
        speechController = FakeSpeechController()
        viewModel = ReaderViewModel(1L, repository, textStore, settingsStore, speechController)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- 添加书签后输入备注（AC-17.1） ----

    @Test
    fun `添加书签后进入备注编辑态`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.addBookmark()
        advanceUntilIdle()

        val editor = viewModel.uiState.value.bookmarkEditor
        assertNotNull("添加后应允许写备注", editor)
        assertTrue(editor!!.isNew)
        assertEquals("", editor.draft)
        assertEquals(UiText.res(R.string.reader_bookmark_added), viewModel.uiState.value.message)
        assertEquals("书签本身已经落库", 1, repository.addedBookmarks.size)
    }

    @Test
    fun `新增备注取消后书签保留`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.addBookmark()
        advanceUntilIdle()

        viewModel.onBookmarkNoteChange("我的备注")
        viewModel.cancelBookmarkNoteEdit()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.bookmarkEditor)
        assertTrue("取消备注不等于删书签", repository.deletedBookmarkIds.isEmpty())
        assertTrue("也不写备注", repository.updatedBookmarkNotes.isEmpty())
    }

    @Test
    fun `保存新增备注写入仓储`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.addBookmark()
        advanceUntilIdle()
        val bookmarkId = viewModel.uiState.value.bookmarkEditor!!.bookmarkId

        viewModel.onBookmarkNoteChange("  精彩的一段  ")
        viewModel.saveBookmarkNote()
        advanceUntilIdle()

        val saved = repository.updatedBookmarkNotes.single()
        assertEquals(bookmarkId, saved.id)
        assertEquals("落库前 trim", "精彩的一段", saved.note)
        assertTrue("updatedAt 必须大于 0（合并裁决依据）", saved.updatedAt > 0L)
        assertNull(viewModel.uiState.value.bookmarkEditor)
        assertEquals(UiText.res(R.string.reader_bookmark_note_saved), viewModel.uiState.value.message)
    }

    // ---- 长度上限（AC-17.1 ≤ 500 字） ----

    @Test
    fun `超过 500 字被裁剪`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.editBookmarkNote(legacyBookmark())

        viewModel.onBookmarkNoteChange("字".repeat(600))

        val editor = viewModel.uiState.value.bookmarkEditor!!
        assertEquals(Bookmark.NOTE_MAX_LENGTH, editor.draft.length)
        assertEquals(0, editor.remaining)
        assertTrue("裁剪后仍可保存（不会被卡在超限态）", editor.canSave)
    }

    @Test
    fun `计数器随输入变化`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.editBookmarkNote(legacyBookmark())

        viewModel.onBookmarkNoteChange("四个字符")

        assertEquals(4, viewModel.uiState.value.bookmarkEditor!!.draft.length)
        assertEquals(Bookmark.NOTE_MAX_LENGTH - 4, viewModel.uiState.value.bookmarkEditor!!.remaining)
    }

    @Test
    fun `空备注可以保存为清除备注`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.editBookmarkNote(legacyBookmark(note = "旧备注", updatedAt = 5L))

        viewModel.onBookmarkNoteChange("   ")
        viewModel.saveBookmarkNote()
        advanceUntilIdle()

        assertEquals("空白备注规范化为空串", "", repository.updatedBookmarkNotes.single().note)
    }

    // ---- 编辑已有书签（AC-17.3） ----

    @Test
    fun `编辑已有书签时预填原备注`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.editBookmarkNote(legacyBookmark(id = 9L, note = "原备注", updatedAt = 5L))

        val editor = viewModel.uiState.value.bookmarkEditor!!
        assertEquals(9L, editor.bookmarkId)
        assertEquals("原备注", editor.draft)
        assertFalse("不是新增", editor.isNew)
    }

    @Test
    fun `删除正在编辑的书签会关闭编辑态`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.editBookmarkNote(legacyBookmark(id = 9L))

        viewModel.deleteBookmark(9L)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.bookmarkEditor)
        assertEquals(listOf(9L), repository.deletedBookmarkIds)
    }

    @Test
    fun `删除其他书签不影响当前编辑`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.editBookmarkNote(legacyBookmark(id = 3L))

        viewModel.deleteBookmark(9L)
        advanceUntilIdle()

        assertEquals(3L, viewModel.uiState.value.bookmarkEditor!!.bookmarkId)
    }

    @Test
    fun `无编辑态时保存是空操作`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.saveBookmarkNote()
        advanceUntilIdle()

        assertTrue(repository.updatedBookmarkNotes.isEmpty())
    }

    @Test
    fun `书签跳转行为不回归`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.goToBookmark(legacyBookmark(id = 5L).copy(chapterIndex = 2, paragraphIndex = 1))
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.chapterIndex)
        assertEquals(1, viewModel.uiState.value.initialParagraph)
    }
}
