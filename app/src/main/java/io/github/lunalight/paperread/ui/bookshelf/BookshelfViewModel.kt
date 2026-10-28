package io.github.lunalight.paperread.ui.bookshelf

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.lunalight.paperread.data.repository.BookImporter
import io.github.lunalight.paperread.data.repository.ImportError
import io.github.lunalight.paperread.data.repository.ImportStage
import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.data.repository.PrepareResult
import io.github.lunalight.paperread.di.AppContainer
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import io.github.lunalight.paperread.ui.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 书架排序方式（T-022：只带 `@StringRes`，文案在 `strings.xml`）。 */
enum class SortOrder(@param:StringRes val labelRes: Int) {
    RECENTLY_READ(R.string.sort_recently_read),
    ADDED(R.string.sort_added),
    TITLE(R.string.sort_title),
    PROGRESS(R.string.sort_progress),
}

/** 导入相关 UI 状态（进度 / 结果待确认 / 提示消息）。 */
data class ImportUiState(
    val running: Boolean = false,
    val stageLabel: String = "",
    val fraction: Float = 0f,
    val pending: PendingImport? = null,
)

data class BookshelfUiState(
    val books: List<Book> = emptyList(),
    val sortOrder: SortOrder = SortOrder.RECENTLY_READ,
    val loading: Boolean = true,
    val import: ImportUiState = ImportUiState(),
    /** 重新解析对话框目标（非空时展示）。 */
    val reparseTarget: Book? = null,
    /** 判重对话框目标（SHA-256 命中，非空时展示「替换 / 取消」，AC-1.5）。 */
    val duplicateTarget: Book? = null,
    /** 一次性提示（Snackbar）；T-022：VM 不持有 Context，携带字符串资源 id。 */
    val message: UiText? = null,
)

