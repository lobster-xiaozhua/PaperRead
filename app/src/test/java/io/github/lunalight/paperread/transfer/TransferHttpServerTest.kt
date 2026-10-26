package io.github.lunalight.paperread.transfer

import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * HTTP 服务 + 客户端：localhost 真实 socket 端到端（AC-9.4 / AC-9.6）。
 *
 * 覆盖：端点行为（meta / upload / bundle / sync）、配对鉴权全流程
 * （未配对拒收 / 配对探测 / 已配对放行 / 重放 / 过期 / 摘要不符）、
 * spill 生命周期（进度回调 / 中止回调 / 残留清理）。
 */
class TransferHttpServerTest {

    private lateinit var server: TransferHttpServer
    private lateinit var executor: java.util.concurrent.ExecutorService
    private lateinit var spillDir: File

    private data class Upload(val fileName: String, val bytes: ByteArray) {
        override fun equals(other: Any?) =
            other is Upload && fileName == other.fileName && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * fileName.hashCode() + bytes.contentHashCode()
    }

    private val uploads = mutableListOf<Upload>()
    private val bundles = mutableListOf<Upload>()
    private var appliedSync: String? = null
    private var failNextUpload = false

    /** spill 进度 / 中止事件（服务端线程写入，需线程安全容器）。 */
    private val progressEvents = CopyOnWriteArrayList<Triple<TransferEndpoint, String, Float>>()
    private val abortEvents = CopyOnWriteArrayList<Pair<TransferEndpoint, String>>()

    private val handler = object : TransferHttpHandler {
        override fun meta(): String = """{"ok":true,"deviceName":"测试机"}"""

        override fun onUpload(fileName: String, contentLength: Long, bodyFile: File): String? {
            if (failNextUpload) {
                failNextUpload = false
                bodyFile.delete()
                return "模拟失败"
            }
            uploads += Upload(fileName, bodyFile.readBytes())
            return null
        }

        override fun onBundle(fileName: String, contentLength: Long, bodyFile: File): String? {
            bundles += Upload(fileName, bodyFile.readBytes())
            return null
        }

        override fun syncState(): String = """{"version":1,"deviceId":"me","books":[]}"""

        override fun onSyncApply(payload: String): String? {
            if (payload.isBlank()) return "empty"
            appliedSync = payload
            return null
        }
    }

    /**
     * 服务端时钟（验签时间窗可控）。
     *
     * [TransferClient] 内部以 System.currentTimeMillis() 签发令牌，
     * 因此 setUp 时将 serverNow 对齐真实时钟量级，使客户端令牌落在
     * 服务端验签的 60s 窗口内；单个测试内时钟保持不变，
     * 「过期令牌」用例以 serverNow - TIME_WINDOW_MS - 1 仍确定性过期。
     */
    private var serverNow = System.currentTimeMillis()

    /** 配对门：既是被测鉴权器，也是配对状态机。 */
    private val gate = PairingGate(selfDeviceId = { "self-device" }, clock = { serverNow })

    @Before
    fun setUp() {
        serverNow = System.currentTimeMillis()
        spillDir = Files.createTempDirectory("transfer-spill").toFile()
        server = TransferHttpServer(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = 0,
            handler = handler,
            tokenVerifier = gate,
            spillDir = spillDir,
            onReceiveProgress = { endpoint, name, fraction ->
                progressEvents += Triple(endpoint, name, fraction)
            },
            onReceiveAborted = { endpoint, reason -> abortEvents += endpoint to reason },
        )
        executor = Executors.newSingleThreadExecutor()
        executor.execute { server.serve() }
    }

    @After
    fun tearDown() {
        server.close()
        executor.shutdownNow()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        spillDir.deleteRecursively()
    }

    private fun port(): Int = server.actualPort

    private fun tempFile(bytes: ByteArray): File =
        Files.createTempFile("transfer-test", ".bin").toFile().apply { writeBytes(bytes) }

    // ---- 原始 socket 请求（精确控制鉴权头，覆盖中间件分支） ----

    private data class RawResponse(val status: Int, val body: String)

