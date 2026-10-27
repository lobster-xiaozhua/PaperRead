package io.github.lunalight.paperread.domain.model

/**
 * 章节：双偏移索引。
 *
 * @param charStart/charEnd 字符偏移（进度计算，UTF-16 code unit）
 * @param byteStart/byteEnd 字节偏移（book.txt 文件读取，UTF-8）
 * @param charCount 本章字符数
 * @param cumulativeChars 本章之前的全文字符数（进度公式分子）
 */
data class Chapter(
    val id: Long = 0,
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
