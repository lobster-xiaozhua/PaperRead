package io.github.lunalight.paperread.sync

import android.content.Context
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.backup.BackupExporter
import io.github.lunalight.paperread.backup.BackupImporter
import io.github.lunalight.paperread.data.settings.SyncSettingsStore
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 同步动作阶段（设置页状态展示用）。 */
enum class SyncPhase {
    IDLE,
    REGISTERING,
    LOGGING_IN,
    SYNCING,
}

/** 同步 UI 状态（设置页同步区块的唯一真相）。 */
data class SyncUiState(
    val busy: Boolean = false,
    val phase: SyncPhase = SyncPhase.IDLE,
    val syncEnabled: Boolean = false,
    val accountEmail: String? = null,
    val loggedIn: Boolean = false,
    val lastSyncAt: Long? = null,
    /** 上次同步结果文案（成功 / 失败原因摘要），仅展示用。 */
    val lastSyncResult: String? = null,
    /** 进行中状态文案（如「正在上传快照…」）。 */
    val detail: String = "",
    val message: String? = null,
    val error: String? = null,
)

/**
 * 云同步编排（US-22，Cloudflare 后端接入）。
 *
 * 职责边界（对照 [io.github.lunalight.paperread.backup.BackupCoordinator]）：
 * - **零知识快照**：push 复用 [BackupExporter.export]（`includeSources=false` + 账户密码
 *   加密）生成「仅状态备份包」→ Base64 → `PUT /api/sync/snapshot`；pull 取回密文 →
 *   解密 → [BackupImporter.restore]（`progressOnly=true`）合并进本地。服务端只存密文，
 *   密码不离开设备（见 `cloudflare/README.md`）。
 * - **单密码**：账户密码同时作为备份加密口令（MVP 简化）。登录后口令留在内存
 *   （[syncPassword]）供 push/pull 加密，logout 时 zero-fill；不进 StateFlow、不打日志。
 * - **令牌**：access / refresh 经 [SyncTokenStore]（Android Keystore）保管；401 自动
 *   refresh 重试一次。
 * - **状态流转**：busy / phase / 账户 / 最近同步时间 / 错误（文案走 strings.xml）。
 *
 * 重活委托 [BackupExporter] / [BackupImporter]（纯逻辑、可 JVM 单测）；本类只做
 * 网络、文件搬运与 Compose 状态之间的适配。生命周期随 Application（由 [AppContainer]
 * 持有），设置页退出时正在跑的同步继续完成。
 */
