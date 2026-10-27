package io.github.lunalight.paperread.transfer

import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.model.ReaderPosition
import io.github.lunalight.paperread.domain.repository.BookRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * 进度同步流程（AC-11.1 ~ AC-11.3，T-012 从 `DefaultLanTransferManager` 拆出，
 * 行为逐行搬迁）：与对端双向交换阅读状态（进度 + 书签），本机侧只落两道写回。
 *
 * 三个角色：
 * - [LocalSyncGateway]：本机快照（仓储读出）与写回（进度 / 书签两类，不动书籍与章节）；
 * - [RemoteSyncGateway]：对端的 HTTP 拉取 / 回推（阻塞调用，零依赖客户端）；
 * - [ProgressSyncCoordinator]：合并决策（谁更新、哪些书签要补），本类只做粘合。
 *
 * `GET /sync/state` 与 `POST /sync/apply` 两个服务端端点也在这里实现：
 * 前者把本机快照编成 JSON，后者落地对端回推的条目（对端已按合并规则挑出
 * 「它更新的一方」，本机只需落地，不推更旧的数据）。
 */
internal class TransferSyncController(
    private val bookRepository: BookRepository,
    private val identityStore: DeviceIdentityStore,
    private val transferClient: TransferClient,
    private val scope: CoroutineScope,
    private val syncState: MutableStateFlow<TransferSyncState>,
) {

    private var syncJob: Job? = null

    private val syncJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 本机阅读状态网关：快照走仓储，写回只做「进度 / 书签」两类，
     * 不动书籍与章节（同步不导书，只同步阅读状态）。
     */
    private val localSyncGateway = object : LocalSyncGateway {

        override suspend fun snapshot(deviceId: String): SyncEnvelope {
            val books = bookRepository.observeBooks().first()
            val states = books.map { book ->
                val bookmarks = bookRepository.observeBookmarks(book.id).first()
                BookSyncState(
                    sha256 = book.sha256,
                    title = book.title,
                    lastChapterIndex = book.lastChapterIndex,
                    lastParagraphIndex = book.lastParagraphIndex,
                    lastScrollOffset = book.lastScrollOffset,
                    lastCharOffset = book.lastCharOffset,
                    progressPercent = book.progressPercent,
                    lastReadAt = book.lastReadAt,
                    bookmarks = bookmarks.map(SyncBookmark::from),
                )
            }
            return SyncEnvelope(version = SYNC_PROTOCOL_VERSION, deviceId = deviceId, books = states)
        }

        override suspend fun applyProgress(state: BookSyncState) {
            val book = bookRepository.findBySha256(state.sha256) ?: return
            // 回推防御（AC-11.2「最后阅读时间较新者胜」）：对端回推的
            // pushBack 条目可能因书签差异携带旧进度——[SyncMerger.merge] 的
            // pushBack 条件是「本地有对端缺的差异」，并非只携带较新进度。
            // 落地前按 [WriteBackDecider] 复核，避免旧进度覆盖本地较新记录
            // （协调器路径的 applyRemoteProgress 已保证较新，复核对其恒真，
            // 此处仅拦截回推通道的旧数据与「从未阅读」的空记录）。
            if (!WriteBackDecider.shouldWriteBack(book.lastReadAt, state.lastReadAt)) return
            bookRepository.saveReaderPosition(
                bookId = book.id,
                position = ReaderPosition(
                    chapterIndex = state.lastChapterIndex,
                    paragraphIndex = state.lastParagraphIndex,
                    scrollOffset = state.lastScrollOffset,
                    charOffset = state.lastCharOffset,
                ),
                progressPercent = state.progressPercent,
            )
        }

        override suspend fun addBookmarks(state: BookSyncState, bookmarks: List<SyncBookmark>) {
            val book = bookRepository.findBySha256(state.sha256) ?: return
            bookmarks.forEach { mark ->
                bookRepository.addBookmark(
                    Bookmark(
                        bookId = book.id,
                        chapterIndex = mark.chapterIndex,
                        paragraphIndex = mark.paragraphIndex,
                        charOffset = mark.charOffset,
                        excerpt = mark.excerpt,
                        createdAt = mark.createdAt,
                    ),
                )
            }
        }
    }

    /** 对端状态交换（HTTP；0.0.0.0 事件循环外，直接走阻塞客户端）。 */
    private val remoteSyncGateway = object : RemoteSyncGateway {

        override fun fetchState(device: DiscoveredDevice): SyncEnvelope? {
            val text = transferClient.fetchSyncState(device.host, device.httpPort) ?: return null
            return runCatching {
                syncJson.decodeFromString(SyncEnvelope.serializer(), text)
            }.getOrNull()?.takeIf { it.version <= SYNC_PROTOCOL_VERSION }
        }

        override fun pushState(device: DiscoveredDevice, envelope: SyncEnvelope): Boolean =
            transferClient.pushSyncState(
                device.host,
                device.httpPort,
                syncJson.encodeToString(SyncEnvelope.serializer(), envelope),
            )
    }

    private val syncCoordinator = ProgressSyncCoordinator(localSyncGateway, remoteSyncGateway)

    /**
     * 与指定设备双向同步阅读进度与书签（AC-11.1 ~ AC-11.3）。
     * 已在同步中时幂等忽略。
     */
    fun start(device: DiscoveredDevice) {
        if (syncState.value.active) return
        syncState.value = TransferSyncState(active = true, phaseLabel = "交换阅读状态")
        syncJob = scope.launch {
            val result = syncCoordinator.sync(device, identityStore.deviceId())
            syncState.value = result.fold(
                onSuccess = { outcome ->
                    TransferSyncState(active = false, phaseLabel = "同步完成", report = outcome.report)
                },
                onFailure = { error ->
                    TransferSyncState(active = false, error = error.message ?: "同步失败")
                },
            )
        }
    }

    /** 关闭同步结果提示。 */
    fun dismissReport() {
        syncJob?.cancel()
        syncJob = null
        syncState.value = TransferSyncState()
    }

    /** `GET /sync/state` 的响应体：本机阅读状态快照（AC-11.1）。 */
    suspend fun stateJson(): String {
        val envelope = localSyncGateway.snapshot(identityStore.deviceId())
        return syncJson.encodeToString(SyncEnvelope.serializer(), envelope)
    }

    /**
     * `POST /sync/apply`：落地对端回推的较新状态（AC-11.1）。
     *
     * @return null = 应用成功；非 null = 失败原因（对端收到 500）
     */
    suspend fun applyRemote(payload: String): String? {
        val envelope = runCatching {
            syncJson.decodeFromString(SyncEnvelope.serializer(), payload)
        }.getOrNull() ?: return "bad payload"
        if (envelope.version > SYNC_PROTOCOL_VERSION) return "version too new"
        // 对端已按合并规则挑出「它更新的一方」，这里只需落地（对端不推更旧的数据）
        envelope.books.forEach { state ->
            localSyncGateway.applyProgress(state)
            if (state.bookmarks.isNotEmpty()) {
                // 只补本地缺的书签（去重，避免重复条目）
                val book = bookRepository.findBySha256(state.sha256) ?: return@forEach
                val existing = bookRepository.observeBookmarks(book.id).first()
                    .map { SyncBookmark.from(it).positionKey }
                    .toSet()
                val missing = state.bookmarks.filter { it.positionKey !in existing }
                if (missing.isNotEmpty()) {
                    localSyncGateway.addBookmarks(state, missing)
                }
            }
        }
        return null
    }
}
