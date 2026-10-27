package io.github.lunalight.paperread.data.repository

import io.github.lunalight.paperread.data.local.BookDao
import io.github.lunalight.paperread.data.local.BookmarkDao
import io.github.lunalight.paperread.data.local.ChapterDao
import io.github.lunalight.paperread.data.local.toDomain
import io.github.lunalight.paperread.data.local.toEntity
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.domain.model.ReaderPosition
import io.github.lunalight.paperread.domain.repository.BookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BookRepositoryImpl(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val bookmarkDao: BookmarkDao,
) : BookRepository {

    override fun observeBooks(): Flow<List<Book>> =
        bookDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getBook(id: Long): Book? = bookDao.getById(id)?.toDomain()

    override suspend fun getChapters(bookId: Long): List<Chapter> =
        chapterDao.getByBook(bookId).map { it.toDomain() }

    override fun observeChapters(bookId: Long): Flow<List<Chapter>> =
        chapterDao.observeByBook(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun saveImport(book: Book, chapters: List<Chapter>): Long {
        val id = bookDao.insert(book.toEntity())
        chapterDao.insertAll(chapters.map { it.toEntity().copy(bookId = id) })
        return id
    }

    override suspend fun replaceChapters(bookId: Long, chapters: List<Chapter>, totalChars: Long) {
        chapterDao.deleteByBook(bookId)
        chapterDao.insertAll(chapters.map { it.toEntity().copy(bookId = bookId) })
        bookDao.updateIndex(bookId, chapters.size, totalChars)
    }

    override suspend fun resetReaderPosition(bookId: Long) {
        bookDao.resetReaderPosition(bookId)
    }

    override suspend fun deleteBook(book: Book) {
        bookDao.delete(book.toEntity())
        chapterDao.deleteByBook(book.id)
        bookmarkDao.deleteByBook(book.id)
    }

    override suspend fun saveReaderPosition(
        bookId: Long,
        position: ReaderPosition,
        progressPercent: Float,
        readAt: Long?,
    ) {
        bookDao.saveReaderPosition(
            id = bookId,
            chapterIndex = position.chapterIndex,
            paragraphIndex = position.paragraphIndex,
            scrollOffset = position.scrollOffset,
            charOffset = position.charOffset,
            progressPercent = progressPercent,
            // F-01：readAt 非空 = 调用方要求还原历史时间戳（备份恢复）；为空 = now，
            // 与修复前的行为逐字节一致（正常阅读 / 迁移 / 同步）。
            readAt = readAt ?: System.currentTimeMillis(),
        )
    }

    override suspend fun findBySha256(sha256: String): Book? =
        bookDao.findBySha256(sha256)?.toDomain()

    // ---- 书签 ----

    override fun observeBookmarks(bookId: Long): Flow<List<Bookmark>> =
        bookmarkDao.observeByBook(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun addBookmark(bookmark: Bookmark): Long =
        bookmarkDao.insert(bookmark.toEntity())

    override suspend fun deleteBookmark(id: Long) {
        bookmarkDao.deleteById(id)
    }

    override suspend fun updateBookmarkNote(id: Long, note: String, updatedAt: Long) {
        bookmarkDao.updateNote(id, note, updatedAt)
    }

    override suspend fun deleteBookmarksByBook(bookId: Long) {
        bookmarkDao.deleteByBook(bookId)
    }
}
