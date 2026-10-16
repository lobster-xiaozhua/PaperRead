package io.github.lunalight.paperread.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeByBook(bookId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    suspend fun getByBook(bookId: Long): List<BookmarkEntity>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)
}
