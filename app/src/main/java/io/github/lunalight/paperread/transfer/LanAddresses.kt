package io.github.lunalight.paperread.transfer

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 局域网网卡地址选择（纯 JVM：[NetworkInterface] 枚举，无需 WifiManager 废弃 API）。
 *
 * AC-9.7：HTTP 服务仅绑定 WiFi 网卡地址，不监听其他接口（公网隔离）。
 */
object LanAddresses {

    /**
     * 取 WiFi 网卡的 IPv4 站点本地地址（wlan* 接口优先）。
     *
     * @return 无 WiFi / 未连接时返回 null（调用方应提示「未接入 WiFi」而非盲听全网卡）
     */
    fun wifiIPv4(): InetAddress? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return null
        val all = mutableListOf<NetworkInterface>()
        while (interfaces.hasMoreElements()) {
            all += interfaces.nextElement()
        }
        return all.asSequence()
            .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
    }
}
