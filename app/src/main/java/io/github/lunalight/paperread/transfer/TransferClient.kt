package io.github.lunalight.paperread.transfer

import java.io.File
import java.security.MessageDigest

/**
 * 上传结果（AC-9.6：失败携带可展示原因）。
 *
 * 拆分说明（T-012）：本文件与 [TransferHttpServer] 原先同住一个 700+ 行文件
 * （服务端帧层 + 路由 + 客户端三件事挤在一起）。客户端与服务端唯一的耦合是
 * **协议契约**（[issueTokenDigest] / 摘要头 / 端点路径），不是代码结构，
 * 因此拆成独立文件后两侧可分别演进：服务端加端点不影响客户端编译单元。
 */
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
 *
 * 线程语义：全部方法为**阻塞**调用，调用方自行切到 IO 线程
 * （编排层经 `withContext(Dispatchers.IO)` 或后台协程调用）。
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
