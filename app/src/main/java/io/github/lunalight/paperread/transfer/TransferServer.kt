package io.github.lunalight.paperread.transfer

import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID

/**
 * 传输协议的**语义层**（T-012 从 `TransferHttpServer` 拆出）：鉴权中间件 →
 * 端点路由 → 请求体落盘 / 摘要核对 → 业务回调 → 应答。
 *
 * 五个端点：
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
 * 与帧层的分工：本类只面对 [TransferHttpConnection]（已解析的请求 + 响应通道），
 * 不碰 `ServerSocket` / 连接派发 / 请求行切分；将来换 HTTP 引擎（如 Ktor）
 * 只需重做帧层，本类语义可原样保留。
 */
internal class TransferServer(
    private val handler: TransferHttpHandler,
    private val tokenVerifier: TokenVerifier,
    private val spillDir: File,
    private val onReceiveProgress: (TransferEndpoint, String, Float) -> Unit = { _, _, _ -> },
    private val onReceiveAborted: (TransferEndpoint, String) -> Unit = { _, _ -> },
) {

    /** 处理一条已解析的请求（单请求单响应，方法返回即连接收摊）。 */
    suspend fun handle(connection: TransferHttpConnection) {
        val method = connection.method
        val path = connection.path
        val headers = connection.headers

        // ---- 鉴权中间件（路由前，G1：未配对拒收一切请求） ----
        // POST 以头部声明的请求体摘要验签（时间窗只覆盖请求发起时刻）；
        // GET 等无请求体场景以空摘要验签。
        val claimedDigest = if (method == "POST") {
            val declared = headers["x-content-sha256"]
            if (declared == null || !isSha256Hex(declared)) {
                connection.drainBody(MAX_BODY_BYTES)
                connection.respond(400, """{"error":"missing x-content-sha256"}""")
                return
            }
            declared.lowercase()
        } else {
            EMPTY_BODY_SHA256
        }
        if (!tokenVerifier.verify(method, path, headers, claimedDigest)) {
            connection.drainBody(MAX_BODY_BYTES)
            connection.respond(401, """{"error":"unauthorized"}""")
            return
        }

        when {
            method == "GET" && path == "/meta" ->
                connection.respond(200, handler.meta())

            method == "POST" && path == "/upload" ->
                handlePayload(connection, claimedDigest, TransferEndpoint.UPLOAD, "x-book-name") { name, length, file ->
                    handler.onUpload(name, length, file)
                }

            method == "POST" && path == "/bundle" ->
                handlePayload(connection, claimedDigest, TransferEndpoint.BUNDLE, "x-bundle-name") { name, length, file ->
                    handler.onBundle(name, length, file)
                }

            method == "GET" && path == "/sync/state" -> connection.respond(200, handler.syncState())

            method == "POST" && path == "/sync/apply" -> handleSyncApply(connection, claimedDigest)

            else -> connection.respond(404, """{"error":"not found"}""")
        }
    }

    /** `POST /sync/apply`：JSON 请求体（≤8MB 内存读入），摘要核对后交实现方。 */
    private suspend fun handleSyncApply(
        connection: TransferHttpConnection,
        claimedDigest: String,
    ) {
        val body = connection.readBody(MAX_JSON_BYTES)
            ?: return connection.respond(411, """{"error":"missing content-length"}""")
        if (sha256Hex(body) != claimedDigest) {
            connection.respond(400, """{"error":"digest mismatch"}""")
            return
        }
        val error = try {
            handler.onSyncApply(String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            e.message ?: "apply failed"
        }
        if (error == null) {
            connection.respond(200, """{"ok":true}""")
        } else {
            connection.respond(500, """{"error":"apply failed"}""")
        }
    }

    /**
     * 上传类端点共用流程：名称 / 长度校验 → spill 落盘（同时算摘要 + 报进度）→
     * 摘要核对 → 调用实现方 → 兜底清理。
     *
     * 「先落盘再交付」保证实现方只见验签通过的字节；spill 与目标同处
     * cacheDir，实现方改名即零拷贝到位。
     */
    private suspend fun handlePayload(
        connection: TransferHttpConnection,
        claimedDigest: String,
        endpoint: TransferEndpoint,
        nameHeader: String,
        call: suspend (fileName: String, contentLength: Long, bodyFile: File) -> String?,
    ) {
        val headers = connection.headers
        val rawName = headers[nameHeader]
        if (rawName.isNullOrBlank()) {
            connection.drainBody(MAX_BODY_BYTES)
            connection.respond(400, """{"error":"missing $nameHeader"}""")
            return
        }
        val contentLength = headers["content-length"]?.toLongOrNull()
        if (contentLength == null || contentLength < 0) {
            connection.respond(411, """{"error":"missing content-length"}""")
            return
        }
        if (contentLength > MAX_BODY_BYTES) {
            connection.drainBody(MAX_BODY_BYTES)
            connection.respond(413, """{"error":"body too large"}""")
            return
        }
        val fileName = try {
            URLDecoder.decode(rawName, "UTF-8")
        } catch (e: Exception) {
            connection.drainBody(MAX_BODY_BYTES)
            connection.respond(400, """{"error":"bad $nameHeader"}""")
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
                    val read = connection.input.read(buffer, 0, want)
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
            connection.respond(400, """{"error":"incomplete body"}""")
            return
        }
        if (digest.digest().toHex() != claimedDigest) {
            spill.delete()
            onReceiveAborted(endpoint, "传输数据校验失败")
            connection.respond(400, """{"error":"digest mismatch"}""")
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
            connection.respond(200, """{"ok":true}""")
        } else {
            connection.respond(500, """{"error":"upload failed"}""")
        }
    }

    companion object {

        /**
         * 同步 JSON 请求体上限（8 MB）：一个数千本书、每本几十条书签的书库
         * 约数百 KB，8 MB 足够且挡住恶意的「声明超大长度」请求。
         */
        private const val MAX_JSON_BYTES = 8L * 1024 * 1024

        /**
         * spill 落盘上限（2 GB）：书籍 / 迁移包的宽松上限，同时挡住
         * 「声明天文数字长度」的滥用（配合排空请求体的同款上限）。
         */
        private const val MAX_BODY_BYTES = 2L * 1024 * 1024 * 1024
    }
}