class SyncCoordinator(
    private val context: Context,
    private val api: SyncApi,
    private val tokenStore: SyncTokenStore,
    private val syncSettings: SyncSettingsStore,
    private val exporter: BackupExporter,
    private val importer: BackupImporter,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * 同步口令（= 账户密码）副本：登录后留存内存，用于 push/pull 加密，logout 时
     * zero-fill。不进 StateFlow、不打日志（零知识约束）。持有的是调用方传入 CharArray
     * 的**副本**，调用方的数组在 register/login 入口即被 zero-fill。
     */
    @Volatile private var syncPassword: CharArray? = null

    init {
        scope.launch {
            syncSettings.syncEnabled.collect { enabled ->
                _state.update { it.copy(syncEnabled = enabled) }
            }
        }
        scope.launch {
            syncSettings.accountEmail.collect { email ->
                _state.update { it.copy(accountEmail = email, loggedIn = email != null) }
            }
        }
    }

    // ---- 账号 ----

    /** 注册并登录（成功后留存令牌 + 邮箱 + 口令）。 */
    fun register(email: String, password: CharArray) {
        if (_state.value.busy) return
        val passwordCopy = password.copyOf()
        password.fill('\u0000') // 调用方数组立即清零
        job = scope.launch {
            _state.update {
                it.copy(busy = true, phase = SyncPhase.REGISTERING, error = null, message = null)
            }
            when (val result = api.register(email, String(passwordCopy))) {
                is SyncAuthResult.Success -> onAuthenticated(result, email, passwordCopy)
                is SyncAuthResult.Failure -> fail(passwordCopy) {
                    context.getString(R.string.sync_error_register, detailOf(result.code, result.message))
                }
                is SyncAuthResult.Error -> fail(passwordCopy) {
                    context.getString(R.string.sync_error_network, result.exception.message ?: "")
                }
            }
        }
    }

    /** 登录（成功后留存令牌 + 邮箱 + 口令）。 */
    fun login(email: String, password: CharArray) {
        if (_state.value.busy) return
        val passwordCopy = password.copyOf()
        password.fill('\u0000')
        job = scope.launch {
            _state.update {
                it.copy(busy = true, phase = SyncPhase.LOGGING_IN, error = null, message = null)
            }
            when (val result = api.login(email, String(passwordCopy))) {
                is SyncAuthResult.Success -> onAuthenticated(result, email, passwordCopy)
                is SyncAuthResult.Failure -> fail(passwordCopy) {
                    context.getString(R.string.sync_error_login, detailOf(result.code, result.message))
                }
                is SyncAuthResult.Error -> fail(passwordCopy) {
                    context.getString(R.string.sync_error_network, result.exception.message ?: "")
                }
            }
        }
    }

    /** 登出：best-effort 吊销刷新令牌 + 清本地令牌 / 口令 / 邮箱。 */
    fun logout() {
        if (_state.value.busy) return
        job = scope.launch {
            _state.update { it.copy(busy = true, phase = SyncPhase.IDLE, error = null, message = null) }
            val refresh = tokenStore.getRefresh()
            if (refresh != null) {
                // 吊销失败不阻塞登出：本地清理才是关键
                runCatching { api.refresh(refresh) }
            }
            tokenStore.clear()
            syncSettings.setAccountEmail(null)
            syncPassword?.fill('\u0000')
            syncPassword = null
            _state.update {
                it.copy(
                    busy = false,
                    phase = SyncPhase.IDLE,
                    accountEmail = null,
                    loggedIn = false,
                    lastSyncResult = null,
                    message = context.getString(R.string.sync_logged_out),
                )
            }
        }
    }

    /** 开启 / 关闭云同步开关（仅持久化开关；登录态由账号流程决定）。 */
    fun setSyncEnabled(enabled: Boolean) {
        job = scope.launch {
            syncSettings.setSyncEnabled(enabled)
            _state.update { it.copy(syncEnabled = enabled) }
        }
    }

    // ---- 同步 ----

    /** 立即同步：先 pull（合并远端）再 push（上传合并结果）。 */
    fun syncNow() {
        if (_state.value.busy) return
        if (!isReady()) return
        job = scope.launch {
            _state.update {
                it.copy(busy = true, phase = SyncPhase.SYNCING, error = null, message = null, detail = "")
            }
            val pullOk = runPull()
            val pushOk = runPush()
            val now = System.currentTimeMillis()
            _state.update {
                it.copy(
                    busy = false,
                    phase = SyncPhase.IDLE,
                    lastSyncAt = now,
                    detail = "",
                    lastSyncResult = when {
                        pullOk && pushOk -> context.getString(R.string.sync_result_ok)
                        else -> context.getString(R.string.sync_result_partial)
                    },
                    message = if (pullOk && pushOk) {
                        context.getString(R.string.sync_done)
                    } else {
                        null
                    },
                )
            }
        }
    }

    // ---- 内部：认证与清理 ----

    private suspend fun onAuthenticated(
        result: SyncAuthResult.Success,
        email: String,
        passwordCopy: CharArray,
    ) {
        tokenStore.save(result.accessToken, result.refreshToken, result.expiresIn)
        syncSettings.setAccountEmail(email)
        syncPassword = passwordCopy // 留存内存供 push/pull 加密
        _state.update {
            it.copy(
                busy = false,
                phase = SyncPhase.IDLE,
                accountEmail = email,
                loggedIn = true,
                error = null,
                message = context.getString(R.string.sync_logged_in_as, email),
            )
        }
    }

    private suspend fun fail(passwordCopy: CharArray, message: () -> String) {
        passwordCopy.fill('\u0000') // 认证失败，口令不留内存
        _state.update {
            it.copy(busy = false, phase = SyncPhase.IDLE, error = message())
        }
    }

    // ---- 内部：push / pull ----

    private fun isReady(): Boolean {
        val password = syncPassword
        if (password == null) {
            _state.update { it.copy(error = context.getString(R.string.sync_error_not_logged_in)) }
            return false
        }
        return true
    }

    private suspend fun runPush(): Boolean {
        val password = syncPassword ?: return false
        val access = tokenStore.getAccess() ?: run {
            _state.update { it.copy(error = context.getString(R.string.sync_error_not_logged_in)) }
            return false
        }
        val deviceId = syncSettings.ensureDeviceId()
        val temp = File(context.cacheDir, "sync/push-${System.currentTimeMillis()}.zip")
        temp.parentFile?.mkdirs()
        _state.update { it.copy(detail = context.getString(R.string.sync_phase_uploading)) }
        return try {
            exporter.export(
                target = temp,
                password = password,
                includeSources = false,
            )
            val bytes = temp.readBytes()
            val ciphertext = Base64.getEncoder().encodeToString(bytes)
            val lastModified = System.currentTimeMillis()
            var result = api.putSnapshot(deviceId, access, ciphertext, lastModified)
            if (result is SyncUnitResult.Failure && result.httpStatus == 401) {
                if (tryRefresh()) {
                    val newAccess = tokenStore.getAccess()
                    if (newAccess != null) {
                        result = api.putSnapshot(deviceId, newAccess, ciphertext, lastModified)
                    }
                }
            }
            when (result) {
                is SyncUnitResult.Success -> true
                is SyncUnitResult.Failure -> {
                    _state.update {
                        it.copy(error = context.getString(R.string.sync_error_upload, detailOf(result.code, result.message)))
                    }
                    false
                }
                is SyncUnitResult.Error -> {
                    _state.update {
                        it.copy(error = context.getString(R.string.sync_error_network, result.exception.message ?: ""))
                    }
                    false
                }
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = context.getString(R.string.sync_error_export, e.message ?: "")) }
            false
        } finally {
            temp.delete()
        }
    }

    private suspend fun runPull(): Boolean {
        val password = syncPassword ?: return false
        val access = tokenStore.getAccess() ?: run {
            _state.update { it.copy(error = context.getString(R.string.sync_error_not_logged_in)) }
            return false
        }
        val selfDeviceId = syncSettings.ensureDeviceId()
        _state.update { it.copy(detail = context.getString(R.string.sync_phase_pulling)) }

        var devicesResult = api.listDevices(selfDeviceId, access)
        if (devicesResult is SyncDevicesResult.Failure && devicesResult.httpStatus == 401) {
            if (tryRefresh()) {
                val newAccess = tokenStore.getAccess()
                if (newAccess != null) devicesResult = api.listDevices(selfDeviceId, newAccess)
            }
        }
        val others = when (devicesResult) {
            is SyncDevicesResult.Success -> devicesResult.devices.filter { it.deviceId != selfDeviceId }
            is SyncDevicesResult.Failure -> {
                _state.update {
                    it.copy(error = context.getString(R.string.sync_error_pull, detailOf(devicesResult.code, devicesResult.message)))
                }
                return false
            }
            is SyncDevicesResult.Error -> {
                _state.update {
                    it.copy(error = context.getString(R.string.sync_error_network, devicesResult.exception.message ?: ""))
                }
                return false
            }
        }

        var anyFailed = false
        for (device in others) {
            var snapshot = api.getSnapshot(device.deviceId, access)
            if (snapshot is SyncSnapshotResult.Failure && snapshot.httpStatus == 401) {
                if (tryRefresh()) {
                    val newAccess = tokenStore.getAccess()
                    if (newAccess != null) snapshot = api.getSnapshot(device.deviceId, newAccess)
                }
            }
            when (snapshot) {
                is SyncSnapshotResult.Success -> {
                    val cipher = snapshot.snapshot?.ciphertext
                    if (cipher != null) {
                        if (!restoreCiphertext(cipher, password, device.deviceId)) anyFailed = true
                    }
                }
                is SyncSnapshotResult.Failure -> anyFailed = true
                is SyncSnapshotResult.Error -> anyFailed = true
            }
        }
        if (anyFailed) {
            _state.update {
                it.copy(error = context.getString(R.string.sync_error_pull_partial))
            }
        }
        return !anyFailed || others.isEmpty()
    }

    /** 把单台设备的密文解码 → 临时文件 → 合并进本地。返回是否成功。 */
    private suspend fun restoreCiphertext(ciphertext: String, password: CharArray, fromDevice: String): Boolean {
        val temp = File(context.cacheDir, "sync/pull-${fromDevice}-${System.currentTimeMillis()}.zip")
        temp.parentFile?.mkdirs()
        return try {
            val bytes = Base64.getDecoder().decode(ciphertext)
            temp.writeBytes(bytes)
            val result = importer.restore(
                file = temp,
                password = password,
                progressOnly = true,
            )
            when (result) {
                is io.github.lunalight.paperread.backup.BackupRestoreResult.Done -> {
                    // 合并完成但仍有单本失败（如本机无该书，未同步进度）：
                    // 标记部分失败，让 UI 展示「部分同步失败」而非静默忽略。
                    if (result.report.failed.isNotEmpty()) {
                        _state.update { it.copy(error = context.getString(R.string.sync_error_pull_partial)) }
                        return false
                    }
                    true
                }
                is io.github.lunalight.paperread.backup.BackupRestoreResult.Rejected,
                is io.github.lunalight.paperread.backup.BackupRestoreResult.PasswordRequired,
                is io.github.lunalight.paperread.backup.BackupRestoreResult.WrongPassword -> {
                    _state.update { it.copy(error = context.getString(R.string.sync_error_restore, fromDevice)) }
                    false
                }
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = context.getString(R.string.sync_error_restore, e.message ?: fromDevice)) }
            false
        } finally {
            temp.delete()
        }
    }

    /** 刷新令牌并重试一次；成功返回 true。 */
    private suspend fun tryRefresh(): Boolean {
        val refresh = tokenStore.getRefresh() ?: return false
        return when (val result = api.refresh(refresh)) {
            is SyncAuthResult.Success -> {
                tokenStore.save(result.accessToken, result.refreshToken, result.expiresIn)
                true
            }
            else -> false
        }
    }

    // ---- 工具 ----

    private fun detailOf(code: String?, message: String?): String =
        message?.takeIf { it.isNotBlank() } ?: code ?: ""

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }
}
