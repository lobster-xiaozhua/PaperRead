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
import io.github.lunalight.paperread.reader.txt.ChapterIndexWriter
import io.github.lunalight.paperread.reader.txt.ChapterRef
import io.github.lunalight.paperread.reader.txt.ChapterSplitter
import io.github.lunalight.paperread.reader.txt.CharsetDetector
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import io.github.lunalight.paperread.reader.xml.XmlParsers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** 导入阶段（UI 进度条用）。 */
enum class ImportStage(val label: String) {
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

/** 导入准备结果。 */
sealed interface PrepareResult {
    /** 解析完成，等待确认。 */
    data class Success(val pending: PendingImport) : PrepareResult

    /** SHA-256 命中已存在书籍。 */
    data class Duplicate(val existing: Book) : PrepareResult

    /** 格式不支持（扩展名不在 txt/epub 内）。 */
    data class Unsupported(val reason: String) : PrepareResult

    /** 失败（空间不足 / DRM / 读取异常等），临时目录已清理。 */
    data class Failure(val message: String) : PrepareResult
}

/** 解析阶段产物：TXT 与 EPUB 分流后统一为「全文 + 章节区间」。 */
private data class ParseOutcome(
    val title: String,
    val author: String,
    val charsetName: String,
    val text: String,
    val refs: List<ChapterRef>,
)

/**
 * 导入流水线：
 * SAF 选文件 → 磁盘预检 → 流式拷贝 + SHA-256 → 去重 →
 * 解析（TXT：编码探测 + 章节切分；EPUB：OPF/spine/NCX + 正文提取）→
 * 双偏移索引写入 → [PendingImport] 待确认 → confirm 入库 / cancel 删目录。
 */
class BookImporter(
    private val context: Context,
    private val bookRepository: BookRepository,
    private val textStore: BookTextStore,
) {

    /**
     * 完整准备流程。失败时自动清理临时目录。
     *
     * @param onProgress 进度回调（调用线程为 IO）
     */
    suspend fun prepare(
        uri: Uri,
        strategy: SplitStrategy = SplitStrategy.CONSERVATIVE,
        onProgress: (ImportProgress) -> Unit = {},
    ): PrepareResult = withContext(Dispatchers.IO) {
        val meta = queryMeta(uri)
            ?: return@withContext PrepareResult.Failure("无法读取文件信息，请重试")
        val format = BookFormat.fromExtension(meta.displayName.substringAfterLast('.', ""))
            ?: return@withContext PrepareResult.Unsupported("仅支持 TXT / EPUB 格式")
        if (!precheckDisk(meta.sizeBytes)) {
            return@withContext PrepareResult.Failure("存储空间不足，无法导入")
        }

        val dirName = UUID.randomUUID().toString()
        val bookDir = textStore.bookDir(dirName)
        if (!bookDir.mkdirs()) {
            return@withContext PrepareResult.Failure("无法创建书籍目录")
        }
        try {
            // 1. 流式拷贝 + SHA-256
            onProgress(ImportProgress(ImportStage.COPYING, 0f))
            val sourceFile = File(bookDir, "source.${format.extension}")
            val sha256 = copyAndHash(uri, sourceFile) { fraction ->
                onProgress(ImportProgress(ImportStage.COPYING, fraction))
            }

            // 2. 去重
            onProgress(ImportProgress(ImportStage.CHECKING_DUPLICATE, 0.95f))
            bookRepository.findBySha256(sha256)?.let { existing ->
                bookDir.deleteRecursively()
                return@withContext PrepareResult.Duplicate(existing)
            }

            // 3. 解析（TXT / EPUB 分流）
            onProgress(ImportProgress(ImportStage.PARSING, 0.3f))
            val outcome = if (format == BookFormat.EPUB) {
                parseEpub(sourceFile)
            } else {
                onProgress(ImportProgress(ImportStage.DETECTING, 0.2f))
                val detection = CharsetDetector.detect(sampleOf(sourceFile))
                parseTxt(sourceFile, detection.displayName, strategy, meta.displayName)
            }

            // 4. 双偏移索引写入
            onProgress(ImportProgress(ImportStage.WRITING_INDEX, 0.8f))
            val entries = FileOutputStream(textStore.textFile(dirName)).use { out ->
                ChapterIndexWriter(out).write(outcome.text, outcome.refs)
            }
            val chapters = entries.toChapters()
            val title = outcome.title.ifBlank {
                meta.displayName.substringBeforeLast('.').ifBlank { "未命名书籍" }
            }
            val pending = PendingImport(
                dirName = dirName,
                displayName = meta.displayName,
                title = title,
                author = outcome.author,
                format = format,
                charsetName = outcome.charsetName,
                strategy = strategy,
                sha256 = sha256,
                fileSize = sourceFile.length(),
                totalChars = outcome.text.length.toLong(),
                chapters = chapters,
            )
            onProgress(ImportProgress(ImportStage.DONE, 1f))
            PrepareResult.Success(pending)
        } catch (e: EpubEncryptedException) {
            bookDir.deleteRecursively()
            PrepareResult.Failure(e.message ?: "该 EPUB 含 DRM 加密，无法导入")
        } catch (e: Exception) {
            bookDir.deleteRecursively()
            PrepareResult.Failure(e.message ?: "导入失败，请重试")
        }
    }

    /**
     * 结果页切换编码 / 切分策略后重新解析（不重新拷贝文件）。
     * EPUB 忽略编码/策略参数（其章节结构来自 OPF/spine）。
     * 返回新的 PendingImport；失败返回 null（目录已保留，可重试）。
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
            val outcome = if (pending.format == BookFormat.EPUB) {
                parseEpub(sourceFile)
            } else {
                parseTxt(sourceFile, charsetName, strategy, pending.displayName)
            }
            val entries = FileOutputStream(textStore.textFile(pending.dirName)).use { out ->
                ChapterIndexWriter(out).write(outcome.text, outcome.refs)
            }
            pending.copy(
                title = outcome.title.ifBlank { pending.title },
                author = outcome.author,
                charsetName = outcome.charsetName,
                strategy = strategy,
                totalChars = outcome.text.length.toLong(),
                chapters = entries.toChapters(),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 确认入库，返回书籍 id。 */
    suspend fun confirm(pending: PendingImport): Long = withContext(Dispatchers.IO) {
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
    fun cancel(pending: PendingImport) {
        textStore.deleteBookDir(pending.dirName)
    }

    /**
     * 书架「重新解析」：TXT 用新策略重新切分；EPUB 重新提取正文。
     * 重置阅读位置（章节语义已变）。成功返回 true。
     */
    suspend fun reparseBook(book: Book, strategy: SplitStrategy): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val sourceFile = File(
                    textStore.bookDir(book.dirName),
                    "source.${book.format.extension}",
                )
                val outcome = if (book.format == BookFormat.EPUB) {
                    parseEpub(sourceFile)
                } else {
                    parseTxt(sourceFile, book.encoding, strategy, book.title)
                }
                val entries = FileOutputStream(textStore.textFile(book.dirName)).use { out ->
                    ChapterIndexWriter(out).write(outcome.text, outcome.refs)
                }
                val chapters = entries.toChapters().map { it.copy(bookId = book.id) }
                bookRepository.replaceChapters(book.id, chapters, outcome.text.length.toLong())
                bookRepository.resetReaderPosition(book.id)
                true
            } catch (e: Exception) {
                false
            }
        }

