package io.github.lunalight.paperread.reader

/**
 * 翻页模式纯分页算法（[io.github.lunalight.paperread.ui.reader] 用）。
 *
 * 只吃整数高度、不依赖 Android / Compose——段落的实际像素高度由 UI 层用
 * `TextMeasurer` 测完后传入，本文件只负责「把段落序列切成一页页」。
 * 因此可像其它引擎逻辑一样写纯 JVM 单测，且符合 `reader/` 零 Android 依赖铁律。
 *
 * 设计取舍（MVP）：**页边界对齐到段落起点**，不做段内字符级拆分。
 * 中文网文段落普遍短于一屏，按段切页在 99% 场景都精确；
 * 极个别「单段长于整屏」的段落会被单独放在一页（允许该页内容纵向溢出，不裁切丢字），
 * 段内拆分作为后续增强（需逐字符高度，成本高）。
 */
data class PageBound(
    /** 本页起始段在章内段落列表中的下标。MVP 段内偏移恒为 0（页从段首开始）。 */
    val startParagraph: Int,
)

/**
 * 把段落高度序列切成若干页。
 *
 * @param paragraphHeights 每段测量得到的像素高度（与 `paragraphs` 等长）
 * @param availableHeight 正文可用高度（px），已扣除顶/底系统栏安全区与页眉/页脚占位
 * @param paragraphSpacing 段间距（px）
 * @return 每页的起始段下标；空输入返回空列表
 */
fun paginate(
    paragraphHeights: List<Int>,
    availableHeight: Int,
    paragraphSpacing: Int,
): List<PageBound> {
    if (paragraphHeights.isEmpty()) return emptyList()
    val pages = mutableListOf<PageBound>()
    var i = 0
    val n = paragraphHeights.size
    while (i < n) {
        val pageStart = i
        var used = 0
        while (i < n) {
            val h = paragraphHeights[i]
            val add = h + if (used > 0) paragraphSpacing else 0
            // 已放了一段、再加这一段会超屏 → 留给下一页。
            // used == 0 时不拦截：保证单段再高也至少能成页（不陷入死循环）。
            if (used > 0 && used + add > availableHeight) break
            used += add
            i += 1
        }
        pages.add(PageBound(pageStart))
    }
    return pages
}

/**
 * 给定已落库/恢复的「首个可见段下标」，反查它落在第几页。
 *
 * `pages` 按 `startParagraph` 升序，返回最后一个 `startParagraph <= paragraph` 的页下标；
 * 用于翻页模式恢复进度时把段落级位置映射到页码（[paginate] 保证页从段首起）。
 */
fun pageIndexForStartParagraph(pages: List<PageBound>, paragraph: Int): Int {
    if (pages.isEmpty()) return 0
    var result = 0
    for (idx in pages.indices) {
        if (pages[idx].startParagraph <= paragraph) result = idx else break
    }
    return result
}
