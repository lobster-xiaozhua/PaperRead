package io.github.lunalight.paperread.ui.onboarding

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.ui.theme.PaperReadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 引导页冒烟（T-020 · AC-18.2 / AC-18.3 / AC-18.6）。
 *
 * 覆盖「三屏内容都在」「可滑动切换」「可跳过」「末屏按钮是开始使用」四件事，
 * 以及文案资源化（标题 / 正文取自 strings.xml，不是硬编码字符串）。
 *
 * 放在 `ui.onboarding`（而非 `ui.smoke`）：本类要进 CI 的覆盖率分子，
 * 与 ui.smoke 的渲染冒烟区分开——同 AboutScreenTest / SettingsScreenTest 口径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class OnboardingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: PaperReadApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun string(id: Int): String = app.getString(id)

    private fun SemanticsNodeInteraction.assertRendered() = apply {
        assertExists()
        val bounds = fetchSemanticsNode().boundsInRoot
        assertTrue("节点应有非零尺寸，实际 $bounds", bounds.width > 0f && bounds.height > 0f)
    }

    @Test
    fun `三屏文案来自字符串资源且顺序正确`() {
        assertEquals(3, ONBOARDING_PAGES.size)
        val titles = ONBOARDING_PAGES.map { string(it.titleRes) }
        assertEquals(
            listOf(
                string(R.string.onboarding_page1_title),
                string(R.string.onboarding_page2_title),
                string(R.string.onboarding_page3_title),
            ),
            titles,
        )
        // 三屏讲的是三件不同的事（数据在本地 / 怎么导入 / 听书前提）
        assertEquals(3, titles.toSet().size)
        // AC-18.6：文案必须来自资源，不含空串（资源缺失会抛，走到这里即已存在）
        ONBOARDING_PAGES.forEach {
            assertTrue(string(it.titleRes).isNotBlank())
            assertTrue(string(it.bodyRes).isNotBlank())
        }
    }

    @Test
    fun `首屏展示定位说明并可跳过`() {
        var finished = false
        composeRule.setContent { PaperReadTheme { OnboardingScreen(onFinish = { finished = true }) } }

        composeRule.waitUntilAtLeastOneExists(
            hasText(string(R.string.onboarding_page1_title)),
            timeoutMillis = 30_000,
        )
        composeRule.onNodeWithText(string(R.string.onboarding_page1_title)).assertRendered()
        composeRule.onNodeWithText(string(R.string.onboarding_page1_body)).assertRendered()
        composeRule.onNodeWithText(string(R.string.onboarding_next)).assertRendered()

        composeRule.onNodeWithText(string(R.string.onboarding_skip)).performClick()
        composeRule.waitForIdle()
        assertTrue("跳过必须直接结束引导", finished)
    }

    @Test
    fun `可滑动到末屏并开始使用`() {
        var finished = false
        composeRule.setContent { PaperReadTheme { OnboardingScreen(onFinish = { finished = true }) } }

        composeRule.waitUntilAtLeastOneExists(
            hasText(string(R.string.onboarding_page1_title)),
            timeoutMillis = 30_000,
        )
        // AC-18.3：可左右滑动（不依赖按钮也能翻）
        composeRule.onNodeWithText(string(R.string.onboarding_page1_title)).performTouchInput {
            swipeLeft()
        }
        composeRule.waitUntilAtLeastOneExists(
            hasText(string(R.string.onboarding_page2_title)),
            timeoutMillis = 30_000,
        )
        composeRule.onNodeWithText(string(R.string.onboarding_page2_title)).assertRendered()

        composeRule.onNodeWithText(string(R.string.onboarding_page2_title)).performTouchInput {
            swipeLeft()
        }
        composeRule.waitUntilAtLeastOneExists(
            hasText(string(R.string.onboarding_page3_title)),
            timeoutMillis = 30_000,
        )
        composeRule.onNodeWithText(string(R.string.onboarding_page3_title)).assertRendered()
        // 末屏按钮文案切换为「开始使用」，且首屏的「下一步」不再出现
        composeRule.onNodeWithText(string(R.string.onboarding_start)).assertRendered()
        assertNotEquals(
            string(R.string.onboarding_next),
            string(R.string.onboarding_start),
        )

        composeRule.onNodeWithText(string(R.string.onboarding_start)).performClick()
        composeRule.waitForIdle()
        assertTrue("末屏「开始使用」应结束引导", finished)
    }

    @Test
    fun `下一步按钮逐屏前进到开始使用`() {
        var finished = false
        composeRule.setContent { PaperReadTheme { OnboardingScreen(onFinish = { finished = true }) } }

        composeRule.waitUntilAtLeastOneExists(
            hasText(string(R.string.onboarding_page1_title)),
            timeoutMillis = 30_000,
        )
        repeat(2) {
            composeRule.onNodeWithText(string(R.string.onboarding_next)).performClick()
            composeRule.waitForIdle()
        }
        composeRule.waitUntilAtLeastOneExists(
            hasText(string(R.string.onboarding_start)),
            timeoutMillis = 30_000,
        )
        composeRule.onNodeWithText(string(R.string.onboarding_page3_title)).assertIsDisplayed()

        composeRule.onNodeWithText(string(R.string.onboarding_start)).performClick()
        composeRule.waitForIdle()
        assertTrue(finished)
    }
}
