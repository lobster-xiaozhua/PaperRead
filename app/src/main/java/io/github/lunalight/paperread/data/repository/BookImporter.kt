package io.github.lunalight.paperread.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore
import io.github.lunalight.paperread.reader.epub.EpubEncryptedException
import io.github.lunalight.paperread.reader.epub.EpubParser
import io.github.lunalight.paperread.reader.txt.ChapterIndexEntry
import io.github.lunalight.paperread.reader.txt.ChapterIndexWriter
import io.github.lunalight.paperread.reader.txt.ChapterSplitter
import io.github.lunalight.paperread.reader.txt.CharsetDetector
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import io.github.lunalight.paperread.reader.txt.TxtChapterStream
import io.github.lunalight.paperread.reader.txt.TxtStreamResult
import io.github.lunalight.paperread.reader.xml.XmlParsers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.UUID

/** 导入阶段（UI 进度条用）。 */
enum class ImportStage(val label: String) {
    PRECHECK("检查文件"),
    COPYING("复制文件"),
    DETECTING("探测编码"),
    CHECKING_DUPLICATE("检查重复"),
    PARSING("解析章节"),
    WRITING_INDEX("写入索引"),
    SAVING("保存到书架"),
    DONE("完成"),
}

data class ImportProgress(val stage: ImportStage, val fraction: Float)

/**
 * 待确认导入：文件已落盘并完成解析，等待用户在结果页确认
 * （TXT 可切换编码 / 切分策略后重新解析，再入库）。
 *
 * @param chapters 章节列表（bookId 暂为 0，confirm 入库时回填）
 */
data class PendingImport(
    val dirName: String,
    val displayName: String,
    val title: String,
    val author: String,
    val format: BookFormat,
    val charsetName: String,
    val strategy: SplitStrategy,
    val sha256: String,
    val fileSize: Long,
    val totalChars: Long,
    val chapters: List<Chapter>,
)

/**
 * 结构化导入 / 解析失败原因（B7）。
 *
 * 每个子类携带三要素：
 * - [stage]：失败发生在流水线哪个阶段（定位断点）
 * - [cause]：底层原因（异常信息 / 触发值），供日志与排查
 * - [userMessage]：面向用户的提示话术，UI 层直接取用，不再自行拼文案
 *
 * 文案区分度即产品验收：DRM / 空间不足 / 编码失败等场景的话术两两不同。
 */
sealed class ImportError {

    abstract val stage: ImportStage
    abstract val cause: String?
    abstract val userMessage: String

    /** 扩展名不在 txt / epub 内（[ImportError.cause] 为扩展名）。 */
    data class UnsupportedFormat(val extension: String) : ImportError() {
        override val stage: ImportStage = ImportStage.PRECHECK
        override val cause: String get() = "extension=$extension"
        override val userMessage: String = "仅支持 TXT / EPUB 格式"
    }

    /** 磁盘预检不通过（剩余空间不足文件双份 + 20% 余量）。 */
    data object InsufficientSpace : ImportError() {
        override val stage: ImportStage = ImportStage.PRECHECK
        override val cause: String? = null
        override val userMessage: String = "存储空间不足，无法导入"
    }

    /** EPUB 含 DRM 加密（[ImportError.cause] 为底层异常信息）。 */
    data class DrmEncrypted(val detail: String?) : ImportError() {
        override val stage: ImportStage = ImportStage.PARSING
        override val cause: String? get() = detail
        override val userMessage: String = "该 EPUB 含 DRM 加密，无法导入"
    }

    /**
     * 文件读取失败：打开文件流 / 拷贝读取异常（SAF 权限失效、文件被移动删除等，
     * [ImportError.cause] 为异常信息）。仅覆盖拷贝期；解析期的 IO 异常归 [ParseFailed]。
     */
    data class ReadFailed(val detail: String?) : ImportError() {
        override val stage: ImportStage = ImportStage.COPYING
        override val cause: String? get() = detail
        override val userMessage: String = "无法读取文件，请重试"
    }

