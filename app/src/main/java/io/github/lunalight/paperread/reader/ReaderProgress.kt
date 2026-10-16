package io.github.lunalight.paperread.reader

/**
 * 阅读进度纯计算：零依赖，可 JVM 单测。
 *
 * 进度公式（字符加权）：`(cumulativeChars + 章内字符偏移) / totalChars * 100`。
 * 章节长短不均时不会出现「章数百分比」式的跳变。
 */
object ReaderProgress {

    /** 段落起始字符偏移表（章内），长度 = paragraphs.size + 1（末元素为章总字符数）。 */
    fun paragraphCharStarts(paragraphs: List<String>): List<Int> {
        val starts = ArrayList<Int>(paragraphs.size + 1)
        var acc = 0
        for (p in paragraphs) {
            starts.add(acc)
            acc += p.length + 1 // +1 对应段间 \n
        }
        starts.add(acc)
        return starts
    }

    /** 章内字符偏移（段落级精度）：首个可见段落的起始偏移。 */
    fun chapterCharOffset(paragraphs: List<String>, paragraphIndex: Int): Int {
        if (paragraphs.isEmpty() || paragraphIndex <= 0) return 0
        val clamped = paragraphIndex.coerceAtMost(paragraphs.size)
        var offset = 0
        for (i in 0 until clamped) offset += paragraphs[i].length + 1
        return offset
    }

    /** 字符加权进度百分比，范围 [0, 100]。 */
    fun percentOf(cumulativeChars: Long, chapterCharOffset: Int, totalChars: Long): Float {
        if (totalChars <= 0L) return 0f
        val read = cumulativeChars + chapterCharOffset
        return (read.toFloat() / totalChars.toFloat() * 100f).coerceIn(0f, 100f)
    }
}
