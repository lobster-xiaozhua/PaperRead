package io.github.lunalight.paperread.transfer

import io.github.lunalight.paperread.domain.model.Bookmark
import kotlinx.serialization.Serializable

/**
 * 进度同步的数据契约与合并决策（AC-11.1 ~ AC-11.3，纯 JVM）。
 *
 * 交换方式：双方各自暴露 `GET /sync/state`，拿到对端状态后在本地合并，
 * 再把「需要回给对端」的差异回发 `POST /sync/apply`——一次同步让两台设备收敛。
 *
 * 冲突规则：
 * - 进度 / 书籍：最后阅读时间较新者胜（AC-11.2）；
 * - 书签：按「章节 + 段落 + 字符偏移」识别同一条，取最早创建时间；
 *   对侧独有的书签一律并入（AC-11.3：不得删除对侧独有数据）。
 */

/** 同步协议版本（与传输协议版本独立演进）。 */
const val SYNC_PROTOCOL_VERSION: Int = 1

/** 单条书签的同步载体（不含 id：跨设备 id 无语义）。 */
@Serializable
data class SyncBookmark(
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val charOffset: Int,
    val excerpt: String,
    val createdAt: Long,
) {
    /** 同一位置视为同一条书签。 */
    val positionKey: String get() = "$chapterIndex:$paragraphIndex:$charOffset"

    companion object {
        fun from(bookmark: Bookmark): SyncBookmark = SyncBookmark(
            chapterIndex = bookmark.chapterIndex,
            paragraphIndex = bookmark.paragraphIndex,
            charOffset = bookmark.charOffset,
            excerpt = bookmark.excerpt,
            createdAt = bookmark.createdAt,
        )
    }
}

/** 单本书的同步状态（AC-11.1：每本书的 SHA-256 / 进度 / 书签 / 最后阅读时间）。 */
@Serializable
data class BookSyncState(
    val sha256: String,
    val title: String,
    val lastChapterIndex: Int,
    val lastParagraphIndex: Int,
    val lastScrollOffset: Int,
    val lastCharOffset: Int,
    val progressPercent: Float,
    val lastReadAt: Long?,
    val bookmarks: List<SyncBookmark>,
)

/**
 * 一台设备的同步快照（AC-11.1）。
 *
 * @param deviceId 随机 UUID（AC-11.4），仅用于识别对端，不作任何持久标识用途
 */
@Serializable
data class SyncEnvelope(
    val version: Int = SYNC_PROTOCOL_VERSION,
    val deviceId: String,
    val books: List<BookSyncState>,
)

/** 单本书的合并结论：本地应更新什么、应回给对端什么。 */
data class BookMergeResult(
    /** 本地进度需要被对端覆盖。 */
    val applyRemoteProgress: Boolean = false,
    /** 本地应新增的书签（对端有、本地没有）。 */
    val addBookmarks: List<SyncBookmark> = emptyList(),
    /** 本地进度需要回给对端（对端落后）。 */
    val pushProgressToRemote: Boolean = false,
    /** 应回给对端的书签（本地有、对端没有）。 */
    val pushBookmarks: List<SyncBookmark> = emptyList(),
) {
    val hasLocalChanges: Boolean get() = applyRemoteProgress || addBookmarks.isNotEmpty()
    val hasRemoteChanges: Boolean get() = pushProgressToRemote || pushBookmarks.isNotEmpty()
}

/** 整库合并报告。 */
data class SyncReport(
    /** 本地进度被覆盖的书数量（AC-11.2：「告知用户被覆盖的条目数」）。 */
    val localOverwritten: Int = 0,
    /** 本地新增的书签数量。 */
    val localBookmarksAdded: Int = 0,
    /** 仅本地存在（对端没有）的书数量——按 AC-11.3 保留，不删除。 */
    val localOnlyBooks: Int = 0,
    /** 仅对端存在的书数量（本次同步不导入这些书，只统计告知）。 */
    val remoteOnlyBooks: Int = 0,
    /** 逐本派生：需要回给对端的书状态。 */
    val pushBack: List<BookSyncState> = emptyList(),
    /** 逐本合并明细（含对端独有的书，用于应用进度 / 书签）。 */
    val perBook: List<BookMerge> = emptyList(),
) {
    val totalChanges: Int get() = localOverwritten + localBookmarksAdded
}

