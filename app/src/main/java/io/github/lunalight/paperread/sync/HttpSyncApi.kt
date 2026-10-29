package io.github.lunalight.paperread.sync

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 基于 [HttpURLConnection] 的 [SyncApi] 实现（项目零网络依赖）。
 *
 * 设计要点：
 * - **构造与发送解耦**：[buildXxxRequest] 是纯函数，只产出 [SyncRequest]
 *   （URL / 方法 / 头 / 请求体字节），不碰网络——单测可直接断言其不变量；
 * - **发送 + 解析解耦**：[send] 把 [SyncRequest] 发成 [SyncRawResponse]（吞掉一切
 *   IO 异常，置 `threw`），[parseXxx] 把响应解析为结果类型——解析函数可独立单测；
 * - **连接可注入**：[SyncConnectionFactory] 默认走真实 [HttpURLConnection]，测试可
 *   替换为返回 canned 响应的 fake，从而在不联网 / 无真机下验证解析分支。
 */

/** 项目统一 JSON 策略（与备份模块一致：忽略未知键、写默认值）。 */
internal val syncJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val UTF8: Charset = Charsets.UTF_8

/**
 * 可注入的连接工厂（测试用 fake 替代 HttpURLConnection）。
 *
 * 设为 public 以便单测在同模块内注入 canned 连接；[HttpSyncApi] 主构造暴露此类型，
 * 故不可为 internal，否则触发「public 声明暴露 internal 参数类型」的编译错误。
 */
fun interface SyncConnectionFactory {
    fun open(url: String): HttpURLConnection
}

/** 真实连接工厂：默认不自动跟随重定向（4xx / 3xx 的 body 要自己读 errorStream）。 */
internal object RealSyncConnectionFactory : SyncConnectionFactory {
    override fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
        }
}

/** 构造出的请求（未发送）：便于单测断言 URL / 方法 / 头 / 请求体字段。 */
internal data class SyncRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
) {
    /** 请求体解析为 JSON 字符串（测试断言字段名 / 值用）。 */
    fun bodyText(): String = body?.toString(UTF8) ?: ""
}

/** 发送后的原始响应：`threw` 非空 = 网络 / IO 异常（不应抛）。 */
internal data class SyncRawResponse(
    val status: Int,
    val body: String,
    val threw: Throwable? = null,
)

