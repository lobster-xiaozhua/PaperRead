package io.github.lunalight.paperread.ui.reader

/**
 * 单页的分页断点：该页首段的段下标，以及该段在页内的起始像素偏移。
 *
 * 本应用段落为原子块（不跨页拆分），因此 [startOffsetPx] 恒为 0；保留该字段是为
 * 与单栏连续滚动的 [io.github.lunalight.paperread.domain.model.ReaderPosition]
 * （段下标 + 段内像素偏移）共用同一进度口径，便于双页/单栏进度互转。
 */
data class PageBreak(
    val startParagraph: Int,
    val startOffsetPx: Int = 0,
)

/**
 * 纯函数分页：把「每段渲染高度(px)」贪心装箱进等高页。
 *
 * 零 Android 依赖，可 JVM 单测（见 [PaginatorTest]）。
 *
 * @param paragraphHeights 每段渲染高度(px，已含段距)；顺序即章节段落顺序
 * @param pageHeightPx 单页可用高度(px)，必须 > 0
 * @return 每页的 [PageBreak] 列表（按页顺序排列）；空输入返回空列表。
 *   某段高度超过 [pageHeightPx] 时该段独占一页（渲染层按需裁切，属已知限制，不影响进度）。
 */
fun packPages(paragraphHeights: List<Int>, pageHeightPx: Int): List<PageBreak> {
    // 开发者断言（不面用户，也不会被渲染）：T-022 的扫描把 ui/** 里的中文字面量
    // 一律视为「漏资源的 UI 文案」，故这条内部断言用英文——它不是文案，硬塞进
    // strings.xml 反而给翻译添噪音。
    require(pageHeightPx > 0) { "pageHeightPx must be > 0, actual $pageHeightPx" }
    if (paragraphHeights.isEmpty()) return emptyList()
    val pages = mutableListOf<PageBreak>()
    var used = 0
    paragraphHeights.forEachIndexed { index, h ->
        if (used == 0 || used + h > pageHeightPx) {
            // 新页起点：首段，或当前段放不下需另起一页
            pages.add(PageBreak(startParagraph = index, startOffsetPx = 0))
            used = h
        } else {
            used += h
        }
    }
    return pages
}

/**
 * 把「段下标」反查到所在页下标（进度恢复用）。
 *
 * 取最后一个 [PageBreak.startParagraph] <= [paragraphIndex] 的页；
 * [paragraphIndex] 越界时夹紧到合法范围。无页时返回 0。
 */
fun pageIndexForPosition(pages: List<PageBreak>, paragraphIndex: Int): Int {
    if (pages.isEmpty()) return 0
    val clamped = paragraphIndex.coerceIn(0, pages.last().startParagraph)
    var result = 0
    for (i in pages.indices) {
        if (pages[i].startParagraph <= clamped) result = i else break
    }
    return result
}
