package io.github.lunalight.paperread.sync

import kotlinx.serialization.Serializable

/**
 * 纸阅云同步后端契约（Cloudflare Workers + D1 + KV）。
 *
 * 接口表见仓库 `cloudflare/README.md`：账号用邮箱 + 密码，同步快照为零知识
 * 加密包（客户端用账户密码加密，服务端只存密文）。本接口层**不持有任何网络库**
 * （项目零网络依赖），具体实现 [HttpSyncApi] 仅用 `java.net.HttpURLConnection`。
 *
 * 所有方法返回**密封结果**：成功携带数据 / 失败含 HTTP 状态码与错误码字符串 /
 * 网络异常。实现层**不抛受检异常**，调用方靠结果类型分支即可。
 */

/** 同步后端基址。生产部署后替换此常量；本地联调用 `wrangler dev`（默认 127.0.0.1:8787）。 */
const val SYNC_API_BASE_URL: String = "https://paperread-sync.lunalight.workers.dev"

/** 认证成功响应体（`/api/auth`）。 */
@Serializable
internal data class AuthSuccessBody(
    val access_token: String = "",
    val refresh_token: String = "",
    val expires_in: Long = 0,
)

/** 注册 / 登录请求体。 */
@Serializable
internal data class AuthRequest(
    val email: String,
    val password: String,
)

/** 刷新请求体。 */
@Serializable
internal data class RefreshRequest(
    val refresh_token: String,
)

/** 登出（吊销刷新令牌）请求体。 */
@Serializable
internal data class LogoutRequest(
    val refresh_token: String,
)

/** 上传快照请求体。 */
@Serializable
internal data class PutSnapshotRequest(
    val ciphertext: String,
    val lastModified: Long,
)

/** 快照响应体（`GET /api/sync/snapshot`）。 */
@Serializable
internal data class SnapshotBody(
    val ciphertext: String? = null,
    val lastModified: Long? = null,
)

/** 设备列表条目（`GET /api/sync/devices`）。 */
@Serializable
internal data class DeviceEntry(
    val deviceId: String = "",
    val lastModified: Long? = null,
)

/** 设备列表响应体。 */
@Serializable
internal data class DevicesBody(
    val devices: List<DeviceEntry> = emptyList(),
)

/** 当前用户响应体（`GET /api/auth/me`）。 */
@Serializable
internal data class UserBody(
    val email: String = "",
    val id: String? = null,
)

/** 后端统一错误响应体。 */
@Serializable
internal data class ApiErrorBody(
    val error: String? = null,
    val message: String? = null,
    val code: String? = null,
)

// ---- 结果类型 ----

/** 注册 / 登录 / 刷新的统一结果。 */
sealed interface SyncAuthResult {

    /** 成功：拿到新的访问 / 刷新令牌与有效期（秒）。 */
    data class Success(
        val accessToken: String,
        val refreshToken: String,
        val expiresIn: Long,
    ) : SyncAuthResult

    /** 业务失败：HTTP 状态码（null = 非 HTTP 层）+ 错误码 + 服务端消息。 */
    data class Failure(
        val httpStatus: Int?,
        val code: String?,
        val message: String?,
    ) : SyncAuthResult

    /** 网络 / IO 异常（不应抛，封装在此）。 */
    data class Error(val exception: Throwable) : SyncAuthResult
}

/** 当前用户查询结果。 */
sealed interface SyncUserResult {
    data class Success(val email: String, val id: String?) : SyncUserResult
    data class Failure(val httpStatus: Int?, val code: String?, val message: String?) : SyncUserResult
    data class Error(val exception: Throwable) : SyncUserResult
}

/** 无返回体的操作结果（上传 / 删除快照）。 */
sealed interface SyncUnitResult {
    data object Success : SyncUnitResult
    data class Failure(val httpStatus: Int?, val code: String?, val message: String?) : SyncUnitResult
    data class Error(val exception: Throwable) : SyncUnitResult
}

/** 单设备快照（ciphertext 为 null 表示云端无该设备快照）。 */
data class SyncSnapshot(
    val ciphertext: String?,
    val lastModified: Long?,
)

/** 取快照结果（404 = 该设备无快照，归为 Success(null)）。 */
sealed interface SyncSnapshotResult {
    data class Success(val snapshot: SyncSnapshot?) : SyncSnapshotResult
    data class Failure(val httpStatus: Int?, val code: String?, val message: String?) : SyncSnapshotResult
    data class Error(val exception: Throwable) : SyncSnapshotResult
}

/** 设备列表中的一台设备。 */
data class SyncDevice(
    val deviceId: String,
    val lastModified: Long?,
)

/** 设备列表结果。 */
sealed interface SyncDevicesResult {
    data class Success(val devices: List<SyncDevice>) : SyncDevicesResult
    data class Failure(val httpStatus: Int?, val code: String?, val message: String?) : SyncDevicesResult
    data class Error(val exception: Throwable) : SyncDevicesResult
}

/** 同步后端客户端（便于测试打 fake）。 */
interface SyncApi {

    /** `POST /api/auth/register` → 用户 + 令牌。 */
    suspend fun register(email: String, password: String): SyncAuthResult

    /** `POST /api/auth/login` → 令牌。 */
    suspend fun login(email: String, password: String): SyncAuthResult

    /** `POST /api/auth/refresh` → 新令牌（轮换旧令牌）。 */
    suspend fun refresh(refreshToken: String): SyncAuthResult

    /** `POST /api/auth/logout` → 吊销刷新令牌（best-effort）。 */
    suspend fun logout(refreshToken: String): SyncUnitResult

    /** `GET /api/auth/me` → 当前用户（需 accessToken）。 */
    suspend fun me(accessToken: String): SyncUserResult

    /** `PUT /api/sync/snapshot` → 上传本设备加密快照（需 accessToken + deviceId）。 */
    suspend fun putSnapshot(
        deviceId: String,
        accessToken: String,
        ciphertext: String,
        lastModified: Long,
    ): SyncUnitResult

    /** `GET /api/sync/snapshot` → 取某设备快照（需 accessToken + deviceId；404 = 无快照）。 */
    suspend fun getSnapshot(deviceId: String, accessToken: String): SyncSnapshotResult

    /** `GET /api/sync/devices` → 列出该用户全部设备（需 accessToken + 自身 deviceId）。 */
    suspend fun listDevices(deviceId: String, accessToken: String): SyncDevicesResult

    /** `DELETE /api/sync/snapshot` → 删本设备快照（需 accessToken + deviceId）。 */
    suspend fun deleteSnapshot(deviceId: String, accessToken: String): SyncUnitResult
}
