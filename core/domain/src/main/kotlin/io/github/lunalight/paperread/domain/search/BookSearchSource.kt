package io.github.lunalight.paperread.domain.search

import kotlinx.coroutines.flow.Flow

/**
 * 全书搜索的数据契约（AC-15.8 / PRD §2.4.2）。
 *
 * v2.0 只有「当前书」一个实现（[io.github.lunalight.paperread.data.search.CurrentBookSearchSource]）；
 * v2.1 的书架级全局搜索（Room FTS + 后台建索引）实现**同一接口**并产出同样的
 * [SearchHit] 流，UI / ViewModel 层协议不变——这是决策 D4 要求的扩展点：
 * ViewModel 只依赖本接口，换实现（或多书聚合的实现）不需要改一行业务代码。
 *
 * 本接口刻意留在 `:core:domain`（纯 JVM、零 Android 依赖）：契约可被任何模块
 * 实现与消费，不被 UI 框架或数据层绑定。
 */
interface BookSearchSource {

    /**
     * 逐条流式产出命中（AC-15.4：先命中的章节先出，不等全书扫完）。
     *
     * 语义约定：
     * - **流式**：命中一条 emit 一条，实现不得先聚成 List 再一次性返回；
     * - **取消即停**（AC-15.5 / AC-15.10）：收集方取消协程后，实现应在下一个
     *   检查点（每章开始 / 每条命中）停止读取，不再触碰后续章节；
     * - **零网络**（AC-15.9）：只允许读本机磁盘 / 内存；
     * - **不得整本载入内存**（AC-15.6）：按章读取、单章处理完即丢弃。
     *
     * @param query 关键词（调用方已保证长度 ≥ [SEARCH_MIN_QUERY_LENGTH]；
     *   实现仍应自行防御，短于门槛时返回空流而不是抛异常）
     */
    fun search(query: String): Flow<SearchHit>
}

/**
 * 一条搜索命中（AC-15.2：结果列表需要「章节名 + 段落摘要 + 命中次数」）。
 *
 * 字段口径与 PRD §2.4.2 的差异说明：
 * - PRD 定义 `SearchHit(bookId, chapterIndex, paragraphIndex, charOffset, chapterTitle, snippet)`；
 *   本实现逐字保留这 6 个字段（含 `bookId`，为 v2.1 书架级搜索预留——那时一条流里
 *   会混着多本书的命中，没有 `bookId` 无法定位）；
 * - 追加 `matchCount`（默认 1）：AC-15.2 明确要求结果条目展示「命中次数」，
 *   PRD 契约漏了该字段。带默认值追加，不改变既有构造调用点（前 6 个参数顺序与
 *   PRD 完全一致），v2.1 实现可选择性填充。
 *
 * @param bookId 命中所属书籍（v2.0 当前书搜索恒定等于当前书 id）
 * @param chapterIndex 章节下标（`chapters` 列表下标，非章节号文本）
 * @param chapterTitle 章节标题（结果列表直接展示，避免 UI 再回查章节表）
 * @param paragraphIndex 命中段落下标——与阅读器渲染单元同一口径
 *   （`ReaderProgress.scanParagraphs` 的 `texts` 下标），因此点击结果可直接
 *   `scrollToItem(paragraphIndex + 1)` 定位（AC-15.3），不需要再做文本匹配
 * @param charOffset 命中词在**全书**的字符偏移（UTF-16 code unit，与
 *   `Chapter.charStart` / `Book.totalChars` 同一坐标系）：首条命中处，
 *   用于跨设备 / 跨版本定位与诊断，不参与滚动
 * @param snippet 命中上下文摘要（首条命中前后各取若干字符，跨边界补省略号）
 * @param matchCount 本段落内的命中次数（≥ 1；AC-15.2 的「命中次数」列）
 */
data class SearchHit(
    val bookId: Long,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val charOffset: Int,
    val chapterTitle: String,
    val snippet: String,
    val matchCount: Int = 1,
) {
    /** 定位键：一「段落」一条命中，同一段不会重复出现（结果列表去重依据）。 */
    val positionKey: String get() = "$chapterIndex:$paragraphIndex"
}

/**
 * 触发搜索的最短关键词长度（AC-15.2：输入 ≥ 2 个字符才扫描）。
 *
 * 门槛放在契约层而非 UI 层：ViewModel 与实现类共用同一常量，避免
 * 「UI 允许 1 个字、实现却按 2 个字过滤」的双口径。
 */
const val SEARCH_MIN_QUERY_LENGTH: Int = 2

/** 搜索关键词输入防抖窗口（AC-15.2 的「触发搜索」体验口径，300ms 业界常态）。 */
const val SEARCH_DEBOUNCE_MS: Long = 300L
