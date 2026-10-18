package io.github.lunalight.paperread.ui.about

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.BuildConfig
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.ui.theme.PaperReadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S1 关于页冒烟（Robolectric + createComposeRule，范式同 UiSmokeTest）。
 *
 * 覆盖 SPEC AC-8.4 ~ AC-8.9：版本号 / 隐私承诺 / 许可入口 / 外链系统浏览器 /
 * 无第三方依赖与维护者 / 零网络权限。
 *
 * 注意：Robolectric 默认设备视口较小，页面下半部分的节点需先
 * performScrollTo 滚入视口再断言 / 交互（与 UiSmokeTest 弹层用例同口径）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class AboutScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: PaperReadApp

    @Before
    fun setUp() {
        // Robolectric 每个测试类全新 Application，状态天然隔离
        app = ApplicationProvider.getApplicationContext()
    }

    /** 渲染断言：节点存在于语义树且尺寸非零（同 UiSmokeTest 口径，Robolectric 下
     *  assertIsDisplayed 对部分节点有误报，exists + 尺寸对冒烟已足够）。 */
    private fun SemanticsNodeInteraction.assertRendered() = apply {
        assertExists()
        val bounds = fetchSemanticsNode().boundsInRoot
        assertTrue("节点应有非零尺寸，实际 $bounds", bounds.width > 0f && bounds.height > 0f)
    }

    /** 滚入视口后断言渲染（视口外节点在 Robolectric 下报零尺寸）。 */
    private fun SemanticsNodeInteraction.scrollToAndAssertRendered(): SemanticsNodeInteraction {
        performScrollTo()
        composeRule.waitForIdle()
        return assertRendered()
    }

    /**
     * 注册一个可解析 ACTION_VIEW 的假浏览器。
     * Robolectric 默认不安装任何浏览器，不注册时 openUrl 会走
     * ActivityNotFoundException 兜底（Toast），无法断言跳转 Intent。
     */
    private fun registerBrowserResolver() {
        val pm = shadowOf(app.packageManager)
        val activityInfo = pm.addActivityIfNotPresent(
            ComponentName("com.android.browser", "com.android.browser.BrowserActivity"),
        )
        val resolveInfo = ResolveInfo().apply { this.activityInfo = activityInfo }
        listOf(AboutLinks.HOME, AboutLinks.ISSUES).forEach { url ->
            pm.addResolveInfoForIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)), resolveInfo)
        }
    }

    @Test
    fun `关于页展示应用名与版本号`() {
        composeRule.setContent {
            PaperReadTheme { AboutScreen(onBack = {}, onOpenLicense = {}) }
        }
        composeRule.onNodeWithText("纸阅 PaperRead").assertRendered()
        composeRule.onNodeWithText("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
            .assertRendered()
    }

    @Test
    fun `关于页展示隐私承诺`() {
        composeRule.setContent {
            PaperReadTheme { AboutScreen(onBack = {}, onOpenLicense = {}) }
        }
        composeRule.onNodeWithText("隐私承诺").assertRendered()
        composeRule.onNodeWithText(
            "纸阅完全离线运行，不申请网络权限：不收集、不上传任何个人信息，" +
                "书籍与阅读记录仅存储在本机。",
        ).assertRendered()
    }

    @Test
    fun `关于页展示无第三方依赖声明与维护者`() {
        composeRule.setContent {
            PaperReadTheme { AboutScreen(onBack = {}, onOpenLicense = {}) }
        }
        composeRule.onNodeWithText("第三方依赖").scrollToAndAssertRendered()
        composeRule.onNodeWithText(
            "无第三方依赖：纸阅不集成任何第三方 SDK 与统计库，" +
                "全部功能基于 AndroidX 与 Kotlin 官方库实现。",
        ).scrollToAndAssertRendered()
        composeRule.onNodeWithText("lobster-xiaozhua").scrollToAndAssertRendered()
    }

    @Test
    fun `点击许可入口进入许可全文页`() {
        var licenseOpened = false
        composeRule.setContent {
            PaperReadTheme {
                AboutScreen(onBack = {}, onOpenLicense = { licenseOpened = true })
            }
        }
        composeRule.onNodeWithText("Apache License 2.0").performClick()
        composeRule.waitForIdle()
        assertTrue("点击许可入口应跳转许可全文页", licenseOpened)
    }

    @Test
    fun `点击项目主页以系统浏览器打开`() {
        registerBrowserResolver()
        composeRule.setContent {
            PaperReadTheme { AboutScreen(onBack = {}, onOpenLicense = {}) }
        }
        composeRule.onNodeWithText("GitHub 项目主页").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("GitHub 项目主页").performClick()
        composeRule.waitForIdle()
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(AboutLinks.HOME), intent.data)
    }

    @Test
    fun `点击问题反馈以系统浏览器打开`() {
        registerBrowserResolver()
        composeRule.setContent {
            PaperReadTheme { AboutScreen(onBack = {}, onOpenLicense = {}) }
        }
        composeRule.onNodeWithText("问题反馈").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("问题反馈").performClick()
        composeRule.waitForIdle()
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(Uri.parse(AboutLinks.ISSUES), intent.data)
    }

    @Test
    fun `关于页不申请网络权限`() {
        // AC-8.9 / AC-7.1：合并 Manifest 不得含 INTERNET 权限。
        // （Android 13+ 合并器会注入 DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION，
        // 属系统行为、非本应用声明，故不断言「零权限」而聚焦网络权限。）
        val info = app.packageManager.getPackageInfo(
            app.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions?.toList().orEmpty()
        assertTrue(
            "不应申请 INTERNET 网络权限，实际 $requested",
            android.Manifest.permission.INTERNET !in requested,
        )
    }

    @Test
    fun `关于页顶栏返回可点击`() {
        var backClicked = false
        composeRule.setContent {
            PaperReadTheme {
                AboutScreen(onBack = { backClicked = true }, onOpenLicense = {})
            }
        }
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        assertTrue(backClicked)
    }
}
