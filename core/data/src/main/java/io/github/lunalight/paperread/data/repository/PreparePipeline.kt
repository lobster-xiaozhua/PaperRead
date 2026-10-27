package io.github.lunalight.paperread.data.repository

import android.net.Uri
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookDecodeException
import io.github.lunalight.paperread.reader.BookTextStore
import io.github.lunalight.paperread.reader.ParseContext
import io.github.lunalight.paperread.reader.ParsePhase
import io.github.lunalight.paperread.reader.ParseResult
import io.github.lunalight.paperread.reader.epub.EpubEncryptedException
import io.github.lunalight.paperread.reader.txt.ChapterIndexEntry
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 导入流水线（T-012 从 `DefaultBookImporter` 拆出，行为逐行搬迁）：
 *
 * 预检 → 流式拷贝 + SHA-256（[ImportSource]）→ 去重 →
 * 解析（[BookParserRegistry] 按格式分发）→ [PendingImport] 待确认；
 * 另含两条非首次导入路径：结果页重新解析（[reparse]）与书架重新解析（[reparseBook]）。
 *
 * 三层职责边界（拆分的意义所在）：
 * - [ImportSource]：SAF / 磁盘 / 摘要——唯一接触 `Context` 的地方；
 * - [PreparePipeline]（本类）：阶段编排、进度上报、错误翻译、目录生命周期；
 * - [BookParser] 实现：某格式「源文件 → 正文 + 章节索引」的解析细节。
 *
 * 错误语义（B7）：拷贝期异常 → [ImportError.ReadFailed]；BOM 编码失败 →
 * [ImportError.DecodeFailed]；EPUB DRM → [ImportError.DrmEncrypted]；
 * 其余解析期异常 → [ImportError.ParseFailed]；取消照常传播且清理半成品目录。
 *
 * 所有方法都在 `Dispatchers.IO` 上执行，失败时自动清理临时目录。
 */
