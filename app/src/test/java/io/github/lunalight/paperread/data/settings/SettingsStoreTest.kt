package io.github.lunalight.paperread.data.settings

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SettingsStore 单测（Robolectric：DataStore 需要 Android Context）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {

    private fun store() = PreferencesSettingsStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `默认设置即开箱可读`() = runTest {
        val settings = store().settings.first()
        assertEquals(ReaderSettings(), settings)
        assertEquals("paper", settings.themeId)
        assertEquals(18, settings.fontSizeSp)
        assertTrue(settings.brightnessFollowsSystem)
        assertFalse(settings.volumeKeyTurnPage)
    }

    @Test
    fun `update 写入后 flow 立即可读`() = runTest {
        val store = store()
        store.update { it.copy(themeId = "ink", fontSizeSp = 22, volumeKeyTurnPage = true) }
        val settings = store.settings.first()
        assertEquals("ink", settings.themeId)
        assertEquals(22, settings.fontSizeSp)
        assertTrue(settings.volumeKeyTurnPage)
    }

    @Test
    fun `update 的 transform 基于当前值累计生效`() = runTest {
        val store = store()
        store.update { it.copy(fontSizeSp = 20) }
        store.update { it.copy(fontSizeSp = it.fontSizeSp + 2) }
        assertEquals(22, store.settings.first().fontSizeSp)
    }

    @Test
    fun `亮度覆盖与跟随系统互转`() = runTest {
        val store = store()
        store.update { it.copy(brightness = 0.4f) }
        assertFalse(store.settings.first().brightnessFollowsSystem)
        assertEquals(0.4f, store.settings.first().brightness, 0.001f)
        store.update { it.copy(brightness = -1f) }
        assertTrue(store.settings.first().brightnessFollowsSystem)
    }
}
