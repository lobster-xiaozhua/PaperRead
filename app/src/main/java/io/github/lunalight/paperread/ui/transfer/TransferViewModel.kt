package io.github.lunalight.paperread.ui.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.lunalight.paperread.di.AppContainer
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import io.github.lunalight.paperread.transfer.DiscoveredDevice
import io.github.lunalight.paperread.transfer.LanTransferManager
import io.github.lunalight.paperread.transfer.MigrationReport
import io.github.lunalight.paperread.transfer.PairingCode
import io.github.lunalight.paperread.transfer.PairingGate
import io.github.lunalight.paperread.transfer.SyncReport
import io.github.lunalight.paperread.transfer.TransferMigrationState
import io.github.lunalight.paperread.transfer.TransferPairingState
import io.github.lunalight.paperread.transfer.TransferReceiveState
import io.github.lunalight.paperread.transfer.TransferSendState
import io.github.lunalight.paperread.transfer.TransferServiceState
import io.github.lunalight.paperread.transfer.TransferSyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 传书页 UI 状态：管理器六个状态流的透传 + 待发送书目 + 配对交互 + 一次性消息。
 *
 * @param booksResolved 书目是否已完成首次解析（未完成时展示加载态，
 *   避免把「尚未解析」误呈现为「未选择书籍」）
 * @param pairing 配对状态（展示端码 / 已配对对端 / 输入侧探测进度与错误）
 * @param pairingTarget 配对对话框的目标设备（非空 = 对话框可见）
 * @param pairingInput 配对输入框内容（仅数字，长度 ≤ [PairingCode.LENGTH]）
 * @param pairedDeviceName 已配对对端的设备名（尽力从设备列表解析）
 */
data class TransferUiState(
    val service: TransferServiceState = TransferServiceState(),
    val devices: List<DiscoveredDevice> = emptyList(),
    val send: TransferSendState = TransferSendState(),
    val receive: TransferReceiveState = TransferReceiveState(),
    val migration: TransferMigrationState = TransferMigrationState(),
    val sync: TransferSyncState = TransferSyncState(),
    val books: List<Book> = emptyList(),
    val booksResolved: Boolean = false,
    /** 一次性提示（Snackbar）：发送 / 接收错误、导入成功、迁移 / 同步结果。 */
    val message: String? = null,
    val pairing: TransferPairingState = TransferPairingState(),
    val pairingTarget: DiscoveredDevice? = null,
    val pairingInput: String = "",
    val pairedDeviceName: String? = null,
)

/**
 * 传书页 ViewModel（AC-9.1 ~ AC-9.7 + R-01 / G1 配对流程）。
 *
 * 职责边界：不直连任何 socket，全部经 [LanTransferManager]；
 * 书目按传入 id 从书架实时解析（删除的书籍自动从待发送列表消失）；
 * 发送 / 迁移 / 同步三类动作在与目标设备配对前先弹配对对话框，
 * 配对成功自动继续挂起的动作（无需用户二次点击）。
 */