class HttpSyncApi(
    private val baseUrl: String = SYNC_API_BASE_URL,
    private val factory: SyncConnectionFactory = RealSyncConnectionFactory,
) : SyncApi {

    // ===== 请求构造（纯函数，可单测） =====

    internal fun buildRegisterRequest(email: String, password: String): SyncRequest =
        SyncRequest(
            method = "POST",
            url = "$baseUrl/api/auth/register",
            headers = jsonHeaders(),
            body = syncJson.encodeToString(AuthRequest.serializer(), AuthRequest(email, password))
                .toByteArray(UTF8),
        )

    internal fun buildLoginRequest(email: String, password: String): SyncRequest =
        SyncRequest(
            method = "POST",
            url = "$baseUrl/api/auth/login",
            headers = jsonHeaders(),
            body = syncJson.encodeToString(AuthRequest.serializer(), AuthRequest(email, password))
                .toByteArray(UTF8),
        )

    internal fun buildRefreshRequest(refreshToken: String): SyncRequest =
        SyncRequest(
            method = "POST",
            url = "$baseUrl/api/auth/refresh",
            headers = jsonHeaders(),
            body = syncJson.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken))
                .toByteArray(UTF8),
        )

    internal fun buildLogoutRequest(refreshToken: String): SyncRequest =
        SyncRequest(
            method = "POST",
            url = "$baseUrl/api/auth/logout",
            headers = jsonHeaders(),
            body = syncJson.encodeToString(LogoutRequest.serializer(), LogoutRequest(refresh_token = refreshToken))
                .toByteArray(UTF8),
        )

    internal fun buildMeRequest(accessToken: String): SyncRequest =
        SyncRequest(
            method = "GET",
            url = "$baseUrl/api/auth/me",
            headers = authHeaders(accessToken),
            body = null,
        )

    internal fun buildPutSnapshotRequest(
        deviceId: String,
        accessToken: String,
        ciphertext: String,
        lastModified: Long,
    ): SyncRequest =
        SyncRequest(
            method = "PUT",
            url = "$baseUrl/api/sync/snapshot",
            headers = syncHeaders(deviceId, accessToken),
            body = syncJson.encodeToString(
                PutSnapshotRequest.serializer(),
                PutSnapshotRequest(ciphertext, lastModified),
            ).toByteArray(UTF8),
        )

    internal fun buildGetSnapshotRequest(deviceId: String, accessToken: String): SyncRequest =
        SyncRequest(
            method = "GET",
            url = "$baseUrl/api/sync/snapshot",
            headers = syncHeaders(deviceId, accessToken),
            body = null,
        )

    internal fun buildListDevicesRequest(deviceId: String, accessToken: String): SyncRequest =
        SyncRequest(
            method = "GET",
            url = "$baseUrl/api/sync/devices",
            headers = syncHeaders(deviceId, accessToken),
            body = null,
        )

    internal fun buildDeleteSnapshotRequest(deviceId: String, accessToken: String): SyncRequest =
        SyncRequest(
            method = "DELETE",
            url = "$baseUrl/api/sync/snapshot",
            headers = syncHeaders(deviceId, accessToken),
            body = null,
        )

    // ===== 公开方法：构造 → 发送 → 解析 =====

    override suspend fun register(email: String, password: String): SyncAuthResult =
        parseAuthResult(send(buildRegisterRequest(email, password)))

    override suspend fun login(email: String, password: String): SyncAuthResult =
        parseAuthResult(send(buildLoginRequest(email, password)))

    override suspend fun refresh(refreshToken: String): SyncAuthResult =
        parseAuthResult(send(buildRefreshRequest(refreshToken)))

    override suspend fun logout(refreshToken: String): SyncUnitResult {
        val response = send(buildLogoutRequest(refreshToken))
        if (response.threw != null) return SyncUnitResult.Error(response.threw)
        if (response.status in 200..299) return SyncUnitResult.Success
        val (code, message) = failureDetail(response)
        return SyncUnitResult.Failure(response.status, code, message)
    }

    override suspend fun me(accessToken: String): SyncUserResult {
        val response = send(buildMeRequest(accessToken))
        if (response.threw != null) return SyncUserResult.Error(response.threw)
        if (response.status in 200..299) {
            val body = runCatching {
                syncJson.decodeFromString(UserBody.serializer(), response.body)
            }.getOrNull()
            if (body != null) return SyncUserResult.Success(body.email, body.id)
            return SyncUserResult.Failure(response.status, "bad_body", response.body)
        }
        val (code, message) = failureDetail(response)
        return SyncUserResult.Failure(response.status, code, message)
    }

    override suspend fun putSnapshot(
        deviceId: String,
        accessToken: String,
        ciphertext: String,
        lastModified: Long,
    ): SyncUnitResult {
        val response = send(
            buildPutSnapshotRequest(deviceId, accessToken, ciphertext, lastModified),
        )
        if (response.threw != null) return SyncUnitResult.Error(response.threw)
        if (response.status in 200..299) return SyncUnitResult.Success
        val (code, message) = failureDetail(response)
        return SyncUnitResult.Failure(response.status, code, message)
    }

    override suspend fun getSnapshot(deviceId: String, accessToken: String): SyncSnapshotResult {
        val response = send(buildGetSnapshotRequest(deviceId, accessToken))
        if (response.threw != null) return SyncSnapshotResult.Error(response.threw)
        // 404 = 该设备尚无快照，视作「无内容」成功（pull 时跳过即可）
        if (response.status == 404) return SyncSnapshotResult.Success(null)
        if (response.status in 200..299) {
            val body = runCatching {
                syncJson.decodeFromString(SnapshotBody.serializer(), response.body)
            }.getOrNull()
            if (body != null) {
                return SyncSnapshotResult.Success(SyncSnapshot(body.ciphertext, body.lastModified))
            }
            return SyncSnapshotResult.Failure(response.status, "bad_body", response.body)
        }
        val (code, message) = failureDetail(response)
        return SyncSnapshotResult.Failure(response.status, code, message)
    }

    override suspend fun listDevices(deviceId: String, accessToken: String): SyncDevicesResult {
        val response = send(buildListDevicesRequest(deviceId, accessToken))
        if (response.threw != null) return SyncDevicesResult.Error(response.threw)
        if (response.status in 200..299) {
            val body = runCatching {
                syncJson.decodeFromString(DevicesBody.serializer(), response.body)
            }.getOrNull()
            if (body != null) {
                return SyncDevicesResult.Success(
                    body.devices.map { SyncDevice(it.deviceId, it.lastModified) },
                )
            }
            return SyncDevicesResult.Failure(response.status, "bad_body", response.body)
        }
        val (code, message) = failureDetail(response)
        return SyncDevicesResult.Failure(response.status, code, message)
    }

    override suspend fun deleteSnapshot(deviceId: String, accessToken: String): SyncUnitResult {
        val response = send(buildDeleteSnapshotRequest(deviceId, accessToken))
        if (response.threw != null) return SyncUnitResult.Error(response.threw)
        if (response.status in 200..299) return SyncUnitResult.Success
        val (code, message) = failureDetail(response)
        return SyncUnitResult.Failure(response.status, code, message)
    }

    // ===== 发送（吞异常） =====

    internal suspend fun send(request: SyncRequest): SyncRawResponse = withContext(Dispatchers.IO) {
        val connection = try {
            factory.open(request.url)
        } catch (e: Exception) {
            // 连都建不起来（DNS / 权限 / 工厂注入抛错）：封装为 threw，绝不向上抛
            return@withContext SyncRawResponse(-1, "", e)
        }
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            request.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            request.body?.let { bytes ->
                connection.doOutput = true
                connection.outputStream.use { out -> out.write(bytes) }
            }
            val status = try {
                connection.responseCode
            } catch (e: Exception) {
                return@withContext SyncRawResponse(-1, "", e)
            }
            val raw = if (status in 200..299) {
                connection.inputStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            } else {
                connection.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            }
            SyncRawResponse(status, raw)
        } catch (e: Exception) {
            // 连接建立后任意 IO 异常：封装为 threw，绝不向上抛
            SyncRawResponse(-1, "", e)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    // ===== 解析辅助 =====

    internal fun parseAuthResult(response: SyncRawResponse): SyncAuthResult {
        if (response.threw != null) return SyncAuthResult.Error(response.threw)
        if (response.status in 200..299) {
            val body = runCatching {
                syncJson.decodeFromString(AuthSuccessBody.serializer(), response.body)
            }.getOrNull()
            if (body != null && body.access_token.isNotEmpty()) {
                return SyncAuthResult.Success(
                    accessToken = body.access_token,
                    refreshToken = body.refresh_token,
                    expiresIn = body.expires_in,
                )
            }
            return SyncAuthResult.Failure(response.status, "bad_body", response.body)
        }
        val (code, message) = failureDetail(response)
        return SyncAuthResult.Failure(response.status, code, message)
    }

    /** 从错误响应体解析 `code` / `message`（缺省回退到原始 body）。 */
    private fun failureDetail(response: SyncRawResponse): Pair<String?, String?> {
        val err = runCatching {
            syncJson.decodeFromString(ApiErrorBody.serializer(), response.body)
        }.getOrNull()
        val code = err?.code ?: err?.error
        val message = err?.message ?: response.body.takeIf { it.isNotBlank() }
        return code to message
    }

    // ===== 头构造 =====

    private fun jsonHeaders(): Map<String, String> =
        mapOf("Content-Type" to "application/json")

    private fun authHeaders(accessToken: String): Map<String, String> =
        jsonHeaders() + ("Authorization" to "Bearer $accessToken")

    private fun syncHeaders(deviceId: String, accessToken: String): Map<String, String> =
        authHeaders(accessToken) + ("X-Device-Id" to deviceId)
}
