package io.github.lunalight.paperread.reader.txt

/**
 * TXT 章节切分器：纯 Kotlin，零 Android 依赖，可 JVM 单测。
 *
 * 策略顺序：
 *  1. 目录页识别与跳过；
 *  2. 强正则匹配章节标题（命中 ≥ 3 生效）；
 *  3. 回退：空行 + 短行启发式（仅 AGGRESSIVE 策略）；
 *  4. 最终回退：按约 3000 字符强制分段（第 N 段）。
 *
 * 输出区间保证连续且覆盖 [0, text.length]。
 * 已知限制：目录页与正文首章标题之间无空行时，若首章标题与目录条目文案完全相同，
 * 依靠「重复标题」启发式终止目录链；极端情况下首个正文章标题仍可能被吞入目录块
 * （文本不丢失，仅标题缺失）；可通过「重新解析」或手动调整规避。
 */
object ChapterSplitter {

    private const val MIN_CHAPTER_MATCHES = 3
    private const val FORCED_SEGMENT_CHARS = 3000
    private const val TOC_SEARCH_LINES = 200
    private const val SHORT_LINE_MAX = 15
    private const val TOC_KEYWORD = "目录"

    private val STRONG_PATTERNS = listOf(
        Regex("""^[ \t\u3000]*第[0-9０-９一二三四五六七八九十百千零两]+[章回节卷集部篇话][^\n]*$""", RegexOption.MULTILINE),
        Regex("""^[ \t\u3000]*(?:Chapter|CHAPTER|chapter)\s+\d+[^\n]*$""", RegexOption.MULTILINE),
        Regex("""^[ \t\u3000]*(?:序章|序言|楔子|尾声|终章|番外篇?|后记|前言)[^\n]*$""", RegexOption.MULTILINE),
    )

    fun normalizeLineEndings(text: String): String =
        text.replace("\r\n", "\n").replace("\r", "\n")

    fun split(text: String, strategy: SplitStrategy = SplitStrategy.CONSERVATIVE): List<ChapterRef> {
        if (text.isEmpty()) return emptyList()
        val tocEnd = detectTocEnd(text)
        val offsets = findStrongOffsets(text, tocEnd)
        if (offsets.size >= MIN_CHAPTER_MATCHES) return refsFromOffsets(text, offsets)
        if (strategy == SplitStrategy.AGGRESSIVE) {
            val heuristic = findShortLineOffsets(text, tocEnd)
            if (heuristic.size >= MIN_CHAPTER_MATCHES) return refsFromOffsets(text, heuristic)
        }
        return splitForced(text)
    }

    // ---- 目录页识别 ----

    private fun detectTocEnd(text: String): Int {
        val lines = splitLines(text)
        for (i in 0 until minOf(lines.size, TOC_SEARCH_LINES)) {
            val content = lines[i].content.trim()
                .replace(" ", "").replace("\t", "").replace("\u3000", "")
            if (content != TOC_KEYWORD) continue
            var j = i + 1
            var chapterLike = 0
            var lastChapterLikeEnd = lines[i].endExclusive
            val seenTitles = HashSet<String>()
            while (j < lines.size) {
                val line = lines[j].content.trim()
                if (line.isEmpty()) {
                    j++
                    continue
                }
                if (isChapterLine(line)) {
                    // 目录条目极少重复；出现重复标题说明已进入正文首章，终止目录链
                    if (chapterLike >= MIN_CHAPTER_MATCHES && line in seenTitles) break
                    seenTitles.add(line)
                    chapterLike++
                    lastChapterLikeEnd = lines[j].endExclusive
                    j++
                    continue
                }
                break
            }
            if (chapterLike >= MIN_CHAPTER_MATCHES) return lastChapterLikeEnd
        }
        return 0
    }

    // ---- 强正则 ----

    private fun findStrongOffsets(text: String, tocEnd: Int): List<Int> {
        val offsets = sortedSetOf<Int>()
        for (pattern in STRONG_PATTERNS) {
            pattern.findAll(text).forEach { match ->
                val start = match.range.first
                if (start >= tocEnd) offsets.add(start)
            }
        }
        return offsets.toList()
    }

    private fun isChapterLine(line: String): Boolean = STRONG_PATTERNS.any { it.matches(line) }

    // ---- 启发式（激进） ----

    private fun findShortLineOffsets(text: String, tocEnd: Int): List<Int> {
        val lines = splitLines(text)
        val offsets = ArrayList<Int>()
        for (i in lines.indices) {
            val line = lines[i]
            if (line.start < tocEnd) continue
            val content = line.content.trim()
            if (content.isEmpty() || content.length > SHORT_LINE_MAX) continue
            val prevBlank = i == 0 || lines[i - 1].content.isBlank()
            val nextBlank = i == lines.size - 1 || lines[i + 1].content.isBlank()
            if (prevBlank && nextBlank) offsets.add(line.start)
        }
        return offsets
    }

    // ---- 强制分段 ----

    private fun splitForced(text: String): List<ChapterRef> {
        val refs = ArrayList<ChapterRef>()
        var start = 0
        var index = 0
        while (start < text.length) {
            var end = minOf(start + FORCED_SEGMENT_CHARS, text.length)
            if (end < text.length) {
                val nextNewline = text.indexOf('\n', end)
                end = if (nextNewline in 0..(end + 200)) {
                    nextNewline + 1
                } else {
                    val prevNewline = text.lastIndexOf('\n', end)
                    if (prevNewline > start) prevNewline + 1 else end
                }
            }
            refs.add(ChapterRef(index, "第 ${index + 1} 段", start, end))
            start = end
            index++
        }
        return refs
    }

    // ---- 工具 ----

    private data class Line(val start: Int, val endExclusive: Int, val content: String)

    private fun splitLines(text: String): List<Line> {
        val lines = ArrayList<Line>()
        var start = 0
        var i = 0
        while (i < text.length) {
            if (text[i] == '\n') {
                lines.add(Line(start, i, text.substring(start, i)))
                start = i + 1
            }
            i++
        }
        if (start < text.length) {
            lines.add(Line(start, text.length, text.substring(start)))
        }
        return lines
    }

    private fun refsFromOffsets(text: String, offsets: List<Int>): List<ChapterRef> {
        val refs = ArrayList<ChapterRef>()
        var index = 0
        if (offsets.isEmpty() || offsets[0] > 0) {
            val end = offsets.firstOrNull() ?: text.length
            refs.add(ChapterRef(index++, "开头", 0, end))
        }
        for (i in offsets.indices) {
            val start = offsets[i]
            val end = offsets.getOrElse(i + 1) { text.length }
            refs.add(ChapterRef(index++, titleAt(text, start), start, end))
        }
        return refs
    }

    private fun titleAt(text: String, offset: Int): String {
        var end = offset
        while (end < text.length && text[end] != '\n') end++
        return text.substring(offset, end).trim().ifEmpty { "未命名章节" }
    }
}
