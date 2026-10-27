package io.github.lunalight.paperread.reader.txt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * TXT 章节切分器：纯 Kotlin，零 Android 依赖，可 JVM 单测。
 *
 * 策略顺序：
 *  1. 目录页识别与跳过；
 *  2. 强正则匹配章节标题（命中 ≥ 3 生效）；
 *  3. 回退：空行 + 短行启发式（仅 AGGRESSIVE 策略）；
 *  4. 最终回退：按约 3000 字符强制分段（第 N 段）。
 *
 * 注意：第 4 步是「行级规则全军覆没」时的兜底，典型场景是**整本只有一个换行**的
 * 单行 TXT。这种文件在导入层还会被 [InlineChapterScanner] 再补切一次
 * （见 `DefaultBookImporter.resplitFallback`），把行内标题恢复成真实章节；
 * 本类自身只负责行级切分，不感知补切。
 *
 * 输出区间保证连续且覆盖 [0, text.length]。
 * 已知限制：目录页与正文首章标题之间无空行时，若首章标题与目录条目文案完全相同，
 * 依靠「重复标题」启发式终止目录链；极端情况下首个正文章标题仍可能被吞入目录块
 * （文本不丢失，仅标题缺失）；可通过「重新解析」或手动调整规避。
 *
 * 实现说明：[split] 委托流式状态机 [TxtChapterStream]（UTF-8 字节往返），
 * 批量与流式共用同一套切分逻辑，parity 由构造保证；
 * 大规模导入请直接使用 [TxtChapterStream]，避免全文驻留内存。
 */
object ChapterSplitter {

    internal const val MIN_CHAPTER_MATCHES = 3
    internal const val FORCED_SEGMENT_CHARS = 3000
    internal const val TOC_SEARCH_LINES = 200
    internal const val SHORT_LINE_MAX = 15
    internal const val TOC_KEYWORD = "目录"

    internal val STRONG_PATTERNS = listOf(
        Regex("""^[ \t\u3000]*第[0-9０-９一二三四五六七八九十百千零两]+[章回节卷集部篇话][^\n]*$""", RegexOption.MULTILINE),
        Regex("""^[ \t\u3000]*(?:Chapter|CHAPTER|chapter)\s+\d+[^\n]*$""", RegexOption.MULTILINE),
        Regex("""^[ \t\u3000]*(?:序章|序言|楔子|尾声|终章|番外篇?|后记|前言)[^\n]*$""", RegexOption.MULTILINE),
    )

    fun normalizeLineEndings(text: String): String =
        text.replace("\r\n", "\n").replace("\r", "\n")

    fun split(text: String, strategy: SplitStrategy = SplitStrategy.CONSERVATIVE): List<ChapterRef> {
        if (text.isEmpty()) return emptyList()
        val result = TxtChapterStream(Charsets.UTF_8, strategy).process(
            input = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
            out = ByteArrayOutputStream(),
        )
        return result.entries.map { ChapterRef(it.index, it.title, it.charStart.toInt(), it.charEnd.toInt()) }
    }

    internal fun isChapterLine(line: String): Boolean = STRONG_PATTERNS.any { it.matches(line) }
}
