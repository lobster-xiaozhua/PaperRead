package io.github.lunalight.paperread.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findBySha256(sha256: String): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Delete
    suspend fun delete(book: BookEntity)

    @Query(
        """
        UPDATE books SET
            lastChapterIndex = :chapterIndex,
            lastParagraphIndex = :paragraphIndex,
            lastScrollOffset = :scrollOffset,
            lastCharOffset = :charOffset,
            progressPercent = :progressPercent,
            lastReadAt = :readAt
        WHERE id = :id
        """,
    )
    suspend fun saveReaderPosition(
        id: Long,
        chapterIndex: Int,
        paragraphIndex: Int,
        scrollOffset: Int,
        charOffset: Int,
        progressPercent: Float,
        readAt: Long,
    )

    @Query("UPDATE books SET chapterCount = :chapterCount, totalChars = :totalChars WHERE id = :id")
    suspend fun updateIndex(id: Long, chapterCount: Int, totalChars: Long)

    /** 重新解析后重置阅读位置（章节语义已变化，旧位置无意义）。 */
    @Query(
        """
        UPDATE books SET
            lastChapterIndex = 0,
            lastParagraphIndex = 0,
            lastScrollOffset = 0,
            lastCharOffset = 0,
            progressPercent = 0.0,
            lastReadAt = NULL
        WHERE id = :id
        """,
    )
    suspend fun resetReaderPosition(id: Long)
}
