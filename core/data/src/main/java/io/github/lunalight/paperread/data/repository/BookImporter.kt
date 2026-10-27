package io.github.lunalight.paperread.data.repository

import android.content.Context
import android.net.Uri
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导入流水线（UI 层只依赖本接口，单测以 fake 替代）：
 * SAF 选文件 → 磁盘预检 → 流式拷贝 + SHA-256 → 去重 →
 * 解析（TXT 流式切分直写 book.txt；EPUB：OPF/spine/NCX + 正文提取）→
 * [PendingImport] 待确认 → confirm 入库 / cancel 删目录。
 *
 * TXT 路径全程流式：内存占用与文件大小无关（滚动缓冲 ≤ 256KB），
 * 200 MB 级文件不再有 OOM 风险；EPUB 因需先解出 spine 结构仍走全量提取。
 *
 * 实现拆分（T-012）：本接口的实现 [DefaultBookImporter] 只做门面编排，
 * 流水线在 [PreparePipeline]，格式解析在 [BookParser][io.github.lunalight.paperread.reader.BookParser]
 * 实现（经 [BookParserRegistry] 分发）；纯数据契约（[ImportStage] / [PendingImport] /
 * [ImportError] / [PrepareResult]）住在 `ImportModels.kt`。
 */
interface BookImporter {

    /**
     * 完整准备流程。失败时自动清理临时目录。
     *
     * @param onProgress 进度回调（调用线程为 IO）
     */
    suspend fun prepare(
        uri: Uri,
        strategy: SplitStrategy = SplitStrategy.CONSERVATIVE,
        onProgress: (ImportProgress) -> Unit = {},
    ): PrepareResult

    /**
     * 结果页切换编码 / 切分策略后重新解析（不重新拷贝文件）。
     * EPUB 忽略编码/策略参数（其章节结构来自 OPF/spine）。
     * 返回新的 PendingImport；失败返回 null（目录已保留，可重试）。
     */
    suspend fun reparse(
        pending: PendingImport,
        charsetName: String,
        strategy: SplitStrategy,
    ): PendingImport?

    /** 确认入库，返回书籍 id。 */
    suspend fun confirm(pending: PendingImport): Long

    /** 取消导入：删除临时目录。 */
    fun cancel(pending: PendingImport)

    /**
     * 书架「重新解析」：TXT 用新策略重新切分；EPUB 重新提取正文。
     * 章节语义已变：重置阅读位置并清空书签（否则旧书签跳到错位内容）。
     *
     * @return 成功返回 null；失败返回结构化的 [ImportError]（书籍保持原状，可重试）。
     */
    suspend fun reparseBook(book: Book, strategy: SplitStrategy): ImportError?
}

/**
 * [BookImporter] 的默认实现：**门面**（T-012 起 ≤ 100 行）。
 *
 * 这里只保留「一次导入的边界」——准备 / 重新解析 / 确认入库 / 取消：
 * - 流水线（预检、拷贝、去重、解析编排、错误翻译）委托 [PreparePipeline]；
 * - 各格式的解析细节委托 [BookParserRegistry] 选出的 `BookParser` 实现；
 * - 本类自身只剩「入库」这一步（[confirm]）与「删目录」（[cancel]）。
 *
 * 构造签名刻意保持不变：`AppContainer`、流程单测与 fake 都以它为准。
 */
class DefaultBookImporter(
    context: Context,
    private val bookRepository: BookRepository,
    private val textStore: BookTextStore,
) : BookImporter {

    private val pipeline = PreparePipeline(
        bookRepository = bookRepository,
        textStore = textStore,
        source = ImportSource(context),
        parsers = BookParserRegistry.defaults(),
    )

    override suspend fun prepare(
        uri: Uri,
        strategy: SplitStrategy,
        onProgress: (ImportProgress) -> Unit,
    ): PrepareResult = pipeline.prepare(uri, strategy, onProgress)

    override suspend fun reparse(
        pending: PendingImport,
        charsetName: String,
        strategy: SplitStrategy,
    ): PendingImport? = pipeline.reparse(pending, charsetName, strategy)

    /** 确认入库：把待确认快照落成书 + 章节（章节 bookId 由仓储回填）。 */
    override suspend fun confirm(pending: PendingImport): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val book = Book(
            title = pending.title,
            author = pending.author,
            format = pending.format,
            encoding = pending.charsetName,
            sha256 = pending.sha256,
            fileSize = pending.fileSize,
            dirName = pending.dirName,
            totalChars = pending.totalChars,
            chapterCount = pending.chapters.size,
            addedAt = now,
        )
        bookRepository.saveImport(book, pending.chapters)
    }

    /** 取消导入：删除临时目录。 */
    override fun cancel(pending: PendingImport) {
        textStore.deleteBookDir(pending.dirName)
    }

    override suspend fun reparseBook(book: Book, strategy: SplitStrategy): ImportError? =
        pipeline.reparseBook(book, strategy)
}
