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
 * 覆盖 SPEC US-8（AC-8.4 ~ AC-8.9）：版本号 / 隐私承诺 / 许可入口 / 外链系统浏览器 /
 * 无第三方依赖与维护者 / 最小权限（INTERNET + 朗读前台服务三件套）；
 * 以及 T-009（R-02）隐私说明一页（AC-20.3 / AC-20.4）：权限清单与合并
 * Manifest 一致、网络行为承诺、数据边界说明。
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
            PaperReadTheme { AboutScreen(onBack = {}, onOpenLicense = {}, onOpenPrivacy = {}) }
        }
        composeRule.onNodeWithText("纸阅 PaperRead").assertRendered()
        composeRule.onNodeWithText("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
            .assertRendered()
    }

    @Test
    fun `关于页展示隐私承诺`() {
        composeRule.setContent {
            PaperReadTheme { AboutScreen(onBack = {}, onOpenLicense = {}, onOpenPrivacy = {}) }
        }
        composeRule.onNodeWithText("隐私承诺").assertRendered()
        composeRule.onNodeWithText(
            "纸阅仅申请 INTERNET 权限，以及朗读后台播放所需的前台服务权限" +
                "（FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PLAYBACK / POST_NOTIFICATIONS）：" +
                "INTERNET 用于同一局域网内的设备发现与书籍传输（传书 / 迁移 / 同步），" +
                "不连接互联网；前台服务与通知权限仅用于朗读退到后台后继续播放并在通知栏控制，" +
                "语音由系统 TTS 引擎在本机离线合成。不收集、不上传任何个人信息，" +
                "书籍与阅读记录仅存储在本机。",
        ).assertRendered()
    }

    @Test
    fun `关于页展示无第三方依赖声明与维护者`() {
        composeRule.setContent {
            PaperReadTheme { AboutScreen(onBack = {}, onOpenLicense = {}, onOpenPrivacy = {}) }
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
                AboutScreen(
                    onBack = {},
                    onOpenLicense = { licenseOpened = true },
                    onOpenPrivacy = {},
                )
            }
        }
        // 隐私承诺文案较长，许可入口可能落在默认视口外：先滚入再点击
        // （同「项目主页」用例口径）。
        composeRule.onNodeWithText("Apache License 2.0").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Apache License 2.0").performClick()
        composeRule.waitForIdle()
        assertTrue("点击许可入口应跳转许可全文页", licenseOpened)
    }

    @Test
    fun `点击项目主页以系统浏览器打开`() {
        registerBrowserResolver()
        composeRule.setContent {
            PaperReadTheme { AboutScreen(onBack = {}, onOpenLicense = {}, onOpenPrivacy = {}) }
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
            PaperReadTheme { AboutScreen(onBack = {}, onOpenLicense = {}, onOpenPrivacy = {}) }
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
    fun `权限清单与隐私承诺一致且不含多余项`() {
        // 权限策略（2026-10-18 / 2026-10-19 用户拍板）：允许申请权限、保持最小权限。
        // 声明项共两组：
        // 1) INTERNET——Android 无「仅局域网」权限，任何 socket（含连接局域网 IP）
        //    都必须声明它；传书 / 迁移 / 同步只在同一局域网内进行，不访问公网
        //    （AC-7.1 / AC-9.7）；
        // 2) 朗读前台服务三件套——FOREGROUND_SERVICE（前台服务必须声明）、
        //    FOREGROUND_SERVICE_MEDIA_PLAYBACK（Android 14+ mediaPlayback 类型强制）、
        //    POST_NOTIFICATIONS（Android 13+ 运行时权限，朗读控制通知）。
        // （Android 13+ 合并器会注入 <applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
        // 供应用自我保护非导出 Receiver，属系统行为、非本应用主动声明的敏感权限，
        // 故不断言「零权限」而聚焦白名单；其前缀随 flavor 的 applicationIdSuffix
        // 变化，这里动态拼接而非写死。）
        val info = app.packageManager.getPackageInfo(
            app.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions?.toList().orEmpty()
        assertTrue(
            "应申请 INTERNET（最小权限，局域网传输用），实际 $requested",
            android.Manifest.permission.INTERNET in requested,
        )
        assertTrue(
            "应申请朗读前台服务三件套，实际 $requested",
            android.Manifest.permission.FOREGROUND_SERVICE in requested &&
                android.Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK in requested &&
                android.Manifest.permission.POST_NOTIFICATIONS in requested,
        )
        val unexpected = requested.toSet() - setOf(
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.FOREGROUND_SERVICE,
            android.Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK,
            android.Manifest.permission.POST_NOTIFICATIONS,
            "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        assertTrue(
            "除上述白名单与系统注入项外不得申请其他权限，实际 $requested",
            unexpected.isEmpty(),
        )
    }

    @Test
    fun `关于页顶栏返回可点击`() {
        var backClicked = false
        composeRule.setContent {
            PaperReadTheme {
                AboutScreen(onBack = { backClicked = true }, onOpenLicense = {}, onOpenPrivacy = {})
            }
        }
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitForIdle()
        assertTrue(backClicked)
    }

    // ---- 隐私说明一页（T-009 · AC-20.3 / AC-20.4） ----

    /**
     * 隐私说明页权限清单口径（AC-20.4）：
     * 页面展示的权限集合必须与合并 Manifest 的 requestedPermissions
     * 逐一一致（系统注入的 DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
     * 除外，其前缀随 flavor 的 applicationIdSuffix 变化，动态拼接）。
     *
     * 与下方既有白名单用例的分工：那条断言「不多不少」的合并清单；
     * 这条断言「页面陈述 == 合并清单」——Manifest 增删权限而不同步
     * 隐私页清单（PrivacyPermissionItems）会直接失败。
     */
    @Test
    fun `隐私说明页权限清单与合并 Manifest 一致`() {
        composeRule.setContent {
            PaperReadTheme { PrivacyScreen(onBack = {}) }
        }

        // 页面逐项渲染（名称节点存在；用途文案随之展示）
        PrivacyPermissionItems.items.forEach { item ->
            composeRule.onNodeWithText(item.name).scrollToAndAssertRendered()
        }

        val info = app.packageManager.getPackageInfo(
            app.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions?.toList().orEmpty()
        val displayed = PrivacyPermissionItems.items.map { it.name }.toSet()
        val expected = requested.toSet() - "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        assertEquals(
            "隐私页权限清单必须与合并 Manifest 实际权限一致（AC-20.4）",
            expected,
            displayed,
        )
    }

    @Test
    fun `隐私说明页展示网络行为承诺与数据边界`() {
        composeRule.setContent {
            PaperReadTheme { PrivacyScreen(onBack = {}) }
        }
        composeRule.onNodeWithText("权限清单").scrollToAndAssertRendered()
        composeRule.onNodeWithText("网络行为").scrollToAndAssertRendered()
        composeRule.onNodeWithText("纸阅不访问公网", substring = true).scrollToAndAssertRendered()
        composeRule.onNodeWithText("备份加密与诊断导出的数据边界").scrollToAndAssertRendered()
        composeRule.onNodeWithText("条目级 AES-256-GCM", substring = true)
            .scrollToAndAssertRendered()
    }

    @Test
    fun `关于页隐私说明入口可进入隐私页`() {
        var privacyOpened = false
        composeRule.setContent {
            PaperReadTheme {
                AboutScreen(
                    onBack = {},
                    onOpenLicense = {},
                    onOpenPrivacy = { privacyOpened = true },
                )
            }
        }
        composeRule.onNodeWithText("隐私说明").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("隐私说明").performClick()
        composeRule.waitForIdle()
        assertTrue("点击隐私说明入口应跳转隐私说明页", privacyOpened)
    }
}
