package io.github.lunalight.paperread.transfer

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 传输 HTTP 服务的业务回调（由 Android 侧实现：落盘 + 复用导入流水线）。
 *
 * 实现方只面对已鉴权的文件 / 文本，不接触 socket / HTTP 细节；进度由实现方
 * 自行更新状态（UI 订阅其状态流），无需经本接口回传。
 *
 * 全部方法为 suspend：服务端以受限并发（Dispatchers.IO.limitedParallelism(4)）
 * 派发连接，实现方的耗时操作（解析 / 入库）不得阻塞 accept 循环。
 */
interface TransferHttpHandler {

    /** `GET /meta` 的响应体（JSON：设备名 + 在线信息）。 */
    suspend fun meta(): String

    /**
     * 处理 `POST /upload`。
     *
     * 请求体已由服务端鉴权（Bearer 令牌验签 + 摘要核对）并完整落盘为 [bodyFile]；
     * 实现方负责把该文件改名到目标位置或直接消费（同分区 rename 为零拷贝），
     * 返回非 null 表示失败（服务端兜底清理仍未被消费的文件）。
     *
     * @param fileName 对端发送的显示文件名（已 URL 解码；调用方需自行校验）
     * @param contentLength 请求体长度（已由服务端校验为正数）
     * @param bodyFile 已鉴权的请求体临时文件（实现方拥有其生命周期）
     * @return null = 成功；非 null = 失败原因（记日志；对端收到 500）
     */
    suspend fun onUpload(fileName: String, contentLength: Long, bodyFile: File): String?

    /**
     * 处理 `POST /bundle`（换机迁移包上传，AC-10.1）。
     *
     * 与 [onUpload] 同一契约：请求体已鉴权落盘，实现方改名消费。
     * 迁移包的处理（解包 / 逐本导入 / 回写）由实现方自行安排——
     * 本服务只保证字节完整送达。
     */
    suspend fun onBundle(fileName: String, contentLength: Long, bodyFile: File): String?

    /**
     * `GET /sync/state` 的响应体（本机阅读状态 JSON，AC-11.1）。
     *
     * @return 状态快照；返回空串表示同步能力不可用（对端据此提示）
     */
    suspend fun syncState(): String

    /**
     * 处理 `POST /sync/apply`（对端回推的较新状态，AC-11.1）。
     *
     * @return null = 应用成功；非 null = 失败原因（对端收到 500）
     */
    suspend fun onSyncApply(payload: String): String?
}

/** 请求体接收端点（进度 / 中止回调的路由标签）。 */
enum class TransferEndpoint { UPLOAD, BUNDLE }

/**
 * 极简 HTTP/1.1 服务（JDK ServerSocket，零依赖）：传输协议的五个端点。
 *
 * - `GET /meta` → 200 JSON（[TransferHttpHandler.meta]）
 * - `POST /upload` → 200 / 400 / 401 / 500（[TransferHttpHandler.onUpload]）
 * - `POST /bundle` → 同上（[TransferHttpHandler.onBundle]，换机迁移包）
 * - `GET /sync/state` → 200 JSON（[TransferHttpHandler.syncState]）
 * - `POST /sync/apply` → 200 / 400 / 401 / 500（[TransferHttpHandler.onSyncApply]）
 *
 * 鉴权（R-01 / G1）：**路由前中间件**——Bearer 令牌经 [TokenVerifier] 校验，
 * 失败即 401（先排空请求体再应答，避免对端读到连接重置而非状态码）。
 * POST 请求必须携带 `X-Content-Sha256`（请求体摘要声明）：中间件以声明摘要
 * 验签——令牌时间窗因此只覆盖「请求发起」时刻，长文件传输不会中途失效；
 * 服务端落盘后再核对实际摘要与声明值，杜绝「验签值 ≠ 交付值」。
 *
 * 设计约束：
 * - **仅绑定调用方指定的网卡地址**（AC-9.7：WiFi 局域网地址，不听其他接口）；
 * - 每连接单请求 + `Connection: close`：无 keep-alive 状态机，实现面最小；
 * - accept 后以 [Dispatchers.IO.limitedParallelism] 受限并发派发（R-10 / G10：
 *   实现方的大书解析 / 迁移解包不再占着 accept 循环，后续连接可被及时处理）；
 * - 单连接异常不中断服务（SupervisorJob + 静默 exception handler 吸收）。
 */
