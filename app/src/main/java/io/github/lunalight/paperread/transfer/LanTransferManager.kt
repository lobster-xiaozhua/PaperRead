package io.github.lunalight.paperread.transfer

import android.content.Context
import io.github.lunalight.paperread.data.repository.BookImporter
import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.data.repository.PrepareResult
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.reader.BookTextStore
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import java.io.File
import java.io.InputStream
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
import kotlinx.coroutines.runBlocking
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

    /** 启动局域网服务（发现 + HTTP 服务）。已在运行时幂等返回。 */
    fun startService()

    /** 停止服务并清理状态。 */
    fun stopService()

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
}

/**
 * [LanTransferManager] 默认实现。
 *
 * 组合：
 * - [LanDiscovery]（UDP 发现状态机）+ [UdpSocketTransport]（发现端口）；
 * - [TransferHttpServer]（仅绑 WiFi 网卡地址，AC-9.7）+ 本类的接收 handler；
 * - [TransferClient]（发送）；
 * - 接收落盘 cacheDir/transfer/ → FileProvider content Uri → [BookImporter]
 *   （复用既有导入流水线，不出现第二条导入逻辑，AC-9.5）。
 */
class DefaultLanTransferManager(
    private val context: Context,
    private val bookImporter: BookImporter,
    private val bookTextStore: BookTextStore,
    private val identityStore: DeviceIdentityStore,
    private val scope: CoroutineScope,
) : LanTransferManager {

    private val _serviceState = MutableStateFlow(TransferServiceState())
    override val serviceState: StateFlow<TransferServiceState> = _serviceState.asStateFlow()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _sendState = MutableStateFlow(TransferSendState())
    override val sendState: StateFlow<TransferSendState> = _sendState.asStateFlow()

    private val _receiveState = MutableStateFlow(TransferReceiveState())
    override val receiveState: StateFlow<TransferReceiveState> = _receiveState.asStateFlow()

    private var server: TransferHttpServer? = null
    private var discovery: LanDiscovery? = null
    private var transport: UdpSocketTransport? = null
    private var serviceJob: Job? = null
    private var sendJob: Job? = null

    /** 收到的临时文件（确认 / 取消后删除）。 */
    @Volatile
    private var receivedTempFile: File? = null

    private val handler = object : TransferHttpHandler {

        override fun meta(): String = Json.encodeToString(
            ServerMeta.serializer(),
            ServerMeta(ok = true, deviceName = identityStore.deviceName(), protocol = TRANSFER_VERSION),
        )

        override fun onUpload(fileName: String, contentLength: Long, body: InputStream): String? {
            val safeName = sanitizeFileName(fileName) ?: return "文件名不合法"
            val tempDir = File(context.cacheDir, "transfer").apply { mkdirs() }
            val target = File(tempDir, safeName)
            _receiveState.value = TransferReceiveState(receiving = true, fileName = safeName)
            var copied = 0L
            try {
                target.outputStream().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (copied < contentLength) {
                        val want = minOf(buffer.size.toLong(), contentLength - copied).toInt()
                        val read = body.read(buffer, 0, want)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        copied += read
                        _receiveState.value = _receiveState.value.copy(
                            fraction = (copied.toFloat() / contentLength).coerceIn(0f, 1f),
                        )
                    }
                }
                if (copied != contentLength) {
                    target.delete()
                    _receiveState.value = TransferReceiveState(error = "传输不完整，已丢弃")
                    return "incomplete"
                }
            } catch (e: Exception) {
                target.delete()
                _receiveState.value = TransferReceiveState(error = "保存失败：${e.message}")
                return e.message ?: "io"
            }

            // 落盘完成 → FileProvider content Uri → 复用导入流水线（AC-9.5）
            receivedTempFile = target
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target,
            )
            val result = runBlocking { bookImporter.prepare(uri) }
            return when (result) {
                is PrepareResult.Success -> {
                    _receiveState.value = TransferReceiveState(pending = result.pending)
                    null
                }

                is PrepareResult.Duplicate -> {
                    // 内容已存在：丢弃临时文件并提示，不弹导入确认
                    deleteReceivedTemp()
                    _receiveState.value = TransferReceiveState(
                        error = "《${result.existing.title}》已存在于书架，已跳过",
                    )
                    null
                }

                is PrepareResult.Unsupported -> {
                    deleteReceivedTemp()
                    _receiveState.value = TransferReceiveState(error = result.error.userMessage)
                    null
                }

                is PrepareResult.Failure -> {
                    deleteReceivedTemp()
                    _receiveState.value = TransferReceiveState(error = result.error.userMessage)
                    null
                }
            }
        }
    }

    override fun startService() {
        if (serviceJob?.isActive == true) return
        _serviceState.value = TransferServiceState(starting = true)
        serviceJob = scope.launch {
            val deviceName = identityStore.deviceName()
            val address = LanAddresses.wifiIPv4()
            if (address == null) {
                _serviceState.value = TransferServiceState(error = "未接入 WiFi，无法启动传书服务")
                return@launch
            }
            val httpServer = TransferHttpServer(address, 0, handler)
            val port = httpServer.actualPort
            val udpSocket = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
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
        server?.close()
        server = null
        transport?.close()
        transport = null
        discovery?.reset()
        discovery = null
        _devices.value = emptyList()
        _serviceState.value = TransferServiceState()
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
                    val result = TransferClient().upload(
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
                            error = "《${book.title}》发送失败：${result.message}",
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
            bookImporter.confirm(pending)
            deleteReceivedTemp()
            _receiveState.value = TransferReceiveState(importedTitle = pending.title)
        }
    }

    override fun cancelReceived() {
        val pending = _receiveState.value.pending
        if (pending != null) {
            bookImporter.cancel(pending)
        }
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

    // ---- 内部 ----

    private suspend fun runDiscoveryLoop(discovery: LanDiscovery, udpTransport: UdpSocketTransport) {
        val broadcastAddress = InetAddress.getByName("255.255.255.255")
        while (currentCoroutineContext().isActive) {
            // 广播本机在线报文
            udpTransport.send(
                discovery.presenceBytes(isBroadcast = true),
                broadcastAddress,
                DISCOVERY_PORT,
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
                            DISCOVERY_PORT,
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

    private fun sanitizeFileName(raw: String): String? {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        if (base.isBlank()) return null
        val cleaned = base.filter { it.code in 32..126 && it !in "/\\:%*?\"<>|" }
            .take(64)
        return cleaned.ifBlank { null }
    }

    private companion object {
        const val RECEIVE_POLL_MS = 500L
    }
}