/** 单本书的合并明细（[state] 为对端状态）。 */
data class BookMerge(
    val state: BookSyncState,
    val result: BookMergeResult,
)

/** 合并决策引擎（纯函数集合，AC-11.2 / AC-11.3）。 */
object SyncMerger {

    /**
     * 合并单本书。
     *
     * @param local 本地状态（null = 本地无此书）
     * @param remote 对端状态
     */
    fun mergeBook(local: BookSyncState?, remote: BookSyncState): BookMergeResult {
        if (local == null) {
            // 本地没有这本书：同步不搬运书目本身（AC-11.3 只管两侧都有的书），
            // 因此既无本地可写，也无「本地缺的书签」可回推。
            // 对端独有的书由 [merge] 计入 remoteOnlyBooks 告知用户。
            return BookMergeResult()
        }
        val remoteWins = remote.lastReadAt != null &&
            (local.lastReadAt == null || remote.lastReadAt > local.lastReadAt)

        val localKeys = local.bookmarks.associateBy { it.positionKey }
        val remoteKeys = remote.bookmarks.associateBy { it.positionKey }

        val toAdd = remote.bookmarks.filter { it.positionKey !in localKeys }
        val toPush = local.bookmarks.filter { it.positionKey !in remoteKeys }

        return BookMergeResult(
            applyRemoteProgress = remoteWins,
            addBookmarks = toAdd,
            pushProgressToRemote = !remoteWins && shouldPush(local, remote),
            pushBookmarks = toPush,
        )
    }

    /**
     * 本地是否应把进度推给对端：本地有更晚的阅读记录。
     *
     * 注意与 [mergeBook] 的分工——[applyRemoteProgress] 与 [pushProgressToRemote]
     * 互斥，避免两台设备在同一次同步里互相覆盖。
     */
    private fun shouldPush(local: BookSyncState, remote: BookSyncState): Boolean =
        local.lastReadAt != null && (remote.lastReadAt == null || local.lastReadAt > remote.lastReadAt)

    /**
     * 整库合并（以本地书目为主）。
     *
     * @param local 本地快照
     * @param remote 对端快照
     * @return 报告；[SyncReport.pushBack] 为需要回发给对端的状态集合
     */
    fun merge(local: SyncEnvelope, remote: SyncEnvelope): SyncReport {
        val localByHash = local.books.associateBy { it.sha256 }
        val remoteByHash = remote.books.associateBy { it.sha256 }

        var overwritten = 0
        var bookmarksAdded = 0
        val pushBack = mutableListOf<BookSyncState>()
        val perBook = mutableListOf<BookMerge>()

        remote.books.forEach { remoteBook ->
            val localBook = localByHash[remoteBook.sha256]
            val result = mergeBook(localBook, remoteBook)
            perBook += BookMerge(remoteBook, result)
            if (result.applyRemoteProgress) overwritten++
            bookmarksAdded += result.addBookmarks.size
            if (localBook != null && result.hasRemoteChanges) {
                // 只推对端缺的那部分：书签取差集，进度按需带上本地位置
                pushBack += localBook.copy(bookmarks = result.pushBookmarks)
            }
        }

        val localOnly = local.books.count { it.sha256 !in remoteByHash }
        val remoteOnly = remote.books.count { it.sha256 !in localByHash }

        return SyncReport(
            localOverwritten = overwritten,
            localBookmarksAdded = bookmarksAdded,
            localOnlyBooks = localOnly,
            remoteOnlyBooks = remoteOnly,
            pushBack = pushBack,
            perBook = perBook,
        )
    }
}
