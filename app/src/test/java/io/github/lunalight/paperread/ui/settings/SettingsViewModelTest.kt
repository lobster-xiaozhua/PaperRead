package io.github.lunalight.paperread.ui.settings

import io.github.lunalight.paperread.data.settings.ReaderSettings
import io.github.lunalight.paperread.fakes.FakeSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * SettingsViewModel 单测（纯 JVM，无 Robolectric，范式同 ReaderViewModelTest）：
 * 设置读写闭环（AC-8.2 的 VM 层）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var settingsStore: FakeSettingsStore
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        settingsStore = FakeSettingsStore()
        viewModel = SettingsViewModel(settingsStore = settingsStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `初始状态为默认阅读设置`() = runTest(dispatcher) {
        advanceUntilIdle()
        assertEquals(ReaderSettings(), viewModel.uiState.value.settings)
    }

    @Test
    fun `修改偏好经VM写入存储并回流到状态`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.updateSettings { it.copy(fontSizeSp = 22) }
        advanceUntilIdle()

        assertEquals(22, settingsStore.current().fontSizeSp)
        assertEquals(22, viewModel.uiState.value.settings.fontSizeSp)
    }

    @Test
    fun `存储流变化驱动UI状态`() = runTest(dispatcher) {
        advanceUntilIdle()

        settingsStore.update { it.copy(themeId = "ink") }
        advanceUntilIdle()

        assertEquals("ink", viewModel.uiState.value.settings.themeId)
    }
}
