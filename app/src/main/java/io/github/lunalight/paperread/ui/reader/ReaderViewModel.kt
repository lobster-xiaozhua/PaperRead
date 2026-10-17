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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    /**
     * 恢复请求序号：每次「加载完成 / 显式跳转」自增。
     *
     * UI 用它作为滚动副作用的一次性票据——同一章内跳到首段时
     * `chapterIndex` 与 `initialParagraph` 都可能不变，靠 key 无法触发重新滚动，
     * 书签落在章首就会点了没反应。序号单调递增保证每次都重新定位，
     * 且消费后不再变化，旋转屏幕不会把用户拖回旧位置。
     */
    val restoreSeq: Long = 0,
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
    /**
     * 错误是否由「正文文件缺失 / 损坏」引起（云备份只恢复书架结构、不含 files/books/）。
     * 为 true 时错误页额外提供「删除该条目」动作，因为重试解析也无济于事。
     */
    val textMissing: Boolean = false,
) {
    val chapterTitle: String get() = chapters.getOrNull(chapterIndex)?.title ?: ""
    val chapterPosition: String
        get() = if (chapters.isEmpty()) "" else "第 ${chapterIndex + 1}/${chapters.size} 章"
}

/**
 * 阅读器 ViewModel：加载书籍/章节、ReaderPosition 恢复、防抖进度保存、
 * 切章、目录与设置面板状态、设置读写。
 *
 * @param appScope 应用级作用域，仅用于 onCleared 后仍需落地的写入
 *   （[flushPendingSave] 的收尾补存、[deleteBrokenBook]）。测试可用默认值。
 */