class PreparePipeline(
    private val bookRepository: BookRepository,
    private val textStore: BookTextStore,
    private val source: ImportSource,
    private val parsers: BookParserRegistry,
    /**
     * 失败外报（T-021 · AC-19.4）：每次产出 [ImportError] 都在这里登记一条，
     * 供「设置 → 关于 → 导出诊断信息」回溯最近几次导入为什么失败。
     * 默认空实现，不影响任何既有调用方。
     */
    private val errorRecorder: ImportErrorRecorder = ImportErrorRecorder.NONE,
) {

    /**
     * 完整准备流程。失败时自动清理临时目录。
     *
     * @param onProgress 进度回调（调用线程为 IO）
     */
    suspend fun prepare(
        uri: Uri,
        strategy: SplitStrategy,
        onProgress: (ImportProgress) -> Unit,
    ): PrepareResult = withContext(Dispatchers.IO) {
        onProgress(ImportProgress(ImportStage.PRECHECK, 0f))
        val meta = source.queryMeta(uri)
            ?: return@withContext fail(ImportError.ReadFailed("无法查询文件信息"))
        val extension = meta.displayName.substringAfterLast('.', "")
        val format = BookFormat.fromExtension(extension)
            ?: return@withContext unsupported(
                ImportError.UnsupportedFormat(extension.ifBlank { "(无扩展名)" }),
            )
        if (!source.precheckDisk(meta.sizeBytes)) {
            return@withContext fail(ImportError.InsufficientSpace)
        }

        val dirName = UUID.randomUUID().toString()
        val bookDir = textStore.bookDir(dirName)
        if (!bookDir.mkdirs()) {
            return@withContext fail(ImportError.DirCreateFailed("mkdirs 失败：${bookDir.path}"))
        }
        try {
            // 阶段间响应取消（AC-1.4：导入保持可取消）
            currentCoroutineContext().ensureActive()
            // 1. 流式拷贝 + SHA-256（拷贝循环内响应取消）
            onProgress(ImportProgress(ImportStage.COPYING, 0f))
            val sourceFile = File(bookDir, "source.${format.extension}")
            // 拷贝期失败（打开流 / 读取 / 写临时文件）统一归为读取失败；
            // 解析期的 IOException 由外层通用 catch 归为解析失败
            val sha256 = try {
                source.copyAndHash(uri, sourceFile) { fraction ->
                    onProgress(ImportProgress(ImportStage.COPYING, fraction))
                }
            } catch (e: CancellationException) {
                throw e // 取消照常传播，由外层统一清理目录
            } catch (e: Exception) {
                bookDir.deleteRecursively()
                return@withContext fail(ImportError.ReadFailed(e.message))
            }

            // 2. 去重
            onProgress(ImportProgress(ImportStage.CHECKING_DUPLICATE, 0.95f))
            bookRepository.findBySha256(sha256)?.let { existing ->
                bookDir.deleteRecursively()
                return@withContext PrepareResult.Duplicate(existing)
            }

            // 3. 解析（具体格式由注册表分发：TXT 流式 / EPUB 全量提取）
            currentCoroutineContext().ensureActive()
            val parsed = parse(
                format = format,
                sourceFile = sourceFile,
                dirName = dirName,
                targetFile = textStore.textFile(dirName),
                displayName = meta.displayName,
                strategy = strategy,
                charsetOverride = null,
                titleOverride = null,
                shouldContinue = { isActive },
                onPhase = { phase -> onProgress(phase.toProgress()) },
                onProgress = { fraction ->
                    onProgress(ImportProgress(ImportStage.PARSING, PARSING_BASE + PARSING_SPAN * fraction))
                },
            )

            val pending = PendingImport(
                dirName = dirName,
                displayName = meta.displayName,
                title = parsed.title.ifBlank {
                    meta.displayName.substringBeforeLast('.').ifBlank { "未命名书籍" }
                },
                author = parsed.author,
                format = format,
                charsetName = parsed.charsetName,
                strategy = strategy,
                sha256 = sha256,
                fileSize = sourceFile.length(),
                totalChars = parsed.totalChars,
                chapters = parsed.entries.toChapters(),
            )
            onProgress(ImportProgress(ImportStage.DONE, 1f))
            PrepareResult.Success(pending)
        } catch (e: CancellationException) {
            // 用户取消（AC-1.4）：清理半成品目录后照常传播取消
            bookDir.deleteRecursively()
            throw e
        } catch (e: BookDecodeException) {
            bookDir.deleteRecursively()
            fail(ImportError.DecodeFailed(e.encoding))
        } catch (e: EpubEncryptedException) {
            bookDir.deleteRecursively()
            fail(ImportError.DrmEncrypted(e.message))
        } catch (e: Exception) {
            // 解析期失败（结构非法 / IO 异常 / 断言失败）统一归为解析失败
            bookDir.deleteRecursively()
            fail(ImportError.ParseFailed(e.message))
        }
    }

    /**
     * 结果页切换编码 / 切分策略后重新解析（不重新拷贝文件）。
     * EPUB 忽略编码 / 策略参数（其章节结构来自 OPF/spine）。
     *
     * @return 新的 [PendingImport]；失败返回 null（目录已保留，可重试）
     */
    suspend fun reparse(
        pending: PendingImport,
        charsetName: String,
        strategy: SplitStrategy,
    ): PendingImport? = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(
                textStore.bookDir(pending.dirName),
                "source.${pending.format.extension}",
            )
            val isTxt = pending.format == BookFormat.TXT
            val parsed = parse(
                format = pending.format,
                sourceFile = sourceFile,
                dirName = pending.dirName,
                targetFile = textStore.textFile(pending.dirName),
                displayName = pending.displayName,
                strategy = strategy,
                // EPUB 忽略编码 / 策略：其章节结构与编码来自包内元数据
                charsetOverride = if (isTxt) charsetName else null,
                titleOverride = if (isTxt) pending.title else null,
                shouldContinue = { isActive },
            )
            pending.copy(
                title = parsed.title.ifBlank { pending.title },
                author = parsed.author,
                charsetName = parsed.charsetName,
                strategy = strategy,
                totalChars = parsed.totalChars,
                chapters = parsed.entries.toChapters(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 书架「重新解析」：TXT 用新策略重新切分；EPUB 重新提取正文。
     * 章节语义已变：重置阅读位置并清空书签（否则旧书签跳到错位内容）。
     *
     * book.txt 采用「临时文件 + 成功后替换」写入：解析失败或取消时原文件保持可用。
     *
     * @return 成功返回 null；失败返回结构化的 [ImportError]（书籍保持原状，可重试）
     */
    suspend fun reparseBook(book: Book, strategy: SplitStrategy): ImportError? =
        withContext(Dispatchers.IO) {
            try {
                val sourceFile = File(
                    textStore.bookDir(book.dirName),
                    "source.${book.format.extension}",
                )
                if (!sourceFile.exists()) {
                    return@withContext ImportError.SourceMissing(book.dirName).also {
                        errorRecorder.record(it)
                    }
                }
                val isTxt = book.format == BookFormat.TXT
                val parsed = parse(
                    format = book.format,
                    sourceFile = sourceFile,
                    dirName = book.dirName,
                    targetFile = textStore.textFile(book.dirName),
                    displayName = null,
                    strategy = strategy,
                    charsetOverride = if (isTxt) book.encoding else null,
                    titleOverride = book.title,
                    shouldContinue = { isActive },
                )
                val chapters = parsed.entries.toChapters().map { it.copy(bookId = book.id) }
                bookRepository.replaceChapters(book.id, chapters, parsed.totalChars)
                bookRepository.resetReaderPosition(book.id)
                bookRepository.deleteBookmarksByBook(book.id)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: EpubEncryptedException) {
                ImportError.DrmEncrypted(e.message).also { errorRecorder.record(it) }
            } catch (e: Exception) {
                ImportError.ParseFailed(e.message).also { errorRecorder.record(it) }
            }
        }

    // ---- 内部 ----

    /** 产出失败结果并登记（AC-19.4）：所有失败出口都必须经过它，避免漏记。 */
    private fun fail(error: ImportError): PrepareResult {
        errorRecorder.record(error)
        return PrepareResult.Failure(error)
    }

    /** 格式不支持也是「导入失败」的一种（诊断包里要给用户看到这条）。 */
    private fun unsupported(error: ImportError.UnsupportedFormat): PrepareResult {
        errorRecorder.record(error)
        return PrepareResult.Unsupported(error)
    }

    /** 组装 [ParseContext] 并按格式取解析器（注册表未命中即编程错误，fail fast）。 */
    private fun parse(
        format: BookFormat,
        sourceFile: File,
        dirName: String,
        targetFile: File,
        displayName: String?,
        strategy: SplitStrategy,
        charsetOverride: String?,
        titleOverride: String?,
        shouldContinue: () -> Boolean,
        onPhase: (ParsePhase) -> Unit = {},
        onProgress: (Float) -> Unit = {},
    ): ParseResult = parsers.forFormat(format).parse(
        ParseContext(
            sourceFile = sourceFile,
            targetFile = targetFile,
            dirName = dirName,
            strategy = strategy,
            displayName = displayName,
            charsetOverride = charsetOverride,
            titleOverride = titleOverride,
            onPhase = onPhase,
            onProgress = onProgress,
            shouldContinue = shouldContinue,
        ),
    )

    /**
     * 解析阶段提示 → UI 进度（阶段 + 该阶段的起始百分比）。
     *
     * 数值与拆分前逐字节一致：探测编码 20%、开始解析 30%、写索引 80%；
     * TXT 的解析进度由 [ParseContext.onProgress] 线性映射到 20%~80%。
     */
    private fun ParsePhase.toProgress(): ImportProgress = when (this) {
        ParsePhase.DETECTING -> ImportProgress(ImportStage.DETECTING, 0.2f)
        ParsePhase.PARSING -> ImportProgress(ImportStage.PARSING, 0.3f)
        ParsePhase.WRITING_INDEX -> ImportProgress(ImportStage.WRITING_INDEX, 0.8f)
    }

    private fun List<ChapterIndexEntry>.toChapters(): List<Chapter> =
        map { entry ->
            Chapter(
                bookId = 0L,
                index = entry.index,
                title = entry.title,
                charStart = entry.charStart,
                charEnd = entry.charEnd,
                byteStart = entry.byteStart,
                byteEnd = entry.byteEnd,
                charCount = entry.charCount,
                cumulativeChars = entry.cumulativeChars,
            )
        }

    private companion object {
        /** TXT 流式解析进度的起点（与探测阶段衔接）。 */
        const val PARSING_BASE = 0.2f

        /** TXT 流式解析进度占的区间宽度（0.2 → 0.8）。 */
        const val PARSING_SPAN = 0.6f
    }
}
