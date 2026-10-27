package io.github.lunalight.paperread.transfer

import android.content.Context
import io.github.lunalight.paperread.data.repository.AndroidBookImportGateway
import io.github.lunalight.paperread.data.repository.BookImportGateway
import io.github.lunalight.paperread.data.repository.BookImporter
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 传输域的**编排中枢**（T-012 / R-11 从 1131 行的 `DefaultLanTransferManager` 拆出）：
 *
 * 1. **五条状态流 + 配对状态**的唯一持有者（UI 订阅的单一真相）；
 * 2. **服务生命周期**：WiFi 网卡地址 → HTTP 服务（仅绑该地址，AC-9.7）→
 *    UDP 发现（广播 / 应答 / 离线淘汰）→ 状态浮出；
 * 3. **配对门**（R-01 / G1）：[PairingGate] 的 secret 生命周期 + 候选探测；
 * 4. **四个流程的装配**：把状态流与共享依赖（客户端 / 仓储 / 导入器）注入
 *    [TransferSendController] / [TransferReceiveController] /
 *    [TransferMigrationController] / [TransferSyncController]，
 *    并实现 [TransferHttpHandler] 把服务端请求转给对应流程。
 *
 * 拆分前后**行为零变更**：所有状态赋值、错误话术、并发守卫、作业取消时机
 * 均按原顺序逐行搬迁；本类只负责「编排」与「装配」，单条流程的细节在各自 Controller。
 */
internal class TransferCoordinator(
    private val context: Context,
    private val identityStore: DeviceIdentityStore,
    private val scope: CoroutineScope,
    private val wifiAddressProvider: () -> InetAddress?,
    private val discoveryPort: Int,
    bookImporter: BookImporter,
    bookRepository: BookRepository,
    bookTextStore: BookTextStore,
) {

    private val _serviceState = MutableStateFlow(TransferServiceState())
    val serviceState: StateFlow<TransferServiceState> = _serviceState.asStateFlow()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _sendState = MutableStateFlow(TransferSendState())
    val sendState: StateFlow<TransferSendState> = _sendState.asStateFlow()

    private val _receiveState = MutableStateFlow(TransferReceiveState())
    val receiveState: StateFlow<TransferReceiveState> = _receiveState.asStateFlow()

    private val _migration = MutableStateFlow(TransferMigrationState())
    val migration: StateFlow<TransferMigrationState> = _migration.asStateFlow()

    private val _sync = MutableStateFlow(TransferSyncState())
    val sync: StateFlow<TransferSyncState> = _sync.asStateFlow()

    private val _pairing = MutableStateFlow(TransferPairingState())
    val pairing: StateFlow<TransferPairingState> = _pairing.asStateFlow()

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

    // ---- 四条流程（各自持有状态流与作业，互相之间只通过状态流与回调协作） ----

    val sendController = TransferSendController(bookTextStore, transferClient, scope, _sendState)

    val receiveController = TransferReceiveController(context, bookImporter, scope, _receiveState)

    /**
     * 共享导入网关（T-015 / AC-9.5 / AC-14.4）：迁移接收与备份恢复的唯一入口。
     *
     * 只在本模块装配一次；迁移 / 备份各自持有的是同一接口，不是同一段复制逻辑。
     */
    private val bookImportGateway: BookImportGateway =
        AndroidBookImportGateway(context, bookImporter, bookRepository)

    val migrationController = TransferMigrationController(
        context = context,
        bookImportGateway = bookImportGateway,
        bookRepository = bookRepository,
        bookTextStore = bookTextStore,
        transferClient = transferClient,
        scope = scope,
        migrationState = _migration,
    )

    val syncController = TransferSyncController(bookRepository, identityStore, transferClient, scope, _sync)

    /** spill 目录（服务端鉴权前置落盘；服务启动时清理上次残留）。 */
    private val spillDir: File
        get() = File(context.cacheDir, "transfer/spill")

    /**
     * 请求体接收进度的分发（服务端 spill 阶段驱动 UI 的「正在接收」）。
     *
     * 按端点分流到对应流程：传书走接收流程（单槽顶替），迁移走迁移流程
     * （发送中不改写状态）。
     */
    private val onReceiveProgress: (TransferEndpoint, String, Float) -> Unit = { endpoint, name, fraction ->
        when (endpoint) {
            TransferEndpoint.UPLOAD -> receiveController.onProgress(name, fraction)
            TransferEndpoint.BUNDLE -> migrationController.onReceiveProgress(fraction)
        }
    }

    /** 请求体接收中止（鉴权后断链 / 摘要不符：服务端已清理 spill，UI 复位）。 */
    private val onReceiveAborted: (TransferEndpoint, String) -> Unit = { endpoint, reason ->
        when (endpoint) {
            TransferEndpoint.UPLOAD -> receiveController.onAborted(reason)
            TransferEndpoint.BUNDLE -> migrationController.onReceiveAborted(reason)
        }
    }

    /** 服务端回调实现：只做路由，业务全在四条流程里。 */
    private val handler = object : TransferHttpHandler {

        override suspend fun meta(): String = Json.encodeToString(
            ServerMeta.serializer(),
            ServerMeta(ok = true, deviceName = identityStore.deviceName(), protocol = TRANSFER_VERSION),
        )

        override suspend fun onUpload(fileName: String, contentLength: Long, bodyFile: File): String? =
            receiveController.onUpload(fileName, bodyFile)

        override suspend fun onBundle(fileName: String, contentLength: Long, bodyFile: File): String? =
            migrationController.onBundle(bodyFile)

        override suspend fun syncState(): String = syncController.stateJson()

        override suspend fun onSyncApply(payload: String): String? = syncController.applyRemote(payload)
    }

    private var server: TransferHttpServer? = null
    private var discovery: LanDiscovery? = null
    private var transport: UdpSocketTransport? = null
    private var serviceJob: Job? = null

    // ---- 服务生命周期 ----

    /** 启动局域网服务（发现 + HTTP 服务）。已在运行时幂等返回。 */
    fun startService() {
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

    /** 停止服务并清理状态。 */
    fun stopService() {
        sendController.cancelJob()
        serviceJob?.cancel()
        serviceJob = null
        receiveController.cancelJob()
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

    /**
     * 展示端：生成配对码并进入等待（PAIRING）。
     *
     * 对端在传书页输入该码即完成配对（服务端验签落定）。
     */
    fun beginPairing() {
        val code = pairingGate.beginPairing()
        _pairing.value = TransferPairingState(
            status = PairingGate.Status.PAIRING,
            displayCode = code,
        )
    }

    /** 取消配对：解除已配对关系 / 中止等待，secret 清零，回到 UNPAIRED。 */
    fun cancelPairing() {
        pairingGate.cancelPairing()
        _pairing.value = TransferPairingState()
    }

    /**
     * 输入侧：以对端设备 + 输入的配对码发起配对探测（AC-9.x 之外的 R-01 流程）。
     *
     * 探测成功即配对落定（后续发送 / 迁移 / 同步均以该 secret 鉴权）；
     * 失败原因经 [pairing] 的 error 字段暴露。
     */
    fun pairWith(device: DiscoveredDevice, code: String) {
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

    // ---- 内部 ----

    /** UDP 发现循环：广播在线 → 收窗口（超时即到期）→ 应答单播 → 淘汰离线。 */
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

    private companion object {
        const val RECEIVE_POLL_MS = 500L
    }
}

/** `GET /meta` 的响应体（设备名 + 协议版本）。 */
@Serializable
private data class ServerMeta(
    val ok: Boolean,
    val deviceName: String,
    val protocol: Int,
)
