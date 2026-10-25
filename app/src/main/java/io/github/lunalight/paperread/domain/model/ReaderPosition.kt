package io.github.lunalight.paperread.domain.model

/**
 * 阅读位置值对象：章 + 段 + 段内像素偏移 + 章内字符偏移。
 *
 * 恢复时序：加载章节段落列表 → scrollToItem(paragraphIndex, scrollOffset) → charOffset 校正进度。
 */
data class ReaderPosition(
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val scrollOffset: Int,
    val charOffset: Int,
) {
    companion object {
        val Initial = ReaderPosition(0, 0, 0, 0)
    }
}
