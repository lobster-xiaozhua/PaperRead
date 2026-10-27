package io.github.lunalight.paperread.transfer

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
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
 * 极简 HTTP/1.1 **帧层**（JDK ServerSocket，零依赖）：accept → 解析请求行 / 头 →
 * 交给路由层（[TransferServer]）→ 写回响应。
 *
 * 拆分说明（T-012 / R-11）：本类只做「字节进、字节出」的活（socket 生命周期、
 * 连接派发、请求框架解析、响应框架写回、请求体读取 / 排空）。
 * **协议语义**（端点路由、鉴权中间件、摘要核对、spill 落盘、业务回调）
 * 在 [TransferServer]；**客户端侧**在 [TransferClient]。三者的分界线是
 * 「谁需要知道 HTTP 报文长什么样」——只有本类与 [TransferHttpConnection] 知道。
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
    handler: TransferHttpHandler,
    tokenVerifier: TokenVerifier,
    spillDir: File,
    onReceiveProgress: (TransferEndpoint, String, Float) -> Unit = { _, _, _ -> },
    onReceiveAborted: (TransferEndpoint, String) -> Unit = { _, _ -> },
    serverSocketFactory: (InetAddress, Int) -> ServerSocket = { address, p ->
        ServerSocket(p, SERVER_BACKLOG, address)
    },
) : Closeable {

    private val serverSocket: ServerSocket = serverSocketFactory(bindAddress, port)

    /** 实际监听端口（port=0 时由系统分配，AC-9.1 展示用）。 */
    val actualPort: Int get() = serverSocket.localPort

    private val running = AtomicBoolean(true)

    /** 协议语义层（路由 + 鉴权 + 落盘），每连接构造一次交换对象后委派给它。 */
    private val router = TransferServer(
        handler = handler,
        tokenVerifier = tokenVerifier,
        spillDir = spillDir,
        onReceiveProgress = onReceiveProgress,
        onReceiveAborted = onReceiveAborted,
    )

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

    /** 帧解析成功则交给路由层；EOF / 坏请求（已应答 400）直接返回。 */
    private suspend fun handleConnection(socket: Socket) {
        val connection = TransferHttpConnection.open(socket, SOCKET_TIMEOUT_MS) ?: return
        router.handle(connection)
    }

    companion object {
        private const val SERVER_BACKLOG = 16

        /** 单连接读写超时（对端长时间无数据即放弃，防止连接泄漏）。 */
        internal const val SOCKET_TIMEOUT_MS = 60_000

        /**
         * 连接处理的最大并发度（R-10 / G10）：一台手机作为接收端的典型并发
         * 只有「对端正在发的连接」一条，4 条并发覆盖「发送中又来同步请求」
         * 的交叠场景，且避免无上限派发耗尽 IO 线程。
         */
        private const val MAX_CONCURRENT_CONNECTIONS = 4
    }
}

/**
 * 一次 HTTP 交换的**帧层**视图：已解析的请求行 / 头 + 请求体流 + 响应写回通道。
 *
 * 刻意做成「解析完才存在」的对象（[open] 是唯一的构造入口）：路由层拿到的都是
 * 合法请求，不必再关心请求行怎么切、头怎么折行、响应框架怎么写。
 *
 * 文件级 internal：路由层（[TransferServer]）与协议层测试共用。
 */
internal class TransferHttpConnection private constructor(
    private val socket: Socket,
    /** 大写方法名（GET / POST）。 */
    val method: String,
    /** 已去查询串的路径（`/meta` / `/upload` …）。 */
    val path: String,
    /** 请求头（键为小写，值为原样 trim）。 */
    val headers: Map<String, String>,
    /** 请求体流（未消费部分）。 */
    val input: InputStream,
) {

    /**
     * 按 Content-Length 读满请求体；长度缺失 / 非法 / 超上限 / 提前 EOF 返回 null。
     *
     * 不用 [InputStream.readNBytes]：那是 API 33 才有的方法，
     * 本项目 minSdk 26，需自行循环读取。
     */
    fun readBody(maxBytes: Long): ByteArray? {
        val length = headers["content-length"]?.toLongOrNull() ?: return null
        if (length < 0 || length > maxBytes) return null
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
     * 提前拒绝时排空请求体再应答：服务端带未读数据关闭会触发 RST，
     * 对端将读到连接重置而非 4xx 状态码（401 → 「需要配对」的映射依赖它）。
     *
     * 上限 [maxBytes]：超出即放弃排空（滥用场景，对端自求多福）。
     */
    fun drainBody(maxBytes: Long) {
        val length = headers["content-length"]?.toLongOrNull() ?: return
        if (length <= 0L || length > maxBytes) return
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
        var remaining = length
        while (remaining > 0L) {
            val want = minOf(buffer.size.toLong(), remaining).toInt()
            val read = runCatching { input.read(buffer, 0, want) }.getOrDefault(-1)
            if (read <= 0) return
            remaining -= read
        }
    }

    /** 写回一条完整响应（JSON 体 + Content-Length + Connection: close）。 */
    fun respond(status: Int, body: String) {
        writeResponse(socket, status, body)
    }

    companion object {

        /**
         * 解析请求行与请求头。
         *
         * @return 解析成功返回连接对象；连接提前关闭（无请求行）或请求行非法
         *   （已写回 400）返回 null——两种情况调用方都只需直接收摊。
         */
        fun open(socket: Socket, timeoutMs: Int): TransferHttpConnection? {
            socket.soTimeout = timeoutMs
            val input = socket.getInputStream()
            val requestLine = input.readHeaderLine() ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 3) {
                writeResponse(socket, 400, """{"error":"bad request"}""")
                return null
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
            return TransferHttpConnection(socket, method, path, headers, input)
        }
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

/** 写回一条 HTTP/1.1 响应（单请求单响应 + Connection: close，故无需状态机）。 */
private fun writeResponse(socket: Socket, status: Int, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val output = socket.getOutputStream()
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

/** 状态码 → reason phrase（固定七种，够本协议用）。 */
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