class TransferViewModel(
    private val bookIds: List<Long>,
    private val bookRepository: BookRepository,
    private val transferManager: LanTransferManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    /** 配对成功后要继续执行的动作（发送 / 迁移 / 同步）。 */
    private var pendingAfterPairing: (() -> Unit)? = null

    init {
        // 进入页面即启动局域网服务（AC-9.1）
        transferManager.startService()

        viewModelScope.launch {
            transferManager.serviceState.collect { service ->
                _uiState.update { it.copy(service = service) }
            }
        }
        viewModelScope.launch {
            transferManager.devices.collect { devices ->
                _uiState.update { it.copy(devices = devices) }
            }
        }
        viewModelScope.launch {
            transferManager.sendState.collect { send ->
                _uiState.update { state ->
                    // 错误只提示一次：随发射转一次性消息，不保留在卡片里重复曝光
                    state.copy(
                        send = send,
                        message = send.error ?: state.message,
                    )
                }
            }
        }
        viewModelScope.launch {
            transferManager.receiveState.collect { receive ->
                _uiState.update { state ->
                    state.copy(
                        receive = receive,
                        message = receive.error ?: receive.importedTitle?.let { "已导入《$it》" }
                            ?: state.message,
                    )
                }
                if (receive.importedTitle != null) {
                    transferManager.consumeImportedTitle()
                }
            }
        }
        viewModelScope.launch {
            transferManager.migration.collect { migration ->
                _uiState.update { state ->
                    // 错误一次性提示；接收端完成（无错误、有报告）时同样给一次性汇总
                    val notice = migration.error ?: migration.report?.let { report ->
                        if (migration.active) null else migrationSummary(report)
                    }
                    state.copy(
                        migration = migration,
                        message = notice ?: state.message,
                    )
                }
            }
        }
        viewModelScope.launch {
            transferManager.sync.collect { sync ->
                _uiState.update { state ->
                    // 错误一次性提示；同步完成（无错误、有报告）时给一次性汇总
                    val notice = sync.error ?: sync.report?.let { report ->
                        if (sync.active) null else syncSummary(report)
                    }
                    state.copy(sync = sync, message = notice ?: state.message)
                }
            }
        }
        viewModelScope.launch {
            transferManager.pairing.collect { pairing ->
                _uiState.update { state ->
                    state.copy(
                        pairing = pairing,
                        pairedDeviceName = pairing.pairedDeviceId?.let { id ->
                            state.devices.firstOrNull { it.deviceId == id }?.deviceName
                        },
                    )
                }
                // 配对落定且正是对话框目标设备：关闭对话框并继续挂起动作
                val target = _uiState.value.pairingTarget
                if (pairing.status == PairingGate.Status.PAIRED &&
                    pairing.error == null &&
                    target != null &&
                    pairing.pairedDeviceId == target.deviceId
                ) {
                    _uiState.update { it.copy(pairingTarget = null, pairingInput = "") }
                    pendingAfterPairing?.invoke()
                    pendingAfterPairing = null
                }
            }
        }
        viewModelScope.launch {
            bookRepository.observeBooks().collect { all ->
                val byId = all.associateBy { it.id }
                _uiState.update { state ->
                    state.copy(books = bookIds.mapNotNull { byId[it] }, booksResolved = true)
                }
            }
        }
    }

    /** 离开页面：停止服务并释放端口（测试可直接调用以验证生命周期）。 */
    public override fun onCleared() {
        // 离开页面停止服务：释放发现端口与 HTTP 监听（AC-9.7：仅按需监听）
        transferManager.stopService()
    }

    // ---- 配对（R-01 / G1） ----

    /** 本机是否已与指定设备配对（PAIRED 且对端一致）。 */
    fun isPairedWith(device: DiscoveredDevice): Boolean =
        _uiState.value.pairing.status == PairingGate.Status.PAIRED &&
            _uiState.value.pairing.pairedDeviceId == device.deviceId

    /**
     * 需要配对才能执行的动作统一入口：已配对直接执行，
     * 否则打开配对对话框并把动作挂起，配对成功后自动继续。
     */
    private fun requirePairingThen(device: DiscoveredDevice, action: () -> Unit) {
        if (isPairedWith(device)) {
            action()
            return
        }
        pendingAfterPairing = action
        _uiState.update {
            it.copy(
                pairingTarget = device,
                pairingInput = "",
                pairing = it.pairing.copy(error = null),
            )
        }
    }

    /** 展示端：生成配对码（对端据此在传书页输入并完成配对）。 */
    fun beginPairing() {
        transferManager.beginPairing()
    }

    /** 取消配对 / 中止等待（展示端与输入侧通用）。 */
    fun cancelPairing() {
        transferManager.cancelPairing()
    }

    /** 配对输入框更新：仅保留数字并限长 [PairingCode.LENGTH]。 */
    fun updatePairingInput(text: String) {
        _uiState.update {
            it.copy(pairingInput = text.filter { ch -> ch.isDigit() }.take(PairingCode.LENGTH))
        }
    }

    /** 确认配对：把输入框内容提交给管理器（探测对端）。 */
    fun confirmPairing() {
        val target = _uiState.value.pairingTarget ?: return
        transferManager.pairWith(target, _uiState.value.pairingInput)
    }

    /** 关闭配对对话框（放弃本次配对与挂起动作）。 */
    fun dismissPairingDialog() {
        pendingAfterPairing = null
        _uiState.update { it.copy(pairingTarget = null, pairingInput = "") }
    }

    // ---- 发送 / 迁移 / 同步（配对门控） ----

    /** 选择目标设备开始发送（AC-9.3；未配对先弹配对对话框）。 */
    fun sendTo(device: DiscoveredDevice) {
        requirePairingThen(device) {
            transferManager.sendBooks(device, _uiState.value.books)
        }
    }

    /** 取消进行中的发送（AC-9.4）。 */
    fun cancelSend() {
        transferManager.cancelSend()
    }

    /** 启动失败（如未接入 WiFi）后重试。 */
    fun retryService() {
        transferManager.startService()
    }

    /** 确认导入收到的书（AC-9.5）。 */
    fun confirmReceived() {
        transferManager.confirmReceived()
    }

    /** 取消导入收到的书（AC-9.6：清理半成品）。 */
    fun cancelReceived() {
        transferManager.cancelReceived()
    }

    /** 收到的书切换编码 / 切分策略后重新解析（AC-9.5）。 */
    fun reparseReceived(charsetName: String, strategy: SplitStrategy) {
        transferManager.reparseReceived(charsetName, strategy)
    }

    /** 向该设备发起整库迁移（AC-10.1；未配对先弹配对对话框）。 */
    fun startMigration(device: DiscoveredDevice) {
        requirePairingThen(device) { transferManager.startMigration(device) }
    }

    /** 取消进行中的迁移（发送或接收）。 */
    fun cancelMigration() {
        transferManager.cancelMigration()
    }

    /** 关闭迁移结果清单（AC-10.3）。 */
    fun dismissMigrationReport() {
        transferManager.dismissMigrationReport()
    }

    /** 与对端交换阅读进度（AC-11.1；未配对先弹配对对话框）。 */
    fun startSync(device: DiscoveredDevice) {
        requirePairingThen(device) { transferManager.startSync(device) }
    }

    /** 关闭同步结果卡片。 */
    fun dismissSyncReport() {
        transferManager.dismissSyncReport()
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    companion object {
        /** 迁移结果一次性汇总（AC-10.3：成功 / 失败清单）。 */
        fun migrationSummary(report: MigrationReport): String = buildString {
            append("迁移完成：新导入 ${report.imported} 本")
            if (report.duplicates > 0) append("，已存在 ${report.duplicates} 本")
            if (report.progressWritten > 0) append("，回写进度 ${report.progressWritten} 本")
            if (report.failed.isNotEmpty()) append("，失败 ${report.failed.size} 本")
        }

        /**
         * 同步结果一次性汇总（AC-11.2：告知被覆盖条目数；AC-11.3：只增不删）。
         */
        fun syncSummary(report: SyncReport): String = buildString {
            append("同步完成：")
            append("覆盖本地进度 ${report.localOverwritten} 本")
            append("，新增书签 ${report.localBookmarksAdded} 条")
            if (report.pushBack.isNotEmpty()) append("，回推 ${report.pushBack.size} 本")
            if (report.remoteOnlyBooks > 0) append("，对端另有 ${report.remoteOnlyBooks} 本本地没有")
        }

        fun factory(bookIds: List<Long>, container: AppContainer): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    TransferViewModel(
                        bookIds = bookIds,
                        bookRepository = container.bookRepository,
                        transferManager = container.transferManager,
                    )
                }
            }
    }
}
