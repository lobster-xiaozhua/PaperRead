package io.github.lunalight.paperread.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 协议编解码：往返一致 + 非法输入一律 null（魔数 / 版本 / JSON 损坏）。 */
class TransferProtocolTest {

    @Test
    fun `广播报文编解码往返一致`() {
        val bytes = TransferProtocol.encodePresence("id-1", "Pixel 7", 45678, isBroadcast = true)
        val decoded = TransferProtocol.decodePresence(bytes)
        assertEquals("id-1", decoded?.deviceId)
        assertEquals("Pixel 7", decoded?.deviceName)
        assertEquals(45678, decoded?.httpPort)
        assertTrue(decoded!!.isBroadcast)
    }

    @Test
    fun `应答报文编解码往返一致`() {
        val bytes = TransferProtocol.encodePresence("id-2", "小米 14", 1234, isBroadcast = false)
        val decoded = TransferProtocol.decodePresence(bytes)
        assertFalse(decoded!!.isBroadcast)
        assertEquals(1234, decoded.httpPort)
    }

    @Test
    fun `默认编码携带 pairingRequired=true（v2 语义）`() {
        val decoded = TransferProtocol.decodePresence(
            TransferProtocol.encodePresence("id-1", "Pixel 7", 45678, isBroadcast = true),
        )!!
        assertTrue(decoded.pairingRequired)
    }

    @Test
    fun `pairingRequired=false 时编解码往返一致`() {
        val decoded = TransferProtocol.decodePresence(
            TransferProtocol.encodePresence(
                "id-1", "Pixel 7", 45678, isBroadcast = true, pairingRequired = false,
            ),
        )!!
        assertFalse(decoded.pairingRequired)
    }

    @Test
    fun `魔数不匹配返回 null`() {
        val bytes = """{"magic":"OTHER_APP","version":2,"deviceId":"a","deviceName":"b","httpPort":1,"isBroadcast":true,"pairingRequired":true}"""
            .toByteArray()
        assertNull(TransferProtocol.decodePresence(bytes))
    }

    @Test
    fun `版本不匹配返回 null`() {
        val bytes = """{"magic":"PAPERREAD_LAN","version":99,"deviceId":"a","deviceName":"b","httpPort":1,"isBroadcast":true,"pairingRequired":true}"""
            .toByteArray()
        assertNull(TransferProtocol.decodePresence(bytes))
    }

    @Test
    fun `v1 旧版报文被拒绝（未引入配对鉴权，不互通）`() {
        val bytes = """{"magic":"PAPERREAD_LAN","version":1,"deviceId":"a","deviceName":"b","httpPort":1,"isBroadcast":true}"""
            .toByteArray()
        assertNull(TransferProtocol.decodePresence(bytes))
    }

    @Test
    fun `v1 报文即使补带 pairingRequired 仍被拒绝（版本门控优先）`() {
        val bytes = """{"magic":"PAPERREAD_LAN","version":1,"deviceId":"a","deviceName":"b","httpPort":1,"isBroadcast":true,"pairingRequired":true}"""
            .toByteArray()
        assertNull(TransferProtocol.decodePresence(bytes))
    }

    @Test
    fun `缺失 pairingRequired 字段的报文被拒绝`() {
        val bytes = """{"magic":"PAPERREAD_LAN","version":2,"deviceId":"a","deviceName":"b","httpPort":1,"isBroadcast":true}"""
            .toByteArray()
        assertNull(TransferProtocol.decodePresence(bytes))
    }

    @Test
    fun `空与损坏报文返回 null`() {
        assertNull(TransferProtocol.decodePresence(ByteArray(0)))
        assertNull(TransferProtocol.decodePresence("not-json".toByteArray()))
    }

    @Test
    fun `同版本新增未知字段可解析（前向兼容）`() {
        val bytes = """{"magic":"PAPERREAD_LAN","version":2,"deviceId":"a","deviceName":"b","httpPort":8,"isBroadcast":true,"pairingRequired":true,"futureField":"x"}"""
            .toByteArray()
        assertEquals(8, TransferProtocol.decodePresence(bytes)?.httpPort)
    }
}
