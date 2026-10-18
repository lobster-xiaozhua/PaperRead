package io.github.lunalight.paperread.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.lunalight.paperread.data.settings.ReaderSettings
import io.github.lunalight.paperread.data.settings.SettingsStore
import io.github.lunalight.paperread.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 设置页 UI 状态。 */
data class SettingsUiState(
    val settings: ReaderSettings = ReaderSettings(),
)

/**
 * 设置页 ViewModel：阅读偏好读写（S2）。
 *
 * 与 ReaderViewModel 的设置链路同构：控件 → [updateSettings] 写 Store →
 * DataStore → settings Flow 回流刷新 UI（AC-8.2）。
 *
 * 构造只依赖 [SettingsStore] 接口，不持有 AppContainer（分层铁律：
 * UI 层不得直接触碰 DataStore 实现）。
 */
class SettingsViewModel(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    SettingsViewModel(settingsStore = container.settingsStore)
                }
            }
    }
}