    /** 书籍目录创建失败（[ImportError.cause] 为异常信息）。 */
    data class DirCreateFailed(val detail: String?) : ImportError() {
        override val stage: ImportStage = ImportStage.COPYING
        override val cause: String? get() = detail
        override val userMessage: String = "无法创建书籍目录，请重试"
    }

    /** BOM 声明的编码无法严格解码（[ImportError.cause] 为编码名）。 */
    data class DecodeFailed(val encoding: String) : ImportError() {
        override val stage: ImportStage = ImportStage.DETECTING
        override val cause: String get() = "encoding=$encoding"
        override val userMessage: String = "文件编码异常，请转换编码后重试"
    }

    /** 章节解析失败：结构非法 / 内容异常 / 拷贝读取异常（[ImportError.cause] 为异常信息）。 */
    data class ParseFailed(val detail: String?) : ImportError() {
        override val stage: ImportStage = ImportStage.PARSING
        override val cause: String? get() = detail
        override val userMessage: String = "文件解析失败，请重试"
    }

    /** 重新解析时源文件丢失（仅 reparseBook 产出，[ImportError.cause] 为书籍目录名）。 */
    data class SourceMissing(val dirName: String) : ImportError() {
        override val stage: ImportStage = ImportStage.PARSING
        override val cause: String get() = "dir=$dirName"
        override val userMessage: String = "源文件已丢失，请删除后重新导入"
    }
}

/** 导入准备结果。 */
sealed interface PrepareResult {
    /** 解析完成，等待确认。 */
    data class Success(val pending: PendingImport) : PrepareResult

    /** SHA-256 命中已存在书籍。 */
    data class Duplicate(val existing: Book) : PrepareResult

    /** 格式不支持（扩展名不在 txt/epub 内）。 */
    data class Unsupported(val error: ImportError.UnsupportedFormat) : PrepareResult

    /** 失败（空间不足 / DRM / 编码异常 / 解析异常等），临时目录已清理。 */
    data class Failure(val error: ImportError) : PrepareResult
}

/** 解析阶段产物（EPUB 分流：OPF/spine 解析出的全文 + 章节区间）。 */
private data class ParseOutcome(
    val title: String,
    val author: String,
    val charsetName: String,
    val text: String,
    val refs: List<io.github.lunalight.paperread.reader.txt.ChapterRef>,
)

