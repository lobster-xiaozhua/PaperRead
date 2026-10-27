package io.github.lunalight.paperread.transfer

import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.reader.BookTextStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 发送端流程（AC-9.4 / AC-9.6，T-012 从 `DefaultLanTransferManager` 拆出，
 * 行为逐行搬迁）：逐本上传，任一失败即中止；进度 / 错误经 [sendState] 上浮。
 *
 * 与接收端的对称关系：这里只「推」，不碰 socket 服务端；传输细节全在
 * [TransferClient]（HTTP）与 [BookTextStore]（取源文件）。
 *
 * @param sendState 由 `TransferCoordinator` 持有的发送状态流（本类只写不读所有权）
 */
internal class TransferSendController(
    private val bookTextStore: BookTextStore,
    private val transferClient: TransferClient,
    private val scope: CoroutineScope,
    private val sendState: MutableStateFlow<TransferSendState>,
) {

    private var sendJob: Job? = null

    /**
     * 向目标设备发送多本书。
     *
     * 空列表 / 已在发送中直接忽略（幂等）；中断（失败 / 取消）后不再续发——
     * 与「任一失败即中止」的产品口径一致（AC-9.6）。
     */
    fun send(target: DiscoveredDevice, books: List<Book>) {
        if (books.isEmpty()) return
        if (sendState.value.active) return
        sendState.value = TransferSendState(active = true, total = books.size, index = 1)
        sendJob = scope.launch {
            try {
                books.forEachIndexed { i, book ->
                    sendState.value = sendState.value.copy(
                        currentTitle = book.title,
                        index = i + 1,
                        fraction = 0f,
                    )
                    val source = bookTextStore.sourceFile(book)
                    if (!source.isFile) {
                        sendState.value = sendState.value.copy(
                            active = false,
                            error = "《${book.title}》源文件丢失，发送中止",
                        )
                        return@launch
                    }
                    val displayName = "${book.title}.${book.format.extension}"
                    val result = transferClient.upload(
                        host = target.host,
                        port = target.httpPort,
                        file = source,
                        displayName = displayName,
                    ) { fraction ->
                        sendState.value = sendState.value.copy(fraction = fraction)
                    }
                    if (result is UploadResult.Failure) {
                        sendState.value = sendState.value.copy(
                            active = false,
                            error = if (result.unauthorized) {
                                result.message
                            } else {
                                "《${book.title}》发送失败：${result.message}"
                            },
                        )
                        return@launch
                    }
                }
                sendState.value = sendState.value.copy(active = false, fraction = 1f)
            } catch (e: CancellationException) {
                sendState.value = TransferSendState()
                throw e
            } catch (e: Exception) {
                sendState.value = sendState.value.copy(
                    active = false,
                    error = e.message ?: "发送异常",
                )
            }
        }
    }

    /**
     * 用户点「取消」：中止作业并复位状态。
     *
     * 复位放在这里（而不是只靠协程的取消分支）：`cancel()` 可能在作业尚未启动 /
     * 已结束时被调用，那时不会有 CancellationException 分支来复位。
     */
    fun cancel() {
        cancelJob()
        sendState.value = TransferSendState()
    }

    /**
     * 只中止作业、**不改状态**（服务停止时调用）。
     *
     * 状态复位的责任仍留给协程的取消分支——与拆分前 `stopService()` 里
     * 只 `sendJob?.cancel()` 的语义一致，避免两处同时写状态产生竞态。
     */
    fun cancelJob() {
        sendJob?.cancel()
        sendJob = null
    }
}
