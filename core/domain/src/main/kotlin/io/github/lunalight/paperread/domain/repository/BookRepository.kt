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

    /**
     * 保存阅读进度。
     *
     * @param readAt 「上次阅读时间」的落库值。默认 `null` = 调用那一刻（`now`），
     *   即正常阅读 / 进度同步的语义；**备份恢复**必须传归档里的原始时间戳
     *   （F-01）——否则恢复一次就把本地时间抬到 now，此后恢复任何「时间戳较旧
     *   但确实更新」的备份都会被 `WriteBackDecider` 判成旧进度而静默跳过回写。
     * @param progressPercent 阅读百分比（0..100，调用方已按字符位置折算）
     */
    suspend fun saveReaderPosition(
        bookId: Long,
        position: ReaderPosition,
        progressPercent: Float,
        readAt: Long? = null,
    )

    suspend fun findBySha256(sha256: String): Book?

    // ---- 书签 ----

    /** 观察某书的书签（按创建时间倒序）。 */
    fun observeBookmarks(bookId: Long): Flow<List<Bookmark>>

    /** 添加书签，返回新书签 id。 */
    suspend fun addBookmark(bookmark: Bookmark): Long

    /** 删除书签。 */
    suspend fun deleteBookmark(id: Long)

    /**
     * 更新书签备注（R-18 / US-17；schema 与 DAO 槽位随 T-006 先行落地）。
     *
     * @param updatedAt 备注最后修改时间（跨设备冲突裁决取较新者，AC-17.7）。
     */
    suspend fun updateBookmarkNote(id: Long, note: String, updatedAt: Long)

    /** 清空某书全部书签（重新解析后章节语义已变，书签必然失配）。 */
    suspend fun deleteBookmarksByBook(bookId: Long)
}