    // ---- 解析分流 ----

    private fun parseTxt(
        sourceFile: File,
        charsetName: String,
        strategy: SplitStrategy,
        displayName: String,
    ): ParseOutcome {
        val charset = CharsetDetector.byDisplayName(charsetName) ?: Charsets.UTF_8
        val text = ChapterSplitter.normalizeLineEndings(sourceFile.readText(charset))
        val refs = ChapterSplitter.split(text, strategy)
        return ParseOutcome(
            // 标题取原始文件名（source.txt 只是内部转存名）
            title = displayName.substringBeforeLast('.').ifBlank { "未命名书籍" },
            author = "未知",
            charsetName = charsetName,
            text = text,
            refs = refs,
        )
    }

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

    private fun List<io.github.lunalight.paperread.reader.txt.ChapterIndexEntry>.toChapters(): List<Chapter> =
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
        sourceFile.readBytes().let {
            if (it.size > CharsetDetector.SAMPLE_SIZE) it.copyOf(CharsetDetector.SAMPLE_SIZE) else it
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

    /** 流式拷贝并计算 SHA-256，返回十六进制摘要。 */
    private fun copyAndHash(uri: Uri, target: File, onProgress: (Float) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val total = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else 0L
            } else 0L
        } ?: 0L
        var copied = 0L
        context.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "无法打开文件流" }
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    copied += read
                    if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                }
                out.flush()
            }
        }
        onProgress(1f)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