class TransferHttpServer(
    bindAddress: InetAddress,
    port: Int,
    private val handler: TransferHttpHandler,
    private val tokenVerifier: TokenVerifier,
    private val spillDir: File,
    private val onReceiveProgress: (TransferEndpoint, String, Float) -> Unit = { _, _, _ -> },
    private val onReceiveAborted: (TransferEndpoint, String) -> Unit = { _, _ -> },
    private val serverSocketFactory: (InetAddress, Int) -> ServerSocket = { address, p ->
        ServerSocket(p, SERVER_BACKLOG, address)
    },
) : Closeable {

    private val serverSocket: ServerSocket = serverSocketFactory(bindAddress, port)

    /** 实际监听端口（port=0 时由系统分配，AC-9.1 展示用）。 */
    val actualPort: Int get() = serverSocket.localPort

    private val running = AtomicBoolean(true)

    /**
     * 连接处理作用域（R-10 / G10）：accept 循环只负责「接收 + 派发」，
     * 单连接的读请求体 → 落盘 → 交实现方的全程在此执行。
     *
     * 并发度 [MAX_CONCURRENT_CONNECTIONS]：传书 / 迁移 / 同步三类连接各自
     * 持文件 I/O 与实现方耗时，4 条并发足够一台手机的对端场景，同时挡住
     * 「多连接堆叠耗尽线程」的滥用。
     */
    private val connectionScope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.IO.limitedParallelism(MAX_CONCURRENT_CONNECTIONS),
    )

    /**
     * 单连接异常的兜底（G10「单连接异常不中断服务」）。
     *
     * SupervisorJob 保证一个连接的失败不波及其他连接；此处静默吸收，
     * 与改造前 accept 循环内的 try/catch 语义一致（客户端多半已断链，
     * 无可挽救也不必惊扰用户）。
     */
    private val connectionErrorHandler = CoroutineExceptionHandler { _, _ -> }

    /** 接收循环（阻塞 accept，随后派发到 [connectionScope]）。 */
    fun serve() {
        while (running.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                if (!running.get()) return // 主动 close 导致的 accept 异常：正常退出
                continue
            }
            // close 与 accept 交叠：作用域已取消时协程不会启动（其 finally
            // 也就不会执行），此处直接关 socket 避免泄漏
            if (!connectionScope.isActive) {
                runCatching { socket.close() }
                return
            }
            connectionScope.launch(connectionErrorHandler) {
                try {
                    handleConnection(socket)
                } finally {
                    runCatching { socket.close() }
                }
            }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
        // 取消在途连接的处理协程（其 finally 负责关 socket）
        connectionScope.cancel()
    }

    // ---- 内部 ----

    private suspend fun handleConnection(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val input = socket.getInputStream()
        val requestLine = input.readHeaderLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 3) {
            respond(socket, 400, """{"error":"bad request"}""")
            return
        }
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')
        val headers = HashMap<String, String>()
        while (true) {
            val line = input.readHeaderLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }

        // ---- 鉴权中间件（路由前，G1：未配对拒收一切请求） ----
        // POST 以头部声明的请求体摘要验签（时间窗只覆盖请求发起时刻）；
        // GET 等无请求体场景以空摘要验签。
        val claimedDigest = if (method == "POST") {
            val declared = headers["x-content-sha256"]
            if (declared == null || !isSha256Hex(declared)) {
                drainBody(headers, input)
                respond(socket, 400, """{"error":"missing x-content-sha256"}""")
                return
            }
            declared.lowercase()
        } else {
            EMPTY_BODY_SHA256
        }
        if (!tokenVerifier.verify(method, path, headers, claimedDigest)) {
            drainBody(headers, input)
            respond(socket, 401, """{"error":"unauthorized"}""")
            return
        }

        when {
            method == "GET" && path == "/meta" ->
                respond(socket, 200, handler.meta())

            method == "POST" && path == "/upload" ->
                handlePayload(socket, headers, input, claimedDigest, TransferEndpoint.UPLOAD, "x-book-name") { name, length, file ->
                    handler.onUpload(name, length, file)
                }

            method == "POST" && path == "/bundle" ->
                handlePayload(socket, headers, input, claimedDigest, TransferEndpoint.BUNDLE, "x-bundle-name") { name, length, file ->
                    handler.onBundle(name, length, file)
                }

            method == "GET" && path == "/sync/state" -> respond(socket, 200, handler.syncState())

            method == "POST" && path == "/sync/apply" -> handleSyncApply(socket, headers, input, claimedDigest)

            else -> respond(socket, 404, """{"error":"not found"}""")
        }
    }

    /** `POST /sync/apply`：JSON 请求体（≤8MB 内存读入），摘要核对后交实现方。 */
    private suspend fun handleSyncApply(
        socket: Socket,
        headers: Map<String, String>,
        input: InputStream,
        claimedDigest: String,
    ) {
        val body = readBody(headers, input)
            ?: return respond(socket, 411, """{"error":"missing content-length"}""")
        if (sha256Hex(body) != claimedDigest) {
            respond(socket, 400, """{"error":"digest mismatch"}""")
            return
        }
        val error = try {
            handler.onSyncApply(String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            e.message ?: "apply failed"
        }
        if (error == null) {
            respond(socket, 200, """{"ok":true}""")
        } else {
            respond(socket, 500, """{"error":"apply failed"}""")
        }
    }

    /**
     * 按 Content-Length 读满请求体；长度缺失 / 非法 / 提前 EOF 返回 null。
     *
     * 不用 [InputStream.readNBytes]：那是 API 33 才有的方法，
     * 本项目 minSdk 26，需自行循环读取。
     */
    private fun readBody(headers: Map<String, String>, input: InputStream): ByteArray? {
        val length = headers["content-length"]?.toLongOrNull() ?: return null
        if (length < 0 || length > MAX_JSON_BYTES) return null
        val size = length.toInt()
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) return null
            offset += read
        }
        return buffer
    }

    /**
     * 上传类端点共用流程：名称 / 长度校验 → spill 落盘（同时算摘要 + 报进度）→
     * 摘要核对 → 调用实现方 → 兜底清理。
     *
     * 「先落盘再交付」保证实现方只见验签通过的字节；spill 与目标同处
     * cacheDir，实现方改名即零拷贝到位。
     */
    private suspend fun handlePayload(
        socket: Socket,
        headers: Map<String, String>,
        input: InputStream,
        claimedDigest: String,
        endpoint: TransferEndpoint,
        nameHeader: String,
        call: suspend (fileName: String, contentLength: Long, bodyFile: File) -> String?,
    ) {
        val rawName = headers[nameHeader]
        if (rawName.isNullOrBlank()) {
            drainBody(headers, input)
            respond(socket, 400, """{"error":"missing $nameHeader"}""")
            return
        }
        val contentLength = headers["content-length"]?.toLongOrNull()
        if (contentLength == null || contentLength < 0) {
            respond(socket, 411, """{"error":"missing content-length"}""")
            return
        }
        if (contentLength > MAX_BODY_BYTES) {
            drainBody(headers, input)
            respond(socket, 413, """{"error":"body too large"}""")
            return
        }
        val fileName = try {
            URLDecoder.decode(rawName, "UTF-8")
        } catch (e: Exception) {
            drainBody(headers, input)
            respond(socket, 400, """{"error":"bad $nameHeader"}""")
            return
        }

        val spill = File(spillDir.apply { mkdirs() }, ".spill-${UUID.randomUUID()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            spill.outputStream().buffered().use { out ->
                var copied = 0L
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                while (copied < contentLength) {
                    val want = minOf(buffer.size.toLong(), contentLength - copied).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read < 0) throw IOException("请求体不完整")
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                    copied += read
                    onReceiveProgress(
                        endpoint,
                        fileName,
                        (copied.toFloat() / contentLength).coerceIn(0f, 1f),
                    )
                }
            }
        } catch (e: Exception) {
            spill.delete()
            onReceiveAborted(endpoint, "传输不完整，已丢弃")
            respond(socket, 400, """{"error":"incomplete body"}""")
            return
        }
        if (digest.digest().toHex() != claimedDigest) {
            spill.delete()
            onReceiveAborted(endpoint, "传输数据校验失败")
            respond(socket, 400, """{"error":"digest mismatch"}""")
            return
        }

        val error = try {
            call(fileName, contentLength, spill)
        } catch (e: Exception) {
            e.message ?: "handler failed"
        } finally {
            // 实现方已改名消费；未消费（失败路径）则清理残留
            spill.delete()
        }
        if (error == null) {
            respond(socket, 200, """{"ok":true}""")
        } else {
            respond(socket, 500, """{"error":"upload failed"}""")
        }
    }

    /**
     * 提前拒绝时排空请求体再应答：服务端带未读数据关闭会触发 RST，
     * 对端将读到连接重置而非 4xx 状态码（401 → 「需要配对」的映射依赖它）。
     *
     * 上限 [MAX_BODY_BYTES]：超出即放弃排空（滥用场景，对端自求多福）。
     */
    private fun drainBody(headers: Map<String, String>, input: InputStream) {
        val length = headers["content-length"]?.toLongOrNull() ?: return
        if (length <= 0L || length > MAX_BODY_BYTES) return
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
        var remaining = length
        while (remaining > 0L) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val read = runCatching { input.read(buffer, 0, want) }.getOrDefault(-1)
            if (read <= 0) return
            remaining -= read
        }
    }

    private fun respond(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val output: OutputStream = socket.getOutputStream()
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        else -> "Error"
    }

    companion object {
        private const val SERVER_BACKLOG = 16
        private const val SOCKET_TIMEOUT_MS = 60_000

        /**
         * 连接处理的最大并发度（R-10 / G10）：一台手机作为接收端的典型并发
         * 只有「对端正在发的连接」一条，4 条并发覆盖「发送中又来同步请求」
         * 的交叠场景，且避免无上限派发耗尽 IO 线程。
         */
        private const val MAX_CONCURRENT_CONNECTIONS = 4

        /**
         * 同步 JSON 请求体上限（8 MB）：一个数千本书、每本几十条书签的书库
         * 约数百 KB，8 MB 足够且挡住恶意的「声明超大长度」请求。
         */
        private const val MAX_JSON_BYTES = 8L * 1024 * 1024

        /**
         * spill 落盘上限（2 GB）：书籍 / 迁移包的宽松上限，同时挡住
         * 「声明天文数字长度」的滥用（配合 drainBody 同款上限）。
         */
        private const val MAX_BODY_BYTES = 2L * 1024 * 1024 * 1024
    }
}

