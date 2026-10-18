package io.github.lunalight.paperread.ui.bookshelf

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import io.github.lunalight.paperread.ui.theme.PaperReadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 共享导入确认对话框（本地导入与传书接收共用，AC-9.5）：
 * 元信息展示、TXT 可切编码 / 策略并触发重新解析、EPUB 隐藏这两组、确认 / 取消回调。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportResultDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun pending(format: BookFormat = BookFormat.TXT) = PendingImport(
        dirName = "d",
        displayName = "书.txt",
        title = "测试之书",
        author = "作者",
        format = format,
        charsetName = "UTF-8",
        strategy = SplitStrategy.CONSERVATIVE,
        sha256 = "sha",
        fileSize = 2_097_152L,
        totalChars = 12_000L,
        chapters = emptyList(),
    )

    @Test
    fun `展示书籍元信息并支持切换切分策略`() {
        val reparses = mutableListOf<Pair<String, SplitStrategy>>()
        composeRule.setContent {
            PaperReadTheme {
                ImportResultDialog(
                    pending = pending(),
                    onReparse = { charset, strategy -> reparses += charset to strategy },
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("导入确认").assertExists()
        composeRule.onNodeWithText("测试之书").assertExists()
        composeRule.onNodeWithText("TXT · 2.0 MB · 0 章 · 1.2 万字").assertExists()

        composeRule.onNodeWithText("激进").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("UTF-8" to SplitStrategy.AGGRESSIVE), reparses)
    }

    @Test
    fun `切换编码触发重新解析`() {
        val reparses = mutableListOf<Pair<String, SplitStrategy>>()
        composeRule.setContent {
            PaperReadTheme {
                ImportResultDialog(
                    pending = pending(),
                    onReparse = { charset, strategy -> reparses += charset to strategy },
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("GB18030").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("GB18030" to SplitStrategy.CONSERVATIVE), reparses)
    }

    @Test
    fun `EPUB 不展示编码与切分策略`() {
        composeRule.setContent {
            PaperReadTheme {
                ImportResultDialog(
                    pending = pending(BookFormat.EPUB),
                    onReparse = { _, _ -> },
                    onConfirm = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("导入确认").assertExists()
        composeRule.onNodeWithText("编码").assertDoesNotExist()
        composeRule.onNodeWithText("切分策略").assertDoesNotExist()
    }

    @Test
    fun `确认与取消回调`() {
        var confirmed = false
        var dismissed = false
        composeRule.setContent {
            PaperReadTheme {
                ImportResultDialog(
                    pending = pending(),
                    onReparse = { _, _ -> },
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithText("导入").performClick()
        composeRule.waitForIdle()
        assertTrue(confirmed)
        composeRule.onNodeWithText("取消").performClick()
        composeRule.waitForIdle()
        assertTrue(dismissed)
    }

    @Test
    fun `大小与字数格式化`() {
        assertEquals("512 B", formatFileSize(512))
        assertEquals("2 KB", formatFileSize(2048))
        assertEquals("1.5 MB", formatFileSize(1024L * 1024 * 3 / 2))
        assertEquals("999 字", formatChars(999))
        assertEquals("1.2 万字", formatChars(12_000))
    }
}
