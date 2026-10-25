package io.github.lunalight.paperread.transfer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 局域网传输协议常量与报文（零第三方依赖，JSON 走已引入的 kotlinx.serialization）。
 *
 * 发现机制：UDP 广播 + 单播应答。每台设备周期性广播 [PresencePacket]（isBroadcast=true），
 * 收到广播的设备单播应答同一报文（isBroadcast=false）——双方互相记录
 * 「设备名 + HTTP 端口」，一轮通信完成双向发现（AC-9.2：3 秒内被发现，广播周期 2s）。
 * 应答不再触发二次应答，链路必然终止（无乒乓）。
 *
 * 传输机制：HTTP（JDK 原生 ServerSocket / HttpURLConnection，见
 * [TransferHttpServer] / [TransferClient]）。
 */

/** 协议魔数：用于在共享广播域内区分本应用与其他应用的 UDP 流量。 */
const val TRANSFER_MAGIC: String = "PAPERREAD_LAN"

/**
 * 协议版本：不一致的报文直接忽略（不兼容版本不互通）。
 *
 * v2（R-01 / G1）：引入传输鉴权。所有 v2 报文必须携带 [PresencePacket.pairingRequired]，
 * 收到端据此在 UI 提示配对；v1 报文（无该字段）一律拒绝，避免新旧版本
 * 在鉴权要求上产生歧义（v1 对端会直连传输端口，v2 服务端将返回 401）。
 */
const val TRANSFER_VERSION: Int = 2

/** UDP 发现端口（固定值：双方无需协商）。 */
const val DISCOVERY_PORT: Int = 45455

/** 广播周期（AC-9.2 要求 3 秒内被发现）。 */
const val ANNOUNCE_INTERVAL_MS: Long = 2_000L

/** 设备离线判定：超过该时长未再收到其报文即从列表移除。 */
const val DEVICE_TIMEOUT_MS: Long = 6_000L

/**
 * 在线广播 / 应答报文（同一结构两种用途）。
 *
 * @param httpPort 本机 HTTP 服务端口（对端据此发起传输）
 * @param isBroadcast true = 周期性广播（对端应单播应答）；false = 应答（不再触发应答）
 * @param pairingRequired true = 本机传输服务要求配对鉴权（v2 起恒为 true；
 *        对端据此在设备列表提示需输入配对码，而非无感知直连）
 */
@Serializable
data class PresencePacket(
    val magic: String,
    val version: Int,
    val deviceId: String,
    val deviceName: String,
    val httpPort: Int,
    val isBroadcast: Boolean,
    val pairingRequired: Boolean,
)

/** 协议编解码。非法（魔数 / 版本不匹配、JSON 损坏）输入一律返回 null。 */
object TransferProtocol {

    private val json = Json {
        ignoreUnknownKeys = true // 前向兼容：对端高版本新增字段不阻塞旧版解析
        encodeDefaults = true
    }

    /**
     * 构造本机在线报文。
     *
     * @param pairingRequired 是否要求配对鉴权（默认 true；仅测试关闭态注入 false）
     */
    fun encodePresence(
        deviceId: String,
        deviceName: String,
        httpPort: Int,
        isBroadcast: Boolean,
        pairingRequired: Boolean = true,
    ): ByteArray = json.encodeToString(
        PresencePacket.serializer(),
        PresencePacket(
            TRANSFER_MAGIC,
            TRANSFER_VERSION,
            deviceId,
            deviceName,
            httpPort,
            isBroadcast,
            pairingRequired,
        ),
    ).toByteArray(Charsets.UTF_8)

    /**
     * 解析在线报文。
     *
     * 魔数不匹配 / JSON 损坏 / 版本不是 [TRANSFER_VERSION]（含 v1 旧版与未知高版本）
     * 一律返回 null：v2 引入了配对鉴权语义，与不携带 [PresencePacket.pairingRequired]
     * 的 v1 报文不互通（v1 对端会绕过配对直连，v2 服务端将拒绝）。
     */
    fun decodePresence(bytes: ByteArray): PresencePacket? {
        if (bytes.isEmpty()) return null
        val packet = try {
            json.decodeFromString(PresencePacket.serializer(), String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            return null // 广播域内其他应用的流量 / 损坏包
        }
        if (packet.magic != TRANSFER_MAGIC) return null
        if (packet.version != TRANSFER_VERSION) return null
        if (packet.deviceId.isBlank() || packet.deviceName.isBlank()) return null
        return packet
    }
}
