package io.github.lunalight.paperread.transfer

import android.content.Context
import io.github.lunalight.paperread.data.repository.BookImporter
import io.github.lunalight.paperread.data.repository.PrepareResult
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 接收端流程（AC-9.5，T-012 从 `DefaultLanTransferManager` 拆出，行为逐行搬迁）：
 *
 * 落盘进度 → 文件改名到位 → **字节已到手即应答**，解析导入转后台
 * （R-10 / G10：大书 prepare 不得占着连接线程）→ 待确认 / 失败经
 * [receiveState] 上浮 → 确认入库走与本地导入完全相同的 [BookImporter] 路径。
 *
 * 单槽语义：同时只保留一个待确认导入——新的落盘进度会作废旧 pending 的后台
 * 解析与临时文件（进度与 pending 共用一条状态流，天然互斥）。
 *
 * @param receiveState 由 `TransferCoordinator` 持有的接收状态流
 */
internal class TransferReceiveController(
    private val context: Context,
    private val bookImporter: BookImporter,
    private val scope: CoroutineScope,
    private val receiveState: MutableStateFlow<TransferReceiveState>,
) {

    /** 收到的临时文件（确认 / 取消后删除）。 */
    @Volatile
    private var receivedTempFile: File? = null

    /**
     * 收到的书的解析作业（R-10 / G10：onUpload 落盘即应答，prepare 转后台）。
     *
     * 与迁移的 bundle 处理同款背景化：大书（数十 MB TXT）的编码探测 / 切分
     * 不再占用 HTTP 连接线程，对端上传完立即拿到 200。
     */
    private var receiveJob: Job? = null

    /**
     * 服务端 spill 阶段的进度（`onReceiveProgress` 的 UPLOAD 分支）。
     *
     * 新上传的落盘进度即顶替旧待确认（见类注释的「单槽语义」）：作废旧 pending
     * 的后台解析与导入元数据，避免「上一本没确认就被下一本顶掉」时的残留。
     */
    fun onProgress(fileName: String, fraction: Float) {
        val stale = receiveState.value.pending
        if (stale != null) {
            bookImporter.cancel(stale)
            receiveJob?.cancel()
            deleteReceivedTemp()
        }
        receiveState.value = TransferReceiveState(
            receiving = true,
            fileName = fileName,
            fraction = fraction,
        )
    }

    /** 请求体接收中止（鉴权后断链 / 摘要不符：服务端已清理 spill，UI 复位）。 */
    fun onAborted(reason: String) {
        receiveState.value = TransferReceiveState(error = reason)
    }

    /**
     * 处理 `POST /upload`：改名到位 + 起后台解析，立即返回成功。
     *
     * @return null（成功）；文件名不合法时返回原因（对端收到 500）
     */
    suspend fun onUpload(fileName: String, bodyFile: File): String? {
        val safeName = sanitizeFileName(fileName) ?: run {
            bodyFile.delete()
            return "文件名不合法"
        }
        val tempDir = File(context.cacheDir, "transfer").apply { mkdirs() }
        val target = File(tempDir, safeName)
        // 已鉴权落盘的文件直接改名到位（同分区原子 rename，零拷贝）；
        // rename 失败（极少见的跨分区场景）回落拷贝后删除原件
        if (!bodyFile.renameTo(target)) {
            bodyFile.copyTo(target, overwrite = true)
            bodyFile.delete()
        }

        // 单槽语义（与旧「顺序处理连接」一致）：上一本尚未确认就被新上传
        // 顶替时，取消其后台解析并清理临时文件——UI 同时只保留一个
        // 待确认导入（pending 的作废已在 onProgress 完成）
        receiveJob?.cancel()
        deleteReceivedTemp()
        receivedTempFile = target

        // 字节已完整落盘（服务端已鉴权 + 摘要核对）：解析导入转后台，
        // HTTP 尽早应答（R-10 / G10：大书 prepare 不得占着连接线程）
        receiveJob = scope.launch {
            prepareReceived(target)
        }
        return null
    }

    /**
     * 落盘后的解析导入（原 onUpload 内联逻辑，R-10 / G10 后台化）。
     *
     * 与 HTTP 应答解耦：prepare 的各结局（成功待确认 / 重复 / 不支持 / 失败）
     * 全部经 [receiveState] 上浮 UI，对端收到的始终是「字节已送达」的 200。
     */
    private suspend fun prepareReceived(target: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target,
            )
            val result = bookImporter.prepare(uri)
            when (result) {
                is PrepareResult.Success -> {
                    receiveState.value = TransferReceiveState(pending = result.pending)
                }

                is PrepareResult.Duplicate -> {
                    // 内容已存在：丢弃临时文件并提示，不弹导入确认
                    deleteReceivedTemp()
                    receiveState.value = TransferReceiveState(
                        error = "《${result.existing.title}》已存在于书架，已跳过",
                    )
                }

                is PrepareResult.Unsupported -> {
                    deleteReceivedTemp()
                    receiveState.value = TransferReceiveState(error = result.error.userMessage)
                }

                is PrepareResult.Failure -> {
                    deleteReceivedTemp()
                    receiveState.value = TransferReceiveState(error = result.error.userMessage)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 解析流水线崩溃（磁盘异常等）：改造前由 HTTP 层的 try/catch 兜成
            // 500，后台化后改经 receiveState 上浮（对端早已收到 200——字节
            // 确已完整送达，失败的是本机解析这一环）
            receiveState.value = TransferReceiveState(
                error = "解析失败：${e.message ?: "未知错误"}",
            )
        }
    }

    /** 确认导入收到的书（走与本地导入完全相同的入库路径，AC-9.5）。 */
    fun confirmReceived() {
        val pending = receiveState.value.pending ?: return
        scope.launch {
            try {
                bookImporter.confirm(pending)
                deleteReceivedTemp()
                receiveState.value = TransferReceiveState(importedTitle = pending.title)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 入库失败（磁盘异常等）：保留 pending 与临时文件供重试，
                // 错误上浮 UI（AC-9.6：提示失败原因）而非静默吞掉
                receiveState.value = receiveState.value.copy(
                    error = "导入失败：${e.message ?: "未知错误"}",
                )
            }
        }
    }

    /** 取消接收：清理半成品目录、后台解析与临时文件，状态复位。 */
    fun cancelReceived() {
        val pending = receiveState.value.pending
        if (pending != null) {
            bookImporter.cancel(pending)
        }
        // 后台 prepare 可能仍在跑（落盘即应答后）：一并取消，
        // 避免其完成后把已取消的状态改写回 pending
        cancelJob()
        deleteReceivedTemp()
        receiveState.value = TransferReceiveState()
    }

    /** 重新解析收到的书（切换编码 / 切分策略，与本地导入同能力，AC-9.5）。 */
    fun reparseReceived(charsetName: String, strategy: SplitStrategy) {
        val pending = receiveState.value.pending ?: return
        scope.launch {
            val reparsed = bookImporter.reparse(pending, charsetName, strategy)
            receiveState.value = if (reparsed == null) {
                receiveState.value.copy(error = "重新解析失败，请重试")
            } else {
                receiveState.value.copy(pending = reparsed)
            }
        }
    }

    /** 消费「已导入」一次性提示（Snackbar 展示后调用，避免重组重复弹出）。 */
    fun consumeImportedTitle() {
        if (receiveState.value.importedTitle != null) {
            receiveState.value = receiveState.value.copy(importedTitle = null)
        }
    }

    /**
     * 只中止后台解析、不改状态（服务停止时调用）：与拆分前 `stopService()`
     * 里只 `receiveJob?.cancel()` 的语义一致。
     */
    fun cancelJob() {
        receiveJob?.cancel()
        receiveJob = null
    }

    private fun deleteReceivedTemp() {
        receivedTempFile?.delete()
        receivedTempFile = null
    }
}