class BookshelfViewModel(
    private val bookRepository: BookRepository,
    private val bookImporter: BookImporter,
    private val bookTextStore: BookTextStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookshelfUiState())
    val uiState: StateFlow<BookshelfUiState> = _uiState.asStateFlow()

    /** 进行中的导入任务（可取消，AC-1.4）。 */
    private var importJob: Job? = null

    /** 最近一次导入来源与策略（判重「替换」时使用）。 */
    private var lastImportUri: android.net.Uri? = null
    private var lastImportStrategy: SplitStrategy = SplitStrategy.CONSERVATIVE

    init {
        viewModelScope.launch {
            bookRepository.observeBooks().collect { books ->
                _uiState.update { state ->
                    state.copy(books = sortBooks(books, state.sortOrder), loading = false)
                }
            }
        }
    }

    // ---- 导入 ----

    fun importBook(uri: android.net.Uri, strategy: SplitStrategy = SplitStrategy.CONSERVATIVE) {
        if (_uiState.value.import.running) return
        lastImportUri = uri
        lastImportStrategy = strategy
        launchImport(uri, strategy)
    }

    private fun launchImport(uri: android.net.Uri, strategy: SplitStrategy) {
        importJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    import = ImportUiState(running = true, stageLabel = ImportStage.COPYING.label),
                )
            }
            try {
                val result = bookImporter.prepare(uri, strategy) { progress ->
                    _uiState.update {
                        it.copy(
                            import = it.import.copy(
                                stageLabel = progress.stage.label,
                                fraction = progress.fraction,
                            ),
                        )
                    }
                }
                _uiState.update { state ->
                    when (result) {
                        is PrepareResult.Success -> state.copy(
                            import = ImportUiState(pending = result.pending),
                        )

                        is PrepareResult.Duplicate -> state.copy(
                            import = ImportUiState(),
                            duplicateTarget = result.existing,
                        )

                        // 导入失败话术来自 :core:data（ImportError.userMessage，要参与
                        // 迁移 / 同步序列化），原样透传为 Plain，不资源化（见
                        // scripts/strings-baseline.txt 的数据层例外登记）。
                        is PrepareResult.Unsupported -> state.copy(
                            import = ImportUiState(),
                            message = UiText.Plain(result.error.userMessage),
                        )

                        is PrepareResult.Failure -> state.copy(
                            import = ImportUiState(),
                            message = UiText.Plain(result.error.userMessage),
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户取消导入（AC-1.4）：导入器已清理半成品目录
                _uiState.update {
                    it.copy(
                        import = ImportUiState(),
                        message = UiText.res(R.string.bookshelf_import_canceled),
                    )
                }
                throw e
            }
        }
    }

    /** 取消进行中的导入（进度对话框外点 / 返回键，AC-1.4）。 */
    fun cancelRunningImport() {
        importJob?.cancel()
    }

    /** 判重对话框：取消（AC-1.5）。 */
    fun dismissDuplicate() {
        _uiState.update { it.copy(duplicateTarget = null) }
    }

    /**
     * 判重对话框：替换（AC-1.5）。
     *
     * SHA-256 命中意味着所选文件与旧书的 source 逐字节相同，因此「替换」默认走
     * **原地重新解析**，而不是「先删旧书再重新导入」：后者一旦第二次导入失败，
     * 旧书连同正文一起没了，没有任何回滚手段。
     * [BookImporter.reparseBook] 走「临时文件 + 成功后替换」，失败时书籍保持原状。
     *
     * 唯一例外：旧书连 source 都没了（云备份恢复后只剩数据库）。
     * 此时无可回滚内容，改用「删记录 + 重新导入」把书真正补回来。
     */
    fun replaceDuplicate() {
        val existing = _uiState.value.duplicateTarget ?: return
        val uri = lastImportUri ?: return
        _uiState.update { it.copy(duplicateTarget = null) }
        importJob = viewModelScope.launch {
            _uiState.update {
                it.copy(import = ImportUiState(running = true, stageLabel = ImportStage.PARSING.label))
            }
            try {
                val error = bookImporter.reparseBook(existing, lastImportStrategy)
                when {
                    error == null -> _uiState.update {
                        it.copy(
                            import = ImportUiState(),
                            message = UiText.res(R.string.bookshelf_replace_done, existing.title),
                        )
                    }

                    error is ImportError.SourceMissing -> {
                        bookRepository.deleteBook(existing)
                        bookTextStore.deleteBookDir(existing.dirName)
                        _uiState.update {
                            it.copy(
                                import = ImportUiState(),
                                message = UiText.res(R.string.bookshelf_reimporting, existing.title),
                            )
                        }
                        launchImport(uri, lastImportStrategy)
                    }

                    else -> _uiState.update {
                        it.copy(import = ImportUiState(), message = UiText.Plain(error.userMessage))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消发生在替换生效之前：book.txt 未被改写，旧书保持可用
                _uiState.update {
                    it.copy(
                        import = ImportUiState(),
                        message = UiText.res(R.string.bookshelf_replace_canceled),
                    )
                }
                throw e
            }
        }
    }

    /** 结果页切换编码 / 切分策略后重新解析。 */
    fun reparsePending(charsetName: String, strategy: SplitStrategy) {
        val pending = _uiState.value.import.pending ?: return
        viewModelScope.launch {
            val reparsed = bookImporter.reparse(pending, charsetName, strategy)
            _uiState.update { state ->
                if (reparsed == null) {
                    state.copy(message = UiText.res(R.string.bookshelf_reparse_failed))
                } else {
                    state.copy(import = state.import.copy(pending = reparsed))
                }
            }
        }
    }

    fun confirmImport() {
        val pending = _uiState.value.import.pending ?: return
        viewModelScope.launch {
            bookImporter.confirm(pending)
            _uiState.update {
                it.copy(
                    import = ImportUiState(),
                    message = UiText.res(R.string.bookshelf_import_done, pending.title),
                )
            }
        }
    }

    fun cancelImport() {
        val pending = _uiState.value.import.pending ?: return
        bookImporter.cancel(pending)
        _uiState.update { it.copy(import = ImportUiState()) }
    }

    fun dismissImport() {
        _uiState.value.import.pending?.let { bookImporter.cancel(it) }
        _uiState.update { it.copy(import = ImportUiState()) }
    }

    // ---- 书架管理 ----

    fun setSortOrder(order: SortOrder) {
        _uiState.update { state ->
            state.copy(sortOrder = order, books = sortBooks(state.books, order))
        }
    }

    fun requestReparse(book: Book) {
        _uiState.update { it.copy(reparseTarget = book) }
    }

    fun dismissReparse() {
        _uiState.update { it.copy(reparseTarget = null) }
    }

    fun reparseBook(strategy: SplitStrategy) {
        val book = _uiState.value.reparseTarget ?: return
        _uiState.update { it.copy(reparseTarget = null) }
        viewModelScope.launch {
            val error = bookImporter.reparseBook(book, strategy)
            _uiState.update {
                it.copy(
                    message = error?.let { UiText.Plain(it.userMessage) }
                        ?: UiText.res(R.string.bookshelf_reparsed_done, book.title),
                )
            }
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            bookRepository.deleteBook(book)
            bookTextStore.deleteBookDir(book.dirName)
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    // ---- 内部 ----

    private fun sortBooks(books: List<Book>, order: SortOrder): List<Book> = when (order) {
        SortOrder.RECENTLY_READ -> books.sortedWith(
            compareByDescending<Book> { it.lastReadAt ?: 0L }.thenByDescending { it.addedAt },
        )

        SortOrder.ADDED -> books.sortedByDescending { it.addedAt }
        SortOrder.TITLE -> books.sortedBy { it.title }
        SortOrder.PROGRESS -> books.sortedByDescending { it.progressPercent }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                BookshelfViewModel(
                    bookRepository = container.bookRepository,
                    bookImporter = container.bookImporter,
                    bookTextStore = container.bookTextStore,
                )
            }
        }
    }
}
