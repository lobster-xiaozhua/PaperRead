package io.github.lunalight.paperread.data.repository

import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.model.ReaderPosition
import io.github.lunalight.paperread.domain.repository.BookRepository

/**
 * 阅读状态回写（AC-10.4 / AC-14.6 的落地动作，T-015 抽出）。
 *
 * 迁移与备份共用同一套写法：进度**替换**（`saveReaderPosition`）、书签**整体替换**
 * （先 `deleteBookmarksByBook` 再逐条 `addBookmark`）——书签是「某个章节坐标上的
 * 一份集合」，增量合并会在章节语义相同的前提下产生重复条目。
 *
 * 「是否该回写」不在这里判断：那是纯函数 `WriteBackDecider` 的职责（迁移与备份
 * 都显式调用它），本类只负责写。拆开的理由是二者可独立单测：决策是纯逻辑，
 * 写入是需要仓储的副作用。
 */
class BookStateWriter(private val bookRepository: BookRepository) {

    /** 替换式写入阅读进度（章节 / 段落 / 滚动偏移 / 字符偏移 + 百分比）。 */
    suspend fun writeProgress(
        bookId: Long,
        chapterIndex: Int,
        paragraphIndex: Int,
        scrollOffset: Int,
        charOffset: Int,
        progressPercent: Float,
    ) {
        bookRepository.saveReaderPosition(
            bookId = bookId,
            position = ReaderPosition(
                chapterIndex = chapterIndex,
                paragraphIndex = paragraphIndex,
                scrollOffset = scrollOffset,
                charOffset = charOffset,
            ),
            progressPercent = progressPercent,
        )
    }

    /**
     * 替换式写入书签。
     *
     * 传入书签的 `id` / `bookId` 会被重置为「目标书籍下的新书签」：
     * 归档数据里的 id 属于源设备，跨设备复用会撞主键。
     */
    suspend fun writeBookmarks(bookId: Long, bookmarks: List<Bookmark>) {
        bookRepository.deleteBookmarksByBook(bookId)
        bookmarks.forEach { mark ->
            bookRepository.addBookmark(mark.copy(id = 0L, bookId = bookId))
        }
    }
}
