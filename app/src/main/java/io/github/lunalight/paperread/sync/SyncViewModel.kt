package io.github.lunalight.paperread.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.lunalight.paperread.backup.BackupCrypto
import io.github.lunalight.paperread.di.AppContainer
import kotlinx.coroutines.flow.StateFlow

/**
 * 云同步 ViewModel：只做状态透传与动作转交（镜像 [io.github.lunalight.paperread.ui.settings.BackupViewModel]）。
 *
 * 真正编排在 [SyncCoordinator]（Application 级，随 appScope 存活：设置页退出时
 * 正在跑的同步继续完成）。密码强度校验复用领域层 [BackupCrypto.isValidPassword]
 * （与导出对话框同源，单一判据），UI 在 register/login 前先校验。
 */
class SyncViewModel(
    private val coordinator: SyncCoordinator,
) : ViewModel() {

    val state: StateFlow<SyncUiState> = coordinator.state

    fun register(email: String, password: CharArray) = coordinator.register(email, password)

    fun login(email: String, password: CharArray) = coordinator.login(email, password)

    fun logout() = coordinator.logout()

    fun setSyncEnabled(enabled: Boolean) = coordinator.setSyncEnabled(enabled)

    fun syncNow() = coordinator.syncNow()

    fun clearError() = coordinator.clearError()

    fun clearMessage() = coordinator.clearMessage()

    /** 密码强度校验（AC：与备份导出同源判据，长度 ≥ 8 且非全空白）。 */
    fun isPasswordValid(password: CharSequence): Boolean =
        BackupCrypto.isValidPassword(password)

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    SyncViewModel(coordinator = container.syncCoordinator)
                }
            }
    }
}
