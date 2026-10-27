package io.github.lunalight.paperread.ui.onboarding

import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.fakes.testBook
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 首启判定的**装配**验证（T-020 · AC-18.1）。
 *
 * `OnboardingStoreTest` 只验证判据语义（lambda 真假的两种分支）；本文件验证
 * `AppContainer` 真的把「书架 / 阅读设置」接进了那个 lambda——否则两者都绿，
 * 线上却可能永远判成新用户（把判据装配成恒 false 也照样过单测）。
 *
 * 判据本身带「一旦判成老用户就补写标记」的副作用（防删完书重启又弹引导），
 * 所以**每条痕迹必须各占一个测试类**：Robolectric 每个测试类新建 Application，
 * 一个类里连测两种痕迹时，第一种痕迹已经把标记写死了，第二种痕迹就测不出东西。
 * 每个类只有一个用例，也就不存在类内顺序问题。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingGateTest {

    @Test
    fun `书架非空判老用户`() = runTest {
        val container = ApplicationProvider.getApplicationContext<PaperReadApp>().container

        assertFalse("初始无任何痕迹", container.hasLegacyUsage())

        container.bookRepository.saveImport(testBook(id = 0L, sha256 = "sha-gate"), emptyList())

        assertTrue("书架有书 → 老用户", container.hasLegacyUsage())
        assertFalse(
            "AC-18.1：升级安装不属于首次，不得弹引导",
            container.onboardingStore.shouldShowGuide(),
        )
    }
}

/** 阅读设置痕迹分支（独立 Application，见 [OnboardingGateTest] 的类注释）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingSettingsGateTest {

    @Test
    fun `改过阅读设置判老用户`() = runTest {
        val container = ApplicationProvider.getApplicationContext<PaperReadApp>().container

        assertFalse("初始无任何痕迹", container.hasLegacyUsage())

        container.settingsStore.update { it.copy(fontSizeSp = 22) }

        assertTrue("设置非默认 → 老用户", container.hasLegacyUsage())
        assertFalse(
            "AC-18.1：升级安装不属于首次，不得弹引导",
            container.onboardingStore.shouldShowGuide(),
        )
    }
}
