package io.github.lunalight.paperread.backup

import io.github.lunalight.paperread.data.repository.BookImportGateway
import io.github.lunalight.paperread.data.repository.ImportHints
import io.github.lunalight.paperread.data.repository.ImportOutcome
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.domain.model.ReaderPosition
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.fakes.FakeBookTextStore
import io.github.lunalight.paperread.fakes.testBook
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 备份单测用的**内存仓储**：具备真实存储语义（saveImport 分配 id、
 * findBySha256 能查到、书签按书写入），而不是只记录调用的 fake。
 *
 * 理由：备份恢复的断言是「恢复后数据与导出前一致」，需要仓储能真正「装下」
 * 恢复出来的书；只记录调用的 fake 会让端到端比对退化成「调用过某方法」。
 */
class MemoryBookRepository(initial: List<Book> = emptyList()) : BookRepository {

    private val booksFlow = MutableStateFlow(initial)
    private val bookmarksFlow = MutableStateFlow<Map<Long, List<Bookmark>>>(emptyMap())
    private var nextBookId: Long = (initial.maxOfOrNull { it.id } ?: 0L) + 1L
    private var nextBookmarkId: Long = 1L

    data class SavedPosition(
        val bookId: Long,
        val position: ReaderPosition,
        val progressPercent: Float,
        /** 显式传入的「上次阅读时间」（null = 调用方未指定，生产实现取 now）。 */
        val readAt: Long? = null,
    )

    val savedPositions = mutableListOf<SavedPosition>()
    val clearedBookmarkBookIds = mutableListOf<Long>()
    val addedBookmarks = mutableListOf<Bookmark>()

    fun nextId(): Long = nextBookId++

    /** 直接种入一本书（等价于导入完成后的库状态）。 */
    fun seed(book: Book) {
        booksFlow.value = booksFlow.value + book
    }

    fun seedBookmark(bookmark: Bookmark) {
        bookmarksFlow.value = bookmarksFlow.value.toMutableMap().apply {
            this[bookmark.bookId] = (this[bookmark.bookId] ?: emptyList()) + bookmark
        }
    }

    fun books(): List<Book> = booksFlow.value

    fun bookmarksOf(bookId: Long): List<Bookmark> = bookmarksFlow.value[bookId] ?: emptyList()

    override fun observeBooks(): Flow<List<Book>> = booksFlow

    override suspend fun getBook(id: Long): Book? = booksFlow.value.firstOrNull { it.id == id }

    override suspend fun getChapters(bookId: Long): List<Chapter> = emptyList()

    override fun observeChapters(bookId: Long): Flow<List<Chapter>> = MutableStateFlow(emptyList())

    override suspend fun saveImport(book: Book, chapters: List<Chapter>): Long {
        val id = nextId()
        seed(book.copy(id = id, chapterCount = chapters.size.coerceAtLeast(book.chapterCount)))
        return id
    }

    override suspend fun replaceChapters(bookId: Long, chapters: List<Chapter>, totalChars: Long) = Unit

    override suspend fun resetReaderPosition(bookId: Long) = Unit

    override suspend fun deleteBook(book: Book) {
        booksFlow.value = booksFlow.value.filterNot { it.id == book.id }
    }

    override suspend fun saveReaderPosition(
        bookId: Long,
        position: ReaderPosition,
        progressPercent: Float,
        readAt: Long?,
    ) {
        savedPositions += SavedPosition(bookId, position, progressPercent, readAt)
    }

    override suspend fun findBySha256(sha256: String): Book? =
        booksFlow.value.firstOrNull { it.sha256 == sha256 }

    override fun observeBookmarks(bookId: Long): Flow<List<Bookmark>> =
        bookmarksFlow.map { it[bookId] ?: emptyList() }

    override suspend fun addBookmark(bookmark: Bookmark): Long {
        val id = nextBookmarkId++
        val stored = bookmark.copy(id = id)
        addedBookmarks += stored
        seedBookmark(stored)
        return id
    }

