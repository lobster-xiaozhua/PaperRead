package io.github.lunalight.paperread.domain.model

/**
 * 书签。
 *
 * [note] / [updatedAt] 为 R-18（US-17 书签备注）字段，schema 随
 * T-006（R-06 / G6）一次性升到 v2：既有书签迁移后 note 为空串、
 * updatedAt 为 0（见 BookmarkMigrationTest）。
 *
 * @param note 备注正文，≤ [NOTE_MAX_LENGTH] 字（AC-17.1；空串 = 无备注）。
 *   领域层的长度校验见 [clampNote] / [normalizeNote]——UI 与仓储都走同一份口径，
 *   不各写一个 500。
 * @param updatedAt 备注最后修改时间（跨设备冲突裁决「较新者胜」，AC-17.7）。
 *   0 = 从未编辑过备注（v1 → v2 迁移后的存量行即此值）。
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
) {
    /** 是否有备注内容（列表展示「备注摘要」与编辑入口的判据）。 */
    val hasNote: Boolean get() = note.isNotBlank()

    companion object {

        /** 备注长度上限（AC-17.1：≤ 500 字）。 */
        const val NOTE_MAX_LENGTH: Int = 500

        /**
         * 输入框侧裁剪：只截断不 trim。
         *
         * 输入过程中用户可能刚敲了空格（准备继续打字），此处 trim 会把光标前的
         * 空格吃掉；落库时改走 [normalizeNote]。
         */
        fun clampNote(text: String): String = text.take(NOTE_MAX_LENGTH)

        /**
         * 落库前规范化：去首尾空白 + 截断到上限（AC-17.1 的领域层校验）。
         *
         * 空备注合法（「备注可空」），统一存空串而不是 null——实体列是 NOT NULL，
         * 且空串在跨设备合并里与「无备注」等价，不需要额外区分。
         */
        fun normalizeNote(text: String): String = text.trim().take(NOTE_MAX_LENGTH)
    }
}
