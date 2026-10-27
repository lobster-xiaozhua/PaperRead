package io.github.lunalight.paperread.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.lunalight.paperread.domain.model.Chapter

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val index: Int,
    val title: String,
    val charStart: Long,
    val charEnd: Long,
    val byteStart: Long,
    val byteEnd: Long,
    val charCount: Long,
    val cumulativeChars: Long,
)

fun ChapterEntity.toDomain(): Chapter = Chapter(
    id = id,
    bookId = bookId,
    index = index,
    title = title,
    charStart = charStart,
    charEnd = charEnd,
    byteStart = byteStart,
    byteEnd = byteEnd,
    charCount = charCount,
    cumulativeChars = cumulativeChars,
)

fun Chapter.toEntity(): ChapterEntity = ChapterEntity(
    id = id,
    bookId = bookId,
    index = index,
    title = title,
    charStart = charStart,
    charEnd = charEnd,
    byteStart = byteStart,
    byteEnd = byteEnd,
    charCount = charCount,
    cumulativeChars = cumulativeChars,
)
