package io.github.lunalight.paperread.transfer

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内存 UDP 传输层：测试完全可控（无真实 socket，无时序不确定性）。 */
private class FakeDatagramTransport : DatagramTransport {

    val sent = mutableListOf<Triple<ByteArray, InetAddress, Int>>()

    /** 待投递报文队列（receive 取一个；空则立即返回 null）。 */
    val inbox = ArrayDeque<UdpPacket>()

    override fun send(data: ByteArray, address: InetAddress, port: Int) {
        sent += Triple(data, address, port)
    }

    override fun receive(timeoutMs: Int): UdpPacket? = inbox.removeFirstOrNull()

    override fun close() = Unit
}

/**
 * 发现状态机：记录 / 去重 / 应答判定 / 超时清理（注入时钟，确定性）。
 */
class LanDiscoveryTest {

    private val peerA = InetAddress.getByName("192.168.1.10")
    private val peerB = InetAddress.getByName("192.168.1.11")

    private fun transport() = FakeDatagramTransport()

    private fun discovery(transport: DatagramTransport, clock: () -> Long = { 0L }) =
        LanDiscovery(
            selfDeviceId = "self",
            selfDeviceName = "本机",
            httpPortProvider = { 8080 },
            transport = transport,
            clock = clock,
        )

    private fun broadcast(from: InetAddress, deviceId: String, name: String, port: Int) =
        UdpPacket(
            TransferProtocol.encodePresence(deviceId, name, port, isBroadcast = true),
            from,
        )

    private fun response(from: InetAddress, deviceId: String, name: String, port: Int) =
        UdpPacket(
            TransferProtocol.encodePresence(deviceId, name, port, isBroadcast = false),
            from,
        )

    @Test
    fun `收到广播记录设备并请求应答`() {
        val d = discovery(transport())
        val shouldRespond = d.handleIncoming(broadcast(peerA, "peer-a", "Pixel 7", 9001))
        assertTrue("广播应触发单播应答", shouldRespond)
        val devices = d.devices.value
        assertEquals(1, devices.size)
        assertEquals("peer-a", devices[0].deviceId)
        assertEquals("192.168.1.10", devices[0].host)
        assertEquals(9001, devices[0].httpPort)
    }

    @Test
    fun `收到应答只记录不触发二次应答（无乒乓）`() {
        val d = discovery(transport())
        assertFalse(d.handleIncoming(response(peerA, "peer-a", "Pixel 7", 9001)))
        assertEquals(1, d.devices.value.size)
    }

    @Test
    fun `本机报文被忽略`() {
        val d = discovery(transport())
        assertFalse(d.handleIncoming(broadcast(peerA, "self", "本机", 8080)))
        assertTrue(d.devices.value.isEmpty())
    }

    @Test
    fun `同一设备重复广播只保留一条记录`() {
        val d = discovery(transport())
        d.handleIncoming(broadcast(peerA, "peer-a", "Pixel 7", 9001))
        d.handleIncoming(broadcast(peerA, "peer-a", "Pixel 7", 9001))
        assertEquals(1, d.devices.value.size)
    }

    @Test
    fun `非法报文被忽略`() {
        val d = discovery(transport())
        assertFalse(d.handleIncoming(UdpPacket("garbage".toByteArray(), peerA)))
        assertTrue(d.devices.value.isEmpty())
    }

    @Test
    fun `超过离线时限的设备被清理`() {
        var now = 0L
        val d = discovery(transport()) { now }
        d.handleIncoming(broadcast(peerA, "peer-a", "Pixel 7", 9001))
        d.handleIncoming(broadcast(peerB, "peer-b", "小米 14", 9002))

        now = DEVICE_TIMEOUT_MS + 1
        d.handleIncoming(broadcast(peerB, "peer-b", "小米 14", 9002)) // B 刷新
        d.tick()

        val ids = d.devices.value.map { it.deviceId }
        assertTrue("超时的 A 应被清理", "peer-a" !in ids)
        assertTrue("刚刷新的 B 应保留", "peer-b" in ids)
    }

    @Test
    fun `设备列表按名称排序`() {
        val d = discovery(transport())
        d.handleIncoming(broadcast(peerB, "peer-b", "B 设备", 9002))
        d.handleIncoming(broadcast(peerA, "peer-a", "A 设备", 9001))
        assertEquals(listOf("A 设备", "B 设备"), d.devices.value.map { it.deviceName })
    }

    @Test
    fun `在线报文携带本机身份与 HTTP 端口`() {
        val t = transport()
        val d = discovery(t)
        val bytes = d.presenceBytes(isBroadcast = true)
        val decoded = TransferProtocol.decodePresence(bytes)!!
        assertEquals("self", decoded.deviceId)
        assertEquals("本机", decoded.deviceName)
        assertEquals(8080, decoded.httpPort)
        assertTrue(decoded.isBroadcast)
    }

    @Test
    fun `reset 清空全部状态`() {
        val d = discovery(transport())
        d.handleIncoming(broadcast(peerA, "peer-a", "Pixel 7", 9001))
        d.reset()
        assertTrue(d.devices.value.isEmpty())
    }
}
