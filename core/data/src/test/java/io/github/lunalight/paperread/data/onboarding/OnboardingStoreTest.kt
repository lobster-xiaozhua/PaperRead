package io.github.lunalight.paperread.data.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.data.settings.PreferencesDataStoreProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [OnboardingStore] 单测（T-020 · AC-18.1 / AC-18.2 / AC-18.4）。
 *
 * 三条分支正是 AC-18.1 的全部内容：真新装（弹）、升级用户（不弹）、
 * 看过（不弹）。老用户判据以 lambda 注入，测试直接控制真假——不必为了
 * 「有 reader_settings」造一份真实偏好、为了「书架非空」造一本书。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** 每个用例一份独立 DataStore 文件（同一文件不允许多实例）。 */
    private fun store(name: String, legacy: Boolean = false): OnboardingStore = OnboardingStore(
        dataStore = PreferencesDataStoreProvider(context, "$DATA_STORE_PREFIX$name").instance,
        isLegacyUser = { legacy },
    )

    @Test
    fun `全新安装应展示引导`() = runTest {
        val store = store("fresh")
        assertFalse(store.hasSeen().first())
        assertTrue("无任何使用痕迹 → 属于首次，应展示", store.shouldShowGuide())
    }

    @Test
    fun `升级用户不展示引导`() = runTest {
        val store = store("legacy", legacy = true)
        assertFalse(store.hasSeen().first())
        assertFalse("AC-18.1：升级安装不属于首次", store.shouldShowGuide())
    }

    @Test
    fun `升级用户判定后补写标记 清空数据也不再弹`() = runTest {
        // 判据本身是可变的（现实中它随数据变化）：先老用户，再「清空书架」
        var legacy = true
        val store = OnboardingStore(
            dataStore = PreferencesDataStoreProvider(context, "${DATA_STORE_PREFIX}legacy-mark")
                .instance,
            isLegacyUser = { legacy },
        )

        assertFalse(store.shouldShowGuide())
        assertTrue("判定为老用户时应补写标记", store.hasSeen().first())

        legacy = false // 用户把书全删了、设置也复位了
        assertFalse("标记已写 → 不再弹（避免删完书重启弹一次）", store.shouldShowGuide())
    }

    @Test
    fun `看完引导后不再展示`() = runTest {
        val store = store("seen")
        store.markSeen()
        assertTrue(store.hasSeen().first())
        assertFalse(store.shouldShowGuide())
    }

    @Test
    fun `重复标记幂等`() = runTest {
        val store = store("idempotent")
        store.markSeen()
        store.markSeen()
        assertTrue(store.hasSeen().first())
        assertFalse(store.shouldShowGuide())
    }

    @Test
    fun `标记落在独立的 DataStore 文件`() = runTest {
        val provider = PreferencesDataStoreProvider(context, "${DATA_STORE_PREFIX}separate")
        val store = OnboardingStore(provider.instance, isLegacyUser = { false })

        store.markSeen()

        assertTrue("引导标记应写进自己的 preferences 文件", provider.file.isFile)
        assertEquals("datastore", provider.file.parentFile?.name)
        // 落盘名与之独立于 reader_settings（AC-18.1 的判据不受字体 / 阅读偏好干扰）
        assertEquals("app_prefs", OnboardingStore.DATA_STORE_NAME)
    }

    private companion object {
        const val DATA_STORE_PREFIX = "onboarding_test_"
    }
}
