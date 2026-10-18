package io.github.lunalight.paperread.ui.about

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
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
 * S1 许可全文页冒烟：raw 资源可读、页面异步渲染全文。
 * 覆盖 SPEC AC-8.6。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class LicenseScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: PaperReadApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `许可原文可加载且为Apache-2_0全文`() {
        val text = readLicenseText(app)
        assertTrue("应包含 Apache License 标题", text.contains("Apache License"))
        assertTrue("应包含条款正文", text.contains("TERMS AND CONDITIONS"))
    }

    @Test
    fun `许可页异步渲染全文`() {
        composeRule.setContent {
            PaperReadTheme { LicenseScreen(onBack = {}) }
        }
        // 全文经 LaunchedEffect + IO 调度器异步读取，显式等待节点出现
        composeRule.waitUntilAtLeastOneExists(
            hasText("Apache License", substring = true),
            timeoutMillis = 5_000,
        )
    }
}
