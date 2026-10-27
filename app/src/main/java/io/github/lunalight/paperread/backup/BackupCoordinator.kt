package io.github.lunalight.paperread.backup

import android.content.Context
import android.net.Uri
import io.github.lunalight.paperread.R
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 备份页当前动作（进度文案与「取消该做什么」的依据）。 */
enum class BackupPhase {
    IDLE,
    EXPORTING,
    OPENING,
    RESTORING,
}

/** 恢复前预览（AC-14.8）：加密包在口令正确前书名以「书籍 N」占位。 */
data class BackupImportPreview(
    val manifest: BackupManifest,
    val bookTitles: List<String>,
    val fontFallbacks: List<String>,
    val missingEntries: Int,
    val passwordRequired: Boolean,
)

/** 备份 / 恢复 UI 状态（设置页备份区块的唯一真相）。 */
data class BackupUiState(
    val busy: Boolean = false,
    val phase: BackupPhase = BackupPhase.IDLE,
    val done: Int = 0,
    val total: Int = 0,
    val fraction: Float = 0f,
    val detail: String = "",
    val exportPreview: BackupPreview? = null,
    val importPreview: BackupImportPreview? = null,
    /** 口令错误标记：对话框保持打开、提示可重试（AC-14.7）。 */
    val wrongPassword: Boolean = false,
    val report: BackupRestoreReport? = null,
    val message: String? = null,
    val error: String? = null,
)

/**
 * 备份 / 恢复编排（T-015 / T-016 的 UI 侧门面）。
 *
 * 职责边界：
 * - **文件搬运**：SAF Uri ↔ 应用缓存临时文件（导出先写临时文件再拷贝，
 *   恢复先拷贝再解包——保证取消时 SAF 目标不会被写成半个 zip）；
 * - **状态流转**：进度 / 预览 / 报告 / 错误（文案走 strings.xml）；
 * - **口令生命周期**：口令只在本类与加密器之间传递，流程结束 zero-fill，
 *   不进状态流、不落盘、不打日志（AC-14.2）。
 *
 * 重活全部委托 [BackupExporter] / [BackupImporter]（纯逻辑、可 JVM 单测）；
 * 本类只做 Android 文件系统与 Compose 状态之间的适配。
 */