class ReaderViewModel(
    private val bookId: Long,
    private val bookRepository: BookRepository,
    private val bookTextStore: BookTextStore,
    private val settingsStore: SettingsStore,
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var saveJob: Job? = null

    /**
     * 尚未落库的最新位置。
     *
     * 防抖窗口内的位置只存在这里：进程被回收 / 用户立刻返回时，
     * 由 [flushPendingSave]（ON_STOP）与 [onCleared] 兜底写出，
     * 不再出现「滑一下立刻退出，最后 500ms 位置丢失」。
     */
    private var pendingSave: PendingSave? = null

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
        // 「空壳书」：章节索引与进度都还在，但 book.txt 不存在。
        // 典型成因是从云端备份恢复——备份规则刻意排除 files/books/（隐私 + 25MB 配额），
        // 于是书架/进度/书签都回来了，正文却没回来。必须显式告知，
        // 否则用户看到的是一页只有章节标题的空白，无从判断是文件丢了。
        if (!bookTextStore.hasText(book.dirName)) {
            _uiState.update {
                it.copy(
                    loading = false,
                    book = book,
                    chapters = chapters,
                    chapterIndex = index,
                    error = TEXT_MISSING_MESSAGE,
                    textMissing = true,
                )
            }
            return
        }
        val scan = paragraphsOf(book, chapters[index])
        // 文件在、这一章却读不出字：可能被截断或章节偏移错乱。
        // 与「文件丢失」区分开——这里不该引导用户删书。
        if (scan.texts.isEmpty() && chapters[index].charCount > 0L) {
            _uiState.update {
                it.copy(
                    loading = false,
                    book = book,
                    chapters = chapters,
                    chapterIndex = index,
                    error = CHAPTER_EMPTY_MESSAGE,
                    textMissing = false,
                )
            }
            return
        }
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
                restoreSeq = it.restoreSeq + 1,
                currentParagraph = paragraphIndex,
                progressPercent = ReaderProgress.percentOf(
                    cumulativeChars = chapters[index].cumulativeChars,
                    chapterCharOffset = ReaderProgress.chapterCharOffset(scan.starts, paragraphIndex),
                    totalChars = book.totalChars,
                ),
                error = null,
                textMissing = false,
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
        val pending = PendingSave(
            chapterIndex = state.chapterIndex,
            paragraphIndex = clamped,
            scrollOffset = pixelOffset.coerceAtLeast(0),
            state = state,
        )
        pendingSave = pending
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            if (pendingSave !== pending) return@launch // 已被更新的滚动位置取代
            pendingSave = null
            persist(pending)
        }
    }

    /**
     * 立即写出尚未落库的位置（不等防抖）。
     *
     * 调用点：宿主 Activity 进入 ON_STOP。此时 ViewModel 仍然存活，
     * 用 viewModelScope 即可；真正「退出即销毁」的场景由 [onCleared] 兜底。
     */
    fun flushPendingSave() {
        val pending = pendingSave ?: return
        pendingSave = null
        saveJob?.cancel()
        saveJob = viewModelScope.launch { persist(pending) }
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope 此刻已取消，必须换作用域，否则最后一次位置静默丢失
        val pending = pendingSave ?: return
        pendingSave = null
        appScope.launch { persist(pending) }
    }

    private suspend fun persist(pending: PendingSave) {
        val state = pending.state
        val book = state.book ?: return
        val chapter = state.chapters.getOrNull(pending.chapterIndex) ?: return
        val charOffset = ReaderProgress.chapterCharOffset(state.paragraphStarts, pending.paragraphIndex)
        val percent = ReaderProgress.percentOf(
            chapter.cumulativeChars,
            charOffset,
            book.totalChars,
        )
        bookRepository.saveReaderPosition(
            bookId = bookId,
            position = ReaderPosition(
                chapterIndex = pending.chapterIndex,
                paragraphIndex = pending.paragraphIndex,
                scrollOffset = pending.scrollOffset,
                charOffset = charOffset,
            ),
            progressPercent = percent,
        )
        _uiState.update { it.copy(progressPercent = percent) }
    }

    /** 一次待落库的位置快照（含当时的 UI 状态，避免异步读写错位）。 */
    private class PendingSave(
        val chapterIndex: Int,
        val paragraphIndex: Int,
        val scrollOffset: Int,
        val state: ReaderUiState,
    )

    // ---- 切章 ----

    fun nextChapter() {
        val state = _uiState.value
        if (state.chapterIndex >= state.chapters.lastIndex) return
        goToChapter(state.chapterIndex + 1)
    }

    fun previousChapter() {
        val state = _uiState.value
        if (state.chapterIndex <= 0) return
        goToChapter(state.chapterIndex - 1)
    }

    /**
     * 跳转章节；[paragraph] 供书签恢复使用（默认章首）。
     *
     * 不再对「目标 == 当前章」早退：书签常落在章首，
     * 而单段落章（无换行 TXT）里段下标恒为 0，早退会让跳转彻底失效。
     * 每次跳转都自增 [ReaderUiState.restoreSeq]，由 UI 消费成一次滚动指令。
     */
    fun goToChapter(index: Int, paragraph: Int = 0) {
        val state = _uiState.value
        if (state.loading || state.chapters.isEmpty()) return
        val target = index.coerceIn(0, state.chapters.lastIndex)
        val book = state.book ?: return
        if (!bookTextStore.hasText(book.dirName)) {
            _uiState.update {
                it.copy(tocVisible = false, error = TEXT_MISSING_MESSAGE, textMissing = true)
            }
            return
        }
        val scan = paragraphsOf(book, state.chapters[target])
        if (scan.texts.isEmpty() && state.chapters[target].charCount > 0L) {
            _uiState.update {
                it.copy(tocVisible = false, error = CHAPTER_EMPTY_MESSAGE, textMissing = false)
            }
            return
        }
        val targetParagraph = paragraph.coerceIn(0, maxOf(0, scan.texts.lastIndex))
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    chapterIndex = target,
                    paragraphs = scan.texts,
                    paragraphStarts = scan.starts,
                    initialParagraph = targetParagraph,
                    initialScrollOffset = 0,
                    restoreSeq = it.restoreSeq + 1,
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
            val current = _uiState.value
            pendingSave = null
            persist(
                PendingSave(
                    chapterIndex = target,
                    paragraphIndex = targetParagraph,
                    scrollOffset = 0,
                    state = current,
                ),
            )
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

    fun dismissError() = _uiState.update { it.copy(error = null, textMissing = false) }

    /**
     * 删除正文缺失的条目（错误页「删除该条目」）。
     *
     * 走 appScope：调用后界面立刻返回书架，ViewModel 随即销毁，
     * 用 viewModelScope 会写一半被取消，留下数据库有行、目录已删的烂账。
     */
    fun deleteBrokenBook() {
        val book = _uiState.value.book ?: return
        appScope.launch {
            bookRepository.deleteBook(book)
            bookTextStore.deleteBookDir(book.dirName)
        }
    }

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

        /** 正文缺失错误文案（书架恢复备份后最常见）。 */
        const val TEXT_MISSING_MESSAGE =
            "正文文件已丢失或损坏，无法阅读。\n删除该条目后可重新导入。"

        /** 正文在、本章却为空（截断/偏移错乱）：可重新解析，不必删书。 */
        const val CHAPTER_EMPTY_MESSAGE =
            "本章内容为空，可能章节索引已损坏。\n请回书架用「重新解析章节」后重试。"

        fun factory(container: AppContainer, bookId: Long): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ReaderViewModel(
                        bookId = bookId,
                        bookRepository = container.bookRepository,
                        bookTextStore = container.bookTextStore,
                        settingsStore = container.settingsStore,
                        appScope = container.appScope,
                    )
                }
            }
    }
}
