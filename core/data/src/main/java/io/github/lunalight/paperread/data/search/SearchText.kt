package io.github.lunalight.paperread.data.search

import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.domain.search.SearchHit
import io.github.lunalight.paperread.reader.ReaderProgress

/**
 * 关键词匹配与摘要截取的纯函数集合（零 Android 依赖 / 零副作用，JVM 可单测）。
 *
 * 从 [CurrentBookSearchSource] 里拆出来的理由：命中判定、计数、摘要窗口都是
 * 「输入字符串 → 输出数字 / 字符串」的纯逻辑，与协程流、文件 IO 无关；
 * 分开后可以逐条断言边界（首尾命中、大小写、空串、单字关键词），不必启动
 * 协程即可覆盖全部分支。
 *
 * 大小写口径：统一按「忽略大小写」匹配（`indexOf(..., ignoreCase = true)`）。
 * 中文没有大小写，等同精确匹配；英文书名 / 人名则符合读者直觉
 * （输入 `harry` 也能命中 `Harry`）。
 */
internal object SearchText {

    /** 摘要中命中词之前保留的字符数。 */
    const val SNIPPET_LEAD: Int = 20

    /** 摘要中命中词之后保留的字符数。 */
    const val SNIPPET_TRAIL: Int = 60

    /**
     * 统计 [needle] 在 [text] 中出现的次数（不重叠、忽略大小写）。
     *
     * 空 needle 返回 0（防御：调用方应已按最短关键词长度过滤）。
     */
    fun countMatches(text: String, needle: String): Int {
        if (needle.isEmpty() || text.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val index = text.indexOf(needle, from, ignoreCase = true)
            if (index < 0) return count
            count++
            from = index + needle.length
            if (from >= text.length) return count
        }
    }

    /** 首个命中的起始下标；未命中返回 -1。 */
    fun indexOfFirst(text: String, needle: String): Int {
        if (needle.isEmpty() || text.isEmpty()) return -1
        return text.indexOf(needle, ignoreCase = true)
    }

    /**
     * 取命中处上下文摘要：命中词前 [SNIPPET_LEAD]、后 [SNIPPET_TRAIL] 个字符，
     * 触到段落边界时补省略号（`…`）。
     *
     * @param matchStart 首条命中的起始下标（调用方已保证 ≥ 0）
     * @param matchLength 命中词长度（保证整词完整出现在摘要里）
     */
    fun snippet(text: String, matchStart: Int, matchLength: Int): String {
        if (text.isEmpty()) return ""
        val start = (matchStart - SNIPPET_LEAD).coerceAtLeast(0)
        val end = (matchStart + matchLength + SNIPPET_TRAIL).coerceAtMost(text.length)
        // 摘要里把行内换行压成空格：搜索结果按单行展示，换行会被 Compose 当成换行符
        // 拉高行高，多行摘要在列表里参差不齐。
        val body = text.substring(start, end).replace('\n', ' ')
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + body + suffix
    }

    /**
     * 扫描单章：按渲染段落切分（[ReaderProgress.scanParagraphs]）后逐段匹配，
     * 每命中一段产出一条 [SearchHit]。
     *
     * 为什么按「渲染段落」而不是按行 / 按字符窗口：
     * - 阅读器的滚动定位以段落为单位（`LazyColumn` item 下标 = 段落下标），
     *   段落粒度的命中才能直接 `scrollToItem` 定位（AC-15.3）；
     * - 同一段的多次命中合并成一条结果 + `matchCount`，结果列表不会被
     *   一段里的重复词刷屏（AC-15.2 的「命中次数」列正为此存在）。
     *
     * @return 本章命中（按段落下标升序）；整章未命中返回空列表
     */
    fun scanChapter(bookId: Long, needle: String, chapter: Chapter, raw: String): List<SearchHit> {
        if (needle.isEmpty() || raw.isEmpty()) return emptyList()
        // 先做一次整章粗筛：绝大多数章节一个词都没有，省掉段落切分的开销
        // （scanParagraphs 要按 '\n' 切行 + trim，是本章最贵的一步）。
        if (indexOfFirst(raw, needle) < 0) return emptyList()
        val scan = ReaderProgress.scanParagraphs(raw)
        val hits = ArrayList<SearchHit>()
        scan.texts.forEachIndexed { paragraphIndex, text ->
            val first = indexOfFirst(text, needle)
            if (first < 0) return@forEachIndexed
            val count = countMatches(text, needle)
            val startInChapter = scan.starts[paragraphIndex]
            hits += SearchHit(
                bookId = bookId,
                chapterIndex = chapter.index,
                paragraphIndex = paragraphIndex,
                charOffset = (chapter.charStart + startInChapter + first).toInt(),
                chapterTitle = chapter.title,
                snippet = snippet(text, first, needle.length),
                matchCount = count,
            )
        }
        return hits
    }
}
