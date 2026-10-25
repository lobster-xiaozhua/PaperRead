package io.github.lunalight.paperread.transfer

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * 局域网传输鉴权（R-01 / G1：配对码派生 secret + HMAC 请求签名）。
 *
 * 零第三方依赖：全部基于 JDK 原生 `javax.crypto`（API 26+ 可用，与 minSdk 一致）。
 *
 * 协议（架构设计 §2.3.1）：
 * ```
 * 配对码（6 位，展示端生成，SecureRandom）
 *   │ PBKDF2-HMAC-SHA256
 *   │   password  = pairingCode
 *   │   salt      = SHA-256(sorted(deviceIdA, deviceIdB).joinToString("|"))
 *   │   iterations = 100_000，dkLen = 256 bit
 *   ▼
 * secret（仅内存 ByteArray；服务停止 / 取消配对即 zero-fill）
 *   │ 每个请求签发
 *   ▼
 * token = ts "." nonce "." mac
 * mac   = HMAC-SHA256(secret, method + "\n" + path + "\n" + ts + "\n" + nonce + "\n" + sha256hex(body))
 * ```
 *
 * 服务端校验（[verifyTokenDigest]）：Bearer 头解析 → `|now - ts| ≤ 60s` 时间窗 →
 * mac 常数时间比对（[MessageDigest.isEqual]）→ nonce 防重放（[NonceCache]）。
 * 任一失败即拒绝（调用方返回 401）。
 *
 * 顺序说明：mac 先于 nonce 记录——nonce 仅在 mac 验证通过后才入缓存，
 * 未持有 secret 的对端无法用伪造令牌实施 nonce 缓存投毒；合法令牌的重放
 * （mac 必然相符）仍被 nonce 检查拦截，防重放强度不受影响。
 */

/** PBKDF2 迭代次数（与 SECURITY.md 记载一致，修改需同步文档与测试）。 */
const val PBKDF2_ITERATIONS: Int = 100_000

/** secret 长度（bit）。 */
const val SECRET_BITS: Int = 256

/** 令牌时间窗（毫秒）：`|now - ts|` 超出即判过期，容忍有限时钟偏移。 */
const val TIME_WINDOW_MS: Long = 60_000L

/** 防重放缓存容量（LRU）。 */
const val NONCE_CACHE_CAPACITY: Int = 1024

/** 防重放缓存条目 TTL（毫秒）：与时间窗同数量级，过期条目自动淘汰。 */
const val NONCE_TTL_MS: Long = 5 * 60_000L

/** nonce 随机字节数（128 bit，hex 后 32 字符）。 */
private const val NONCE_BYTES: Int = 16

/** mac 的 hex 长度（SHA-256 = 32 字节 = 64 hex 字符）。 */
private const val MAC_HEX_LENGTH: Int = 64

/** 配对码（6 位数字）：展示端生成、对端输入，双方据此派生同一 secret。 */
object PairingCode {

    /** 配对码位数。 */
    const val LENGTH: Int = 6

    private val random = SecureRandom()

    /** 生成 6 位数字配对码（`SecureRandom.nextInt` 无取模偏差；允许前导零）。 */
    fun generate(): String = random.nextInt(1_000_000).toString().padStart(LENGTH, '0')

    /** 格式校验：长度 [LENGTH] 且全数字。 */
    fun isValidFormat(code: String): Boolean =
        code.length == LENGTH && code.all { it in '0'..'9' }

    /** 常数时间比较（防时序侧信道）。 */
    fun equalsConstantTime(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}

/**
 * 由配对码派生共享 secret（PBKDF2-HMAC-SHA256，参数见 [PBKDF2_ITERATIONS] / [SECRET_BITS]）。
 *
 * salt 取双方设备 ID 排序后的拼接哈希：两端无论谁发起都能派生同一 secret，
 * 且同一配对码在不同设备对之间派生出不同 secret（隔离并行配对）。
 *
 * @param pairingCode 6 位配对码
 * @param deviceIds 双方设备 ID（恰好两个；内部排序，顺序无关）
 * @return 256 bit secret（新分配 ByteArray，调用方负责 zero-fill）
 */
fun deriveSecret(pairingCode: String, deviceIds: Collection<String>): ByteArray {
    require(deviceIds.size >= 2) { "派生 secret 需要双方设备 ID（实际 ${deviceIds.size} 个）" }
    val saltInput = deviceIds.sorted().joinToString("|")
    val salt = MessageDigest.getInstance("SHA-256").digest(saltInput.toByteArray(Charsets.UTF_8))
    val spec = PBEKeySpec(pairingCode.toCharArray(), salt, PBKDF2_ITERATIONS, SECRET_BITS)
    try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
    } finally {
        spec.clearPassword()
    }
}

