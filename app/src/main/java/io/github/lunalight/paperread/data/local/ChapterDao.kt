package io.github.lunalight.paperread.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index`")
    suspend fun getByBook(bookId: Long): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index`")
    fun observeByBook(bookId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND `index` = :index")
    suspend fun get(bookId: Long, index: Int): ChapterEntity?

    @Insert
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: Long)
}
