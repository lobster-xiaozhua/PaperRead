package io.github.lunalight.paperread.ui.settings

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.ui.theme.PaperReadTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * S2 设置页冒烟（Robolectric + createComposeRule + 真实 AppContainer，
 * 范式同 UiSmokeTest 有状态屏幕用例）。
 *
 * 覆盖 SPEC AC-8.1 / AC-8.2 / AC-8.4：全部阅读偏好控件渲染；
 * 拖动滑块经 VM → DataStore → Flow → UI 端到端回流；关于入口可点击。
 *
 * 注意：Robolectric 默认设备视口较小，页面下半部分的节点需先
 * performScrollTo 滚入视口再断言 / 交互（与 UiSmokeTest 弹层用例同口径）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: PaperReadApp

    @Before
    fun setUp() {
        // Robolectric 每个测试类全新 Application，DataStore / Room 状态天然隔离
        app = ApplicationProvider.getApplicationContext()
    }

    /** 渲染断言：节点存在于语义树且尺寸非零（同 UiSmokeTest 口径）。 */
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

    @Test
    fun `设置页渲染全部阅读偏好控件`() {
        composeRule.setContent {
            PaperReadTheme {
                SettingsScreen(onBack = {}, onOpenAbout = {})
            }
        }
        // 分区标题与主题 / 字体 / 字号默认值（首屏内，按页面自上而下断言）
        composeRule.onNodeWithText("主题").assertRendered()
        composeRule.onNodeWithText("字体").assertRendered()
        composeRule.onNodeWithText("排版").assertRendered()
        // 5 套主题 chip
        listOf("纸白", "米黄", "苔绿", "黛蓝", "墨黑").forEach { name ->
            composeRule.onNodeWithText(name).assertRendered()
        }
        // 字体 chip
        composeRule.onNodeWithText("衬线").assertRendered()
        composeRule.onNodeWithText("黑体").assertRendered()
        // 字号默认值
        composeRule.onNodeWithText("18 sp").assertRendered()
        // 行距 / 段距 / 页边距（滚入视口）
        composeRule.onNodeWithText("1.7 倍").scrollToAndAssertRendered()
        composeRule.onNodeWithText("12 dp").scrollToAndAssertRendered()
        composeRule.onNodeWithText("20 dp").scrollToAndAssertRendered()
        // 亮度（默认跟随系统）
        composeRule.onNodeWithText("亮度").scrollToAndAssertRendered()
        composeRule.onNodeWithText("跟随系统").scrollToAndAssertRendered()
        // 行为开关
        composeRule.onNodeWithText("行为").scrollToAndAssertRendered()
        composeRule.onNodeWithText("阅读时常亮").scrollToAndAssertRendered()
        composeRule.onNodeWithText("沉浸模式").scrollToAndAssertRendered()
        composeRule.onNodeWithText("音量键翻章").scrollToAndAssertRendered()
    }

    @Test
    fun `设置页拖动字号滑块实时生效并经DataStore回流`() {
        composeRule.setContent {
            PaperReadTheme {
                SettingsScreen(onBack = {}, onOpenAbout = {})
            }
        }
        composeRule.onNodeWithText("18 sp").assertRendered()

        // 内容高于视口，先滚到排版区再操作字号 slider
        composeRule.onNodeWithText("18 sp").performScrollTo()
        composeRule.waitForIdle()

        // 字号 slider：range 14..28、steps 13，初始 18（其余 slider range 各不相同，可精确定位）
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(18f, 14f..28f, 13)))
            .performTouchInput {
                down(center)
                moveTo(center + percentOffset(0.15f, 0f))
                up()
            }
        composeRule.waitForIdle()

        // 端到端闭环：VM → 真实 DataStore 写 → settings Flow → UI 重组。
        // 等待旧值文本消失即证明回流到达（与 UiSmokeTest 弹层用例同口径）。
        // 30s 上限：除慢 runner 外，@GraphicsMode(NATIVE) 每类首次创建 RenderNode 时
        // Robolectric 需在主线程解压拷贝 native 字体（约 3-5s，着陆点随机），
        // CI 慢机器上会计入等待窗口。
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("18 sp").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `点击关于入口进入关于页`() {
        var aboutOpened = false
        composeRule.setContent {
            PaperReadTheme {
                SettingsScreen(onBack = {}, onOpenAbout = { aboutOpened = true })
            }
        }
        // 30s 上限同 UiSmokeTest：容纳 NATIVE 图形字体一次性拷贝成本，防止慢 runner 误超时
        composeRule.waitUntilAtLeastOneExists(hasText("关于"), timeoutMillis = 30_000)
        // 「关于」在滚动列底部，先滚入视口再点击
        composeRule.onNodeWithText("关于").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("关于").performClick()
        composeRule.waitForIdle()
        assertTrue("点击关于入口应跳转关于页", aboutOpened)
    }

    // ---- 备份与恢复入口（T-015 / US-14） ----

    @Test
    fun `设置页展示备份与恢复入口`() {
        composeRule.setContent {
            PaperReadTheme {
                SettingsScreen(onBack = {}, onOpenAbout = {})
            }
        }
        composeRule.waitUntilAtLeastOneExists(hasText("备份与恢复"), timeoutMillis = 30_000)
        composeRule.onNodeWithText("备份与恢复").performScrollTo().assertRendered()
        composeRule.onNodeWithText("导出备份").performScrollTo().assertRendered()
        composeRule.onNodeWithText("从备份恢复").performScrollTo().assertRendered()
    }

    @Test
    fun `点击导出备份先展示预览摘要`() {
        composeRule.setContent {
            PaperReadTheme {
                SettingsScreen(onBack = {}, onOpenAbout = {})
            }
        }
        composeRule.waitUntilAtLeastOneExists(hasText("导出备份"), timeoutMillis = 30_000)
        composeRule.onNodeWithText("导出备份").performScrollTo()
        composeRule.onNodeWithText("导出备份").performClick()

        // 真实 AppContainer 的空书架：预览应给出「无书可备份」并禁用确认
        composeRule.waitUntilAtLeastOneExists(
            hasText("书架为空，没有可备份的书籍"),
            timeoutMillis = 30_000,
        )
        composeRule.onNodeWithText("选择保存位置").assertIsNotEnabled()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.waitForIdle()
    }
}
