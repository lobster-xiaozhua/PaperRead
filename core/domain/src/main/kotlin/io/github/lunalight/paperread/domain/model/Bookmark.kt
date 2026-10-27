package io.github.lunalight.paperread.domain.model

/**
 * 书签。
 *
 * [note] / [updatedAt] 为 R-18（US-17 书签备注）预留字段，schema 随
 * T-006（R-06 / G6）一次性升到 v2：既有书签迁移后 note 为空串、
 * updatedAt 为 0（见 BookmarkMigrationTest）。
 *
 * @param note 备注正文，≤ 500 字（AC-17.1，第 2 阶段 UI 消费）。
 * @param updatedAt 备注最后修改时间（跨设备冲突裁决，AC-17.7）。
 */
data class Bookmark(
    val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val charOffset: Int,
    val excerpt: String,
    val createdAt: Long,
    val note: String = "",
    val updatedAt: Long = 0L,
)