/** 令牌验签结果（五类失败分支 + 成功，对应 G1④ 的单测矩阵）。 */
sealed interface VerifyResult {
    /** 验签通过（nonce 已登记）。 */
    data object Ok : VerifyResult

    /** 缺失：未携带 Authorization 头。 */
    data object Missing : VerifyResult

    /** 格式错：非 Bearer 方案，或令牌不符合 `ts.nonce.mac` 结构。 */
    data object Malformed : VerifyResult

    /** 过期：超出 ±[TIME_WINDOW_MS] 时间窗。 */
    data object Expired : VerifyResult

    /** 重放：nonce 已在缓存中见过（时间窗内）。 */
    data object Replayed : VerifyResult

    /** 错码：mac 常数时间比对不符（配对码错误 / secret 不匹配 / 请求要素被篡改）。 */
    data object Mismatch : VerifyResult
}

/**
 * 签发令牌（请求体为字节数组；大文件走 [issueTokenDigest] 避免整载入内存）。
 *
 * @param bodySha256Hex 请求体 SHA-256 十六进制（无请求体的 GET 等场景传 [EMPTY_BODY_SHA256]）
 */
fun issueTokenDigest(
    secret: ByteArray,
    method: String,
    path: String,
    bodySha256Hex: String,
    nowMillis: Long = System.currentTimeMillis(),
    nonce: String = newNonce(),
): String {
    val ts = nowMillis.coerceAtLeast(0L)
    val mac = hmacHex(secret, macMessage(method, path, ts, nonce, bodySha256Hex))
    return "$ts.$nonce.$mac"
}

/** 签发令牌（便捷重载：请求体字节数组 → 内部计算摘要）。 */
fun issueToken(
    secret: ByteArray,
    method: String,
    path: String,
    body: ByteArray,
    nowMillis: Long = System.currentTimeMillis(),
    nonce: String = newNonce(),
): String = issueTokenDigest(secret, method, path, sha256Hex(body), nowMillis, nonce)

/** 验签（便捷重载：请求体字节数组 → 内部计算摘要）。 */
fun verifyToken(
    secret: ByteArray,
    header: String?,
    method: String,
    path: String,
    body: ByteArray,
    nowMillis: Long = System.currentTimeMillis(),
    nonceCache: NonceCache = NonceCache(),
): VerifyResult = verifyTokenDigest(
    secret = secret,
    header = header,
    method = method,
    path = path,
    bodySha256Hex = sha256Hex(body),
    nowMillis = nowMillis,
    nonceCache = nonceCache,
)

/**
 * 验签（服务端流式路径：请求体已由上层计算摘要，无需整载入内存）。
 *
 * 校验顺序见类文档：缺失 → 格式 → 时间窗 → mac（常数时间）→ nonce 防重放。
 *
 * @param header Authorization 头完整值（如 `Bearer ts.nonce.mac`）
 * @param bodySha256Hex 请求体 SHA-256 十六进制（GET 等无请求体场景传 [EMPTY_BODY_SHA256]）
 * @param nowMillis 当前时钟（测试可注入）
 * @param nonceCache 防重放缓存（服务端单例，跨请求共享）
 */
fun verifyTokenDigest(
    secret: ByteArray,
    header: String?,
    method: String,
    path: String,
    bodySha256Hex: String,
    nowMillis: Long = System.currentTimeMillis(),
    nonceCache: NonceCache = NonceCache(),
): VerifyResult {
    if (header.isNullOrBlank()) return VerifyResult.Missing
    val token = extractBearerToken(header) ?: return VerifyResult.Malformed

    val parts = token.split('.')
    if (parts.size != 3) return VerifyResult.Malformed
    val ts = parts[0].toLongOrNull() ?: return VerifyResult.Malformed
    val nonce = parts[1]
    val macHex = parts[2]
    if (nonce.isBlank() || !isHex(macHex)) return VerifyResult.Malformed

    if (abs(nowMillis - ts) > TIME_WINDOW_MS) return VerifyResult.Expired

    val expected = hmacHex(secret, macMessage(method, path, ts, nonce, bodySha256Hex))
    if (!MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            macHex.lowercase().toByteArray(Charsets.UTF_8),
        )
    ) {
        return VerifyResult.Mismatch
    }

    // mac 通过后才登记 nonce：合法令牌的重放被拦截，伪造令牌无法投毒缓存
    if (nonceCache.record(nonce)) return VerifyResult.Replayed
    return VerifyResult.Ok
}

