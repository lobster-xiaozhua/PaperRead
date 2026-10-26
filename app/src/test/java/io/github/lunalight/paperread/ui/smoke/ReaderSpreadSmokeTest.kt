package io.github.lunalight.paperread.ui.smoke

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.fakes.FakeSpeechController
import io.github.lunalight.paperread.reader.FileBookTextStore
import io.github.lunalight.paperread.ui.adaptive.LocalWindowWidthClass
import io.github.lunalight.paperread.ui.adaptive.WindowWidthClass
import io.github.lunalight.paperread.ui.adaptive.rememberWindowWidthClass
import io.github.lunalight.paperread.ui.reader.PageBreak
import io.github.lunalight.paperread.ui.reader.ReaderScreen
import io.github.lunalight.paperread.ui.reader.rememberChapterPages
import io.github.lunalight.paperread.ui.theme.PaperReadTheme
import kotlinx.coroutines.runBlocking
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
 * 平板双页跨页 UI 测试（Robolectric + createComposeRule，SPEC US-13 / AC-13.1~13.5）。
 *
 * 覆盖点：
 * - Expanded 档位下 ReaderScreen 走 PaginatedSpread 分支：左右两页渲染、
 *   点按右区翻下一 spread、点按左区回翻、点按中央切换工具栏；
 * - rememberChapterPages 的空输入守卫与装箱路径；
 * - rememberWindowWidthClass 的三档断点映射。
 *
 * 与 ReaderSpeechSmokeTest 的分工：那边验证 Compact 滚动模式下的朗读链路，
 * 这里只验证双页模式独有的交互与分页装配。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ReaderSpreadSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: PaperReadApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // 双页断言不依赖朗读控制器，装一个空 fake 防止继承态。
        app.container.speechController = FakeSpeechController()
        // 朗读权限场景化（T-009 · AC-20.1）：预授权 POST_NOTIFICATIONS，
        // 顶栏「开始朗读」入口点击走既有起播路径（本类断言工具栏显隐，
        // 不涉对话框；对话框流程由 ReaderSpeechPermissionTest 覆盖）。
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun seedBook(title: String, paragraphs: List<String>): Long = runBlocking {
        val dirName = "spread-$title"
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
            sha256 = "spread-$title",
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

    /** 长段落：保证一页放不下全部段落，翻页后首段必然离开视口。 */
    private fun longParagraphs(count: Int): List<String> =
        (1..count).map { i ->
            "这是第 $i 段正文，" +
                "内容足够长以便分页器把本章切成多页，方便验证双页跨页与点按翻页交互。".repeat(3)
        }

    /** 点击根节点横向 [fraction]（0~1）处：三区手势的左 / 中 / 右。 */
    private fun clickZone(fraction: Float) {
        composeRule.onRoot().performTouchInput {
            click(Offset(width * fraction, height / 2f))
        }
    }

    private fun firstParagraphNode() = composeRule.onNodeWithText("这是第 1 段正文", substring = true)

    @Test
    fun `Expanded双页_渲染翻页与工具栏切换`() {
        val id = seedBook("双页", longParagraphs(30))
        composeRule.setContent {
            PaperReadTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.EXPANDED) {
                    ReaderScreen(bookId = id, onBack = {})
                }
            }
        }
        // 双页分支渲染：章首段出现在视口（左页）
        // Robolectric 在内存受限的串行全量跑中渲染可能超过 1s；15s 仅防挂死，不作为性能断言
        composeRule.waitUntilAtLeastOneExists(
            hasText("这是第 1 段正文", substring = true),
            timeoutMillis = 15_000L,
        )
        firstParagraphNode().assertExists()

        // 点按右区（第三区）：翻到下一 spread，首段离开视口
        clickZone(5f / 6f)
        composeRule.waitForIdle()
        firstParagraphNode().assertDoesNotExist()
        composeRule.onNodeWithText("这是第 3 段正文", substring = true).assertExists()

        // 点按左区（第一区）：回翻到上一 spread，首段回到视口
        clickZone(1f / 6f)
        composeRule.waitForIdle()
        firstParagraphNode().assertExists()

        // 点按中央（第二区）：切换工具栏，顶栏「开始朗读」入口消失
        composeRule.onNodeWithContentDescription("开始朗读").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("开始朗读").assertDoesNotExist()
        // 再点一次恢复
        clickZone(0.5f)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("开始朗读").assertExists()
    }

    @Test
    fun `Compact单栏_不受双页分支影响`() {
        val id = seedBook("单栏", longParagraphs(6))
        composeRule.setContent { PaperReadTheme { ReaderScreen(bookId = id, onBack = {}) } }
        // 默认 COMPACT：连续滚动渲染首段，工具栏入口在
        // Robolectric 在内存受限的串行全量跑中渲染可能超过 1s；15s 仅防挂死，不作为性能断言
        composeRule.waitUntilAtLeastOneExists(
            hasText("这是第 1 段正文", substring = true),
            timeoutMillis = 15_000L,
        )
        composeRule.onNodeWithContentDescription("开始朗读").assertExists()
    }

    @Test
    fun `rememberChapterPages_空输入与装箱`() {
        var empty: List<PageBreak>? = null
        var packed: List<PageBreak>? = null
        composeRule.setContent {
            val density = LocalDensity.current
            val resolver = LocalFontFamilyResolver.current
            val style = TextStyle(fontSize = 18.sp, textAlign = TextAlign.Justify)
            empty = rememberChapterPages(
                paragraphs = emptyList(),
                textStyle = style,
                pageWidthPx = 200,
                pageHeightPx = 400,
                density = density,
                fontFamilyResolver = resolver,
                paragraphSpacingPx = 8,
            )
            packed = rememberChapterPages(
                paragraphs = listOf("短段落一", "短段落二", "短段落三"),
                textStyle = style,
                pageWidthPx = 200,
                pageHeightPx = 400,
                density = density,
                fontFamilyResolver = resolver,
                paragraphSpacingPx = 8,
            )
        }
        composeRule.waitForIdle()
        assertTrue("空段落列表应返回空分页", empty!!.isEmpty())
        assertTrue("短段落应至少装出一页", packed!!.isNotEmpty())
        assertEquals(0, packed!!.first().startParagraph)
    }

    @Test
    fun `窗口宽度档位_默认Compact`() {
        var cls: WindowWidthClass? = null
        composeRule.setContent { cls = rememberWindowWidthClass() }
        composeRule.waitForIdle()
        // Robolectric 默认窗口宽度约 320dp < 600dp
        assertEquals(WindowWidthClass.COMPACT, cls)
    }

    @Test
    @Config(qualifiers = "w700dp-h400dp")
    fun `窗口宽度档位_700dp映射Medium`() {
        var cls: WindowWidthClass? = null
        composeRule.setContent { cls = rememberWindowWidthClass() }
        composeRule.waitForIdle()
        assertEquals(WindowWidthClass.MEDIUM, cls)
    }

    @Test
    @Config(qualifiers = "w900dp-h600dp")
    fun `窗口宽度档位_900dp映射Expanded`() {
        var cls: WindowWidthClass? = null
        composeRule.setContent { cls = rememberWindowWidthClass() }
        composeRule.waitForIdle()
        assertEquals(WindowWidthClass.EXPANDED, cls)
    }
}
