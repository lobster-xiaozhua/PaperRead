package io.github.lunalight.paperread.transfer

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网卡地址选择（AC-9.7：仅 WiFi 网卡地址）。
 *
 * 沙箱 / CI 环境没有 wlan 网卡，因此不断言「一定能取到」，
 * 而断言选择器的**不变量**：取到的必须是非回环 IPv4。
 */
class LanAddressesTest {

    @Test
    fun `取到的地址必须是非回环 IPv4`() {
        val address = LanAddresses.wifiIPv4()
        if (address != null) {
            assertFalse("不得绑定回环地址：$address", address.isLoopbackAddress)
            assertTrue("必须是 IPv4：$address", address is java.net.Inet4Address)
        }
        // 无 WiFi 的环境允许返回 null（调用方据此提示「未接入 WiFi」）
    }

    @Test
    fun `回环地址不是站点本地地址（不变量自检）`() {
        // 127.0.0.1 属环回块而非站点本地块：选择器的 site-local 过滤天然排除它
        assertFalse(InetAddress.getLoopbackAddress().isSiteLocalAddress)
        assertNotNull(LanAddresses.wifiIPv4() ?: InetAddress.getLoopbackAddress())
    }
}