/**
 * 服务端令牌验签器（由 [TransferHttpServer] 在路由前调用）。
 *
 * 请求体摘要由服务端在鉴权落盘阶段流式计算后传入，验签器不接触字节流。
 * 头名大小写由调用方规范化为小写。
 */
fun interface TokenVerifier {
    /**
     * @param method HTTP 方法（大写）
     * @param path 请求路径（已去查询串）
     * @param headers 请求头（键为小写；含 authorization / x-device-id）
     * @param bodySha256Hex 请求体 SHA-256 十六进制
     * @return true = 放行；false = 调用方应返回 401
     */
    fun verify(method: String, path: String, headers: Map<String, String>, bodySha256Hex: String): Boolean
}

/**
 * 配对状态机 + secret 生命周期（UNPAIRED → PAIRING → PAIRED）。
 *
 * 同时充当服务端 [TokenVerifier]：
 * - UNPAIRED：拒绝一切请求（未配对拒收）；
 * - PAIRING（本机展示配对码中）：以「展示的配对码 + 对端设备 ID」派生候选 secret
 *   验签，通过即配对落定（PAIRED）；
 * - PAIRED：仅接受以已存 secret 验签通过的请求。
 *
 * 线程安全：状态字段为 `@Volatile`，nonce 缓存内部同步；并发请求下配对落定
 * 具备幂等性（同一对端的重复确认不改变结果）。
 */
class PairingGate(
    private val selfDeviceId: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
) : TokenVerifier {

    /** 配对状态。 */
    enum class Status { UNPAIRED, PAIRING, PAIRED }

    @Volatile
    var status: Status = Status.UNPAIRED
        private set

    /** 已配对的对端设备 ID（PAIRED 时非空）。 */
    @Volatile
    var pairedDeviceId: String? = null
        private set

    /** 当前生效 secret（PAIRED 时非空；仅内存，取消时 zero-fill）。 */
    @Volatile
    var secret: ByteArray? = null
        private set

    /** PAIRING 期间展示的 6 位配对码（仅内存）。 */
    @Volatile
    private var pendingCode: String? = null

    private val nonceCache = NonceCache(clock = clock)

    /**
     * 展示端：生成配对码并进入 PAIRING（重复调用重置旧状态）。
     *
     * @return 6 位配对码（UI 展示用）
     */
    fun beginPairing(): String {
        cancelPairing()
        val code = PairingCode.generate()
        pendingCode = code
        status = Status.PAIRING
        return code
    }

    /** 取消配对 / 服务停止：清零 secret 与 pendingCode，回到 UNPAIRED。 */
    fun cancelPairing() {
        secret?.fill(0)
        secret = null
        pendingCode = null
        pairedDeviceId = null
        status = Status.UNPAIRED
    }

    /**
     * 发送端：按对端设备 ID + 输入的配对码派生候选 secret（尚未生效）。
     *
     * @return 候选 secret；配对码格式非法时返回 null
     */
    fun deriveForPeer(peerDeviceId: String, code: String): ByteArray? {
        if (!PairingCode.isValidFormat(code)) return null
        return deriveSecret(code, listOf(peerDeviceId, selfDeviceId()))
    }

    /**
     * 发送端：探测请求得到对端 200 后调用，配对落定（PAIRED）。
     *
     * @param secret [deriveForPeer] 返回的候选 secret（移交生命周期管理权）
     */
    fun onPairingConfirmed(peerDeviceId: String, secret: ByteArray) {
        this.secret?.fill(0)
        this.secret = secret
        this.pairedDeviceId = peerDeviceId
        this.pendingCode = null
        this.status = Status.PAIRED
    }

    override fun verify(
        method: String,
        path: String,
        headers: Map<String, String>,
        bodySha256Hex: String,
    ): Boolean {
        val authorization = headers["authorization"]
        return when (status) {
            Status.UNPAIRED -> false

            Status.PAIRED -> {
                val current = secret ?: return false
                verifyTokenDigest(
                    secret = current,
                    header = authorization,
                    method = method,
                    path = path,
                    bodySha256Hex = bodySha256Hex,
                    nowMillis = clock(),
                    nonceCache = nonceCache,
                ) == VerifyResult.Ok
            }

            Status.PAIRING -> {
                val code = pendingCode ?: return false
                val peerId = headers["x-device-id"]?.takeIf { it.isNotBlank() } ?: return false
                val candidate = deriveSecret(code, listOf(peerId, selfDeviceId()))
                val result = verifyTokenDigest(
                    secret = candidate,
                    header = authorization,
                    method = method,
                    path = path,
                    bodySha256Hex = bodySha256Hex,
                    nowMillis = clock(),
                    nonceCache = nonceCache,
                )
                if (result == VerifyResult.Ok) {
                    // 对端输入了正确配对码：本机配对落定，候选 secret 转正
                    secret?.fill(0)
                    secret = candidate
                    pairedDeviceId = peerId
                    pendingCode = null
                    status = Status.PAIRED
                    true
                } else {
                    candidate.fill(0)
                    false
                }
            }
        }
    }
}

