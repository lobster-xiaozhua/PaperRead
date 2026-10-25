package io.github.lunalight.paperread.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传输鉴权单测（G1④ 矩阵：配对码 / secret 派生 / 令牌五分支 / 时间窗边界 /
 * NonceCache LRU+TTL / PairingGate 状态机；协议版本协商见 [TransferProtocolTest]）。
 */
class TransferAuthTest {

    // ---------- PairingCode ----------

    @Test
    fun `生成的配对码均为 6 位数字格式`() {
        repeat(50) {
            val code = PairingCode.generate()
            assertTrue("配对码应全为数字：$code", PairingCode.isValidFormat(code))
        }
    }

    @Test
    fun `配对码格式校验拒绝非 6 位与非数字`() {
        assertFalse(PairingCode.isValidFormat(""))
        assertFalse(PairingCode.isValidFormat("12345"))
        assertFalse(PairingCode.isValidFormat("1234567"))
        assertFalse(PairingCode.isValidFormat("12a456"))
        assertTrue(PairingCode.isValidFormat("000000"))
        assertTrue(PairingCode.isValidFormat("999999"))
    }

    @Test
    fun `配对码常数时间比较按内容判定`() {
        assertTrue(PairingCode.equalsConstantTime("123456", "123456"))
        assertFalse(PairingCode.equalsConstantTime("123456", "123457"))
        assertFalse(PairingCode.equalsConstantTime("123456", "1234567"))
    }

    // ---------- deriveSecret ----------

    @Test
    fun `secret 派生确定且与设备 ID 顺序无关`() {
        val a = deriveSecret("123456", listOf("dev-a", "dev-b"))
        val b = deriveSecret("123456", listOf("dev-b", "dev-a"))
        assertArrayEquals(a, b)
    }

    @Test
    fun `不同配对码或不同设备对派生不同 secret`() {
        val base = deriveSecret("123456", listOf("dev-a", "dev-b"))
        assertFalse(base.contentEquals(deriveSecret("123457", listOf("dev-a", "dev-b"))))
        assertFalse(base.contentEquals(deriveSecret("123456", listOf("dev-a", "dev-c"))))
    }

