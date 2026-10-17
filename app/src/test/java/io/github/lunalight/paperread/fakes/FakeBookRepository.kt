package io.github.lunalight.paperread.fakes

import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.domain.model.ReaderPosition
import io.github.lunalight.paperread.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * [BookRepository] 的内存 fake：可配置返回值 + 记录调用。
 *
 * 观察流由 [booksFlow] / [bookmarksFlow] 驱动，测试用 [emitBooks] / [emitBookmarks] 推送新值。
 */
class FakeBookRepository : BookRepository {

    private val booksFlow = MutableStateFlow<List<Book>>(emptyList())
    private val bookmarksFlow = MutableStateFlow<List<Bookmark>>(emptyList())

    /** getBook / getChapters 的返回（按需配置）。 */
    var book: Book? = null
    var chapters: List<Chapter> = emptyList()

    /** findBySha256 命中时返回的已存在书籍（判重流程）。 */
    var duplicateOf: Book? = null

    /** saveImport 返回的书籍 id。 */
    var nextImportId: Long = 1L

    val savedPositions = mutableListOf<SavedPosition>()
    val replacedChapters = mutableListOf<ReplacedChapters>()
    val resetPositionBookIds = mutableListOf<Long>()
    val deletedBooks = mutableListOf<Book>()
    val addedBookmarks = mutableListOf<Bookmark>()
    val deletedBookmarkIds = mutableListOf<Long>()
    val clearedBookmarkBookIds = mutableListOf<Long>()
    val savedImports = mutableListOf<SavedImport>()

    data class SavedPosition(
        val bookId: Long,
        val position: ReaderPosition,
        val progressPercent: Float,
    )

    data class ReplacedChapters(val bookId: Long, val chapterCount: Int, val totalChars: Long)

    data class SavedImport(val book: Book, val chapterCount: Int)

    // ---- 流 ----

    fun emitBooks(books: List<Book>) {
        booksFlow.value = books
    }

    fun emitBookmarks(bookmarks: List<Bookmark>) {
        bookmarksFlow.value = bookmarks
    }

    override fun observeBooks(): Flow<List<Book>> = booksFlow

    override fun observeChapters(bookId: Long): Flow<List<Chapter>> =
        MutableStateFlow(chapters)

    override fun observeBookmarks(bookId: Long): Flow<List<Bookmark>> =
        bookmarksFlow.map { list -> list.filter { it.bookId == bookId } }

    // ---- 查询 ----

    override suspend fun getBook(id: Long): Book? = book

    override suspend fun getChapters(bookId: Long): List<Chapter> = chapters

    override suspend fun findBySha256(sha256: String): Book? = duplicateOf

    // ---- 写 ----

    override suspend fun saveImport(book: Book, chapters: List<Chapter>): Long {
        savedImports += SavedImport(book, chapters.size)
        return nextImportId
    }

    override suspend fun replaceChapters(bookId: Long, chapters: List<Chapter>, totalChars: Long) {
        replacedChapters += ReplacedChapters(bookId, chapters.size, totalChars)
    }

    override suspend fun resetReaderPosition(bookId: Long) {
        resetPositionBookIds += bookId
    }

    override suspend fun deleteBook(book: Book) {
        deletedBooks += book
    }

    override suspend fun saveReaderPosition(bookId: Long, position: ReaderPosition, progressPercent: Float) {
        savedPositions += SavedPosition(bookId, position, progressPercent)
    }

    // ---- 书签 ----

    override suspend fun addBookmark(bookmark: Bookmark): Long {
        addedBookmarks += bookmark
        return bookmark.id.takeIf { it != 0L } ?: (addedBookmarks.size.toLong())
    }

    override suspend fun deleteBookmark(id: Long) {
        deletedBookmarkIds += id
    }

    override suspend fun deleteBookmarksByBook(bookId: Long) {
        clearedBookmarkBookIds += bookId
    }
}
