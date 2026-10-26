package io.github.lunalight.paperread.transfer

import android.content.Context
import io.github.lunalight.paperread.data.repository.BookImporter
import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.data.repository.PrepareResult
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.model.ReaderPosition
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import java.io.File
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
/** 传书服务状态（AC-9.1：本机标识 = 设备名 + IP:端口）。 */
data class TransferServiceState(
    val starting: Boolean = false,
    val running: Boolean = false,
    val deviceName: String = "",
    val addressText: String = "",
    /** 启动失败原因（如未接入 WiFi）。 */
    val error: String? = null,
)

/** 发送状态（AC-9.4：进度 + 可取消；AC-9.6：失败原因）。 */
data class TransferSendState(
    val active: Boolean = false,
    val currentTitle: String = "",
    val index: Int = 0,
    val total: Int = 0,
    val fraction: Float = 0f,
    val error: String? = null,
)

/** 接收状态：落盘进度 → 复用导入流水线的待确认 / 失败。 */
data class TransferReceiveState(
    val receiving: Boolean = false,
    val fileName: String = "",
    val fraction: Float = 0f,
    val pending: PendingImport? = null,
    val error: String? = null,
    /** 确认导入后的成功提示（一次性）。 */
    val importedTitle: String? = null,
)

/** 迁移方向（发送端打包上行 / 接收端解包导入）。 */
enum class MigrationDirection { NONE, SEND, RECEIVE }

/** 进度同步状态（AC-11.1 ~ AC-11.3）。 */
data class TransferSyncState(
    val active: Boolean = false,
    val phaseLabel: String = "",
    val error: String? = null,
    /** 完成后的一次性结果（被覆盖 / 新增书签等计数）。 */
    val report: SyncReport? = null,
)

/**
 * 配对状态（R-01 / G1：UI 展示 + 对话框驱动）。
 *
 * @param status 配对状态机状态（[PairingGate.Status]）
 * @param displayCode 本机展示中的配对码（PAIRING 时非空，仅内存）
 * @param pairedDeviceId 已配对的对端设备 ID（PAIRED 时非空）
 * @param busy 配对探测请求进行中（输入侧；防重复提交）
 * @param error 配对失败原因（输入侧；下次尝试前保持展示）
 */