    @Test
    fun `secret 长度为 256 bit`() {
        assertEquals(SECRET_BITS / 8, deriveSecret("123456", listOf("a", "b")).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `设备 ID 不足两个时拒绝派生`() {
        deriveSecret("123456", listOf("only-one"))
    }

    // ---------- 令牌签发与验签 ----------

    @Test
    fun `空请求体摘要常量为 SHA-256 标准值`() {
        assertEquals(EMPTY_BODY_SHA256, sha256Hex(ByteArray(0)))
        assertEquals(64, EMPTY_BODY_SHA256.length)
    }

    @Test
    fun `令牌验签往返成功（含无请求体 GET 场景）`() {
        val secret = deriveSecret("123456", listOf("dev-a", "dev-b"))
        val token = issueTokenDigest(secret, "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000L)
        assertEquals(
            VerifyResult.Ok,
            verifyTokenDigest(
                secret, "Bearer $token", "GET", "/status", EMPTY_BODY_SHA256,
                nowMillis = 1_000L, nonceCache = NonceCache(clock = { 1_000L }),
            ),
        )
    }

    @Test
    fun `令牌绑定请求体摘要（体变更即拒）`() {
        val secret = deriveSecret("123456", listOf("dev-a", "dev-b"))
        val body = "hello".toByteArray()
        val token = issueToken(secret, "POST", "/migrate", body, nowMillis = 1_000L)
        val cache = NonceCache(clock = { 1_000L })
        assertEquals(
            VerifyResult.Ok,
            verifyToken(secret, "Bearer $token", "POST", "/migrate", body, nowMillis = 1_000L, nonceCache = cache),
        )
        val tampered = "hellp".toByteArray()
        assertEquals(
            VerifyResult.Mismatch,
            verifyToken(secret, "Bearer $token", "POST", "/migrate", tampered, nowMillis = 1_000L, nonceCache = cache),
        )
    }

    @Test
    fun `令牌绑定方法与路径（任一被篡改即拒）`() {
        val secret = deriveSecret("123456", listOf("dev-a", "dev-b"))
        val token = issueTokenDigest(secret, "POST", "/migrate", EMPTY_BODY_SHA256, nowMillis = 1_000L)
        val cache = NonceCache(clock = { 1_000L })
        assertEquals(
            VerifyResult.Ok,
            verifyTokenDigest(secret, "Bearer $token", "POST", "/migrate", EMPTY_BODY_SHA256, nowMillis = 1_000L, nonceCache = cache),
        )
        assertEquals(
            VerifyResult.Mismatch,
            verifyTokenDigest(secret, "Bearer $token", "GET", "/migrate", EMPTY_BODY_SHA256, nowMillis = 1_000L, nonceCache = cache),
        )
        assertEquals(
            VerifyResult.Mismatch,
            verifyTokenDigest(secret, "Bearer $token", "POST", "/other", EMPTY_BODY_SHA256, nowMillis = 1_000L, nonceCache = cache),
        )
    }

    @Test
    fun `错误 secret 验签不匹配（配对码不一致场景）`() {
        val alice = deriveSecret("123456", listOf("dev-a", "dev-b"))
        val bob = deriveSecret("654321", listOf("dev-a", "dev-b"))
        val token = issueTokenDigest(alice, "POST", "/migrate", EMPTY_BODY_SHA256, nowMillis = 1_000L)
        assertEquals(
            VerifyResult.Mismatch,
            verifyTokenDigest(bob, "Bearer $token", "POST", "/migrate", EMPTY_BODY_SHA256, nowMillis = 1_000L, nonceCache = NonceCache()),
        )
    }

    // ---------- 五类失败分支 ----------

    @Test
    fun `缺失 Authorization 头返回 Missing`() {
        val secret = deriveSecret("123456", listOf("dev-a", "dev-b"))
        assertEquals(
            VerifyResult.Missing,
            verifyTokenDigest(secret, null, "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000L, nonceCache = NonceCache()),
        )
        assertEquals(
            VerifyResult.Missing,
            verifyTokenDigest(secret, "   ", "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000L, nonceCache = NonceCache()),
        )
    }

    @Test
    fun `非 Bearer 方案或结构错乱返回 Malformed`() {
        val secret = deriveSecret("123456", listOf("dev-a", "dev-b"))
        val cache = NonceCache(clock = { 1_000L })
        assertEquals(
            VerifyResult.Malformed,
            verifyTokenDigest(secret, "Basic dXNlcjpwYXNz", "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000L, nonceCache = cache),
        )
        assertEquals(
            VerifyResult.Malformed,
            verifyTokenDigest(secret, "1700000000.abc", "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000L, nonceCache = cache),
        )
        assertEquals(
            VerifyResult.Malformed,
            verifyTokenDigest(secret, "Bearer notanumber.nonce.mac", "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000L, nonceCache = cache),
        )
        assertEquals(
            VerifyResult.Malformed,
            verifyTokenDigest(secret, "Bearer 1700000000.nonce.tooshort", "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000L, nonceCache = cache),
        )
        assertEquals(
            VerifyResult.Malformed,
            verifyTokenDigest(secret, "Bearer 1700000000.." + "a".repeat(64), "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000L, nonceCache = cache),
        )
    }

    @Test
    fun `Bearer 方案大小写不敏感`() {
        val secret = deriveSecret("123456", listOf("dev-a", "dev-b"))
        val token = issueTokenDigest(secret, "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000L)
        assertEquals(
            VerifyResult.Ok,
            verifyTokenDigest(secret, "bearer $token", "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000L, nonceCache = NonceCache()),
        )
    }

    @Test
    fun `超出时间窗返回 Expired（正负两个方向）`() {
        val secret = deriveSecret("123456", listOf("dev-a", "dev-b"))
        val token = issueTokenDigest(secret, "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000_000L)
        assertEquals(
            VerifyResult.Expired,
            verifyTokenDigest(secret, "Bearer $token", "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000_000L + TIME_WINDOW_MS + 1, nonceCache = NonceCache()),
        )
        assertEquals(
            VerifyResult.Expired,
            verifyTokenDigest(secret, "Bearer $token", "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000_000L - TIME_WINDOW_MS - 1, nonceCache = NonceCache()),
        )
    }

    @Test
    fun `时间窗边界值放行（恰好正负 60 秒）`() {
        val secret = deriveSecret("123456", listOf("dev-a", "dev-b"))
        val token = issueTokenDigest(secret, "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000_000L)
        assertEquals(
            VerifyResult.Ok,
            verifyTokenDigest(secret, "Bearer $token", "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000_000L + TIME_WINDOW_MS, nonceCache = NonceCache(clock = { 1_000_000L + TIME_WINDOW_MS })),
        )
        assertEquals(
            VerifyResult.Ok,
            verifyTokenDigest(secret, "Bearer $token", "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000_000L - TIME_WINDOW_MS, nonceCache = NonceCache(clock = { 1_000_000L - TIME_WINDOW_MS })),
        )
    }

    @Test
    fun `同一令牌重放返回 Replayed`() {
        val secret = deriveSecret("123456", listOf("dev-a", "dev-b"))
        val token = issueTokenDigest(secret, "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000L)
        val cache = NonceCache(clock = { 1_000L })
        assertEquals(
            VerifyResult.Ok,
            verifyTokenDigest(secret, "Bearer $token", "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000L, nonceCache = cache),
        )
        assertEquals(
            VerifyResult.Replayed,
            verifyTokenDigest(secret, "Bearer $token", "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000L, nonceCache = cache),
        )
    }

    @Test
    fun `mac 校验失败不登记 nonce（无投毒面）`() {
        val secret = deriveSecret("123456", listOf("dev-a", "dev-b"))
        val forged = "1700000000.fixed-nonce." + "0".repeat(64)
        val cache = NonceCache(clock = { 1_700_000_000L })
        assertEquals(
            VerifyResult.Mismatch,
            verifyTokenDigest(secret, "Bearer $forged", "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_700_000_000L, nonceCache = cache),
        )
        // 伪造令牌未登记 nonce：持正确 secret 签发的同 nonce 令牌仍可放行
        val legit = issueTokenDigest(secret, "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_700_000_000L, nonce = "fixed-nonce")
        assertEquals(
            VerifyResult.Ok,
            verifyTokenDigest(secret, "Bearer $legit", "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_700_000_000L, nonceCache = cache),
        )
    }

    @Test
    fun `每次签发生成不同 nonce`() {
        val secret = deriveSecret("123456", listOf("dev-a", "dev-b"))
        val nonces = (1..20).map {
            issueTokenDigest(secret, "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 1_000L).split('.')[1]
        }.toSet()
        assertEquals(20, nonces.size)
    }

    // ---------- NonceCache ----------

    @Test
    fun `nonce 缓存首次登记放行、重复登记判重放`() {
        val cache = NonceCache(clock = { 0L })
        assertFalse(cache.record("n1"))
        assertTrue(cache.record("n1"))
        assertEquals(1, cache.size())
    }

    @Test
    fun `nonce 缓存 TTL 过期后同一 nonce 可再次登记`() {
        var now = 0L
        val cache = NonceCache(ttlMillis = 1_000L, clock = { now })
        assertFalse(cache.record("n1"))
        now = 1_001L // 超过 TTL：登记前统一清理
        assertFalse(cache.record("n1"))
        assertEquals(1, cache.size())
    }

    @Test
    fun `nonce 缓存容量溢出按 LRU 淘汰`() {
        val cache = NonceCache(capacity = 2, ttlMillis = Long.MAX_VALUE, clock = { 0L })
        assertFalse(cache.record("a"))
        assertFalse(cache.record("b"))
        assertTrue(cache.record("a")) // 读取刷新顺序：a 变为最新
        assertFalse(cache.record("c")) // 溢出：淘汰最久未用的 b
        assertTrue(cache.record("a"))
        assertFalse(cache.record("b")) // b 已被淘汰，视为首次出现
        assertEquals(2, cache.size())
    }

    // ---------- PairingGate 状态机 ----------

    /** 发送端视角：按对端设备 ID + 输入的配对码派生候选 secret 并构造请求。 */
    private class SenderSide(private val gate: PairingGate, private val peerId: String) {
        fun derive(code: String): ByteArray? = gate.deriveForPeer(peerId, code)

        fun headers(secret: ByteArray, method: String, path: String, nowMillis: Long): Map<String, String> {
            val token = issueTokenDigest(secret, method, path, EMPTY_BODY_SHA256, nowMillis = nowMillis)
            return mapOf("authorization" to "Bearer $token", "x-device-id" to peerId)
        }
    }

    /** 取一个必然不同于 [code] 的合法格式配对码（规避随机碰撞导致的偶发误判）。 */
    private fun otherCode(code: String): String = if (code == "000000") "000001" else "000000"

    @Test
    fun `未配对状态拒绝一切请求`() {
        val gate = PairingGate(selfDeviceId = { "self" }, clock = { 0L })
        assertEquals(PairingGate.Status.UNPAIRED, gate.status)
        val sender = SenderSide(gate, "peer-a")
        val secret = sender.derive(PairingCode.generate())!!
        assertFalse(gate.verify("GET", "/status", sender.headers(secret, "GET", "/status", 0L), EMPTY_BODY_SHA256))
    }

    @Test
    fun `展示端配对流程：对端输入正确配对码即落定 PAIRED`() {
        var now = 0L
        val gate = PairingGate(selfDeviceId = { "self" }, clock = { now })
        val code = gate.beginPairing()
        assertEquals(PairingGate.Status.PAIRING, gate.status)

        val sender = SenderSide(gate, "peer-a")
        val candidate = sender.derive(code)!!
        assertTrue(
            "正确配对码的请求应放行",
            gate.verify("GET", "/status", sender.headers(candidate, "GET", "/status", now), EMPTY_BODY_SHA256),
        )
        assertEquals(PairingGate.Status.PAIRED, gate.status)
        assertEquals("peer-a", gate.pairedDeviceId)
        assertArrayEquals("配对落定后 secret 应与发送端候选一致", candidate, gate.secret)

        // PAIRED 后同 secret 的后续请求直接放行
        val followUp = sender.headers(candidate, "POST", "/migrate", ++now)
        assertTrue(gate.verify("POST", "/migrate", followUp, EMPTY_BODY_SHA256))
    }

    @Test
    fun `配对码错误时保持 PAIRING 且拒绝请求`() {
        val gate = PairingGate(selfDeviceId = { "self" }, clock = { 0L })
        val code = gate.beginPairing()
        val sender = SenderSide(gate, "peer-a")
        val wrong = sender.derive(otherCode(code))!!
        assertFalse(gate.verify("GET", "/status", sender.headers(wrong, "GET", "/status", 0L), EMPTY_BODY_SHA256))
        assertEquals(PairingGate.Status.PAIRING, gate.status)
        assertNull(gate.secret)
        assertNull(gate.pairedDeviceId)
    }

    @Test
    fun `PAIRING 期间缺失 X-Device-Id 头拒绝派生`() {
        val gate = PairingGate(selfDeviceId = { "self" }, clock = { 0L })
        val code = gate.beginPairing()
        val candidate = deriveSecret(code, listOf("peer-a", "self"))
        val token = issueTokenDigest(candidate, "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 0L)
        assertFalse(
            gate.verify("GET", "/status", mapOf("authorization" to "Bearer $token"), EMPTY_BODY_SHA256),
        )
        assertEquals(PairingGate.Status.PAIRING, gate.status)
    }

    @Test
    fun `PAIRED 后错误 secret 的请求被拒绝`() {
        var now = 0L
        val gate = PairingGate(selfDeviceId = { "self" }, clock = { now })
        val code = gate.beginPairing()
        val sender = SenderSide(gate, "peer-a")
        val candidate = sender.derive(code)!!
        assertTrue(gate.verify("GET", "/status", sender.headers(candidate, "GET", "/status", now), EMPTY_BODY_SHA256))

        val impostor = deriveSecret(otherCode(code), listOf("peer-a", "self"))
        val forged = issueTokenDigest(impostor, "POST", "/migrate", EMPTY_BODY_SHA256, nowMillis = ++now)
        assertFalse(
            gate.verify(
                "POST", "/migrate",
                mapOf("authorization" to "Bearer $forged", "x-device-id" to "peer-a"),
                EMPTY_BODY_SHA256,
            ),
        )
        assertEquals(PairingGate.Status.PAIRED, gate.status)
    }

    @Test
    fun `取消配对清零 secret 并回到未配对`() {
        var now = 0L
        val gate = PairingGate(selfDeviceId = { "self" }, clock = { now })
        val code = gate.beginPairing()
        val sender = SenderSide(gate, "peer-a")
        val candidate = sender.derive(code)!!
        assertTrue(gate.verify("GET", "/status", sender.headers(candidate, "GET", "/status", now), EMPTY_BODY_SHA256))
        val heldSecret = gate.secret!! // 持有内部引用（非拷贝），用于验证 zero-fill

        gate.cancelPairing()
        assertEquals(PairingGate.Status.UNPAIRED, gate.status)
        assertNull(gate.pairedDeviceId)
        assertNull(gate.secret)
        assertArrayEquals("内部 secret 引用应已被清零", ByteArray(SECRET_BITS / 8), heldSecret)

        // 状态机回到 UNPAIRED：即便持有（已清零的）secret 也不再放行
        val stale = issueTokenDigest(heldSecret, "GET", "/status", EMPTY_BODY_SHA256, nowMillis = ++now)
        assertFalse(gate.verify("GET", "/status", mapOf("authorization" to "Bearer $stale"), EMPTY_BODY_SHA256))
    }

    @Test
    fun `重复 beginPairing 重置旧状态并返回合法配对码`() {
        val gate = PairingGate(selfDeviceId = { "self" }, clock = { 0L })
        val first = gate.beginPairing()
        val second = gate.beginPairing()
        assertTrue(PairingCode.isValidFormat(first))
        assertTrue(PairingCode.isValidFormat(second))
        assertEquals(PairingGate.Status.PAIRING, gate.status)
    }

    @Test
    fun `发送端配对码格式非法时无法派生候选 secret`() {
        val gate = PairingGate(selfDeviceId = { "self" })
        assertNull(gate.deriveForPeer("peer-a", "12345"))
        assertNull(gate.deriveForPeer("peer-a", "12a456"))
        assertNotNull(gate.deriveForPeer("peer-a", "123456"))
    }

    @Test
    fun `配对确认移交的 secret 可立即用于验签`() {
        val gate = PairingGate(selfDeviceId = { "self" }, clock = { 0L })
        val candidate = gate.deriveForPeer("peer-a", "123456")!!
        gate.onPairingConfirmed("peer-a", candidate)
        assertEquals(PairingGate.Status.PAIRED, gate.status)
        val token = issueTokenDigest(candidate, "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 0L)
        assertTrue(gate.verify("GET", "/status", mapOf("authorization" to "Bearer $token"), EMPTY_BODY_SHA256))
    }

    @Test
    fun `确认配对会清零此前的旧 secret`() {
        val gate = PairingGate(selfDeviceId = { "self" }, clock = { 0L })
        val first = gate.deriveForPeer("peer-a", "111111")!!
        gate.onPairingConfirmed("peer-a", first)
        val second = gate.deriveForPeer("peer-b", "222222")!!
        gate.onPairingConfirmed("peer-b", second)
        assertArrayEquals(ByteArray(SECRET_BITS / 8), first)
        assertArrayEquals(second, gate.secret)
        assertEquals("peer-b", gate.pairedDeviceId)
    }

    @Test
    fun `配对确认请求重复投递被 nonce 拦截（状态保持 PAIRED）`() {
        val gate = PairingGate(selfDeviceId = { "self" }, clock = { 0L })
        val code = gate.beginPairing()
        val candidate = deriveSecret(code, listOf("peer-a", "self"))
        val headers = mapOf(
            "authorization" to "Bearer " + issueTokenDigest(candidate, "GET", "/status", EMPTY_BODY_SHA256, nowMillis = 0L),
            "x-device-id" to "peer-a",
        )
        assertTrue(gate.verify("GET", "/status", headers, EMPTY_BODY_SHA256))
        assertEquals(PairingGate.Status.PAIRED, gate.status)
        // 同一确认请求原样重放：PAIRED 分支下该 nonce 已登记 → 拒绝，状态不变
        assertFalse(gate.verify("GET", "/status", headers, EMPTY_BODY_SHA256))
        assertEquals(PairingGate.Status.PAIRED, gate.status)
        assertEquals("peer-a", gate.pairedDeviceId)
    }
}
