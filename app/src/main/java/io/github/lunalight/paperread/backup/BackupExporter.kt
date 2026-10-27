package io.github.lunalight.paperread.backup

import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 本地备份导出（T-015 / US-14，R-13）。
 *
 * 流程：读书架 → 逐本读书签 → 组装 manifest（含内容摘要）→ 写 zip
 * （manifest + 每本的 state + 源文件）。
 *
 * **导出内容口径**（AC-14.1）：
 * - 全部书籍的**源文件**（`books/<dirName>/source.<ext>`）；
 * - 全部阅读进度（`lastChapterIndex` / `progressPercent` / `lastReadAt` 等）；
 * - 全部书签（含备注字段，保真）；
 * - 字体**引用**（AC-14.9：不打包字体文件）；
 * - **不导出** `book.txt`：它是导入流水线从源文件派生的 UTF-8 正文 + 章节索引，
 *   恢复时由 `PreparePipeline` 按需重建（AC-14.4）。打包它会让备份体积近似
 *   翻倍，且两份数据可能不一致（源文件才是权威）。
 *
 * 取消与失败语义：导出只写调用方给的临时文件；任何异常 / 取消都会删除该
 * 临时文件（**不留半成品**），SAF 目标文件由调用方在成功后才写入。
 */
class BackupExporter(
    private val bookRepository: BookRepository,
    private val bookTextStore: BookTextStore,
    /** 当前引用的字体（AC-14.9：记录名字，不打包文件）。 */
    private val fontReferencesProvider: suspend () -> List<BackupFontReference> = { emptyList() },
    /** 备份包记录的应用版本（排障用，非敏感）。 */
    private val appVersion: () -> String = { "" },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * 导出前预览（AC-14.8）：书籍数 / 进度数 / 书签数 / 字体引用 / 总字节数。
     *
     * 只读仓储与文本库，不落盘。
     */
    suspend fun preview(): BackupPreview = withContext(Dispatchers.IO) {
        val prepared = collect()
        BackupPreview(
            bookCount = prepared.size,
            progressCount = prepared.count { it.state.lastReadAt != null },
            bookmarkCount = prepared.sumOf { it.state.bookmarks.size },
            fontReferences = fontReferencesProvider(),
            totalSourceBytes = prepared.sumOf { it.book.fileSize },
        )
    }

    /**
     * 导出到 [target]（调用方提供临时文件路径）。
     *
     * @param password 非空 = 口令加密（AC-14.2），长度 ≥ [BACKUP_MIN_PASSWORD_LENGTH]
     * @param onProgress 进度回调（IO 线程）
     * @param shouldContinue 取消探针（与协程取消双通道，测试可确定性地中途停）
     * @return 实际写入的 manifest（调用方可用于展示计数）
     */
    suspend fun export(
        target: File,
        password: CharArray? = null,
        onProgress: (BackupProgress) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
    ): BackupManifest = withContext(Dispatchers.IO) {
        require(password == null || BackupCrypto.isValidPassword(password)) {
            "备份口令至少 $BACKUP_MIN_PASSWORD_LENGTH 位"
        }
        target.parentFile?.mkdirs()

        onProgress(BackupProgress(stage = BackupStage.COLLECTING))
        val prepared = collect { done, total, title ->
            onProgress(
                BackupProgress(
                    stage = BackupStage.COLLECTING,
                    done = done,
                    total = total,
                    fraction = if (total == 0) 0f else 0.05f * done / total,
                    currentTitle = title,
                ),
            )
        }
        val encrypted = password != null
        val createdAt = clock()
        val fontReferences = fontReferencesProvider()
        val manifest = buildManifest(prepared, encrypted, createdAt, fontReferences)
        onProgress(
            BackupProgress(
                stage = BackupStage.MANIFEST,
                total = prepared.size,
                fraction = 0.05f,
            ),
        )

        val crypto = password?.let { BackupCrypto.create(it) }
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
                BackupWriter.writeBytes(
                    zip = zip,
                    name = BackupEntries.MANIFEST,
                    bytes = backupJson.encodeToString(BackupManifest.serializer(), manifest)
                        .toByteArray(Charsets.UTF_8),
                    crypto = null, // manifest 始终明文：AC-14.8 恢复前可预览
                    time = createdAt,
                )
                if (crypto != null) {
                    BackupWriter.writeBytes(
                        zip = zip,
                        name = BackupEntries.CRYPTO,
                        bytes = backupJson.encodeToString(BackupCryptoParams.serializer(), crypto.params)
                            .toByteArray(Charsets.UTF_8),
                        crypto = null,
                        time = createdAt,
                    )
                }

                prepared.forEachIndexed { index, item ->
                    currentCoroutineContext().ensureActive()
                    if (!shouldContinue()) throw CancellationException("已取消备份")
                    val entry = manifest.books[index]
                    BackupWriter.writeBytes(
                        zip = zip,
                        name = entry.stateEntry,
                        bytes = backupJson.encodeToString(BackupBookState.serializer(), item.state)
                            .toByteArray(Charsets.UTF_8),
                        crypto = crypto,
                        time = createdAt,
                    )
                    val sourceFile = item.sourceFile
                    if (!entry.sourceMissing && sourceFile != null) {
                        BackupWriter.writeSource(
                            zip = zip,
                            name = entry.sourceEntry,
                            source = sourceFile,
                            crypto = crypto,
                            time = createdAt,
                        )
                    }
                    val done = index + 1
                    onProgress(
                        BackupProgress(
                            stage = BackupStage.BOOKS,
                            done = done,
                            total = prepared.size,
                            fraction = if (prepared.isEmpty()) 1f else 0.05f + 0.95f * done / prepared.size,
                            currentTitle = item.book.title,
                        ),
                    )
                }
            }
            onProgress(
                BackupProgress(
                    stage = BackupStage.DONE,
                    done = prepared.size,
                    total = prepared.size,
                    fraction = 1f,
                ),
            )
            manifest
        } catch (e: Throwable) {
            // 失败 / 取消都不留半成品（SAF 目标此时还没被写过）
            target.delete()
            throw e
        } finally {
            crypto?.close()
        }
    }

    // ---- 内部 ----

    /** 收集阶段的中间产物：书籍 + 阅读状态 + 源文件（可能已丢失）。 */
    private data class PreparedBook(
        val book: Book,
        val state: BackupBookState,
        val sourceFile: File?,
    )

    private suspend fun collect(
        onCollect: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): List<PreparedBook> {
        val books = bookRepository.observeBooks().first()
        return books.mapIndexed { index, book ->
            onCollect(index, books.size, book.title)
            val bookmarks = bookRepository.observeBookmarks(book.id).first()
            val source = bookTextStore.sourceFile(book)
            PreparedBook(
                book = book,
                state = BackupBookState(
                    title = book.title,
                    author = book.author,
                    encoding = book.encoding,
                    fileSize = book.fileSize,
                    totalChars = book.totalChars,
                    chapterCount = book.chapterCount,
                    lastChapterIndex = book.lastChapterIndex,
                    lastParagraphIndex = book.lastParagraphIndex,
                    lastScrollOffset = book.lastScrollOffset,
                    lastCharOffset = book.lastCharOffset,
                    progressPercent = book.progressPercent,
                    lastReadAt = book.lastReadAt,
                    addedAt = book.addedAt,
                    bookmarks = bookmarks.map { mark ->
                        BackupBookmark(
                            chapterIndex = mark.chapterIndex,
                            paragraphIndex = mark.paragraphIndex,
                            charOffset = mark.charOffset,
                            excerpt = mark.excerpt,
                            createdAt = mark.createdAt,
                            note = mark.note,
                            updatedAt = mark.updatedAt,
                        )
                    },
                ),
                sourceFile = source.takeIf { it.isFile },
            )
        }.also { result -> onCollect(result.size, result.size, "") }
    }

    private fun buildManifest(
        prepared: List<PreparedBook>,
        encrypted: Boolean,
        createdAt: Long,
        fontReferences: List<BackupFontReference>,
    ): BackupManifest {
        val entries = prepared.map { item ->
            val book = item.book
            BackupBookEntry(
                dirName = book.dirName,
                format = book.format.name,
                sourceEntry = BackupEntries.sourceEntry(book.dirName, book.format.extension),
                sourceSha256 = book.sha256,
                sourceSize = item.sourceFile?.length() ?: book.fileSize,
                sourceMissing = item.sourceFile == null,
                stateEntry = BackupEntries.stateEntry(book.dirName),
                // 加密备份不在明文 manifest 里暴露书名 / 作者
                title = if (encrypted) null else book.title,
                author = if (encrypted) null else book.author,
            )
        }
        return BackupManifest(
            version = BACKUP_VERSION,
            encrypted = encrypted,
            createdAt = createdAt,
            appVersion = appVersion(),
            bookCount = entries.size,
            progressCount = prepared.count { it.state.lastReadAt != null },
            bookmarkCount = prepared.sumOf { it.state.bookmarks.size },
            fontReferences = fontReferences,
            books = entries,
            contentDigest = backupContentDigest(entries),
        )
    }
}
