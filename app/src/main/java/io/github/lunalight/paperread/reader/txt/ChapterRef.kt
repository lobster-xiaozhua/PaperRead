package io.github.lunalight.paperread.reader.txt

/** 切分策略。 */
enum class SplitStrategy {
    /** 保守：仅强正则（第 X 章 / Chapter N / 序章等），误判最少。 */
    CONSERVATIVE,

    /** 激进：强正则 + 空行短行启发式，误判风险略高。 */
    AGGRESSIVE,
}

/**
 * 章节引用：切分阶段输出，偏移均为字符（UTF-16 code unit）。
 * 区间保证连续且覆盖 [0, text.length]。
 */
data class ChapterRef(
    val index: Int,
    val title: String,
    val charStart: Int,
    val charEnd: Int,
)
