package io.github.lunalight.paperread.fakes

import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import io.github.lunalight.paperread.transfer.DiscoveredDevice
import io.github.lunalight.paperread.transfer.LanTransferManager
import io.github.lunalight.paperread.transfer.TransferReceiveState
import io.github.lunalight.paperread.transfer.TransferSendState
import io.github.lunalight.paperread.transfer.TransferServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [LanTransferManager] 的内存 fake：状态流可驱动、调用全部记录。
 *
 * 不触任何 socket——[LanTransferManager] 接口的存在意义就是让 UI 层可如此替代。
 */
class FakeLanTransferManager : LanTransferManager {

    private val _serviceState = MutableStateFlow(TransferServiceState())
    override val serviceState: StateFlow<TransferServiceState> = _serviceState.asStateFlow()

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _sendState = MutableStateFlow(TransferSendState())
    override val sendState: StateFlow<TransferSendState> = _sendState.asStateFlow()

    private val _receiveState = MutableStateFlow(TransferReceiveState())
    override val receiveState: StateFlow<TransferReceiveState> = _receiveState.asStateFlow()

    // ---- 调用记录 ----

    var startServiceCount = 0
        private set
    var stopServiceCount = 0
        private set
    var cancelSendCount = 0
        private set
    var confirmReceivedCount = 0
        private set
    var cancelReceivedCount = 0
        private set
    var consumeImportedTitleCount = 0
        private set

    val sentTargets = mutableListOf<SentBooks>()
    val reparses = mutableListOf<Reparse>()

    data class SentBooks(val target: DiscoveredDevice, val books: List<Book>)
    data class Reparse(val charsetName: String, val strategy: SplitStrategy)

    // ---- 状态驱动 ----

    fun emitService(state: TransferServiceState) {
        _serviceState.value = state
    }

    fun emitDevices(list: List<DiscoveredDevice>) {
        _devices.value = list
    }

    fun emitSend(state: TransferSendState) {
        _sendState.value = state
    }

    fun emitReceive(state: TransferReceiveState) {
        _receiveState.value = state
    }

    // ---- 接口实现 ----

    override fun startService() {
        startServiceCount++
    }

    override fun stopService() {
        stopServiceCount++
    }

    override fun sendBooks(target: DiscoveredDevice, books: List<Book>) {
        sentTargets += SentBooks(target, books)
    }

    override fun cancelSend() {
        cancelSendCount++
    }

    override fun confirmReceived() {
        confirmReceivedCount++
    }

    override fun cancelReceived() {
        cancelReceivedCount++
    }

    override fun reparseReceived(charsetName: String, strategy: SplitStrategy) {
        reparses += Reparse(charsetName, strategy)
    }

    override fun consumeImportedTitle() {
        consumeImportedTitleCount++
    }
}
