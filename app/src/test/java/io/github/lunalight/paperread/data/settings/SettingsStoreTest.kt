package io.github.lunalight.paperread.data.settings

import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.PaperReadApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SettingsStore 单测（Robolectric：DataStore 需要 Android Context）。
 *
 * store 一律取自 `PaperReadApp.container`（生产装配路径），不再自己 new：
 * 这样本类同时守住「DataStore 由 Application 托管」的装配契约——若有人改回
 * 「每次 new 一个 DataStore」，同一文件上会出现两个实例，DataStore 会在首读时
 * 直接抛 `IllegalStateException`（进程级 activeFiles 互斥），用例立刻红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {

    private lateinit var app: PaperReadApp

    private fun store() = app.container.settingsStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // DataStore 文件在 Application 生命周期内跨用例保留，
        // 先全量复位到默认值，保证「默认值」用例与用例执行顺序无关。
        runBlocking { store().update { ReaderSettings() } }
    }

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

    @Test
    fun `朗读默认值即开箱可听`() = runTest {
        val settings = store().settings.first()
        assertEquals(1.0f, settings.ttsRate, 0.001f)
        assertEquals(1.0f, settings.ttsPitch, 0.001f)
        assertTrue("章尾自动续读默认开启", settings.ttsAutoNextChapter)
    }

    @Test
    fun `朗读语速音调与续读开关可持久化`() = runTest {
        val store = store()
        store.update { it.copy(ttsRate = 1.5f, ttsPitch = 0.8f, ttsAutoNextChapter = false) }
        val settings = store.settings.first()
        assertEquals(1.5f, settings.ttsRate, 0.001f)
        assertEquals(0.8f, settings.ttsPitch, 0.001f)
        assertFalse(settings.ttsAutoNextChapter)
    }

    @Test
    fun `旧字段写入不影响朗读默认值`() = runTest {
        // 升级用户场景：只写过旧版字段（主题等），未写 tts_* key，
        // 朗读相关字段应保持默认值（缺 key 回退，不崩溃）。
        val store = store()
        store.update { it.copy(themeId = "ink") }
        val settings = store.settings.first()
        assertEquals("ink", settings.themeId)
        assertEquals(1.0f, settings.ttsRate, 0.001f)
        assertEquals(1.0f, settings.ttsPitch, 0.001f)
        assertTrue(settings.ttsAutoNextChapter)
    }

    @Test
    fun `双页跨页开关读写`() = runTest {
        val store = store()
        assertTrue(store.settings.first().twoPageSpread)
        store.update { it.copy(twoPageSpread = false) }
        assertFalse(store.settings.first().twoPageSpread)
    }

    // ---- 自定义字体（T-018 · AC-16.4 / AC-16.5） ----

    @Test
    fun `自定义字体 id 默认为空`() = runTest {
        assertNull(store().settings.first().customFontId)
    }

    @Test
    fun `自定义字体 id 可持久化并清除`() = runTest {
        val store = store()
        store.update { it.copy(customFontId = "font-1") }
        assertEquals("font-1", store.settings.first().customFontId)

        // AC-16.5：删除正在使用的字体后清 null，必须真的落成「无该键」，
        // 否则下次读到空串会指向不存在的字体
        store.update { it.copy(customFontId = null) }
        assertNull(store.settings.first().customFontId)
    }
}