/**
 * 从流中读一行 HTTP 头（CRLF 结束；不用 BufferedReader，避免缓冲吞掉请求体）。
 *
 * 文件级 internal：服务端解析与测试的裸 socket 客户端共用。
 */
internal fun InputStream.readHeaderLine(): String? {
    val sb = StringBuilder()
    while (true) {
        val b = read()
        if (b < 0) return if (sb.isEmpty()) null else sb.toString()
        if (b == '\n'.code) {
            if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
            return sb.toString()
        }
        sb.append(b.toChar())
    }
}

/** 上传结果（AC-9.6：失败携带可展示原因）。 */
sealed interface UploadResult {
    data object Success : UploadResult
    data class Failure(val message: String, val unauthorized: Boolean = false) : UploadResult
}

/**
 * 配对探测结果（发送端输入配对码后向对端 `GET /meta` 发起的首个请求）。
 */
sealed interface PairingProbeResult {
    /** 对端接受该配对码（200）：配对落定。 */
    data object Paired : PairingProbeResult

    /** 对端拒绝（401）：配对码错误 / 对端未在配对中 / 对端已与他人配对。 */
    data object Rejected : PairingProbeResult

    /** 对端返回异常状态码（连接可达但服务异常）。 */
    data object Failed : PairingProbeResult

