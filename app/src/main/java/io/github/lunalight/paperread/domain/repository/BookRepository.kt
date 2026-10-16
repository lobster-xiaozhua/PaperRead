package io.github.lunalight.paperread.domain.repository

import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.domain.model.ReaderPosition
import kotlinx.coroutines.flow.Flow

/** 书籍仓储接口（UI 层只依赖此接口）。 */
interface BookRepository {

    fun observeBooks(): Flow<List<Book>>

    suspend fun getBook(id: Long): Book?

    suspend fun getChapters(bookId: Long): List<Chapter>

    fun observeChapters(bookId: Long): Flow<List<Chapter>>

    /** 保存导入的书籍与章节，返回书籍 id。 */
    suspend fun saveImport(book: Book, chapters: List<Chapter>): Long

    /** 重新解析后整体替换章节并更新索引统计。 */
    suspend fun replaceChapters(bookId: Long, chapters: List<Chapter>, totalChars: Long)

    /** 重新解析后重置阅读位置。 */
    suspend fun resetReaderPosition(bookId: Long)

    suspend fun deleteBook(book: Book)

    suspend fun saveReaderPosition(bookId: Long, position: ReaderPosition, progressPercent: Float)

    suspend fun findBySha256(sha256: String): Book?

    // ---- 书签 ----

    /** 观察某书的书签（按创建时间倒序）。 */
    fun observeBookmarks(bookId: Long): Flow<List<Bookmark>>

    /** 添加书签，返回新书签 id。 */
    suspend fun addBookmark(bookmark: Bookmark): Long

    /** 删除书签。 */
    suspend fun deleteBookmark(id: Long)
}