/**
 * nonce 防重放缓存（LRU 容量 [NONCE_CACHE_CAPACITY] / TTL [NONCE_TTL_MS]，线程安全）。
 *
 * 仅记录 mac 验证通过的 nonce（见 [verifyTokenDigest] 顺序说明）；
 * 过期条目在每次登记前统一清理，容量溢出淘汰最久未用者。
 */
class NonceCache(
    private val capacity: Int = NONCE_CACHE_CAPACITY,
    private val ttlMillis: Long = NONCE_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    // accessOrder = true：读取 / 包含判定即刷新顺序，淘汰最久未用者
    private val seen = LinkedHashMap<String, Long>(16, 0.75f, true)

    /**
     * 登记 nonce。
     *
     * @return true = 已见过（重放，应拒绝）；false = 首次出现（已登记）
     */
    fun record(nonce: String): Boolean = synchronized(seen) {
        val now = clock()
        purgeExpiredLocked(now)
        // accessOrder 的 LinkedHashMap 中 put 命中旧键会同时刷新顺序与时间戳：
        // 重放判定与 LRU 顺延在一次操作内完成（containsKey 不刷新顺序，不可用）
        if (seen.put(nonce, now) != null) return true
        if (seen.size > capacity) {
            val eldest = seen.keys.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
        return false
    }

    /** 当前登记数（测试 / 诊断用）。 */
    fun size(): Int = synchronized(seen) { seen.size }

    /** 清理过期条目（accessOrder 下时间戳非有序，需全量扫描）。 */
    private fun purgeExpiredLocked(now: Long) {
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > ttlMillis) iterator.remove()
        }
    }
}

/** 空请求体的 SHA-256 十六进制（GET 等无请求体场景的 MAC 输入）。 */
const val EMPTY_BODY_SHA256: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

/** 进程内共享的强随机源（配对码 / nonce）。 */
private val secureRandom = SecureRandom()

/** 生成随机 nonce（128 bit hex）。 */
internal fun newNonce(): String =
    ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }.toHex()

/** SHA-256 十六进制（小写）。 */
internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

/** HMAC-SHA256 十六进制（小写）。 */
private fun hmacHex(secret: ByteArray, message: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret, "HmacSHA256"))
    return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
}

/** MAC 输入序列化：方法 / 路径 / 时间戳 / nonce / 请求体摘要，逐行拼接。 */
private fun macMessage(
    method: String,
    path: String,
    ts: Long,
    nonce: String,
    bodySha256Hex: String,
): String = buildString {
    append(method.uppercase())
    append('\n')
    append(path)
    append('\n')
    append(ts)
    append('\n')
    append(nonce)
    append('\n')
    append(bodySha256Hex)
}

/** 解析 Bearer 方案令牌；方案缺失 / 非 Bearer / 令牌为空返回 null。 */
private fun extractBearerToken(header: String): String? {
    val value = header.trim()
    val schemeEnd = value.indexOf(' ')
    if (schemeEnd <= 0) return null
    if (!value.substring(0, schemeEnd).equals("Bearer", ignoreCase = true)) return null
    return value.substring(schemeEnd + 1).trim().takeIf { it.isNotEmpty() }
}

/** mac hex 格式：64 个十六进制字符（大小写均可，比对前归一化为小写）。 */
private fun isHex(value: String): Boolean =
    value.length == MAC_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/** 字节数组转小写十六进制字符串。 */
internal fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