    override suspend fun deleteBookmark(id: Long) {
        bookmarksFlow.value = bookmarksFlow.value.mapValues { (_, marks) ->
            marks.filterNot { it.id == id }
        }
    }

    override suspend fun updateBookmarkNote(id: Long, note: String, updatedAt: Long) {
        bookmarksFlow.value = bookmarksFlow.value.mapValues { (_, marks) ->
            marks.map { if (it.id == id) it.copy(note = note, updatedAt = updatedAt) else it }
        }
    }

    override suspend fun deleteBookmarksByBook(bookId: Long) {
        clearedBookmarkBookIds += bookId
        bookmarksFlow.value = bookmarksFlow.value - bookId
    }
}

/**
 * 导入网关 fake：不跑真实解析，只按配置返回结局并记录元数据提示。
 *
 * 真实网关（`AndroidBookImportGateway`）的行为由
 * `AndroidBookImportGatewayTest` 与 `MigrationFlowTest` 覆盖；这里关注
 * 「恢复编排是否正确使用网关结果」。
 */
class FakeImportGateway(
    private val failingSha: Set<String> = emptySet(),
    private val idBase: Long = 100L,
) : BookImportGateway {

    val importedHints = mutableListOf<ImportHints?>()
    val importedFiles = mutableListOf<File>()

    override suspend fun importFromUri(uri: android.net.Uri, hints: ImportHints?): ImportOutcome =
        ImportOutcome.Skipped("测试未使用 Uri 路径")

    override suspend fun importFromFile(file: File, hints: ImportHints?): ImportOutcome {
        importedFiles += file
        importedHints += hints
        if (sha256Hex(file.readBytes()) in failingSha) {
            return ImportOutcome.Skipped("模拟导入失败")
        }
        return ImportOutcome.Imported(
            Book(
                id = idBase + importedFiles.size,
                title = hints?.title.orEmpty().ifBlank { "导入书" },
                author = hints?.author.orEmpty(),
                format = BookFormat.TXT,
                encoding = "UTF-8",
                sha256 = sha256Hex(file.readBytes()),
                fileSize = file.length(),
                dirName = "restored-${importedFiles.size}",
                totalChars = file.length(),
                chapterCount = 1,
                lastReadAt = null,
                addedAt = 0L,
            ),
        )
    }
}

/**
 * 种一本「源文件与申请哈希一致」的书（恢复时的 SHA-256 校验必须通过，
 * 因此哈希由真实文件内容计算，不能用占位字符串）。
 */
internal fun seedBook(
    repository: MemoryBookRepository,
    textStore: FakeBookTextStore,
    dirName: String,
    title: String,
    content: String = "正文内容：$title",
    author: String = "作者",
    lastReadAt: Long? = 100L,
    progressPercent: Float = 0.5f,
    lastChapterIndex: Int = 2,
    lastParagraphIndex: Int = 3,
    lastCharOffset: Int = 0,
    bookmarks: List<Bookmark> = emptyList(),
): Book {
    val dir = textStore.bookDir(dirName).apply { mkdirs() }
    val source = File(dir, "source.txt").apply { writeText(content) }
    val id = repository.nextId()
    val book = testBook(
        id = id,
        title = title,
        author = author,
        sha256 = sha256Hex(source.readBytes()),
        dirName = dirName,
        lastReadAt = lastReadAt,
        progressPercent = progressPercent,
        lastChapterIndex = lastChapterIndex,
        lastParagraphIndex = lastParagraphIndex,
        lastCharOffset = lastCharOffset,
        fileSize = source.length(),
        totalChars = content.length.toLong(),
        chapterCount = 1,
    )
    repository.seed(book)
    bookmarks.forEach { repository.seedBookmark(it.copy(bookId = id, id = 0L)) }
    return book
}
