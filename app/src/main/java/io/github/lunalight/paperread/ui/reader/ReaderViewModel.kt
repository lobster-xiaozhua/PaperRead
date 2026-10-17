package io.github.lunalight.paperread.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.lunalight.paperread.data.settings.ReaderSettings
import io.github.lunalight.paperread.data.settings.SettingsStore
import io.github.lunalight.paperread.di.AppContainer
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.domain.model.ReaderPosition
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore
import io.github.lunalight.paperread.reader.ReaderProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 阅读器 UI 状态。 */
data class ReaderUiState(
    val loading: Boolean = true,
    val book: Book? = null,
    val chapters: List<Chapter> = emptyList(),
    /** 当前章在 chapters 中的下标。 */
    val chapterIndex: Int = 0,
    /** 当前章段落列表（渲染单元）。 */
    val paragraphs: List<String> = emptyList(),
    /** 每段在章内的原始字符偏移（与 paragraphs 等长；进度以原文口径为准）。 */
    val paragraphStarts: List<Int> = emptyList(),
    /** 首次渲染要滚动到的段落（恢复进度用）。 */
    val initialParagraph: Int = 0,
    /** 首次渲染的段内像素偏移（AC-5.2 段级 + 像素级恢复）。 */
    val initialScrollOffset: Int = 0,
    /** 当前章首个可见段落（滚动上报 / 加书签锚点）。 */
    val currentParagraph: Int = 0,
    val settings: ReaderSettings = ReaderSettings(),
    val progressPercent: Float = 0f,
    /** 工具栏（顶/底栏）显隐；点击正文中央切换。 */
    val chromeVisible: Boolean = true,
    val tocVisible: Boolean = false,
    val settingsVisible: Boolean = false,
    /** 当前书签列表（创建时间倒序）。 */
    val bookmarks: List<Bookmark> = emptyList(),
    /** 一次性提示（Snackbar）。 */
    val message: String? = null,
    val error: String? = null,
) {
    val chapterTitle: String get() = chapters.getOrNull(chapterIndex)?.title ?: ""
    val chapterPosition: String
        get() = if (chapters.isEmpty()) "" else "第 ${chapterIndex + 1}/${chapters.size} 章"
}

/**
 * 阅读器 ViewModel：加载书籍/章节、ReaderPosition 恢复、防抖进度保存、
 * 切章、目录与设置面板状态、设置读写。
 */