class BackupCoordinator(
    private val context: Context,
    private val exporter: BackupExporter,
    private val importer: BackupImporter,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** 已拷入缓存的待恢复备份（预览对话框打开期间保留，关闭即删）。 */
    private var pendingImportFile: File? = null

    /** 读取导出预览（AC-14.8）：书籍数 / 进度数 / 书签数 / 字体引用。 */
    fun loadExportPreview() {
        if (_state.value.busy) return
        job = scope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    phase = BackupPhase.EXPORTING,
                    detail = context.getString(R.string.backup_phase_collecting),
                    error = null,
                )
            }
            try {
                val preview = exporter.preview()
                _state.update {
                    it.copy(
                        busy = false,
                        phase = BackupPhase.IDLE,
                        detail = "",
                        exportPreview = preview,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        phase = BackupPhase.IDLE,
                        detail = "",
                        error = context.getString(
                            R.string.backup_error_read_library,
                            e.message ?: "",
                        ),
                    )
                }
            }
        }
    }

    /**
     * 导出到 SAF 目标 [uri]。
     *
     * @param password null = 明文包；非空 = 口令加密（调用方已做长度校验）
     */
    fun export(uri: Uri, password: CharArray?) {
        if (_state.value.busy) return
        job = scope.launch {
            val temp = File(backupCacheDir(), "export-${System.currentTimeMillis()}.zip")
            try {
                _state.update {
                    it.copy(
                        busy = true,
                        phase = BackupPhase.EXPORTING,
                        exportPreview = null,
                        done = 0,
                        total = 0,
                        fraction = 0f,
                        detail = context.getString(R.string.backup_phase_exporting),
                        error = null,
                        message = null,
                    )
                }
                exporter.export(
                    target = temp,
                    password = password,
                    onProgress = { progress ->
                        _state.update {
                            it.copy(
                                done = progress.done,
                                total = progress.total,
                                fraction = progress.fraction,
                                detail = progress.currentTitle.ifBlank {
                                    context.getString(R.string.backup_phase_exporting)
                                },
                            )
                        }
                    },
                )
                withContext(Dispatchers.IO) {
                    copyToDocument(temp, uri)
                }
                _state.update {
                    it.copy(
                        busy = false,
                        phase = BackupPhase.IDLE,
                        detail = "",
                        fraction = 1f,
                        message = context.getString(R.string.backup_export_done),
                    )
                }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(
                        busy = false,
                        phase = BackupPhase.IDLE,
                        detail = "",
                        message = context.getString(R.string.backup_export_canceled),
                    )
                }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        phase = BackupPhase.IDLE,
                        detail = "",
                        error = context.getString(
                            R.string.backup_export_failed,
                            e.message ?: "",
                        ),
                    )
                }
            } finally {
                temp.delete()
                password?.fill('\u0000')
            }
        }
    }

    /**
     * 打开备份（AC-14.8 预览）：拷入缓存 → 读 manifest → 展示摘要。
     *
     * 加密包在这一步只展示计数（书名在口令正确后才解密）。
     */
    fun openBackup(uri: Uri) {
        if (_state.value.busy) return
        job = scope.launch {
            removePendingImport()
            _state.update {
                it.copy(
                    busy = true,
                    phase = BackupPhase.OPENING,
                    importPreview = null,
                    wrongPassword = false,
                    error = null,
                    message = null,
                )
            }
            val temp = File(backupCacheDir(), "import.zip")
            try {
                withContext(Dispatchers.IO) { copyFromDocument(uri, temp) }
                when (val inspection = importer.inspect(temp)) {
                    is BackupInspection.Ready -> {
                        pendingImportFile = temp
                        _state.update {
                            it.copy(
                                busy = false,
                                phase = BackupPhase.IDLE,
                                wrongPassword = false,
                                importPreview = BackupImportPreview(
                                    manifest = inspection.manifest,
                                    bookTitles = inspection.bookTitles,
                                    fontFallbacks = inspection.fontFallbacks,
                                    missingEntries = inspection.missingEntries,
                                    passwordRequired = false,
                                ),
                            )
                        }
                    }

                    is BackupInspection.PasswordRequired -> {
                        pendingImportFile = temp
                        _state.update {
                            it.copy(
                                busy = false,
                                phase = BackupPhase.IDLE,
                                wrongPassword = false,
                                importPreview = BackupImportPreview(
                                    manifest = inspection.manifest,
                                    bookTitles = inspection.manifest.books.mapIndexed { index, entry ->
                                        entry.title ?: "书籍 ${index + 1}"
                                    },
                                    fontFallbacks = emptyList(),
                                    missingEntries = 0,
                                    passwordRequired = true,
                                ),
                            )
                        }
                    }

                    is BackupInspection.WrongPassword -> {
                        pendingImportFile = temp
                        _state.update {
                            it.copy(
                                busy = false,
                                phase = BackupPhase.IDLE,
                                wrongPassword = true,
                            )
                        }
                    }

                    is BackupInspection.Invalid -> {
                        temp.delete()
                        _state.update {
                            it.copy(
                                busy = false,
                                phase = BackupPhase.IDLE,
                                error = invalidMessage(inspection.reason),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                temp.delete()
                throw e
            } catch (e: Exception) {
                temp.delete()
                _state.update {
                    it.copy(
                        busy = false,
                        phase = BackupPhase.IDLE,
                        error = invalidMessage(BackupInvalidReason.NOT_A_BACKUP),
                    )
                }
            }
        }
    }

    /**
     * 执行恢复（对话框确认后）。
     *
     * @param password 加密包口令；明文包传 null
     */
    fun restore(password: CharArray?) {
        if (_state.value.busy) return
        val file = pendingImportFile ?: return
        job = scope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    phase = BackupPhase.RESTORING,
                    wrongPassword = false,
                    error = null,
                    done = 0,
                    total = it.importPreview?.manifest?.bookCount ?: 0,
                    fraction = 0f,
                    detail = context.getString(R.string.backup_phase_restoring),
                )
            }
            try {
                val result = importer.restore(
                    file = file,
                    password = password,
                    onProgress = { progress ->
                        _state.update {
                            it.copy(
                                done = progress.done,
                                total = progress.total,
                                fraction = progress.fraction,
                                detail = progress.currentTitle.ifBlank {
                                    context.getString(R.string.backup_phase_restoring)
                                },
                            )
                        }
                    },
                )
                when (result) {
                    is BackupRestoreResult.Done -> {
                        removePendingImport()
                        _state.update {
                            it.copy(
                                busy = false,
                                phase = BackupPhase.IDLE,
                                importPreview = null,
                                detail = "",
                                fraction = 1f,
                                report = result.report,
                            )
                        }
                    }

                    BackupRestoreResult.PasswordRequired,
                    is BackupRestoreResult.WrongPassword -> _state.update {
                        it.copy(
                            busy = false,
                            phase = BackupPhase.IDLE,
                            detail = "",
                            wrongPassword = true,
                        )
                    }

                    is BackupRestoreResult.Rejected -> {
                        removePendingImport()
                        _state.update {
                            it.copy(
                                busy = false,
                                phase = BackupPhase.IDLE,
                                importPreview = null,
                                detail = "",
                                error = invalidMessage(result.reason),
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(
                        busy = false,
                        phase = BackupPhase.IDLE,
                        detail = "",
                        message = context.getString(R.string.backup_import_canceled),
                    )
                }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = false,
                        phase = BackupPhase.IDLE,
                        detail = "",
                        error = context.getString(
                            R.string.backup_import_failed,
                            e.message ?: "",
                        ),
                    )
                }
            } finally {
                password?.fill('\u0000')
            }
        }
    }

    /** 取消进行中的导出 / 打开 / 恢复（AC 要求的可取消）。 */
    fun cancel() {
        val phase = _state.value.phase
        job?.cancel()
        job = null
        if (phase == BackupPhase.OPENING || phase == BackupPhase.RESTORING) {
            removePendingImport()
        }
        _state.update {
            it.copy(
                busy = false,
                phase = BackupPhase.IDLE,
                detail = "",
                importPreview = if (phase == BackupPhase.RESTORING) null else it.importPreview,
                message = context.getString(
                    if (phase == BackupPhase.EXPORTING) {
                        R.string.backup_export_canceled
                    } else {
                        R.string.backup_import_canceled
                    },
                ),
            )
        }
    }

    fun dismissExportPreview() {
        _state.update { it.copy(exportPreview = null) }
    }

    /** 关闭恢复对话框（AC-14.8 预览之后未确认）：缓存文件一并删除。 */
    fun dismissImportPreview() {
        removePendingImport()
        _state.update { it.copy(importPreview = null, wrongPassword = false) }
    }

    fun dismissReport() {
        _state.update { it.copy(report = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    // ---- 内部 ----

    private fun backupCacheDir(): File = File(context.cacheDir, "backup").apply { mkdirs() }

    private fun removePendingImport() {
        pendingImportFile?.delete()
        pendingImportFile = null
    }

    private fun copyToDocument(source: File, uri: Uri) {
        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("无法写入所选位置")
        output.use { out -> source.inputStream().buffered().use { it.copyTo(out) } }
    }

    private fun copyFromDocument(uri: Uri, target: File) {
        target.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选文件")
        input.use { source -> target.outputStream().buffered().use { source.copyTo(it) } }
    }

    /** 包级错误 → strings.xml 文案（AC-14.3 的「版本过新」有独立话术）。 */
    private fun invalidMessage(reason: BackupInvalidReason): String = when (reason) {
        BackupInvalidReason.NOT_A_BACKUP -> context.getString(R.string.backup_error_not_a_backup)
        BackupInvalidReason.CORRUPT -> context.getString(R.string.backup_error_corrupt)
        BackupInvalidReason.VERSION_TOO_NEW -> context.getString(R.string.backup_error_version_too_new)
    }
}
