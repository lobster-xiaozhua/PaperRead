package io.github.lunalight.paperread.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    /** BookFormat.name */
    val format: String,
    val encoding: String,
    val sha256: String,
    val fileSize: Long,
    val dirName: String,
    val totalChars: Long,
    val chapterCount: Int,
    val lastChapterIndex: Int,
    val lastParagraphIndex: Int,
    val lastScrollOffset: Int,
    val lastCharOffset: Int,
    val progressPercent: Float,
    val lastReadAt: Long?,
    val addedAt: Long,
)

fun BookEntity.toDomain(): Book = Book(
    id = id,
    title = title,
    author = author,
    format = BookFormat.valueOf(format),
    encoding = encoding,
    sha256 = sha256,
    fileSize = fileSize,
    dirName = dirName,
    totalChars = totalChars,
    chapterCount = chapterCount,
    lastChapterIndex = lastChapterIndex,
    lastParagraphIndex = lastParagraphIndex,
    lastScrollOffset = lastScrollOffset,
    lastCharOffset = lastCharOffset,
    progressPercent = progressPercent,
    lastReadAt = lastReadAt,
    addedAt = addedAt,
)

fun Book.toEntity(): BookEntity = BookEntity(
    id = id,
    title = title,
    author = author,
    format = format.name,
    encoding = encoding,
    sha256 = sha256,
    fileSize = fileSize,
    dirName = dirName,
    totalChars = totalChars,
    chapterCount = chapterCount,
    lastChapterIndex = lastChapterIndex,
    lastParagraphIndex = lastParagraphIndex,
    lastScrollOffset = lastScrollOffset,
    lastCharOffset = lastCharOffset,
    progressPercent = progressPercent,
    lastReadAt = lastReadAt,
    addedAt = addedAt,
)
