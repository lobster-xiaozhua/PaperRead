package io.github.lunalight.paperread.ui.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.lunalight.paperread.data.repository.BookImporter
import io.github.lunalight.paperread.data.repository.ImportStage
import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.data.repository.PrepareResult
import io.github.lunalight.paperread.di.AppContainer
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 书架排序方式。 */
enum class SortOrder(val label: String) {
    RECENTLY_READ("最近阅读"),
    ADDED("最近导入"),
    TITLE("书名"),
    PROGRESS("进度"),
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
    /** 一次性提示（Snackbar）。 */
    val message: String? = null,
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

    /** 最近一次导入参数（判重「替换」时重跑）。 */
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

                        is PrepareResult.Unsupported -> state.copy(
                            import = ImportUiState(),
                            message = result.reason,
                        )

                        is PrepareResult.Failure -> state.copy(
                            import = ImportUiState(),
                            message = result.message,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户取消导入（AC-1.4）：导入器已清理半成品目录
                _uiState.update {
                    it.copy(import = ImportUiState(), message = "已取消导入")
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

    /** 判重对话框：替换——删除旧书后用原参数重新导入（AC-1.5）。 */
    fun replaceDuplicate() {
        val existing = _uiState.value.duplicateTarget ?: return
        val uri = lastImportUri ?: return
        _uiState.update { it.copy(duplicateTarget = null) }
        viewModelScope.launch {
            bookRepository.deleteBook(existing)
            bookTextStore.deleteBookDir(existing.dirName)
            _uiState.update { it.copy(message = "已替换《${existing.title}》，正在导入") }
            launchImport(uri, lastImportStrategy)
        }
    }

    /** 结果页切换编码 / 切分策略后重新解析。 */
    fun reparsePending(charsetName: String, strategy: SplitStrategy) {
        val pending = _uiState.value.import.pending ?: return
        viewModelScope.launch {
            val reparsed = bookImporter.reparse(pending, charsetName, strategy)
            _uiState.update { state ->
                if (reparsed == null) {
                    state.copy(message = "重新解析失败，请重试")
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
                it.copy(import = ImportUiState(), message = "已导入《${pending.title}》")
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
                    message = error ?: "已按新策略重新解析《${book.title}》（进度与书签已重置）",
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