data class TransferPairingState(
    val status: PairingGate.Status = PairingGate.Status.UNPAIRED,
    val displayCode: String? = null,
    val pairedDeviceId: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * 换机迁移状态（AC-10.1 ~ AC-10.4）。
 *
 * @param report 完成后的成功 / 失败清单（AC-10.3）；仅接收端产出
 *   （发送端完成以 active 落潮 + VM 一次性提示表达）
 */
data class TransferMigrationState(
    val active: Boolean = false,
    val direction: MigrationDirection = MigrationDirection.NONE,
    val phaseLabel: String = "",
    val fraction: Float = 0f,
    val index: Int = 0,
    val total: Int = 0,
    val currentTitle: String = "",
    val error: String? = null,
    val report: MigrationReport? = null,
)

@Serializable
private data class ServerMeta(
    val ok: Boolean,
    val deviceName: String,
    val protocol: Int,
)

/**
 * 局域网传输管理器（UI 层只依赖本接口，单测以 fake 替代）。
 *
 * 生命周期与「传书页面」绑定：进入页面 [startService]，离开 [stopService]。
 */
interface LanTransferManager {

    val serviceState: StateFlow<TransferServiceState>
    val devices: StateFlow<List<DiscoveredDevice>>
    val sendState: StateFlow<TransferSendState>
    val receiveState: StateFlow<TransferReceiveState>
    val migration: StateFlow<TransferMigrationState>
    val sync: StateFlow<TransferSyncState>

    /** 配对状态（展示端码 / 已配对对端 / 输入侧探测进度与错误）。 */
    val pairing: StateFlow<TransferPairingState>

    /** 启动局域网服务（发现 + HTTP 服务）。已在运行时幂等返回。 */
    fun startService()

    /** 停止服务并清理状态。 */
    fun stopService()

    /**
     * 展示端：生成配对码并进入等待（PAIRING）。
     *
     * 对端在传书页输入该码即完成配对（服务端验签落定）。
     */
    fun beginPairing()

    /** 取消配对：解除已配对关系 / 中止等待，secret 清零，回到 UNPAIRED。 */
    fun cancelPairing()

    /**
     * 输入侧：以对端设备 + 输入的配对码发起配对探测（AC-9.x 之外的 R-01 流程）。
     *
     * 探测成功即配对落定（后续发送 / 迁移 / 同步均以该 secret 鉴权）；
     * 失败原因经 [pairing] 的 error 字段暴露。
     */
    fun pairWith(device: DiscoveredDevice, code: String)

    /** 向已发现设备发送多本书（逐本上传，任一失败即中止，AC-9.6）。 */
    fun sendBooks(target: DiscoveredDevice, books: List<Book>)

    /** 取消进行中的发送。 */
    fun cancelSend()

    /** 确认导入收到的书（走与本地导入完全相同的入库路径，AC-9.5）。 */
    fun confirmReceived()

    /** 取消导入收到的书（清理半成品）。 */
    fun cancelReceived()

    /** 重新解析收到的书（切换编码 / 切分策略，与本地导入同能力，AC-9.5）。 */
    fun reparseReceived(charsetName: String, strategy: SplitStrategy)

    /** 消费「已导入」一次性提示（Snackbar 展示后调用，避免重组重复弹出）。 */
    fun consumeImportedTitle()

    /**
     * 换机迁移（AC-10.1）：把整库（书籍源文件 + 进度 / 书签）打包为单一 zip
     * 发送至对端。已在迁移中时幂等忽略。
     */
    fun startMigration(target: DiscoveredDevice)

    /** 取消进行中的迁移（发送或接收）。 */
    fun cancelMigration()

    /** 关闭迁移结果报告（AC-10.3 清单展示后）。 */
    fun dismissMigrationReport()

    /**
     * 与指定设备双向同步阅读进度与书签（AC-11.1 ~ AC-11.3）。
     * 已在同步中时幂等忽略。
     */
    fun startSync(device: DiscoveredDevice)

    /** 关闭同步结果提示。 */
    fun dismissSyncReport()
}

/**
 * [LanTransferManager] 默认实现。
 *
 * 组合：
 * - [LanDiscovery]（UDP 发现状态机）+ [UdpSocketTransport]（发现端口）；
 * - [TransferHttpServer]（仅绑 WiFi 网卡地址，AC-9.7）+ 本类的接收 handler，
 *   路由前经 [pairingGate] 鉴权（未配对拒收，R-01 / G1）；
 * - [TransferClient]（发送；Bearer 令牌 + 摘要声明，配对成功后才拿得到 secret）；
 * - [PairingGate]（配对状态机 + secret 生命周期：仅内存，取消即清零）；
 * - 接收落盘 cacheDir/transfer/ → FileProvider content Uri → [BookImporter]
 *   （复用既有导入流水线，不出现第二条导入逻辑，AC-9.5）；
 * - 换机迁移（[MigrationBundleCodec] 打包 / 解包 + [MigrationProcessor] 编排，
 *   导入与回写同样复用 [BookImporter] / [BookRepository]，AC-10.2）。
 */
class DefaultLanTransferManager(
    private val context: Context,
    private val bookImporter: BookImporter,
    private val bookRepository: BookRepository,
    private val bookTextStore: BookTextStore,
    private val identityStore: DeviceIdentityStore,
    private val scope: CoroutineScope,
    /**
     * WiFi 网卡地址解析（生产见 [LanAddresses.wifiIPv4]）。
     *
     * 抽为注入点：编排层流程单测（G4）需要驱动真实 HTTP 服务，而测试环境
     * （Robolectric 沙箱）无 wlan 网卡，注入回环地址即可不改生产行为地
     * 覆盖「服务已启动」之后的全部接收 / 迁移 / 同步分支。
     */
    private val wifiAddressProvider: () -> InetAddress? = { LanAddresses.wifiIPv4() },
    /**
     * UDP 发现端口（生产固定 [DISCOVERY_PORT]）。
     *
     * 抽为注入点：同一 JVM 内并存多个管理器实例（同步 / 迁移的双端编排单测）
     * 时各持独立发现端口，避免固定端口争抢导致启动失败。
     */
    private val discoveryPort: Int = DISCOVERY_PORT,
) : LanTransferManager {

    private val _serviceState = MutableStateFlow(TransferServiceState())
    override val serviceState: StateFlow<TransferServiceState> = _serviceState.asStateFlow()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _sendState = MutableStateFlow(TransferSendState())
    override val sendState: StateFlow<TransferSendState> = _sendState.asStateFlow()

    private val _receiveState = MutableStateFlow(TransferReceiveState())
    override val receiveState: StateFlow<TransferReceiveState> = _receiveState.asStateFlow()

    private val _migration = MutableStateFlow(TransferMigrationState())
    override val migration: StateFlow<TransferMigrationState> = _migration.asStateFlow()

    private val _sync = MutableStateFlow(TransferSyncState())
    override val sync: StateFlow<TransferSyncState> = _sync.asStateFlow()

    private val _pairing = MutableStateFlow(TransferPairingState())
    override val pairing: StateFlow<TransferPairingState> = _pairing.asStateFlow()

    private var syncJob: Job? = null

    /**
     * 配对门（R-01 / G1）：HTTP 服务的路由前鉴权器 + secret 生命周期。
     *
     * [PairingGate.onPaired] 在服务端 accept 线程触发（对端输入正确配对码），
     * 仅做 MutableStateFlow 赋值，线程安全。
     */
    private val pairingGate = PairingGate(
        selfDeviceId = { identityStore.deviceId() },
        onPaired = { peerId ->
            _pairing.value = TransferPairingState(
                status = PairingGate.Status.PAIRED,
                pairedDeviceId = peerId,
            )
        },
    )

    /**
     * 传输客户端（凭证随取随用：已配对时由 [pairingGate] 供给 secret）。
     *
     * 单实例复用：无连接状态，每个请求自建 HttpURLConnection。
     */
    private val transferClient = TransferClient(
        secretProvider = { pairingGate.secret },
        selfDeviceId = { identityStore.deviceId() },
    )

    /** spill 目录（服务端鉴权前置落盘；服务启动时清理上次残留）。 */
    private val spillDir: File
        get() = File(context.cacheDir, "transfer/spill")

    /** 请求体接收进度（服务端 spill 阶段驱动 UI 的「正在接收」）。 */
    private val onReceiveProgress: (TransferEndpoint, String, Float) -> Unit = { endpoint, name, fraction ->
        when (endpoint) {
            TransferEndpoint.UPLOAD -> {
                // 新上传的落盘进度即顶替旧待确认（进度与 pending 共用单状态流，
                // 天然互斥）：作废旧 pending 的后台解析与导入元数据，避免
                // 「上一本没确认就被下一本顶掉」时的残留
                val stale = _receiveState.value.pending
                if (stale != null) {
                    bookImporter.cancel(stale)
                    receiveJob?.cancel()
                    deleteReceivedTemp()
                }
                _receiveState.value = TransferReceiveState(
                    receiving = true,
                    fileName = name,
                    fraction = fraction,
                )
            }

            TransferEndpoint.BUNDLE -> {
                // 发送中的迁移与本接收共用同一状态通道：progress 回调先于
                // onBundle 的 busy 拒绝触发，若无条件改写会把 SEND 状态踩成
                // RECEIVE（既错乱 UI，又让 busy 拒绝永远不生效）。
                // 发送进行中（active + SEND）时保持原状，交由 onBundle 拒绝。
                val sending = _migration.value.active &&
                    _migration.value.direction == MigrationDirection.SEND
                if (!sending) {
                    _migration.value = TransferMigrationState(
                        active = true,
                        direction = MigrationDirection.RECEIVE,
                        phaseLabel = "接收迁移包",
                        fraction = fraction,
                    )
                }
            }
        }
    }

    /** 请求体接收中止（鉴权后断链 / 摘要不符：服务端已清理 spill，UI 复位）。 */
    private val onReceiveAborted: (TransferEndpoint, String) -> Unit = { endpoint, reason ->
        when (endpoint) {
            TransferEndpoint.UPLOAD -> _receiveState.value = TransferReceiveState(error = reason)
            TransferEndpoint.BUNDLE -> {
                // 同上：发送进行中的迁移不接收中止反馈（该上传本就要被 busy 拒绝）
                val sending = _migration.value.active &&
                    _migration.value.direction == MigrationDirection.SEND
                if (!sending) {
                    _migration.value = TransferMigrationState(error = reason)
                }
            }
        }
    }

    private val syncJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var server: TransferHttpServer? = null
    private var discovery: LanDiscovery? = null
    private var transport: UdpSocketTransport? = null
    private var serviceJob: Job? = null
    private var sendJob: Job? = null
    private var migrationJob: Job? = null

    /**
     * 收到的书的解析作业（R-10 / G10：onUpload 落盘即应答，prepare 转后台）。
     *
     * 与 [migrationJob] 同款背景化：大书（数十 MB TXT）的编码探测 / 切分
     * 不再占用 HTTP 连接线程，对端上传完立即拿到 200。
     */
    private var receiveJob: Job? = null

    /** 迁移导入网关（FileProvider → 导入流水线 → 仓储回写）。 */
    private val migrationGateway = object : MigrationImportGateway {

        override suspend fun importFromSource(
            sourceFile: File,
            snapshot: BookStateSnapshot,
        ): ImportOutcome {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                sourceFile,
            )
            return when (val result = bookImporter.prepare(uri)) {
                is PrepareResult.Success -> {
                    val bookId = bookImporter.confirm(result.pending)
                    val book = bookRepository.getBook(bookId)
                    if (book != null) {
                        ImportOutcome.Imported(book)
                    } else {
                        ImportOutcome.Skipped("入库成功但读取失败")
                    }
                }

                is PrepareResult.Duplicate -> ImportOutcome.Skipped("内容已存在于书架")

                is PrepareResult.Unsupported -> ImportOutcome.Skipped(result.error.userMessage)

                is PrepareResult.Failure -> ImportOutcome.Skipped(result.error.userMessage)
            }
        }

        override suspend fun findBySha256(sha256: String): Book? =
            bookRepository.findBySha256(sha256)

        override suspend fun writeProgress(book: Book, snapshot: BookStateSnapshot) {
            bookRepository.saveReaderPosition(
                bookId = book.id,
                position = ReaderPosition(
                    chapterIndex = snapshot.lastChapterIndex,
                    paragraphIndex = snapshot.lastParagraphIndex,
                    scrollOffset = snapshot.lastScrollOffset,
                    charOffset = snapshot.lastCharOffset,
                ),
                progressPercent = snapshot.progressPercent,
            )
        }

        override suspend fun writeBookmarks(book: Book, snapshot: BookStateSnapshot) {
            bookRepository.deleteBookmarksByBook(book.id)
            snapshot.bookmarks.forEach { mark ->
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

    private val migrationProcessor = MigrationProcessor(migrationGateway)

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

    /** 收到的临时文件（确认 / 取消后删除）。 */
    @Volatile
    private var receivedTempFile: File? = null

    private val handler = object : TransferHttpHandler {

        override suspend fun meta(): String = Json.encodeToString(
            ServerMeta.serializer(),
            ServerMeta(ok = true, deviceName = identityStore.deviceName(), protocol = TRANSFER_VERSION),
        )

        override suspend fun onUpload(fileName: String, contentLength: Long, bodyFile: File): String? {
            val safeName = sanitizeFileName(fileName) ?: run {
                bodyFile.delete()
                return "文件名不合法"
            }
            val tempDir = File(context.cacheDir, "transfer").apply { mkdirs() }
            val target = File(tempDir, safeName)
            // 已鉴权落盘的文件直接改名到位（同分区原子 rename，零拷贝）；
            // rename 失败（极少见的跨分区场景）回落拷贝后删除原件
            if (!bodyFile.renameTo(target)) {
                bodyFile.copyTo(target, overwrite = true)
                bodyFile.delete()
            }

            // 单槽语义（与旧「顺序处理连接」一致）：上一本尚未确认就被新上传
            // 顶替时，取消其后台解析并清理临时文件——UI 同时只保留一个
            // 待确认导入（pending 的作废已在 onReceiveProgress 完成）
            receiveJob?.cancel()
            deleteReceivedTemp()
            receivedTempFile = target

            // 字节已完整落盘（服务端已鉴权 + 摘要核对）：解析导入转后台，
            // HTTP 尽早应答（R-10 / G10：大书 prepare 不得占着连接线程）
            receiveJob = scope.launch {
                prepareReceived(target)
            }
            return null
        }

        override suspend fun onBundle(fileName: String, contentLength: Long, bodyFile: File): String? {
            // 发送中的迁移占用同一状态通道：接收请求让位（服务端顺序处理，真正的
            // 并发冲突不存在，此处仅防「边发边收」的 UI 状态互踩）
            if (_migration.value.active && _migration.value.direction == MigrationDirection.SEND) {
                bodyFile.delete()
                return "busy"
            }
            val tempDir = File(context.cacheDir, "transfer").apply { mkdirs() }
            val target = File(tempDir, "incoming.bundle.zip")
            if (!bodyFile.renameTo(target)) {
                bodyFile.copyTo(target, overwrite = true)
                bodyFile.delete()
            }
            // 字节已完整落盘（服务端已鉴权 + 摘要核对）：解包与逐本导入转为后台，
            // HTTP 尽早应答（避免对端超时）
            migrationJob = scope.launch {
                processIncomingBundle(target)
            }
            return null
        }

        override suspend fun syncState(): String {
            val envelope = localSyncGateway.snapshot(identityStore.deviceId())
            return syncJson.encodeToString(SyncEnvelope.serializer(), envelope)
        }

        override suspend fun onSyncApply(payload: String): String? {
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

    /**
     * 落盘后的解析导入（原 onUpload 内联逻辑，R-10 / G10 后台化）。
     *
     * 与 HTTP 应答解耦：prepare 的各结局（成功待确认 / 重复 / 不支持 / 失败）
     * 全部经 [receiveState] 上浮 UI，对端收到的始终是「字节已送达」的 200。
     */
    private suspend fun prepareReceived(target: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target,
            )
            val result = bookImporter.prepare(uri)
            when (result) {
                is PrepareResult.Success -> {
                    _receiveState.value = TransferReceiveState(pending = result.pending)
                }

                is PrepareResult.Duplicate -> {
                    // 内容已存在：丢弃临时文件并提示，不弹导入确认
                    deleteReceivedTemp()
                    _receiveState.value = TransferReceiveState(
                        error = "《${result.existing.title}》已存在于书架，已跳过",
                    )
                }

                is PrepareResult.Unsupported -> {
                    deleteReceivedTemp()
                    _receiveState.value = TransferReceiveState(error = result.error.userMessage)
                }

                is PrepareResult.Failure -> {
                    deleteReceivedTemp()
                    _receiveState.value = TransferReceiveState(error = result.error.userMessage)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 解析流水线崩溃（磁盘异常等）：改造前由 HTTP 层的 try/catch 兜成
            // 500，后台化后改经 receiveState 上浮（对端早已收到 200——字节
            // 确已完整送达，失败的是本机解析这一环）
            _receiveState.value = TransferReceiveState(
                error = "解析失败：${e.message ?: "未知错误"}",
            )
        }
    }

    override fun startService() {
        if (serviceJob?.isActive == true) return
        _serviceState.value = TransferServiceState(starting = true)
        serviceJob = scope.launch {
            val deviceName = identityStore.deviceName()
            val address = wifiAddressProvider()
            if (address == null) {
                _serviceState.value = TransferServiceState(error = "未接入 WiFi，无法启动传书服务")
                return@launch
            }
            // 清理上次异常退出可能残留的 spill 临时文件（cacheDir 可被系统回收，
            // 此处仅避免堆积）
            spillDir.deleteRecursively()
            val httpServer = TransferHttpServer(
                bindAddress = address,
                port = 0,
                handler = handler,
                tokenVerifier = pairingGate,
                spillDir = spillDir,
                onReceiveProgress = onReceiveProgress,
                onReceiveAborted = onReceiveAborted,
            )
            val port = httpServer.actualPort
            val udpSocket = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(discoveryPort))
                }
            } catch (e: Exception) {
                httpServer.close()
                _serviceState.value = TransferServiceState(error = "发现端口被占用：${e.message}")
                return@launch
            }
            server = httpServer
            val udpTransport = UdpSocketTransport(udpSocket)
            transport = udpTransport
            val lanDiscovery = LanDiscovery(
                selfDeviceId = identityStore.deviceId(),
                selfDeviceName = deviceName,
                httpPortProvider = { httpServer.actualPort },
                transport = udpTransport,
            )
            discovery = lanDiscovery
            launch(Dispatchers.IO) { httpServer.serve() }
            launch(Dispatchers.IO) { runDiscoveryLoop(lanDiscovery, udpTransport) }
            launch {
                lanDiscovery.devices.collect { list -> _devices.value = list }
            }
            _serviceState.value = TransferServiceState(
                running = true,
                deviceName = deviceName,
                addressText = "${address.hostAddress}:$port",
            )
        }
    }

    override fun stopService() {
        sendJob?.cancel()
        sendJob = null
        serviceJob?.cancel()
        serviceJob = null
        receiveJob?.cancel()
        receiveJob = null
        server?.close()
        server = null
        transport?.close()
        transport = null
        discovery?.reset()
        discovery = null
        // 配对随服务会话终结：secret 清零，下次进入传书页需重新配对
        pairingGate.cancelPairing()
        _pairing.value = TransferPairingState()
        _devices.value = emptyList()
        _serviceState.value = TransferServiceState()
    }

    // ---- 配对（R-01 / G1） ----

    override fun beginPairing() {
        val code = pairingGate.beginPairing()
        _pairing.value = TransferPairingState(
            status = PairingGate.Status.PAIRING,
            displayCode = code,
        )
    }

    override fun cancelPairing() {
        pairingGate.cancelPairing()
        _pairing.value = TransferPairingState()
    }

    override fun pairWith(device: DiscoveredDevice, code: String) {
        if (_pairing.value.busy) return
        val candidate = pairingGate.deriveForPeer(device.deviceId, code)
        if (candidate == null) {
            _pairing.value = _pairing.value.copy(busy = false, error = "配对码应为 6 位数字")
            return
        }
        _pairing.value = _pairing.value.copy(busy = true, error = null)
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    transferClient.probePairing(
                        host = device.host,
                        port = device.httpPort,
                        candidateSecret = candidate,
                        selfDeviceId = identityStore.deviceId(),
                    )
                }
            } catch (e: Exception) {
                PairingProbeResult.Unreachable
            }
            when (result) {
                is PairingProbeResult.Paired -> {
                    // 候选 secret 移交配对门（其生命周期自此由门管理）
                    pairingGate.onPairingConfirmed(device.deviceId, candidate)
                    _pairing.value = TransferPairingState(
                        status = PairingGate.Status.PAIRED,
                        pairedDeviceId = device.deviceId,
                    )
                }

                is PairingProbeResult.Rejected -> {
                    candidate.fill(0)
                    _pairing.value = _pairing.value.copy(
                        busy = false,
                        error = "配对码不正确，请确认后重试",
                    )
                }

                is PairingProbeResult.Failed -> {
                    candidate.fill(0)
                    _pairing.value = _pairing.value.copy(busy = false, error = "对端无响应，请重试")
                }

                is PairingProbeResult.Unreachable -> {
                    candidate.fill(0)
                    _pairing.value = _pairing.value.copy(
                        busy = false,
                        error = "无法连接对端，请确认两台设备处于同一 WiFi 且对端已打开传书页",
                    )
                }
            }
        }
    }

    override fun sendBooks(target: DiscoveredDevice, books: List<Book>) {
        if (books.isEmpty()) return
        if (_sendState.value.active) return
        _sendState.value = TransferSendState(active = true, total = books.size, index = 1)
        sendJob = scope.launch {
            try {
                books.forEachIndexed { i, book ->
                    _sendState.value = _sendState.value.copy(
                        currentTitle = book.title,
                        index = i + 1,
                        fraction = 0f,
                    )
                    val source = bookTextStore.sourceFile(book)
                    if (!source.isFile) {
                        _sendState.value = _sendState.value.copy(
                            active = false,
                            error = "《${book.title}》源文件丢失，发送中止",
                        )
                        return@launch
                    }
                    val displayName = "${book.title}.${book.format.extension}"
                    val result = transferClient.upload(
                        host = target.host,
                        port = target.httpPort,
                        file = source,
                        displayName = displayName,
                    ) { fraction ->
                        _sendState.value = _sendState.value.copy(fraction = fraction)
                    }
                    if (result is UploadResult.Failure) {
                        _sendState.value = _sendState.value.copy(
                            active = false,
                            error = if (result.unauthorized) {
                                result.message
                            } else {
                                "《${book.title}》发送失败：${result.message}"
                            },
                        )
                        return@launch
                    }
                }
                _sendState.value = _sendState.value.copy(active = false, fraction = 1f)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _sendState.value = TransferSendState()
                throw e
            } catch (e: Exception) {
                _sendState.value = _sendState.value.copy(
                    active = false,
                    error = e.message ?: "发送异常",
                )
            }
        }
    }

    override fun cancelSend() {
        // 取消 sendBooks 协程：其 CancellationException 分支负责复位状态
        sendJob?.cancel()
        sendJob = null
        _sendState.value = TransferSendState()
    }

    override fun confirmReceived() {
        val pending = _receiveState.value.pending ?: return
        scope.launch {
            try {
                bookImporter.confirm(pending)
                deleteReceivedTemp()
                _receiveState.value = TransferReceiveState(importedTitle = pending.title)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 入库失败（磁盘异常等）：保留 pending 与临时文件供重试，
                // 错误上浮 UI（AC-9.6：提示失败原因）而非静默吞掉
                _receiveState.value = _receiveState.value.copy(
                    error = "导入失败：${e.message ?: "未知错误"}",
                )
            }
        }
    }

    override fun cancelReceived() {
        val pending = _receiveState.value.pending
        if (pending != null) {
            bookImporter.cancel(pending)
        }
        // 后台 prepare 可能仍在跑（落盘即应答后）：一并取消，
        // 避免其完成后把已取消的状态改写回 pending
        receiveJob?.cancel()
        receiveJob = null
        deleteReceivedTemp()
        _receiveState.value = TransferReceiveState()
    }

    override fun reparseReceived(charsetName: String, strategy: SplitStrategy) {
        val pending = _receiveState.value.pending ?: return
        scope.launch {
            val reparsed = bookImporter.reparse(pending, charsetName, strategy)
            _receiveState.value = if (reparsed == null) {
                _receiveState.value.copy(error = "重新解析失败，请重试")
            } else {
                _receiveState.value.copy(pending = reparsed)
            }
        }
    }

    override fun consumeImportedTitle() {
        if (_receiveState.value.importedTitle != null) {
            _receiveState.value = _receiveState.value.copy(importedTitle = null)
        }
    }

    // ---- 换机迁移 ----

    override fun startMigration(target: DiscoveredDevice) {
        if (_migration.value.active) return
        _migration.value = TransferMigrationState(
            active = true,
            direction = MigrationDirection.SEND,
            phaseLabel = "收集书库",
        )
        migrationJob = scope.launch {
            var bundleFile: File? = null
            try {
                val books = bookRepository.observeBooks().first()
                if (books.isEmpty()) {
                    _migration.value = TransferMigrationState(error = "书架为空，没有可迁移的书籍")
                    return@launch
                }
                _migration.value = _migration.value.copy(total = books.size, phaseLabel = "打包书库")
                val snapshots = mutableListOf<BookStateSnapshot>()
                val sources = mutableMapOf<String, File>()
                books.forEachIndexed { index, book ->
                    _migration.value = _migration.value.copy(
                        index = index + 1,
                        currentTitle = book.title,
                        fraction = (index.toFloat() / books.size) * 0.2f,
                    )
                    val bookmarks = bookRepository.observeBookmarks(book.id).first()
                    snapshots += book.toSnapshot(bookmarks)
                    val source = bookTextStore.sourceFile(book)
                    if (source.isFile) sources[book.sha256] = source
                }
                val tempDir = File(context.cacheDir, "transfer").apply { mkdirs() }
                val zip = File(tempDir, "paperread-migrate.zip")
                MigrationBundleCodec.pack(MigrationManifest(books = snapshots), sources, zip)
                bundleFile = zip
                _migration.value = _migration.value.copy(
                    phaseLabel = "发送迁移包",
                    fraction = 0.2f,
                    currentTitle = "${books.size} 本书",
                )
                val result = transferClient.uploadBundle(
                    host = target.host,
                    port = target.httpPort,
                    file = zip,
                    displayName = "paperread-migrate.zip",
                ) { fraction ->
                    _migration.value = _migration.value.copy(fraction = 0.2f + fraction * 0.8f)
                }
                when (result) {
                    is UploadResult.Success -> _migration.value = _migration.value.copy(
                        active = false,
                        fraction = 1f,
                        phaseLabel = "已发送",
                    )

                    is UploadResult.Failure -> _migration.value = _migration.value.copy(
                        active = false,
                        error = if (result.unauthorized) {
                            result.message
                        } else {
                            "迁移包发送失败：${result.message}"
                        },
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _migration.value = TransferMigrationState(error = "已取消迁移")
                throw e
            } catch (e: Exception) {
                _migration.value = _migration.value.copy(
                    active = false,
                    error = e.message ?: "迁移异常",
                )
            } finally {
                bundleFile?.delete()
            }
        }
    }

    override fun cancelMigration() {
        // 状态复位由协程的取消分支负责（"已取消迁移"提示，见
        // processIncomingBundle / startMigration 的 CancellationException 分支）：
        // 此处若再无条件复位，与协程的写入存在竞态，取消反馈可能被吞掉。
        migrationJob?.cancel()
        migrationJob = null
    }

    override fun dismissMigrationReport() {
        _migration.value = TransferMigrationState()
    }

    // ---- 进度同步 ----

    override fun startSync(device: DiscoveredDevice) {
        if (_sync.value.active) return
        _sync.value = TransferSyncState(active = true, phaseLabel = "交换阅读状态")
        syncJob = scope.launch {
            val result = syncCoordinator.sync(device, identityStore.deviceId())
            _sync.value = result.fold(
                onSuccess = { outcome ->
                    TransferSyncState(active = false, phaseLabel = "同步完成", report = outcome.report)
                },
                onFailure = { error ->
                    TransferSyncState(active = false, error = error.message ?: "同步失败")
                },
            )
        }
    }

    override fun dismissSyncReport() {
        syncJob?.cancel()
        syncJob = null
        _sync.value = TransferSyncState()
    }

    /** 解包 → 逐本导入 → 按 AC-10.4 回写（在 [scope] 的后台上下文中执行）。 */
    private suspend fun processIncomingBundle(zip: File) {
        val workDir = File(context.cacheDir, "transfer/unpack").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            _migration.value = _migration.value.copy(phaseLabel = "解包迁移包", fraction = 1f)
            val unpacked = MigrationBundleCodec.unpack(zip, workDir)
            if (unpacked == null) {
                _migration.value = _migration.value.copy(
                    active = false,
                    error = "迁移包损坏或版本不兼容",
                )
                return
            }
            _migration.value = _migration.value.copy(
                total = unpacked.manifest.books.size,
                phaseLabel = "导入书籍",
            )
            val report = migrationProcessor.process(unpacked) { done, total, title ->
                _migration.value = _migration.value.copy(
                    index = done,
                    total = total,
                    currentTitle = title,
                    fraction = if (total == 0) 1f else done.toFloat() / total,
                )
            }
            _migration.value = _migration.value.copy(
                active = false,
                phaseLabel = "迁移完成",
                fraction = 1f,
                report = report,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            _migration.value = TransferMigrationState(error = "已取消迁移")
            throw e
        } catch (e: Exception) {
            _migration.value = _migration.value.copy(
                active = false,
                error = e.message ?: "迁移失败",
            )
        } finally {
            zip.delete()
            workDir.deleteRecursively()
        }
    }

    // ---- 内部 ----

    private suspend fun runDiscoveryLoop(discovery: LanDiscovery, udpTransport: UdpSocketTransport) {
        val broadcastAddress = InetAddress.getByName("255.255.255.255")
        while (currentCoroutineContext().isActive) {
            // 广播本机在线报文
            udpTransport.send(
                discovery.presenceBytes(isBroadcast = true),
                broadcastAddress,
                discoveryPort,
            )
            // 接收窗口 = 一个广播周期（超时即到期）
            var elapsed = 0L
            while (elapsed < ANNOUNCE_INTERVAL_MS) {
                val packet = udpTransport.receive((ANNOUNCE_INTERVAL_MS - elapsed).toInt())
                if (packet == null) {
                    elapsed = ANNOUNCE_INTERVAL_MS
                } else {
                    if (discovery.handleIncoming(packet)) {
                        udpTransport.send(
                            discovery.presenceBytes(isBroadcast = false),
                            packet.address,
                            discoveryPort,
                        )
                    }
                    elapsed += RECEIVE_POLL_MS
                }
            }
            discovery.tick()
        }
    }

    private fun deleteReceivedTemp() {
        receivedTempFile?.delete()
        receivedTempFile = null
    }

    private companion object {
        const val RECEIVE_POLL_MS = 500L
    }
}

/**
 * 文件名净化（HTTP 上传通道的路径穿越防线，与 [MigrationBundleCodec] 解包时的
 * zip slip 防护对称）：取末段 basename → 拒绝 "." / ".." → 剔除控制字符与
 * Windows 非法字符 → 限长 [SANITIZED_NAME_MAX_LENGTH]。
 *
 * 保留 Unicode 可打印字符：Android 文件系统无保留设备名（CON / PRN / COM1 等
 * 为 Windows 语义），中文书名等非 ASCII 名是正常输入，一并剔除会丢书名。
 *
 * @return 净化后的文件名；输入不合法（空 basename / 全非法字符）时返回 null，
 *   调用方应拒绝该上传
 */
internal fun sanitizeFileName(raw: String): String? {
    val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
    if (base.isBlank() || base == "." || base == "..") return null
    val cleaned = base.filter { it.code >= 32 && it !in "/\\:%*?\"<>|" }
        .take(SANITIZED_NAME_MAX_LENGTH)
    return cleaned.ifBlank { null }
}

/** 净化后文件名长度上限（超长名截断而非拒绝：对端展示名常带长书名）。 */
private const val SANITIZED_NAME_MAX_LENGTH = 64
