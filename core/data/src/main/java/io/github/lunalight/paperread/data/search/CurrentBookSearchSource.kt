package io.github.lunalight.paperread.data.search

import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.domain.search.BookSearchSource
import io.github.lunalight.paperread.domain.search.SEARCH_MIN_QUERY_LENGTH
import io.github.lunalight.paperread.domain.search.SearchHit
import io.github.lunalight.paperread.reader.BookTextStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 「当前书」全书搜索实现（AC-15.2 ~ AC-15.6，PRD §2.4.2 的唯一 v2.0 实现类）。
 *
 * 三条硬约束的落地方式：
 *
 * 1. **流式**（AC-15.4）：`flow { }` 里逐章扫描、逐条 `emit`——首批结果在读完
 *    第一章后即可到达 UI，不等全书扫完。章节顺序即 `chapters` 列表顺序，
 *    因此「先命中的章节先展示」天然成立（先章先出）。
 * 2. **不整本载入内存**（AC-15.6）：每次只 `readChapter` 一章，处理完立刻丢弃
 *    局部变量，滚动内存占用 ≈ 单章文本；不建索引、不做全书拼接。100MB / 200MB
 *    书的峰值增量与单章大小同阶，与总字数无关。
 * 3. **取消即停**（AC-15.5 / AC-15.10）：每章开始前与每条命中后各做一次
 *    `ensureActive()`。取消发生在两章之间时，**不会再打开下一章的文件**
 *    （[BookTextStore.readChapter] 一次都不会被调用）——文件句柄因此不会泄漏。
 *
 * 线程：本类自身不切换调度器（纯读 + 纯算，无挂起点），由调用方决定 IO 线程
 * （ViewModel 侧 `launch(searchDispatcher)`）；这样测试可以用测试调度器完全
 * 掌控执行顺序，不需要跨线程等待。
 *
 * @param bookId 当前书 id（v2.0 结果里的 `bookId` 恒等于它）
 * @param chapters 章节表（顺序即搜索顺序；来自 `BookRepository.getChapters`）
 * @param textStore 正文读取（生产为 `FileBookTextStore`，测试为内存 fake）
 * @param dirName 书籍私有目录名（`Book.dirName`）
 */
class CurrentBookSearchSource(
    private val bookId: Long,
    private val chapters: List<Chapter>,
    private val textStore: BookTextStore,
    private val dirName: String,
) : BookSearchSource {

    override fun search(query: String): Flow<SearchHit> = flow {
        val needle = query.trim()
        // 契约防御：短于门槛的关键词不扫描（同时避免 1 个字的查询把整本书扫一遍）。
        if (needle.length < SEARCH_MIN_QUERY_LENGTH) return@flow
        for (chapter in chapters) {
            // 取消检查点①：进入下一章前。放这里而不是读完再查，是为了「取消后
            // 一次 readChapter 都不再发生」——文件句柄不会因为多读一章而残留。
            currentCoroutineContext().ensureActive()
            val raw = textStore.readChapter(dirName, chapter)
            if (raw.isEmpty()) continue
            val hits = SearchText.scanChapter(bookId, needle, chapter, raw)
            for (hit in hits) {
                // 取消检查点②：逐条 emit，取消后立刻停止，不把本章剩余命中塞进
                // 已取消的通道（collect 方已不在，emit 会抛 CancellationException）。
                currentCoroutineContext().ensureActive()
                emit(hit)
            }
            // raw / hits 出作用域即可回收：下一轮循环不会再持有上一章文本
        }
    }
}
