package io.github.lunalight.paperread.data.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.PaperReadApp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SettingsDataStoreProvider] 契约测试：守住「按 Application 托管」与
 * 「旧路径数据零迁移」两条硬约束。
 *
 * 为什么需要这一组用例（回归守卫）：此前的文件级 `Context.settingsDataStore by
 * preferencesDataStore(...)` 委托把 DataStore 缓存成进程级单例（委托的 `INSTANCE`
 * 字段一旦写入就忽略后续 Context），Robolectric 每个测试类的独立 Application 会
 * 继续指向第一个测试类**已销毁**的目录与作用域，`settings` 流不再发射，
 * 表现为 UI 冒烟「正文永不渲染」的间歇性 `ComposeTimeoutException`。
 * 第 3 例就是那条抖动的机器化断言：两个 Application 各自的写入必须互不可见。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsDataStoreProviderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val baseContext: Context = ApplicationProvider.getApplicationContext()

    /**
     * 生产装配（`PaperReadApp.container`）写出的设置必须落在旧版委托的同一路径上。
     *
     * 路径一致性论证见 [SettingsDataStoreProvider] 类注释：`preferencesDataStore(name)`
     * 内部等价于 `context.preferencesDataStoreFile(name)`，后者等价于
     * `File(applicationContext.filesDir, "datastore/$name.preferences_pb")`。
     * 本用例同时断言「库公开工具函数的求值结果」与「登记的旧相对路径」一致，
     * 任一漂移都会让升级用户读不到既有设置。
     */
    @Test
    fun `生产装配的设置在旧版委托的同一路径上落盘`() = runTest {
        val app: PaperReadApp = ApplicationProvider.getApplicationContext()
        val legacyFile = File(app.filesDir, SettingsDataStoreProvider.LEGACY_RELATIVE_PATH)
        assertEquals(
            "preferencesDataStoreFile 的求值结果必须等于登记的旧路径",
            app.preferencesDataStoreFile(SettingsDataStoreProvider.DATA_STORE_NAME),
            legacyFile,
        )

        app.container.settingsStore.update { it.copy(themeId = "ink", fontSizeSp = 24) }

        assertTrue("设置必须落在旧路径 $legacyFile，换路径等于升级用户设置全丢", legacyFile.isFile)
        val reread = app.container.settingsStore.settings.first()
        assertEquals("ink", reread.themeId)
        assertEquals(24, reread.fontSizeSp)
    }

    /**
     * 迁移零风险：升级用户设备上旧路径已有文件时，新实现必须原样读到。
     *
     * 存量字节由同一套序列化实现产出（先写到一个不同的文件名，再搬到旧路径，
     * 避免同一文件上出现两个 DataStore 实例），因此这是对「路径 + 格式」双重一致的
     * 端到端验证，而非「新建了个空文件」的假绿。
     */
    @Test
    fun `预置旧路径的存量设置文件能被新实现读到`() = runTest {
        val targetRoot = tempFolder.newFolder("upgraded-app")
        val scratchRoot = tempFolder.newFolder("released-version-scratch")

        // 1) 产出「已发布版本写下的字节」
        val scratchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val scratchFile = File(scratchRoot, "released.preferences_pb")
        val scratchStore = PreferenceDataStoreFactory.create(
            scope = scratchScope,
            produceFile = { scratchFile },
        )
        scratchStore.edit { prefs ->
            prefs[KEY_THEME_ID] = "ink"
            prefs[KEY_FONT_SIZE] = 26
            prefs[KEY_TTS_AUTO_NEXT] = false
        }
        scratchScope.cancel()

        // 2) 搬到旧实现的路径（filesDir/datastore/reader_settings.preferences_pb）
        val legacyFile = File(targetRoot, SettingsDataStoreProvider.LEGACY_RELATIVE_PATH)
        legacyFile.parentFile?.mkdirs()
        scratchFile.copyTo(legacyFile, overwrite = true)
        assertTrue("前置条件：旧路径上必须已有存量文件", legacyFile.isFile)

        // 3) 新实现读到的必须是存量值，而不是默认值
        val provider = SettingsDataStoreProvider(FilesDirContext(baseContext, targetRoot))
        assertEquals(legacyFile, provider.file)
        val settings = PreferencesSettingsStore(provider.instance).settings.first()
        assertEquals("ink", settings.themeId)
        assertEquals(26, settings.fontSizeSp)
        assertFalse(settings.ttsAutoNextChapter)
    }

    /**
     * 抖动回归守卫：两个 Application 实例（Robolectric 每个测试类一个）各自持有
     * DataStore，写入互不可见。
     *
     * 若有人改回「进程级缓存实例 / 顶层委托」，第二个 Application 会读到第一个的
     * 写入（"ink"），本用例立即失败。
     */
    @Test
    fun `不同 Application 实例各持一份 DataStore，写入互不可见`() = runTest {
        val rootA = tempFolder.newFolder("app-a")
        val rootB = tempFolder.newFolder("app-b")
        val providerA = SettingsDataStoreProvider(FilesDirContext(baseContext, rootA))
        val providerB = SettingsDataStoreProvider(FilesDirContext(baseContext, rootB))
        assertNotEquals(
            "两个 Application 的 DataStore 必须落在各自的 filesDir 下",
            providerA.file,
            providerB.file,
        )

        val storeA = PreferencesSettingsStore(providerA.instance)
        val storeB = PreferencesSettingsStore(providerB.instance)
        storeA.update { it.copy(themeId = "ink", fontSizeSp = 22) }

        assertEquals("ink", storeA.settings.first().themeId)
        assertEquals(22, storeA.settings.first().fontSizeSp)
        assertEquals(
            "第二个 Application 必须读自己的文件（默认值）；读到 ink 说明又回到了进程级共享实例",
            "paper",
            storeB.settings.first().themeId,
        )
        assertEquals(18, storeB.settings.first().fontSizeSp)
        assertTrue(providerA.file.isFile)
    }

    /**
     * 独立的 `filesDir` 视图：模拟「两个 Application 各有自己的文件根目录」。
     *
     * DataStore 的路径解析是 `context.applicationContext.filesDir`，故这里同时
     * 覆写 `getFilesDir()` 与 `getApplicationContext()`，让 provider 解析到各自的根目录。
     */
    private class FilesDirContext(
        base: Context,
        private val root: File,
    ) : ContextWrapper(base) {

        override fun getFilesDir(): File = root

        override fun getApplicationContext(): Context = this
    }

    private companion object {
        // 与 PreferencesSettingsStore 的 key 名逐字一致：存量文件是「已发布版本的
        // 字节」，key 名漂移会直接让这些断言失败。
        val KEY_THEME_ID = stringPreferencesKey("theme_id")
        val KEY_FONT_SIZE = intPreferencesKey("font_size_sp")
        val KEY_TTS_AUTO_NEXT = booleanPreferencesKey("tts_auto_next_chapter")
    }
}
