package io.github.lunalight.paperread.ui.smoke

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.fakes.FakeSpeechController
import io.github.lunalight.paperread.reader.FileBookTextStore
import io.github.lunalight.paperread.ui.reader.ReaderScreen
import io.github.lunalight.paperread.ui.theme.PaperReadTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 朗读通知权限场景化 UI 测试（T-009 · R-02 / AC-20.1）。
 *
 * 与 [ReaderSpeechSmokeTest] 的分工：那边预授权 POST_NOTIFICATIONS 验证
 * 朗读主链路；这里**刻意不授权**，验证首次朗读前的场景化说明对话框：
 *   1. 未授权首次点朗读 → 弹场景对话框（告知用于「朗读控制通知」），未起播；
 *   2. 对话框「暂不」→ 不申请权限，朗读照常开始并给出降级提示；
 *   3. 对话框「同意并继续」→ 发起系统权限申请（起播延后到申请结果）。
 *
 * 置于 ui.smoke 包：与既有 Compose UI 测试同口径，纳入 robolectric-ui
 * 作业的 flaky 重试隔离（G9），不阻塞主单元门禁。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ReaderSpeechPermissionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: PaperReadApp
    private lateinit var controller: FakeSpeechController

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        controller = FakeSpeechController()
        app.container.speechController = controller
        // 本类焦点即「未授权」路径：不调用 grantPermissions，
        // Robolectric 默认 POST_NOTIFICATIONS 未授权。
    }

    private fun seedBook(title: String, paragraphs: List<String>): Long = runBlocking {
        val dirName = "speech-perm-$title"
        val textStore = FileBookTextStore(app)
        val content = (listOf("第一章 起点") + paragraphs).joinToString("\n")
        textStore.textFile(dirName).apply { parentFile?.mkdirs() }.writeText(content)
        val bytes = content.toByteArray(Charsets.UTF_8)
        val chapter = Chapter(
            bookId = 0,
            index = 0,
            title = "第一章 起点",
            charStart = 0,
            charEnd = content.length.toLong(),
            byteStart = 0,
            byteEnd = bytes.size.toLong(),
            charCount = content.length.toLong(),
            cumulativeChars = 0,
        )
        val book = Book(
            id = 0,
            title = title,
            author = "测试作者",
            format = BookFormat.TXT,
            encoding = "UTF-8",
            sha256 = "speech-perm-$title",
            fileSize = bytes.size.toLong(),
            dirName = dirName,
            totalChars = content.length.toLong(),
            chapterCount = 1,
            progressPercent = 0f,
            lastReadAt = null,
            addedAt = 1L,
        )
        app.container.bookRepository.saveImport(book, listOf(chapter))
    }

    private fun paragraphs(count: Int): List<String> =
        (1..count).map { "这是第 $it 段正文，长度足够参与排版计算。" }

    private fun SemanticsNodeInteraction.assertRendered() = apply {
        assertExists()
        val bounds = fetchSemanticsNode().boundsInRoot
        assertTrue("节点应有非零尺寸，实际 $bounds", bounds.width > 0f && bounds.height > 0f)
    }

    private fun startSpeechFromTopBar() {
        composeRule.waitUntilAtLeastOneExists(hasText("这是第 1 段正文", substring = true))
        composeRule.onNodeWithContentDescription("开始朗读").performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `未授权首次朗读先弹场景对话框且未起播`() {
        val id = seedBook("场景对话框链路", paragraphs(4))
        composeRule.setContent { PaperReadTheme { ReaderScreen(bookId = id, onBack = {}) } }

        startSpeechFromTopBar()

        // 场景化说明：标题 + 用途文案（含「朗读控制通知」）
        composeRule.onNodeWithText("朗读控制通知").assertRendered()
        composeRule.onNodeWithText("用于在通知栏显示朗读控制", substring = true).assertRendered()
        composeRule.onNodeWithText("拒绝不影响朗读", substring = true).assertRendered()
        // 对话框展示期间不得起播（AC-20.1：先告知、再申请、后朗读）
        assertTrue("场景对话框展示前不得起播", controller.speakCalls.isEmpty())
    }

    @Test
    fun `对话框选择暂不申请则朗读照常并提示降级`() {
        val id = seedBook("拒绝后链路", paragraphs(4))
        composeRule.setContent { PaperReadTheme { ReaderScreen(bookId = id, onBack = {}) } }

        startSpeechFromTopBar()
        composeRule.onNodeWithText("暂不").performClick()
        composeRule.waitForIdle()

        // 对话框消失、朗读开始（不申请权限）
        composeRule.onNodeWithText("朗读控制通知").assertDoesNotExist()
        assertTrue("取消场景申请不阻塞朗读", controller.speakCalls.isNotEmpty())
        // 降级提示（Snackbar）
        composeRule.onNodeWithText("未获得通知权限", substring = true).assertExists()
    }

    @Test
    fun `对话框同意后发起系统权限申请`() {
        val id = seedBook("确认后链路", paragraphs(4))
        composeRule.setContent { PaperReadTheme { ReaderScreen(bookId = id, onBack = {}) } }

        startSpeechFromTopBar()
        composeRule.onNodeWithText("同意并继续").performClick()
        composeRule.waitForIdle()

        // 对话框消失；系统申请结果返回前不起播（Robolectric 不会自动
        // 回调权限结果，故此处 speakCalls 仍应为空——起播延后到结果）。
        composeRule.onNodeWithText("朗读控制通知").assertDoesNotExist()
        assertTrue("系统申请结果回来前不得起播", controller.speakCalls.isEmpty())
    }

    @Test
    fun `已授权时首次朗读不弹对话框直接起播`() {
        // 对照口径：授权用户无感知（与 ReaderSpeechSmokeTest 预授权等价，
        // 这里在类内单独 grant 验证「无对话框」这一负面断言）。
        org.robolectric.Shadows.shadowOf(app)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val id = seedBook("已授权链路", paragraphs(4))
        composeRule.setContent { PaperReadTheme { ReaderScreen(bookId = id, onBack = {}) } }

        startSpeechFromTopBar()

        composeRule.onNodeWithText("朗读控制通知").assertDoesNotExist()
        assertEquals("授权后直接起播", 1, controller.speakCalls.size)
    }
}
