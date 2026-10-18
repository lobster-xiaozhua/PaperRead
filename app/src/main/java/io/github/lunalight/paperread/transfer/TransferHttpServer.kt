package io.github.lunalight.paperread.transfer

import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 传输 HTTP 服务的业务回调（由 Android 侧实现：落盘 + 复用导入流水线）。
 *
 * 实现方只面对流，不接触 socket / HTTP 细节；进度由实现方自行更新状态
 * （UI 订阅其状态流），无需经本接口回传。
 */
interface TransferHttpHandler {

    /** `GET /meta` 的响应体（JSON：设备名 + 在线信息）。 */
    fun meta(): String

    /**
     * 处理 `POST /upload`。**实现方必须读满 [contentLength] 字节**（服务端会兜底
     * 排空，但提前读完可让对端尽早收到响应）。
     *
     * @param fileName 对端发送的显示文件名（已 URL 解码；调用方需自行校验）
     * @param contentLength 请求体长度（-1 表示缺失，应拒绝）
     * @param body 请求体输入流
     * @return null = 成功；非 null = 失败原因（记日志；对端收到 500）
     */
    fun onUpload(fileName: String, contentLength: Long, body: InputStream): String?
}

/** 计数输入流：服务端据此兜底排空未读尽的请求体。 */
private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count: Long = 0
        private set

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count += n
        return n
    }
}

/**
 * 极简 HTTP/1.1 服务（JDK ServerSocket，零依赖）：仅支撑传输协议的两个端点。
 *
 * - `GET /meta` → 200 JSON（[TransferHttpHandler.meta]）
 * - `POST /upload` → 200 JSON 或 500（[TransferHttpHandler.onUpload]）
 *
 * 设计约束：
 * - **仅绑定调用方指定的网卡地址**（AC-9.7：WiFi 局域网地址，不听其他接口）；
 * - 每连接单请求 + `Connection: close`：无 keep-alive 状态机，实现面最小；
 * - 顺序处理连接（局域网传书工具的对端极少，避免线程池复杂度）；
 * - 单连接异常不中断服务（accept 循环容错）。
 */
class TransferHttpServer(
    bindAddress: InetAddress,
    port: Int,
    private val handler: TransferHttpHandler,
    private val serverSocketFactory: (InetAddress, Int) -> ServerSocket = { address, p ->
        ServerSocket(p, SERVER_BACKLOG, address)
    },
) : Closeable {

    private val serverSocket: ServerSocket = serverSocketFactory(bindAddress, port)

    /** 实际监听端口（port=0 时由系统分配，AC-9.1 展示用）。 */
    val actualPort: Int get() = serverSocket.localPort

    private val running = AtomicBoolean(true)

    /** 接收循环（阻塞 accept）。调用方负责在独立线程 / 协程中运行。 */
    fun serve() {
        while (running.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (e: IOException) {
                if (!running.get()) return // 主动 close 导致的 accept 异常：正常退出
                continue
            }
            try {
                handleConnection(socket)
            } catch (e: Exception) {
                // 单连接异常不得中断服务：吞掉后继续 accept
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket.close() }
    }

    // ---- 内部 ----

    private fun handleConnection(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val input = socket.getInputStream()
        val requestLine = input.readHeaderLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 3) {
            respond(socket, 400, """{"error":"bad request"}""")
            return
        }
        val method = parts[0]
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
        when {
            method == "GET" && path == "/meta" ->
                respond(socket, 200, handler.meta())

            method == "POST" && path == "/upload" -> handleUpload(socket, headers, input)

            else -> respond(socket, 404, """{"error":"not found"}""")
        }
    }

    private fun handleUpload(socket: Socket, headers: Map<String, String>, input: InputStream) {
        val rawName = headers["x-book-name"]
        if (rawName.isNullOrBlank()) {
            respond(socket, 400, """{"error":"missing x-book-name"}""")
            return
        }
        val contentLength = headers["content-length"]?.toLongOrNull()
        if (contentLength == null || contentLength < 0) {
            respond(socket, 411, """{"error":"missing content-length"}""")
            return
        }
        val fileName = try {
            URLDecoder.decode(rawName, "UTF-8")
        } catch (e: Exception) {
            respond(socket, 400, """{"error":"bad x-book-name"}""")
            return
        }
        val counting = CountingInputStream(input)
        val error = handler.onUpload(fileName, contentLength, counting)
        // 兜底排空未读尽的请求体，避免对端写入中途被 RST
        var remaining = contentLength - counting.count
        val skipBuffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
        while (remaining > 0) {
            val skipped = runCatching { counting.skip(remaining.coerceAtMost(skipBuffer.size.toLong())) }
                .getOrDefault(0L)
            if (skipped <= 0L) {
                val read = runCatching { counting.read(skipBuffer, 0, remaining.coerceAtMost(skipBuffer.size.toLong()).toInt()) }
                    .getOrDefault(-1)
                if (read <= 0) break
                remaining -= read
            } else {
                remaining -= skipped
            }
        }
        if (error == null) {
            respond(socket, 200, """{"ok":true}""")
        } else {
            respond(socket, 500, """{"error":"upload failed"}""")
        }
    }

    private fun respond(socket: Socket, status: Int, body: String) {
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

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        411 -> "Length Required"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "Error"
    }

    companion object {
        private const val SERVER_BACKLOG = 16
        private const val SOCKET_TIMEOUT_MS = 60_000

        /** 从流中读一行 HTTP 头（CRLF 结束；不用 BufferedReader，避免缓冲吞掉请求体）。 */
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
    }
}

/** 上传结果（AC-9.6：失败携带可展示原因）。 */
sealed interface UploadResult {
    data object Success : UploadResult
    data class Failure(val message: String) : UploadResult
}

/**
 * 传输 HTTP 客户端（JDK HttpURLConnection，零依赖）：向对端上传单本书。
 *
 * 进度经 [onProgress] 回调（0..1，单调）；大文件走分块流式发送，不常驻内存
 * （与导入流水线一致的流式口径）。
 */
class TransferClient {

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
    ): UploadResult {
        if (!file.isFile) return UploadResult.Failure("文件不存在或不可读")
        val total = file.length()
        if (total <= 0L) return UploadResult.Failure("文件为空")
        var connection: java.net.HttpURLConnection? = null
        return try {
            val url = java.net.URL("http://$host:$port/upload")
            connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setFixedLengthStreamingMode(total)
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty(
                    "X-Book-Name",
                    java.net.URLEncoder.encode(displayName, "UTF-8"),
                )
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
            val status = connection.responseCode
            if (status == 200) {
                onProgress(1f)
                UploadResult.Success
            } else {
                UploadResult.Failure("对端返回 $status")
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