    private fun sendRaw(
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        headers: Map<String, String> = emptyMap(),
    ): RawResponse {
        Socket("127.0.0.1", port()).use { socket ->
            socket.soTimeout = 5_000
            val out: OutputStream = socket.getOutputStream()
            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1\r\n")
                headers.forEach { (k, v) -> append("$k: $v\r\n") }
                if (body.isNotEmpty()) append("Content-Length: ${body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.UTF_8))
            if (body.isNotEmpty()) {
                out.write(body)
                out.flush()
            }
            val input = socket.getInputStream()
            val statusLine = input.readHeaderLine() ?: error("对端无响应")
            val status = statusLine.split(' ')[1].toInt()
            val respHeaders = HashMap<String, String>()
            while (true) {
                val line = input.readHeaderLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    respHeaders[line.substring(0, idx).trim().lowercase()] =
                        line.substring(idx + 1).trim()
                }
            }
            val length = respHeaders["content-length"]?.toIntOrNull() ?: 0
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = input.read(bytes, offset, length - offset)
                if (read < 0) break
                offset += read
            }
            return RawResponse(status, String(bytes, Charsets.UTF_8))
        }
    }

    /** 构造带鉴权头的请求头集合（POST 附摘要声明；上传类端点可附文件名头）。 */
    private fun authHeaders(
        secret: ByteArray,
        method: String,
        path: String,
        bodySha256Hex: String,
        nowMillis: Long = serverNow,
        deviceId: String = "peer-a",
        bookName: String? = null,
    ): Map<String, String> {
        val token = issueTokenDigest(secret, method, path, bodySha256Hex, nowMillis = nowMillis)
        return buildMap {
            put("Authorization", "Bearer $token")
            put("X-Device-Id", deviceId)
            if (bookName != null) put("X-Book-Name", bookName)
            if (method == "POST") put("X-Content-Sha256", bodySha256Hex)
        }
    }

    // ---- 配对辅助 ----

    /** 展示端生成配对码 → 发送端派生候选 secret 并完成配对，返回候选 secret。 */
    private fun pairAsSender(): ByteArray {
        val code = gate.beginPairing()
        val candidate = deriveSecret(code, listOf("peer-a", "self-device"))
        val probe = TransferClient().probePairing("127.0.0.1", port(), candidate, "peer-a")
        assertEquals(PairingProbeResult.Paired, probe)
        return candidate
    }

    private fun client(secret: ByteArray) =
        TransferClient(secretProvider = { secret }, selfDeviceId = { "peer-a" })

    private fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
    }

    // ---- 端点行为（配对后） ----

    @Test
    fun `GET meta 返回设备信息`() {
        val secret = pairAsSender()
        val response = sendRaw(
            "GET", "/meta",
            headers = authHeaders(secret, "GET", "/meta", EMPTY_BODY_SHA256),
        )
        assertEquals(200, response.status)
        assertTrue("meta 应含设备名：${response.body}", response.body.contains("测试机"))
    }

    @Test
    fun `POST upload 完整传输字节与文件名`() {
        val secret = pairAsSender()
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() } // 256KB，跨多个缓冲区
        val file = tempFile(payload)
        val result = client(secret).upload("127.0.0.1", port(), file, "测试书名.txt")
        assertEquals(UploadResult.Success, result)
        assertEquals(1, uploads.size)
        assertEquals("测试书名.txt", uploads[0].fileName)
        assertArrayEquals(payload, uploads[0].bytes)
        file.delete()
    }

    @Test
    fun `上传进度回调单调递增至 1`() {
        val secret = pairAsSender()
        val payload = ByteArray(512 * 1024) { it.toByte() }
        val file = tempFile(payload)
        client(secret).upload("127.0.0.1", port(), file, "进度书.txt")
        val fractions = progressEvents.map { it.third }
        assertTrue("应有进度回调", fractions.isNotEmpty())
        assertTrue("进度单调递增：$fractions", fractions.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1f, fractions.last(), 1e-6f)
        assertTrue("进度不越界", fractions.all { it in 0f..1f })
        file.delete()
    }

    @Test
    fun `未知路径返回 404`() {
        val secret = pairAsSender()
        val response = sendRaw(
            "GET", "/nope",
            headers = authHeaders(secret, "GET", "/nope", EMPTY_BODY_SHA256),
        )
        assertEquals(404, response.status)
    }

    @Test
    fun `对端处理失败返回 500`() {
        val secret = pairAsSender()
        failNextUpload = true
        val file = tempFile(ByteArray(1024))
        val result = client(secret).upload("127.0.0.1", port(), file, "失败书.txt")
        assertTrue("应判失败：$result", result is UploadResult.Failure)
        assertTrue("不应落库", uploads.isEmpty())
        file.delete()
    }

    @Test
    fun `连接被拒返回 Failure（AC-9 6 对端不可达）`() {
        // 占用一个端口后立即释放，得到几乎必然被拒的端口号
        val probe = java.net.ServerSocket(0)
        val deadPort = probe.localPort
        probe.close()
        val file = tempFile(ByteArray(16))
        val result = client(ByteArray(32) { 1 }).upload("127.0.0.1", deadPort, file, "死路书.txt")
        assertTrue("应判失败：$result", result is UploadResult.Failure)
        file.delete()
    }

    @Test
    fun `连续多请求服务不中断`() {
        val secret = pairAsSender()
        val file = tempFile(ByteArray(64) { 7 })
        repeat(3) {
            assertEquals(UploadResult.Success, client(secret).upload("127.0.0.1", port(), file, "多本.txt"))
        }
        assertEquals(3, uploads.size)
        file.delete()
    }

    @Test
    fun `空文件上传被判失败且不发请求`() {
        val secret = pairAsSender()
        val file = tempFile(ByteArray(0))
        val result = client(secret).upload("127.0.0.1", port(), file, "空书.txt")
        assertTrue("空文件应失败：$result", result is UploadResult.Failure)
        assertTrue(uploads.isEmpty())
        file.delete()
    }

    @Test
    fun `未配对客户端上传直接被拒且不发请求`() {
        val file = tempFile(ByteArray(1024))
        val result = TransferClient().upload("127.0.0.1", port(), file, "未配对书.txt")
        assertTrue("无 secret 应失败：$result", result is UploadResult.Failure)
        assertTrue(uploads.isEmpty())
        file.delete()
    }

    @Test
    fun `POST bundle 端到端送达且字节一致`() {
        val secret = pairAsSender()
        val zip = tempFile(ByteArray(4096) { (it % 251).toByte() })
        try {
            val result = client(secret).uploadBundle(
                host = "127.0.0.1",
                port = port(),
                file = zip,
                displayName = "paperread-migrate.zip",
            )
            assertEquals(UploadResult.Success, result)
            val received = bundles.single()
            assertEquals("paperread-migrate.zip", received.fileName)
            assertArrayEquals(zip.readBytes(), received.bytes)
            assertTrue("不应落到 /upload 通道", uploads.isEmpty())
        } finally {
            zip.delete()
        }
    }

    @Test
    fun `GET sync_state 返回本机状态`() {
        val secret = pairAsSender()
        val text = client(secret).fetchSyncState("127.0.0.1", port())
        assertEquals("""{"version":1,"deviceId":"me","books":[]}""", text)
    }

    @Test
    fun `POST sync_apply 送达对端状态`() {
        val secret = pairAsSender()
        val ok = client(secret).pushSyncState(
            "127.0.0.1",
            port(),
            """{"version":1,"deviceId":"me","books":[]}""",
        )
        assertTrue(ok)
        assertEquals("""{"version":1,"deviceId":"me","books":[]}""", appliedSync)
    }

    @Test
    fun `对端不可达时拉取状态返回 null 且回推失败`() {
        // 端口 1 上不会有服务：连接被拒应返回 null / false 而不是抛异常
        val c = TransferClient(secretProvider = { ByteArray(32) { 1 } }, selfDeviceId = { "peer-a" })
        assertNull(c.fetchSyncState("127.0.0.1", 1))
        assertFalse(c.pushSyncState("127.0.0.1", 1, "{}"))
    }

    // ---- 鉴权中间件 ----

    @Test
    fun `未配对状态拒绝一切请求（401）且不落盘`() {
        assertEquals(PairingGate.Status.UNPAIRED, gate.status)
        val candidate = deriveSecret("123456", listOf("peer-a", "self-device"))

        // 无 Authorization 头
        assertEquals(401, sendRaw("GET", "/meta").status)
        // 有令牌但服务端未配对（UNPAIRED 拒收）
        assertEquals(
            401,
            sendRaw(
                "GET", "/meta",
                headers = authHeaders(candidate, "GET", "/meta", EMPTY_BODY_SHA256),
            ).status,
        )
        // POST 上传同样被拒（先排空再应答，客户端读得到 401）
        val payload = ByteArray(2048) { it.toByte() }
        val upload = sendRaw(
            "POST", "/upload", payload,
            headers = authHeaders(
                candidate, "POST", "/upload", sha256Hex(payload),
                bookName = "未配对书.txt",
            ),
        )
        assertEquals(401, upload.status)
        assertTrue("未配对不得落库", uploads.isEmpty())
        assertTrue("未配对不得落盘", spillDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `配对码正确即完成配对并放行后续请求`() {
        val secret = pairAsSender()
        assertEquals(PairingGate.Status.PAIRED, gate.status)
        assertEquals("peer-a", gate.pairedDeviceId)

        val file = tempFile(ByteArray(256 * 1024) { (it % 251).toByte() })
        val result = client(secret).upload("127.0.0.1", port(), file, "配对书.txt")
        assertEquals(UploadResult.Success, result)
        file.delete()
    }

    @Test
    fun `配对码错误保持 PAIRING（401）`() {
        val code = gate.beginPairing()
        val wrong = if (code == "000000") "000001" else "000000"
        val candidate = deriveSecret(wrong, listOf("peer-a", "self-device"))
        val probe = TransferClient().probePairing("127.0.0.1", port(), candidate, "peer-a")
        assertEquals(PairingProbeResult.Rejected, probe)
        assertEquals(PairingGate.Status.PAIRING, gate.status)
        assertNull(gate.secret)
    }

    @Test
    fun `配对中缺失设备 ID 头被拒（401）`() {
        val code = gate.beginPairing()
        val candidate = deriveSecret(code, listOf("peer-a", "self-device"))
        val headers = authHeaders(candidate, "GET", "/meta", EMPTY_BODY_SHA256) - "X-Device-Id"
        assertEquals(401, sendRaw("GET", "/meta", headers = headers).status)
        assertEquals(PairingGate.Status.PAIRING, gate.status)
    }

    @Test
    fun `已配对后错误 secret 的请求被拒（401）`() {
        val secret = pairAsSender()
        val impostor = deriveSecret("999999", listOf("peer-a", "self-device"))
        val file = tempFile(ByteArray(1024))
        val result = client(impostor).upload("127.0.0.1", port(), file, "伪书.txt")
        assertTrue(result is UploadResult.Failure)
        assertTrue("401 应标记未授权：$result", (result as UploadResult.Failure).unauthorized)
        assertTrue(uploads.isEmpty())
        file.delete()
    }

    @Test
    fun `同一令牌重放被拒（401）`() {
        val secret = pairAsSender()
        val payload = ByteArray(4096) { 7 }
        val headers = authHeaders(secret, "POST", "/upload", sha256Hex(payload), bookName = "重放书.txt")
        assertEquals(200, sendRaw("POST", "/upload", payload, headers).status)
        assertEquals(401, sendRaw("POST", "/upload", payload, headers).status)
        assertEquals("重放不应二次落库", 1, uploads.size)
    }

    @Test
    fun `过期令牌被拒（401）且不落盘`() {
        val secret = pairAsSender()
        val payload = ByteArray(2048)
        val stale = authHeaders(
            secret, "POST", "/upload", sha256Hex(payload),
            nowMillis = serverNow - TIME_WINDOW_MS - 1,
            bookName = "过期书.txt",
        )
        assertEquals(401, sendRaw("POST", "/upload", payload, stale).status)
        assertTrue(uploads.isEmpty())
        assertTrue("过期请求不落盘", spillDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `非 Bearer 方案被拒（401）`() {
        val secret = pairAsSender()
        val response = sendRaw(
            "GET", "/meta",
            headers = mapOf("Authorization" to "Basic dXNlcjpwYXNz", "X-Device-Id" to "peer-a"),
        )
        assertEquals(401, response.status)
        assertTrue("合法 secret 不应影响方案校验", secret.isNotEmpty())
    }

    @Test
    fun `POST 缺少摘要声明被拒（400）`() {
        val secret = pairAsSender()
        val payload = ByteArray(1024)
        // 只有 Authorization、无 X-Content-Sha256
        val response = sendRaw(
            "POST", "/upload", payload,
            headers = mapOf("Authorization" to "Bearer ${issueTokenDigest(secret, "POST", "/upload", sha256Hex(payload))}"),
        )
        assertEquals(400, response.status)
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun `请求体摘要与声明不符被拒（400）且清理残留`() {
        val secret = pairAsSender()
        val payload = ByteArray(4096) { 3 }
        val wrongDigest = sha256Hex(ByteArray(8))
        val response = sendRaw(
            "POST", "/upload", payload,
            headers = authHeaders(
                secret, "POST", "/upload", wrongDigest,
                bookName = "校验书.txt",
            ),
        )
        assertEquals(400, response.status)
        assertTrue(uploads.isEmpty())
        assertTrue("spill 应被清理", spillDir.listFiles().isNullOrEmpty())
        assertTrue("应有中止回调", abortEvents.any { it.first == TransferEndpoint.UPLOAD })
    }

    @Test
    fun `sync_apply 摘要不符被拒（400）`() {
        val secret = pairAsSender()
        val json = """{"version":1,"deviceId":"me","books":[]}"""
        val response = sendRaw(
            "POST", "/sync/apply", json.toByteArray(),
            headers = authHeaders(secret, "POST", "/sync/apply", sha256Hex(ByteArray(4))),
        )
        assertEquals(400, response.status)
        assertNull(appliedSync)
    }

    @Test
    fun `上传中途断链触发中止回调并清理 spill`() {
        val secret = pairAsSender()
        val digest = sha256Hex(ByteArray(64 * 1024))
        val headers = authHeaders(
            secret, "POST", "/upload", digest,
            bookName = "断链书.txt",
        )
        // 声明 64KB 但只发 1KB 后断链：服务端 spill 读到 EOF
        Socket("127.0.0.1", port()).use { socket ->
            val out: OutputStream = socket.getOutputStream()
            out.write(
                buildString {
                    append("POST /upload HTTP/1.1\r\nHost: 127.0.0.1\r\n")
                    headers.forEach { (k, v) -> append("$k: $v\r\n") }
                    append("Content-Length: ${64 * 1024}\r\nConnection: close\r\n\r\n")
                }.toByteArray(),
            )
            out.write(ByteArray(1024))
            out.flush()
        }
        waitUntil { abortEvents.any { it.first == TransferEndpoint.UPLOAD } }
        assertTrue("应有上传中止回调", abortEvents.any { it.first == TransferEndpoint.UPLOAD })
        assertTrue("spill 应被清理", spillDir.listFiles().isNullOrEmpty())
        assertTrue(uploads.isEmpty())
    }

    @Test
    fun `成功上传后 spill 无残留`() {
        val secret = pairAsSender()
        val file = tempFile(ByteArray(128 * 1024) { 9 })
        assertEquals(UploadResult.Success, client(secret).upload("127.0.0.1", port(), file, "干净书.txt"))
        assertTrue("改名消费后 spill 目录应为空", spillDir.listFiles().isNullOrEmpty())
        file.delete()
    }

    @Test
    fun `缺少文件名头返回 400`() {
        val secret = pairAsSender()
        val payload = ByteArray(512)
        val headers = authHeaders(secret, "POST", "/upload", sha256Hex(payload))
        val response = sendRaw("POST", "/upload", payload, headers - "X-Book-Name")
        assertEquals(400, response.status)
        assertTrue(uploads.isEmpty())
    }
}