    /** 连接失败 / 超时。 */
    data object Unreachable : PairingProbeResult
}

/**
 * 传输 HTTP 客户端（JDK HttpURLConnection，零依赖）：向对端上传单本书或迁移包。
 *
 * 进度经 [onProgress] 回调（0..1，单调）；大文件走分块流式发送，不常驻内存
 * （与导入流水线一致的流式口径）。
 *
 * 鉴权（R-01 / G1）：每个请求携带 `Authorization: Bearer <令牌>` 与
 * `X-Device-Id: <本机设备 ID>`；POST 另带 `X-Content-Sha256: <请求体摘要>`
 * （先声明后核对，见 [TransferHttpServer]）。凭证由 [secretProvider] 提供，
 * 未配对（null）时请求直接失败——配对走 [probePairing]（候选 secret 显式传入）。
 */
class TransferClient(
    private val secretProvider: () -> ByteArray? = { null },
    private val selfDeviceId: () -> String = { "" },
) {

    /**
     * 上传文件到对端。
     *
     * @param host 对端 IPv4（[DiscoveredDevice.host]）
     * @param port 对端 HTTP 端口（[DiscoveredDevice.httpPort]）
     * @param file 待发送的源文件
     * @param displayName 对端展示用的文件名（含扩展名）
     */
    fun upload(
        host: String,
        port: Int,
        file: File,
        displayName: String,
        onProgress: (Float) -> Unit = {},
    ): UploadResult = uploadTo(
        host = host,
        port = port,
        file = file,
        displayName = displayName,
        path = "/upload",
        nameHeader = "X-Book-Name",
        onProgress = onProgress,
    )

    /**
     * 上传换机迁移包（zip）到对端（AC-10.1）。
     *
     * 与 [upload] 同一连接语义，仅端点 / 名称头不同。
     */
    fun uploadBundle(
        host: String,
        port: Int,
        file: File,
        displayName: String,
        onProgress: (Float) -> Unit = {},
    ): UploadResult = uploadTo(
        host = host,
        port = port,
        file = file,
        displayName = displayName,
        path = "/bundle",
        nameHeader = "X-Bundle-Name",
        onProgress = onProgress,
    )

    /**
     * 配对探测：以候选 secret 向对端 `GET /meta` 发起请求（AC-9.x 之外的
     * R-01 流程）。对端在配对中且配对码一致时返回 200，配对即落定。
     *
     * @param candidateSecret [io.github.lunalight.paperread.transfer.deriveSecret]
     *   派生的候选 secret（探测失败时调用方负责清零）
     * @param selfDeviceId 本机设备 ID（对端据此派生同一 secret）
     */
    fun probePairing(
        host: String,
        port: Int,
        candidateSecret: ByteArray,
        selfDeviceId: String,
    ): PairingProbeResult {
        val token = issueTokenDigest(candidateSecret, "GET", "/meta", EMPTY_BODY_SHA256)
        var connection: java.net.HttpURLConnection? = null
        return try {
            connection = (java.net.URL("http://$host:$port/meta").openConnection()
                as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-Device-Id", selfDeviceId)
            }
            when (connection.responseCode) {
                200 -> PairingProbeResult.Paired
                401 -> PairingProbeResult.Rejected
                else -> PairingProbeResult.Failed
            }
        } catch (e: Exception) {
            PairingProbeResult.Unreachable
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 拉取对端阅读状态（AC-11.1）。失败 / 未配对 / 响应不可解析返回 null。
     */
    fun fetchSyncState(host: String, port: Int): String? {
        val secret = secretProvider() ?: return null
        val token = issueTokenDigest(secret, "GET", "/sync/state", EMPTY_BODY_SHA256)
        var connection: java.net.HttpURLConnection? = null
        return try {
            connection = (java.net.URL("http://$host:$port/sync/state").openConnection()
                as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-Device-Id", selfDeviceId())
            }
            if (connection.responseCode != 200) {
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }.ifBlank { null }
            }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** 把本地较新的状态回推对端（AC-11.1）。返回是否成功。 */
    fun pushSyncState(host: String, port: Int, json: String): Boolean {
        val secret = secretProvider() ?: return false
        val bytes = json.toByteArray(Charsets.UTF_8)
        val digest = sha256Hex(bytes)
        val token = issueTokenDigest(secret, "POST", "/sync/apply", digest)
        var connection: java.net.HttpURLConnection? = null
        return try {
            connection = (java.net.URL("http://$host:$port/sync/apply").openConnection()
                as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setFixedLengthStreamingMode(bytes.size)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-Device-Id", selfDeviceId())
                setRequestProperty("X-Content-Sha256", digest)
            }
            connection.outputStream.use { it.write(bytes) }
            connection.responseCode == 200
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun uploadTo(
        host: String,
        port: Int,
        file: File,
        displayName: String,
        path: String,
        nameHeader: String,
        onProgress: (Float) -> Unit,
    ): UploadResult {
        if (!file.isFile) return UploadResult.Failure("文件不存在或不可读")
        val total = file.length()
        if (total <= 0L) return UploadResult.Failure("文件为空")
        val secret = secretProvider() ?: return UploadResult.Failure("尚未与对端配对")
        // 令牌必须随头发送：先完整算一遍文件摘要（多一次顺序读，常驻内存不变）
        val bodyDigest = try {
            file.sha256Hex()
        } catch (e: Exception) {
            return UploadResult.Failure("读取待发送文件失败")
        }
        val token = issueTokenDigest(secret, "POST", path, bodyDigest)
        var connection: java.net.HttpURLConnection? = null
        return try {
            val url = java.net.URL("http://$host:$port$path")
            connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setFixedLengthStreamingMode(total)
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty(
                    nameHeader,
                    java.net.URLEncoder.encode(displayName, "UTF-8"),
                )
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-Device-Id", selfDeviceId())
                setRequestProperty("X-Content-Sha256", bodyDigest)
            }
            var sent = 0L
            connection.outputStream.use { out ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        sent += read
                        onProgress((sent.toFloat() / total).coerceIn(0f, 1f))
                    }
                    out.flush()
                }
            }
            when (val status = connection.responseCode) {
                200 -> {
                    onProgress(1f)
                    UploadResult.Success
                }

                401 -> UploadResult.Failure("对端未配对或配对已失效，请重新配对", unauthorized = true)
                else -> UploadResult.Failure("对端返回 $status")
            }
        } catch (e: Exception) {
            UploadResult.Failure(e.message ?: "网络异常")
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000
    }
}

/** 文件 SHA-256 十六进制（流式计算，不常驻内存）。 */
private fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}
