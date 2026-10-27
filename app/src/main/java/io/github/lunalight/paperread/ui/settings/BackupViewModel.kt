package io.github.lunalight.paperread.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.lunalight.paperread.backup.BackupCoordinator
import io.github.lunalight.paperread.backup.BackupUiState
import io.github.lunalight.paperread.di.AppContainer
import kotlinx.coroutines.flow.StateFlow

/**
 * 备份 / 恢复（US-14）的 ViewModel：只做状态透传与动作转交。
 *
 * 真正的编排在 [BackupCoordinator]（Application 级，随 appScope 存活：
 * 用户从设置页退出时，正在跑的导出 / 恢复继续完成，不会被 ViewModel 销毁打断）。
 * 设置页原有的 [SettingsViewModel] 因此无需承担备份职责——阅读偏好与备份
 * 是两条互不相干的数据流。
 */
class BackupViewModel(
    private val coordinator: BackupCoordinator,
) : ViewModel() {

    val state: StateFlow<BackupUiState> = coordinator.state

    /** 读取导出预览（AC-14.8）。 */
    fun loadExportPreview() = coordinator.loadExportPreview()

    /** 导出到用户选择的 SAF 目标；[password] 为空表示明文包。 */
    fun export(uri: Uri, password: CharArray?) = coordinator.export(uri, password)

    /** 打开用户选择的备份文件并预览（AC-14.8）。 */
    fun openBackup(uri: Uri) = coordinator.openBackup(uri)

    /** 执行恢复；[password] 为空表示明文包。 */
    fun restore(password: CharArray?) = coordinator.restore(password)

    fun cancel() = coordinator.cancel()

    fun dismissExportPreview() = coordinator.dismissExportPreview()

    fun dismissImportPreview() = coordinator.dismissImportPreview()

    fun dismissReport() = coordinator.dismissReport()

    fun clearError() = coordinator.clearError()

    fun clearMessage() = coordinator.clearMessage()

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    BackupViewModel(coordinator = container.backupCoordinator)
                }
            }
    }
}
