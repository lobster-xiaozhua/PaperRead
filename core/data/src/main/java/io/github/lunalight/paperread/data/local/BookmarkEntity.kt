package io.github.lunalight.paperread.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.lunalight.paperread.domain.model.Bookmark

/**
 * 书签实体（schema v2，T-006 · R-06 / G6）。
 *
 * [note] / [updatedAt] 为 v1→v2 AutoMigration 新增列。Room 要求新增
 * NOT NULL 列必须经 [ColumnInfo.defaultValue] 显式声明默认值（否则
 * AutoMigration 无法为存量行生成回填值），默认值与 v1 数据的兼容语义
 * 一致：note = ""、updatedAt = 0。
 */
@Entity(
    tableName = "bookmarks",
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
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val charOffset: Int,
    val excerpt: String,
    val createdAt: Long,
    /** 备注正文（R-18 前置字段；v2 默认空串）。 */
    @ColumnInfo(defaultValue = "''") val note: String = "",
    /** 备注最后修改时间（R-18 前置字段；v2 默认 0）。 */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0L,
)

fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    paragraphIndex = paragraphIndex,
    charOffset = charOffset,
    excerpt = excerpt,
    createdAt = createdAt,
    note = note,
    updatedAt = updatedAt,
)

fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    paragraphIndex = paragraphIndex,
    charOffset = charOffset,
    excerpt = excerpt,
    createdAt = createdAt,
    note = note,
    updatedAt = updatedAt,
)
