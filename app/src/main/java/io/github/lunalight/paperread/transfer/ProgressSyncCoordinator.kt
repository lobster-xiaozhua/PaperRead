package io.github.lunalight.paperread.transfer

/**
 * 进度同步的本地/远端网关（Android 侧实现，编排逻辑保持纯 JVM 可测）。
 *
 * 两个方向刻意分开：
 * - [LocalSyncGateway]：读写本机书库状态；
 * - [RemoteSyncGateway]：与对端交换快照（HTTP）。
 */
interface LocalSyncGateway {

    /** 构建本机快照（AC-11.1）。 */
    suspend fun snapshot(deviceId: String): SyncEnvelope

    /** 应用对端较新的进度（AC-11.2）。 */
    suspend fun applyProgress(state: BookSyncState)

    /** 追加对端独有的书签（AC-11.3：只增不删）。 */
    suspend fun addBookmarks(state: BookSyncState, bookmarks: List<SyncBookmark>)

    /**
     * 就地更新已存在书签的备注（AC-17.7：「备注冲突按更新时间较新者胜」）。
     *
     * 与 [addBookmarks] 分开的理由：新增要插行（会撞主键 / 产生重复），
     * 备注更新只改列；两者混用会让「只增不删」语义被重复条目污染。
     */
    suspend fun updateNotes(state: BookSyncState, bookmarks: List<SyncBookmark>)
}

/** 对端状态交换（HTTP `/sync/state` 与 `/sync/apply`）。 */
interface RemoteSyncGateway {

    /** 拉取对端快照。 */
    fun fetchState(device: DiscoveredDevice): SyncEnvelope?

    /** 把本地较新的状态回推给对端。 */
    fun pushState(device: DiscoveredDevice, envelope: SyncEnvelope): Boolean
}

/**
 * 一次双向同步的编排（AC-11.1 ~ AC-11.3）。
 *
 * 流程：本机快照 → 拉取对端 → 本地合并应用 → 把本地更新的一方回推对端。
 * 任一步骤失败返回 null 并给出原因，不留下半套状态。
 */
class ProgressSyncCoordinator(
    private val local: LocalSyncGateway,
    private val remote: RemoteSyncGateway,
) {

    /** 同步结果：本地变化 + 已回推的对端状态。 */
    data class Outcome(
        val report: SyncReport,
        val pushed: Int,
    )

    suspend fun sync(device: DiscoveredDevice, selfDeviceId: String): Result<Outcome> {
        val localEnvelope = try {
            local.snapshot(selfDeviceId)
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("读取本机阅读状态失败：${e.message}", e))
        }
        val remoteEnvelope = remote.fetchState(device)
            ?: return Result.failure(IllegalStateException("无法获取对端阅读状态"))

        val report = SyncMerger.merge(localEnvelope, remoteEnvelope)

        // 1) 应用对端较新的部分到本地
        var pushed = 0
        try {
            report.perBook.forEach { merge ->
                if (merge.result.applyRemoteProgress) {
                    local.applyProgress(merge.state)
                }
                if (merge.result.addBookmarks.isNotEmpty()) {
                    local.addBookmarks(merge.state, merge.result.addBookmarks)
                }
                if (merge.result.updateNotes.isNotEmpty()) {
                    local.updateNotes(merge.state, merge.result.updateNotes)
                }
            }
            // 2) 把本地较新的部分回推对端（空则不请求）
            if (report.pushBack.isNotEmpty()) {
                val ok = remote.pushState(
                    device,
                    SyncEnvelope(
                        version = SYNC_PROTOCOL_VERSION,
                        deviceId = selfDeviceId,
                        books = report.pushBack,
                    ),
                )
                if (!ok) {
                    return Result.success(Outcome(report, pushed = 0))
                }
                pushed = report.pushBack.size
            }
        } catch (e: Exception) {
            return Result.failure(IllegalStateException("同步过程中出错：${e.message}", e))
        }
        return Result.success(Outcome(report, pushed))
    }
}
