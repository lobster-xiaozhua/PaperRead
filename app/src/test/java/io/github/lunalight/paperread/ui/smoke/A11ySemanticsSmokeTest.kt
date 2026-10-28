package io.github.lunalight.paperread.ui.smoke

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.data.settings.ReaderSettings
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.reader.FileBookTextStore
import io.github.lunalight.paperread.ui.bookshelf.BookshelfScreen
import io.github.lunalight.paperread.ui.reader.PageTurnHandle
import io.github.lunalight.paperread.ui.reader.ReaderBottomBar
import io.github.lunalight.paperread.ui.reader.ReaderScreen
import io.github.lunalight.paperread.ui.reader.ReaderUiState
import io.github.lunalight.paperread.ui.reader.ReaderSettingsSheet
import io.github.lunalight.paperread.ui.theme.PaperReadTheme
import io.github.lunalight.paperread.ui.theme.ReaderThemes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
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
 * 无障碍基线冒烟（T-023 · US-22 AC-22.1 / AC-22.2 / AC-22.5）。
 *
 * 与 UiSmokeTest 同一套 Robolectric + createComposeRule 手法，断言「TalkBack
 * 依赖的语义树」而非像素：关键节点必须有 contentDescription、必须可点击 /
 * 可禁用。真机侧的 TalkBack 走查与 200% 字号人工项在 docs/DEVICE_CHECKLIST.md。
 *
 * 传书页设备项的朗读描述（a11y_transfer_device_item）不在本类覆盖：设备行
 * 只在发现对端后渲染，Robolectric 无网络对端可注入，由 TransferScreen 的
 * 生产语义实现（合并 contentDescription）+ 真机走查兜底。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class A11ySemanticsSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: PaperReadApp

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---- 预置（与 UiSmokeTest 同款：Room + 文本存储落在 Robolectric 文件系统） ----

    private val smokeContent = "第一章 起点\n这是第一段正文。\n这是第二段正文。"

    private fun seedBook(title: String = "冒烟之书"): Long = runBlocking {
        val dirName = "smoke-$title"
        val textStore = FileBookTextStore(app)
        textStore.textFile(dirName).apply { parentFile?.mkdirs() }.writeText(smokeContent)
        val bytes = smokeContent.toByteArray(Charsets.UTF_8)
        val chapter = Chapter(
            bookId = 0,
            index = 0,
            title = "第一章 起点",
            charStart = 0,
            charEnd = smokeContent.length.toLong(),
            byteStart = 0,
            byteEnd = bytes.size.toLong(),
            charCount = smokeContent.length.toLong(),
            cumulativeChars = 0,
        )
        val book = Book(
            id = 0,
            title = title,
            author = "冒烟作者",
            format = BookFormat.TXT,
            encoding = "UTF-8",
            sha256 = "smoke-$title",
            fileSize = bytes.size.toLong(),
            dirName = dirName,
            totalChars = smokeContent.length.toLong(),
            chapterCount = 1,
            progressPercent = 0f,
            lastReadAt = null,
            addedAt = 1L,
        )
        app.container.bookRepository.saveImport(book, listOf(chapter))
    }

    // ---- AC-22.1：顶栏入口全部可由 TalkBack 定位 ----

    @Test
    fun `阅读器顶栏入口全部有朗读标签`() {
        val id = seedBook()
        composeRule.setContent {
            PaperReadTheme { ReaderScreen(bookId = id, onBack = {}) }
        }
        composeRule.waitUntilAtLeastOneExists(hasText("这是第一段正文。"), timeoutMillis = 30_000)

        composeRule.onNodeWithContentDescription("返回").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("添加书签").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("开始朗读").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("搜索本书").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("目录").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("阅读设置").assertIsDisplayed()
    }

    // ---- AC-22.2：三区点按 / 滑动翻页的 TalkBack 替代入口 ----

    @Test
    fun `底栏翻页按钮是手势替代入口且首末页正确禁用`() {
        var previousClicked = false
        var nextClicked = false
        val pageTurn = PageTurnHandle().apply {
            bind(
                canPrevious = false,
                canNext = true,
                previous = { previousClicked = true },
                next = { nextClicked = true },
            )
        }
        val chapter = Chapter(
            bookId = 1,
            index = 0,
            title = "第一章 起点",
            charStart = 0,
            charEnd = 100,
            byteStart = 0,
            byteEnd = 100,
            charCount = 100,
            cumulativeChars = 0,
        )
        val state = ReaderUiState(loading = false, chapters = listOf(chapter))
        composeRule.setContent {
            PaperReadTheme {
                ReaderBottomBar(
                    state = state,
                    theme = ReaderThemes.byId("paper"),
                    onPrevious = {},
                    onNext = {},
                    pageTurn = pageTurn,
                )
            }
        }

        // 首页：「上一页」禁用（TalkBack 读作已禁用），「下一页」可点且触发翻页
        composeRule.onNodeWithContentDescription("上一页").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("下一页").assertIsEnabled().performClick()
        composeRule.waitForIdle()
        assertTrue("下一页按钮应触发翻页", nextClicked)

        // 页状态变化（翻过一页）后句柄重绑：「上一页」随之恢复可点
        pageTurn.bind(
            canPrevious = true,
            canNext = false,
            previous = { previousClicked = true },
            next = { nextClicked = true },
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("上一页").assertIsEnabled().performClick()
        composeRule.waitForIdle()
        assertTrue("上一页按钮应触发翻页", previousClicked)
        composeRule.onNodeWithContentDescription("下一页").assertIsNotEnabled()
    }

    @Test
    fun `连续滚动模式下底栏不渲染翻页按钮`() {
        val state = ReaderUiState(loading = false)
        composeRule.setContent {
            PaperReadTheme {
                ReaderBottomBar(
                    state = state,
                    theme = ReaderThemes.byId("paper"),
                    onPrevious = {},
                    onNext = {},
                    pageTurn = null,
                )
            }
        }

        // 手势替代入口与实际翻页能力一致：无翻页句柄（连续滚动）就没有翻页按钮，
        // TalkBack 用户不会被朗读出一个点了没反应的控件
        composeRule.onNodeWithContentDescription("上一页").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("下一页").assertDoesNotExist()
        // 切章按钮（滚动手势的替代入口）仍在
        composeRule.onNodeWithText("上一章").assertIsDisplayed()
        composeRule.onNodeWithText("下一章").assertIsDisplayed()
    }

    // ---- AC-22.1：书架书籍卡合并朗读（书名 + 进度一句话） ----

    @Test
    fun `书架书籍卡有合并朗读描述且可点击`() {
        seedBook()
        var opened: Long? = null
        composeRule.setContent {
            PaperReadTheme {
                BookshelfScreen(
                    onOpenSettings = {},
                    onOpenBook = { opened = it },
                    onStartTransfer = {},
                )
            }
        }
        composeRule.waitUntilAtLeastOneExists(hasText("冒烟之书"), timeoutMillis = 30_000)

        // 无进度书朗读为「书名，N 章」（a11y_bookshelf_book_item 拼装），
        // 整卡是一个可点击语义节点（mergeDescendants）
        composeRule.onNodeWithContentDescription("冒烟之书，1 章")
            .assertExists()
            .assertHasClickAction()
            .performClick()
        composeRule.waitForIdle()
        assertNotNull("点击书封卡片应回调打开", opened)
    }

    // ---- AC-22.1：设置开关行朗读「标题，副标题」 ----

    @Test
    fun `设置开关行有合并朗读描述`() {
        val settingsState = androidx.compose.runtime.mutableStateOf(ReaderSettings())
        composeRule.setContent {
            PaperReadTheme {
                ReaderSettingsSheet(
                    settings = settingsState.value,
                    onUpdate = { transform -> settingsState.value = transform(settingsState.value) },
                    onDismiss = {},
                )
            }
        }

        // SettingsSwitch 的朗读内容 = 「标题，副标题」（Switch 自身只报开/关）
        composeRule.onNodeWithContentDescription("阅读时常亮，阅读过程中保持屏幕点亮")
            .performScrollTo()
            .assertExists()
        composeRule.onNodeWithContentDescription("沉浸模式，隐藏系统状态栏与导航栏")
            .performScrollTo()
            .assertExists()
    }
}