class ReaderViewModel(
    private val bookId: Long,
    private val bookRepository: BookRepository,
    private val bookTextStore: BookTextStore,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            bookRepository.observeBookmarks(bookId).collect { bookmarks ->
                _uiState.update { it.copy(bookmarks = bookmarks) }
            }
        }
        viewModelScope.launch { load() }
    }

    // ---- 加载与恢复 ----

    private suspend fun load() {
        val book = bookRepository.getBook(bookId)
        if (book == null) {
            _uiState.update { it.copy(loading = false, error = "书籍不存在或已删除") }
            return
        }
        val chapters = bookRepository.getChapters(bookId)
        if (chapters.isEmpty()) {
            _uiState.update { it.copy(loading = false, error = "章节为空，请尝试重新解析") }
            return
        }
        val index = book.lastChapterIndex.coerceIn(0, chapters.lastIndex)
        val scan = paragraphsOf(book, chapters[index])
        val paragraphIndex = book.lastParagraphIndex.coerceIn(0, maxOf(0, scan.texts.lastIndex))
        val scrollOffset = book.lastScrollOffset.coerceAtLeast(0)
        _uiState.update {
            it.copy(
                loading = false,
                book = book,
                chapters = chapters,
                chapterIndex = index,
                paragraphs = scan.texts,
                paragraphStarts = scan.starts,
                initialParagraph = paragraphIndex,
                initialScrollOffset = scrollOffset,
                progressPercent = ReaderProgress.percentOf(
                    cumulativeChars = chapters[index].cumulativeChars,
                    chapterCharOffset = ReaderProgress.chapterCharOffset(scan.starts, paragraphIndex),
                    totalChars = book.totalChars,
                ),
                error = null,
            )
        }
    }

    private fun paragraphsOf(book: Book, chapter: Chapter): ReaderProgress.ParagraphScan =
        ReaderProgress.scanParagraphs(bookTextStore.readChapter(book.dirName, chapter))

    // ---- 滚动位置与进度保存 ----

    /** 正文滚动变化（首个可见段落 + 段内像素偏移）。防抖 500ms 后落库。 */
    fun onScrollPosition(firstVisibleParagraph: Int, pixelOffset: Int = 0) {
        val state = _uiState.value
        if (state.loading || state.book == null || state.paragraphs.isEmpty()) return
        val clamped = firstVisibleParagraph.coerceIn(0, state.paragraphs.lastIndex)
        if (clamped != state.currentParagraph) {
            _uiState.update { it.copy(currentParagraph = clamped) }
        }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            persist(state.chapterIndex, clamped, pixelOffset.coerceAtLeast(0), state)
        }
    }

    private suspend fun persist(
        chapterIndex: Int,
        paragraphIndex: Int,
        scrollOffset: Int,
        state: ReaderUiState,
    ) {
        val book = state.book ?: return
        val chapter = state.chapters.getOrNull(chapterIndex) ?: return
        val charOffset = ReaderProgress.chapterCharOffset(state.paragraphStarts, paragraphIndex)
        val percent = ReaderProgress.percentOf(
            chapter.cumulativeChars,
            charOffset,
            book.totalChars,
        )
        bookRepository.saveReaderPosition(
            bookId = bookId,
            position = ReaderPosition(
                chapterIndex = chapterIndex,
                paragraphIndex = paragraphIndex,
                scrollOffset = scrollOffset,
                charOffset = charOffset,
            ),
            progressPercent = percent,
        )
        _uiState.update { it.copy(progressPercent = percent) }
    }

    // ---- 切章 ----

    fun nextChapter() = goToChapter(_uiState.value.chapterIndex + 1)

    fun previousChapter() = goToChapter(_uiState.value.chapterIndex - 1)

    /** 跳转章节；[paragraph] 供书签恢复使用（默认章首）。 */
    fun goToChapter(index: Int, paragraph: Int = 0) {
        val state = _uiState.value
        if (state.loading || state.chapters.isEmpty()) return
        val target = index.coerceIn(0, state.chapters.lastIndex)
        if (target == state.chapterIndex && state.paragraphs.isNotEmpty() && paragraph <= 0) return
        val book = state.book ?: return
        val scan = paragraphsOf(book, state.chapters[target])
        val targetParagraph = paragraph.coerceIn(0, maxOf(0, scan.texts.lastIndex))
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    chapterIndex = target,
                    paragraphs = scan.texts,
                    paragraphStarts = scan.starts,
                    initialParagraph = targetParagraph,
                    initialScrollOffset = 0,
                    currentParagraph = targetParagraph,
                    tocVisible = false,
                    progressPercent = ReaderProgress.percentOf(
                        state.chapters[target].cumulativeChars,
                        ReaderProgress.chapterCharOffset(scan.starts, targetParagraph),
                        book.totalChars,
                    ),
                )
            }
            // 切章立即落库（不等防抖）
            saveJob?.cancel()
            persist(target, targetParagraph, 0, _uiState.value)
        }
    }

    // ---- 面板与工具栏 ----

    fun toggleChrome() = _uiState.update { it.copy(chromeVisible = !it.chromeVisible) }

    fun showToc() = _uiState.update { it.copy(tocVisible = true, chromeVisible = true) }

    fun hideToc() = _uiState.update { it.copy(tocVisible = false) }

    fun showSettings() = _uiState.update { it.copy(settingsVisible = true, chromeVisible = true) }

    fun hideSettings() = _uiState.update { it.copy(settingsVisible = false) }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    // ---- 书签 ----

    /** 在当前首个可见段落处添加书签（含摘录）。 */
    fun addBookmark() {
        val state = _uiState.value
        val book = state.book ?: return
        if (state.loading || state.paragraphs.isEmpty()) return
        val paragraphIndex = state.currentParagraph.coerceIn(0, state.paragraphs.lastIndex)
        val excerpt = state.paragraphs[paragraphIndex].take(EXCERPT_MAX_CHARS)
        val charOffset = ReaderProgress.chapterCharOffset(state.paragraphStarts, paragraphIndex)
        viewModelScope.launch {
            bookRepository.addBookmark(
                Bookmark(
                    bookId = bookId,
                    chapterIndex = state.chapterIndex,
                    paragraphIndex = paragraphIndex,
                    charOffset = charOffset,
                    excerpt = excerpt,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            _uiState.update { it.copy(message = "已添加书签") }
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { bookRepository.deleteBookmark(id) }
    }

    /** 跳转到书签位置（章节 + 段级）。 */
    fun goToBookmark(bookmark: Bookmark) =
        goToChapter(bookmark.chapterIndex, bookmark.paragraphIndex)

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    companion object {
        private const val SAVE_DEBOUNCE_MS = 500L

        /** 书签摘录最大字符数。 */
        const val EXCERPT_MAX_CHARS = 60

        fun factory(container: AppContainer, bookId: Long): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ReaderViewModel(
                        bookId = bookId,
                        bookRepository = container.bookRepository,
                        bookTextStore = container.bookTextStore,
                        settingsStore = container.settingsStore,
                    )
                }
            }
    }
}
