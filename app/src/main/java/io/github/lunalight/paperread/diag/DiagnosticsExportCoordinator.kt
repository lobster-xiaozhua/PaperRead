package io.github.lunalight.paperread.diag

import android.content.Context
import android.net.Uri
import io.github.lunalight.paperread.R
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 诊断导出的 UI 状态（关于页诊断区块的唯一真相）。
 *
 * @param busy 正在生成 / 拷贝诊断包
 * @param summary 待确认的摘要（非空 = 摘要对话框可见，AC-19.2）
 * @param message 一次性成功 / 取消提示
 * @param error 失败提示
 */
data class DiagnosticsUiState(
    val busy: Boolean = false,
    val summary: DiagnosticsSummary? = null,
    val message: String? = null,
    val error: String? = null,
)

/**
 * 诊断导出的 Android 侧门面（T-021 / AC-19.1 / AC-19.2 / AC-19.6）。
 *
 * 职责与备份导出（`BackupCoordinator`）**逐条对齐**，因为踩过的坑一样：
 * - 先摘要后文件：用户点「导出诊断信息」先看到「包里有什么」（条数 / 边界声明），
 *   再选保存位置（AC-19.2）；
 * - 只写临时文件再拷进 SAF：中途取消不会在用户文档里留下半个 zip；
 * - **清理本次新建、但没写成的目标文档**：判据是 `targetCreatedByThisExport`。
 *   默认 `false`（上一轮 QA 的结论）：只有调用方明确知道「这个 Uri 是
 *   `CreateDocument` 刚给我的」时才允许删；默认 true 会在将来支持「覆盖既有
 *   文件」时误删用户的旧诊断包。
 *
 * 重活（zip 组装 / 脱敏）在 [DiagnosticsExporter]，本类只做状态流转 +
 * 文件搬运 + 文案。
 */
class DiagnosticsExportCoordinator(
    private val context: Context,
    private val exporter: DiagnosticsExporter,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** 读取摘要并弹出确认对话框（AC-19.2）。 */
    fun loadSummary() {
        if (_state.value.busy) return
        job = scope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            try {
                val summary = withContext(Dispatchers.IO) { exporter.summary() }
                _state.update { it.copy(busy = false, summary = summary) }
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = false) }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = context.getString(R.string.diagnostics_export_failed, e.message ?: ""),
                    )
                }
            }
        }
    }

    /** 关闭摘要对话框（未导出）。 */
    fun dismissSummary() {
        _state.update { it.copy(summary = null) }
    }

    /**
     * 导出到 SAF 目标 [uri]（用户确认摘要之后）。
     *
     * @param targetCreatedByThisExport 目标文档是否**由本次导出新建**
     *   （`CreateDocument` 的返回值恒为 true；复用既有文件的选择器传 false）。
     *   **默认 false**：宁可留下一个无人认领的空文件，也不误删用户既有数据。
     */
    fun export(uri: Uri, targetCreatedByThisExport: Boolean = false) {
        if (_state.value.busy) return
        job = scope.launch {
            val temp = File(cacheDir(), "diagnostics-${System.currentTimeMillis()}.zip")
            var targetWritten = false
            try {
                _state.update { it.copy(busy = true, summary = null, error = null, message = null) }
                withContext(Dispatchers.IO) { exporter.export(temp) }
                withContext(Dispatchers.IO) { copyToDocument(temp, uri) }
                targetWritten = true
                _state.update {
                    it.copy(
                        busy = false,
                        message = context.getString(R.string.diagnostics_export_done),
                    )
                }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(
                        busy = false,
                        message = context.getString(R.string.diagnostics_export_canceled),
                    )
                }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        error = context.getString(R.string.diagnostics_export_failed, e.message ?: ""),
                    )
                }
            } finally {
                temp.delete()
                // NonCancellable：取消路径下 finally 里的挂起调用会被立刻打断，
                // 而清理必须完成（AC-19.6）
                if (!targetWritten && targetCreatedByThisExport) {
                    discardDocument(uri)
                }
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    // ---- 内部 ----

    private fun cacheDir(): File = File(context.cacheDir, "diagnostics").apply { mkdirs() }

    private fun copyToDocument(source: File, uri: Uri) {
        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("无法写入所选位置")
        output.use { out -> source.inputStream().buffered().use { it.copyTo(out) } }
    }

    /** 删除本次新建、但没写成的 SAF 文档（AC-19.6）；清理失败不再抛。 */
    private suspend fun discardDocument(uri: Uri) {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }
}
