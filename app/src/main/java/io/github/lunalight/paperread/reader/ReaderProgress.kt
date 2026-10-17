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
 *
 * 长行软切分：正常小说的段落远短于 [SOFT_SPLIT_CHARS]，该分支永不触发；
 * 但单行 TXT（整本只有一个换行）会让「一章 = 一个段落」，此时
 * LazyColumn 只有 1 个可见段落 → 首个可见段落恒为 0 → 进度条只随切章跳变，
 * 正文也糊成一整块。故对超长行进软切分（优先句末标点），
 * 段落起点仍取该段落在原文中的真实偏移，进度口径不受影响。
 */
object ReaderProgress {

    /**
     * 长行软切分阈值（字符）。
     *
     * 门槛取 400：中文正文正常段落集中在 40~200 字，400 已经明显异常；
     * 同时保证 [SOFT_SPLIT_CHARS] × 0.6 的下限窗口仍能容纳一个完整句子。
     */
    const val SOFT_SPLIT_CHARS = 400

    /** 软切分断点回退下限比例：至少切出 60% 才回退找句末标点。 */
    private const val SOFT_SPLIT_FLOOR_RATIO = 0.6f

    /**
     * 软切分优先断点：句末/收尾标点。刻意不含 `…`——中文省略号成对出现，
     * 在其间断开会出现「……」被拆到两段。
     */
    private val SOFT_BREAK_AFTER = charArrayOf('。', '！', '？', '；', '!', '?', ';', '”', '」', '』')

    /** 段落扫描结果：[texts] 为渲染文本（trim 后非空行），[starts] 为每段在章内的原始字符偏移。 */
    data class ParagraphScan(val texts: List<String>, val starts: List<Int>)

    /**
     * 扫描章原文：按 '\n' 切行，trim 后非空的行进入渲染列表，
     * 并记录该行在章内的原始起点（行起点 + 前导空白长度）。
     * 超过 [SOFT_SPLIT_CHARS] 的行按句末标点软切分为多个渲染单元。
     */
    fun scanParagraphs(raw: String): ParagraphScan {
        val texts = ArrayList<String>()
        val starts = ArrayList<Int>()
        var pos = 0
        for (line in raw.split('\n')) {
            val leading = line.length - line.trimStart().length
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                val base = pos + leading
                if (trimmed.length <= SOFT_SPLIT_CHARS) {
                    texts.add(trimmed)
                    starts.add(base)
                } else {
                    appendSoftSplit(trimmed, base, texts, starts)
                }
            }
            pos += line.length + 1 // +1 对应段间 \n
        }
        return ParagraphScan(texts, starts)
    }

    /** 把一段超长文本按 [SOFT_SPLIT_CHARS] 切成多个渲染单元，起点保持原文偏移。 */
    private fun appendSoftSplit(
        text: String,
        base: Int,
        texts: MutableList<String>,
        starts: MutableList<Int>,
    ) {
        var offset = 0
        while (offset < text.length) {
            val end = softSplitEnd(text, offset)
            texts.add(text.substring(offset, end))
            starts.add(base + offset)
            offset = end
        }
    }

    /**
     * 求 [from] 起一个软切分单元的结束位置（exclusive）：
     * 以 `from + SOFT_SPLIT_CHARS` 为上界，在 [下限, 上界] 内自后向前找句末标点并断在其后；
     * 找不到则硬切在上界。返回值为结束位置，保证 > from。
     */
    private fun softSplitEnd(text: String, from: Int): Int {
        val limit = minOf(text.length, from + SOFT_SPLIT_CHARS)
        if (limit >= text.length) return text.length
        val floor = from + (SOFT_SPLIT_CHARS * SOFT_SPLIT_FLOOR_RATIO).toInt()
        var i = limit - 1
        while (i >= floor) {
            if (text[i] in SOFT_BREAK_AFTER) return i + 1
            i--
        }
        return limit
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