/**
 * 导入流水线（UI 层只依赖本接口，单测以 fake 替代）：
 * SAF 选文件 → 磁盘预检 → 流式拷贝 + SHA-256 → 去重 →
 * 解析（TXT：[TxtChapterStream] 流式解码切分直写 book.txt；EPUB：OPF/spine/NCX + 正文提取）→
 * [PendingImport] 待确认 → confirm 入库 / cancel 删目录。
 *
 * TXT 路径全程流式：内存占用与文件大小无关（滚动缓冲 ≤ 256KB），
 * 200 MB 级文件不再有 OOM 风险；EPUB 因需先解出 spine 结构仍走全量提取。
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

/** [BookImporter] 的默认实现（真实导入流水线）。 */
class DefaultBookImporter(
    private val context: Context,
    private val bookRepository: BookRepository,
    private val textStore: BookTextStore,
) : BookImporter {

    /**
     * 完整准备流程。失败时自动清理临时目录。
     *
     * @param onProgress 进度回调（调用线程为 IO）
     */
    override suspend fun prepare(
        uri: Uri,
        strategy: SplitStrategy,
        onProgress: (ImportProgress) -> Unit,
    ): PrepareResult = withContext(Dispatchers.IO) {
        onProgress(ImportProgress(ImportStage.PRECHECK, 0f))
        val meta = queryMeta(uri)
            ?: return@withContext PrepareResult.Failure(ImportError.ReadFailed("无法查询文件信息"))
        val extension = meta.displayName.substringAfterLast('.', "")
        val format = BookFormat.fromExtension(extension)
            ?: return@withContext PrepareResult.Unsupported(
                ImportError.UnsupportedFormat(extension.ifBlank { "(无扩展名)" }),
            )
        if (!precheckDisk(meta.sizeBytes)) {
            return@withContext PrepareResult.Failure(ImportError.InsufficientSpace)
        }

        val dirName = UUID.randomUUID().toString()
        val bookDir = textStore.bookDir(dirName)
        if (!bookDir.mkdirs()) {
            return@withContext PrepareResult.Failure(
                ImportError.DirCreateFailed("mkdirs 失败：${bookDir.path}"),
            )
        }
        try {
            // 阶段间响应取消（AC-1.4：导入保持可取消）
            currentCoroutineContext().ensureActive()
            // 1. 流式拷贝 + SHA-256（拷贝循环内响应取消）
            onProgress(ImportProgress(ImportStage.COPYING, 0f))
            val sourceFile = File(bookDir, "source.${format.extension}")
            // 拷贝期失败（打开流 / 读取 / 写临时文件）统一归为读取失败；
            // 解析期（parseEpub / streamTxt）的 IOException 由外层通用 catch 归为解析失败
            val sha256 = try {
                copyAndHash(uri, sourceFile) { fraction ->
                    onProgress(ImportProgress(ImportStage.COPYING, fraction))
                }
            } catch (e: CancellationException) {
                throw e // 取消照常传播，由外层统一清理目录
            } catch (e: Exception) {
                bookDir.deleteRecursively()
                return@withContext PrepareResult.Failure(ImportError.ReadFailed(e.message))
            }

            // 2. 去重
            onProgress(ImportProgress(ImportStage.CHECKING_DUPLICATE, 0.95f))
            bookRepository.findBySha256(sha256)?.let { existing ->
                bookDir.deleteRecursively()
                return@withContext PrepareResult.Duplicate(existing)
            }

            // 3. 解析（TXT 流式 / EPUB 分流）
            currentCoroutineContext().ensureActive()
            val entries: List<ChapterIndexEntry>
            val parsedTitle: String
            val parsedAuthor: String
            val parsedCharset: String
            val totalChars: Long
            if (format == BookFormat.EPUB) {
                onProgress(ImportProgress(ImportStage.PARSING, 0.3f))
                val outcome = parseEpub(sourceFile)
                onProgress(ImportProgress(ImportStage.WRITING_INDEX, 0.8f))
                entries = FileOutputStream(textStore.textFile(dirName)).use { out ->
                    ChapterIndexWriter(out).write(outcome.text, outcome.refs)
                }
                parsedTitle = outcome.title
                parsedAuthor = outcome.author
                parsedCharset = outcome.charsetName
                totalChars = outcome.text.length.toLong()
            } else {
                onProgress(ImportProgress(ImportStage.DETECTING, 0.2f))
                val sample = sampleOf(sourceFile)
                val detection = CharsetDetector.detect(sample)
                // BOM 声明了编码而样本无法按该编码严格解码 → 编码失败（转换编码后重试）；
                // 无 BOM 的探测结果（UTF-8 严格试解码失败 → GB18030 回退）是设计内容错路径，
                // 由流式解码 REPLACE 兜底，不在此拦截
                if (detection.bomLength > 0 &&
                    !CharsetDetector.decodesStrictly(sample, detection.charset)
                ) {
                    bookDir.deleteRecursively()
                    return@withContext PrepareResult.Failure(
                        ImportError.DecodeFailed(detection.displayName),
                    )
                }
                val result = streamTxt(
                    sourceFile = sourceFile,
                    charset = detection.charset,
                    bomLength = detection.bomLength,
                    strategy = strategy,
                    dirName = dirName,
                    shouldContinue = { isActive },
                ) { fraction ->
                    onProgress(ImportProgress(ImportStage.PARSING, 0.2f + 0.6f * fraction))
                }
                entries = result.entries
                totalChars = result.totalChars
                // 标题取原始文件名（source.txt 只是内部转存名）
                parsedTitle = meta.displayName.substringBeforeLast('.').ifBlank { "未命名书籍" }
                parsedAuthor = "未知"
                parsedCharset = detection.displayName
            }

            val title = parsedTitle.ifBlank {
                meta.displayName.substringBeforeLast('.').ifBlank { "未命名书籍" }
            }
            val pending = PendingImport(
                dirName = dirName,
                displayName = meta.displayName,
                title = title,
                author = parsedAuthor,
                format = format,
                charsetName = parsedCharset,
                strategy = strategy,
                sha256 = sha256,
                fileSize = sourceFile.length(),
                totalChars = totalChars,
                chapters = entries.toChapters(),
            )
            onProgress(ImportProgress(ImportStage.DONE, 1f))
            PrepareResult.Success(pending)
        } catch (e: CancellationException) {
            // 用户取消（AC-1.4）：清理半成品目录后照常传播取消
            bookDir.deleteRecursively()
            throw e
        } catch (e: EpubEncryptedException) {
            bookDir.deleteRecursively()
            PrepareResult.Failure(ImportError.DrmEncrypted(e.message))
        } catch (e: Exception) {
            // 解析期失败（结构非法 / IO 异常 / 断言失败）统一归为解析失败
            bookDir.deleteRecursively()
            PrepareResult.Failure(ImportError.ParseFailed(e.message))
        }
    }

    /**
     * 结果页切换编码 / 切分策略后重新解析（不重新拷贝文件）。
     * EPUB 忽略编码/策略参数（其章节结构来自 OPF/spine）。
     * 返回新的 PendingImport；失败返回 null（目录已保留，可重试）。
     */
    override suspend fun reparse(
        pending: PendingImport,
        charsetName: String,
        strategy: SplitStrategy,
    ): PendingImport? = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(
                textStore.bookDir(pending.dirName),
                "source.${pending.format.extension}",
            )
            val entries: List<ChapterIndexEntry>
            val title: String
            val author: String
            val charset: String
            val totalChars: Long
            if (pending.format == BookFormat.EPUB) {
                val outcome = parseEpub(sourceFile)
                entries = FileOutputStream(textStore.textFile(pending.dirName)).use { out ->
                    ChapterIndexWriter(out).write(outcome.text, outcome.refs)
                }
                title = outcome.title
                author = outcome.author
                charset = outcome.charsetName
                totalChars = outcome.text.length.toLong()
            } else {
                val cs = CharsetDetector.byDisplayName(charsetName) ?: Charsets.UTF_8
                val result = streamTxt(
                    sourceFile = sourceFile,
                    charset = cs,
                    bomLength = CharsetDetector.bomLengthOf(sampleOf(sourceFile)),
                    strategy = strategy,
                    dirName = pending.dirName,
                    shouldContinue = { isActive },
                ) {}
                entries = result.entries
                totalChars = result.totalChars
                title = pending.title
                author = "未知"
                charset = charsetName
            }
            pending.copy(
                title = title.ifBlank { pending.title },
                author = author,
                charsetName = charset,
                strategy = strategy,
                totalChars = totalChars,
                chapters = entries.toChapters(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** 确认入库，返回书籍 id。 */
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

    /**
     * 书架「重新解析」：TXT 用新策略重新切分；EPUB 重新提取正文。
     * 章节语义已变：重置阅读位置并清空书签（否则旧书签跳到错位内容）。
     *
     * book.txt 采用「临时文件 + 成功后替换」写入：解析失败或取消时原文件保持可用。
     *
     * @return 成功返回 null；失败返回结构化的 [ImportError]（书籍保持原状，可重试）。
     */
    override suspend fun reparseBook(book: Book, strategy: SplitStrategy): ImportError? =
        withContext(Dispatchers.IO) {
            try {
                val sourceFile = File(
                    textStore.bookDir(book.dirName),
                    "source.${book.format.extension}",
                )
                if (!sourceFile.exists()) {
                    return@withContext ImportError.SourceMissing(book.dirName)
                }
                val entries: List<ChapterIndexEntry>
                val totalChars: Long
                if (book.format == BookFormat.EPUB) {
                    val outcome = parseEpub(sourceFile)
                    entries = FileOutputStream(textStore.textFile(book.dirName)).use { out ->
                        ChapterIndexWriter(out).write(outcome.text, outcome.refs)
                    }
                    totalChars = outcome.text.length.toLong()
                } else {
                    val charset = CharsetDetector.byDisplayName(book.encoding) ?: Charsets.UTF_8
                    val result = streamTxt(
                        sourceFile = sourceFile,
                        charset = charset,
                        bomLength = CharsetDetector.bomLengthOf(sampleOf(sourceFile)),
                        strategy = strategy,
                        dirName = book.dirName,
                        shouldContinue = { isActive },
                    ) {}
                    entries = result.entries
                    totalChars = result.totalChars
                }
                val chapters = entries.toChapters().map { it.copy(bookId = book.id) }
                bookRepository.replaceChapters(book.id, chapters, totalChars)
                bookRepository.resetReaderPosition(book.id)
                bookRepository.deleteBookmarksByBook(book.id)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: EpubEncryptedException) {
                ImportError.DrmEncrypted(e.message)
            } catch (e: Exception) {
                ImportError.ParseFailed(e.message)
            }
        }

    // ---- 解析分流 ----

    private fun parseEpub(sourceFile: File): ParseOutcome {
        val content = EpubParser(XmlParsers::android).parse(sourceFile)
        return ParseOutcome(
            title = content.title,
            author = content.author,
            charsetName = "UTF-8", // 内部统一转存 UTF-8
            text = content.fullText,
            refs = content.refs,
        )
    }

    /**
     * TXT 流式解析：单遍解码 + 切分 + 写出 book.txt（经临时文件原子替换）。
     * 失败/取消时删除临时文件，原有 book.txt 不受影响。
     */
    private fun streamTxt(
        sourceFile: File,
        charset: Charset,
        bomLength: Int,
        strategy: SplitStrategy,
        dirName: String,
        shouldContinue: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): TxtStreamResult {
        val textFile = textStore.textFile(dirName)
        val tmp = File(textFile.parentFile, "book.txt.tmp")
        val result = try {
            FileOutputStream(tmp).use { out ->
                sourceFile.inputStream().use { input ->
                    TxtChapterStream(charset, strategy, bomLength).process(
                        input = input,
                        out = out,
                        totalBytes = sourceFile.length(),
                        shouldContinue = shouldContinue,
                        onProgress = onProgress,
                    )
                }
            }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        if (!tmp.renameTo(textFile)) {
            tmp.copyTo(textFile, overwrite = true)
            tmp.delete()
        }
        return result
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

    private fun sampleOf(sourceFile: File): ByteArray =
        sourceFile.inputStream().use { input ->
            val buffer = ByteArray(CharsetDetector.SAMPLE_SIZE)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n <= 0) break
                read += n
            }
            if (read == buffer.size) buffer else buffer.copyOf(read)
        }

    // ---- 内部 ----

    private data class FileMeta(val displayName: String, val sizeBytes: Long)

    private fun queryMeta(uri: Uri): FileMeta? {
        val fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: "book.txt"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    cursor.getString(nameIndex)
                } else {
                    fallbackName
                }
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                return FileMeta(name, size)
            }
        }
        // 某些 Provider 不支持 query（极少见）：退化用路径名，大小拷贝时统计
        return FileMeta(fallbackName, 0L)
    }

    private fun precheckDisk(sizeBytes: Long): Boolean {
        if (sizeBytes <= 0) return true
        // 用 filesDir（必然存在）评估空间；books/ 子目录首次导入时可能尚未创建
        // 预留 20% 余量：book.txt 与 source 双份存储 + 文件系统开销
        return context.filesDir.freeSpace > sizeBytes * 2.2
    }

    /** 流式拷贝并计算 SHA-256，返回十六进制摘要；每个缓冲区响应协程取消。 */
    private suspend fun copyAndHash(
        uri: Uri,
        target: File,
        onProgress: (Float) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val total = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else 0L
            } else 0L
        } ?: 0L
        var copied = 0L
        val input = context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("无法打开文件流")
        input.use {
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    copied += read
                    if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                    // 取消检查放在 IO 之后：取消时上层统一清理目录
                    currentCoroutineContext().ensureActive()
                }
                out.flush()
            }
        }
        onProgress(1f)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
