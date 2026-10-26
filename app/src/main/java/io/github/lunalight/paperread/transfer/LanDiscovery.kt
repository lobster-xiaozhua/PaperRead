package io.github.lunalight.paperread.transfer

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 已发现的局域网设备（AC-9.1：设备名 + IP:端口）。 */
data class DiscoveredDevice(
    val deviceId: String,
    val deviceName: String,
    /** 点分十进制 IPv4（HTTP 服务地址）。 */
    val host: String,
    val httpPort: Int,
    /** 最近一次收到其报文的时刻（[LanDiscovery] 注入时钟，测试可控）。 */
    val lastSeenAt: Long,
    /** 对端是否要求配对鉴权（v2 报文恒 true；UI 据此提示「需配对」）。 */
    val pairingRequired: Boolean = true,
)

/** 收到的 UDP 报文（data + 源地址；端口由传输层固定为发现端口，无需透出）。 */
data class UdpPacket(val data: ByteArray, val address: InetAddress) {
    override fun equals(other: Any?): Boolean =
        other is UdpPacket && data.contentEquals(other.data) && address == other.address

    override fun hashCode(): Int = 31 * data.contentHashCode() + address.hashCode()
}

/**
 * UDP 传输抽象（生产实现包 [DatagramSocket]，测试用内存队列）。
 *
 * [receive] 在超时内无报文时返回 null（不得抛异常表示超时）。
 */
interface DatagramTransport : Closeable {
    fun send(data: ByteArray, address: InetAddress, port: Int)
    fun receive(timeoutMs: Int): UdpPacket?
}

/** [DatagramTransport] 的 [DatagramSocket] 实现。 */
class UdpSocketTransport(private val socket: DatagramSocket) : DatagramTransport {

    override fun send(data: ByteArray, address: InetAddress, port: Int) {
        socket.send(DatagramPacket(data, data.size, address, port))
    }

    override fun receive(timeoutMs: Int): UdpPacket? {
        socket.soTimeout = timeoutMs
        val buffer = ByteArray(MAX_PACKET_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            UdpPacket(packet.data.copyOf(packet.length), packet.address)
        } catch (e: java.net.SocketTimeoutException) {
            null
        }
    }

    override fun close() {
        socket.close()
    }

    companion object {
        /** PresencePacket 的 JSON 远小于此；留足余量防截断。 */
        const val MAX_PACKET_BYTES: Int = 2048
    }
}

/**
 * 局域网发现状态机（纯 JVM：不持有任何 Android 类型，协程循环由外层驱动）。
 *
 * 协议行为：
 * - 收到**广播**报文且非本机 → 记录 / 刷新设备（host = 报文源地址），
 *   并告知外层单播应答（返回值 true）；应答不再触发应答，链路必然终止；
 * - 收到**应答**报文且非本机 → 仅记录 / 刷新设备；
 * - [tick] 到期：清理超过 [DEVICE_TIMEOUT_MS] 未刷新的设备。
 *
 * 网络动作（广播 / 应答 / 接收）全部经注入的 [DatagramTransport] 由外层执行，
 * 测试以内存传输层驱动，无需真实 socket。
 */
class LanDiscovery(
    private val selfDeviceId: String,
    private val selfDeviceName: String,
    private val httpPortProvider: () -> Int,
    private val transport: DatagramTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())

    /** 当前在线设备（按设备名排序，稳定呈现）。 */
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    /** 构造本机在线报文（广播 = true，应答 = false）。 */
    fun presenceBytes(isBroadcast: Boolean): ByteArray =
        TransferProtocol.encodePresence(selfDeviceId, selfDeviceName, httpPortProvider(), isBroadcast)

    /**
     * 处理收到的报文。
     *
     * @return true 表示外层应以本机在线报文（isBroadcast=false）单播应答来源地址；
     *         本机报文 / 非法报文 / 应答类报文一律 false。
     */
    fun handleIncoming(packet: UdpPacket): Boolean {
        val presence = TransferProtocol.decodePresence(packet.data) ?: return false
        if (presence.deviceId == selfDeviceId) return false
        val host = packet.address.hostAddress ?: return false
        val device = DiscoveredDevice(
            deviceId = presence.deviceId,
            deviceName = presence.deviceName,
            host = host,
            httpPort = presence.httpPort,
            lastSeenAt = clock(),
            pairingRequired = presence.pairingRequired,
        )
        _devices.value = (_devices.value.filter { it.deviceId != device.deviceId } + device)
            .sortedBy { it.deviceName }
        return presence.isBroadcast
    }

    /** 到期动作：清理超时未现身的设备（由外层广播周期驱动）。 */
    fun tick() {
        val now = clock()
        _devices.value = _devices.value.filter { now - it.lastSeenAt <= DEVICE_TIMEOUT_MS }
    }

    /** 重置全部状态（服务停止时）。 */
    fun reset() {
        _devices.value = emptyList()
    }
}
