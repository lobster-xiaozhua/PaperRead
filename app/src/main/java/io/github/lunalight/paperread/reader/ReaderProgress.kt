package io.github.lunalight.paperread.reader

/**
 * 阅读进度纯计算：零依赖，可 JVM 单测。
 *
 * 进度公式（字符加权）：`(cumulativeChars + 章内字符偏移) / totalChars * 100`。
 * 章节长短不均时不会出现「章数百分比」式的跳变。
 *
 * 章内字符偏移口径：**以原文为准**。渲染段落经过 trim / 空行过滤，
 * 与原文行不再等长；每段携带它在章内的原始起点（[ParagraphScan.starts]），
 * 进度取该起点，与切分器记录的双偏移（charStart/charEnd）同一坐标系。
 */
object ReaderProgress {

    /** 段落扫描结果：[texts] 为渲染文本（trim 后非空行），[starts] 为每段在章内的原始字符偏移。 */
    data class ParagraphScan(val texts: List<String>, val starts: List<Int>)

    /**
     * 扫描章原文：按 '\n' 切行，trim 后非空的行进入渲染列表，
     * 并记录该行在章内的原始起点（行起点 + 前导空白长度）。
     */
    fun scanParagraphs(raw: String): ParagraphScan {
        val texts = ArrayList<String>()
        val starts = ArrayList<Int>()
        var pos = 0
        for (line in raw.split('\n')) {
            val leading = line.length - line.trimStart().length
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                texts.add(trimmed)
                starts.add(pos + leading)
            }
            pos += line.length + 1 // +1 对应段间 \n
        }
        return ParagraphScan(texts, starts)
    }

    /** 章内字符偏移（段落级精度）：第 [paragraphIndex] 段在原文中的起点；越界夹取。 */
    fun chapterCharOffset(paragraphStarts: List<Int>, paragraphIndex: Int): Int {
        if (paragraphStarts.isEmpty()) return 0
        return paragraphStarts[paragraphIndex.coerceIn(0, paragraphStarts.lastIndex)]
    }

    /** 字符加权进度百分比，范围 [0, 100]。 */
    fun percentOf(cumulativeChars: Long, chapterCharOffset: Int, totalChars: Long): Float {
        if (totalChars <= 0L) return 0f
        val read = cumulativeChars + chapterCharOffset
        return (read.toFloat() / totalChars.toFloat() * 100f).coerceIn(0f, 100f)
    }
}
