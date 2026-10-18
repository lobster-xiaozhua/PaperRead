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
import io.github.lunalight.paperread.transfer.TransferMigrationState
import io.github.lunalight.paperread.transfer.TransferReceiveState
import io.github.lunalight.paperread.transfer.TransferSendState
import io.github.lunalight.paperread.transfer.TransferServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 传书页 UI 状态：管理器四个状态流的透传 + 待发送书目 + 一次性消息。
 *
 * @param booksResolved 书目是否已完成首次解析（未完成时展示加载态，
 *   避免把「尚未解析」误呈现为「未选择书籍」）
 */
data class TransferUiState(
    val service: TransferServiceState = TransferServiceState(),
    val devices: List<DiscoveredDevice> = emptyList(),
    val send: TransferSendState = TransferSendState(),
    val receive: TransferReceiveState = TransferReceiveState(),
    val migration: TransferMigrationState = TransferMigrationState(),
    val books: List<Book> = emptyList(),
    val booksResolved: Boolean = false,
    /** 一次性提示（Snackbar）：发送 / 接收错误、导入成功、迁移结果。 */
    val message: String? = null,
)

/**
 * 传书页 ViewModel（AC-9.1 ~ AC-9.7）。
 *
 * 职责边界：不直连任何 socket，全部经 [LanTransferManager]；
 * 书目按传入 id 从书架实时解析（删除的书籍自动从待发送列表消失）。
 */
class TransferViewModel(
    private val bookIds: List<Long>,
    private val bookRepository: BookRepository,
    private val transferManager: LanTransferManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

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

    /** 选择目标设备开始发送（AC-9.3）。 */
    fun sendTo(device: DiscoveredDevice) {
        transferManager.sendBooks(device, _uiState.value.books)
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

    /** 向该设备发起整库迁移（AC-10.1）。 */
    fun startMigration(device: DiscoveredDevice) {
        transferManager.startMigration(device)
    }

    /** 取消进行中的迁移（发送或接收）。 */
    fun cancelMigration() {
        transferManager.cancelMigration()
    }

    /** 关闭迁移结果清单（AC-10.3）。 */
    fun dismissMigrationReport() {
        transferManager.dismissMigrationReport()
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
