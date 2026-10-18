package io.github.lunalight.paperread.ui.smoke

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.data.settings.ReaderSettings
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.reader.FileBookTextStore
import io.github.lunalight.paperread.ui.bookshelf.BookshelfScreen
import io.github.lunalight.paperread.ui.reader.ReaderScreen
import io.github.lunalight.paperread.ui.reader.ReaderSettingsSheet
import io.github.lunalight.paperread.ui.reader.ReaderUiState
import io.github.lunalight.paperread.ui.reader.TocSheet
import io.github.lunalight.paperread.ui.theme.PaperReadTheme
import io.github.lunalight.paperread.ui.theme.ReaderThemes
import io.github.lunalight.paperread.ui.transfer.TransferScreen
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
 * B3 Compose UI 冒烟测试（Robolectric + createComposeRule，无需真机）。
 *
 * 有状态屏幕（书架 / 阅读器）走真实 AppContainer：Room 与文本存储落在
 * Robolectric 文件系统上，@Before 后预置数据；无状态弹层（设置 / 目录）
 * 直接以受控参数驱动。
 *
 * 覆盖（对应 ROADMAP B3）：
 * 1. 空书架导入入口可见
 * 2. 书架渲染书籍并可点击打开
 * 3. 点击正文区域切换工具栏显隐
 * 4. 设置面板调整字号（UI 回流验证）
 * 5. 书签 Tab 空态
 * 6. 传书页骨架渲染（本机标识 / 待发送书目 / 在线设备，AC-9.1）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class UiSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: PaperReadApp

    @Before
    fun setUp() {
        // Robolectric 每个测试类全新 Application，仓储状态天然隔离
        app = ApplicationProvider.getApplicationContext()
    }

    // ---- 预置 ----

    private val smokeContent = "第一章 起点\n这是第一段正文。\n这是第二段正文。"

    /** 写入 book.txt + 入库一本书（一章一段），返回书籍 id。 */
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

    // ---- 用例 ----

    /**
     * 渲染断言：节点存在于语义树且尺寸非零。
     * 不用 assertIsDisplayed：Robolectric 下其对合并节点（如 FAB）的
     * visible-frame 计算有误报（节点 bounds 在窗口内仍判 not displayed），
     * exists + 尺寸对冒烟测试已足够（交互链路由后续 performClick 验证）。
     */
    private fun SemanticsNodeInteraction.assertRendered() = apply {
        assertExists()
        val bounds = fetchSemanticsNode().boundsInRoot
        assertTrue("节点应有非零尺寸，实际 $bounds", bounds.width > 0f && bounds.height > 0f)
    }

    @Test
    fun `空书架显示导入入口`() {
        composeRule.setContent {
            PaperReadTheme { BookshelfScreen(onOpenSettings = {}, onOpenBook = {}, onStartTransfer = {}) }
        }
        // 首帧是加载态（CircularProgressIndicator），Room 空流到达后才切空态。
        // waitForIdle 只保证 Compose 空闲、不保证异步状态已到达，CI 高负载下
        // 曾偶发拿到加载帧即断言失败（attempt 1 红 / attempt 2 同代码绿）。
        // 显式等待节点出现再断言，超时放到 30s：除容忍慢 runner 外，
        // @GraphicsMode(NATIVE) 每类首次创建 RenderNode 时 Robolectric 需在主线程
        // 解压拷贝 native 字体（约 3-5s，着陆点随机），CI 慢机器上会计入等待窗口。
        composeRule.waitUntilAtLeastOneExists(hasText("书架空空如也"), timeoutMillis = 30_000)
        composeRule.onNodeWithText("书架空空如也").assertRendered()
        composeRule.waitUntilAtLeastOneExists(hasText("选择文件"), timeoutMillis = 30_000)
        composeRule.onNodeWithText("选择文件").assertRendered()
        // FAB 文本未并入合并语义树，waitUntilAtLeastOneExists 只查合并树，
        // 改用 waitUntil + 未合并树查询等待其出现
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("导入书籍", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("导入书籍", useUnmergedTree = true).assertRendered()
    }

    @Test
    fun `书架渲染书籍并可点击打开`() {
        val id = seedBook()
        var opened: Long? = null
        composeRule.setContent {
            PaperReadTheme { BookshelfScreen(onOpenSettings = {}, onOpenBook = { opened = it }, onStartTransfer = {}) }
        }
        composeRule.waitUntilAtLeastOneExists(hasText("冒烟之书"))
        composeRule.onNodeWithText("冒烟之书").assertRendered()
        // 书名在书封卡片外（兄弟节点），点击落在封面上才触发打开；
        // 封面有格式角标「TXT」，点它冒泡到 Card 的 combinedClickable
        composeRule.onNodeWithText("TXT").performClick()
        composeRule.waitForIdle()
        assertEquals("点击书封应回调打开", id, opened)
    }

    @Test
    fun `点击正文区域切换工具栏显隐`() {
        val id = seedBook()
        composeRule.setContent {
            PaperReadTheme { ReaderScreen(bookId = id, onBack = {}) }
        }
        composeRule.waitUntilAtLeastOneExists(hasText("这是第一段正文。"))

        // 初始 chrome 可见：顶栏目录图标在
        composeRule.onNodeWithContentDescription("目录").assertIsDisplayed()

        // 点击正文 → 工具栏隐藏
        composeRule.onNodeWithText("这是第一段正文。").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("目录").assertDoesNotExist()

        // 再点正文 → 工具栏恢复
        composeRule.onNodeWithText("这是第二段正文。").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("目录").assertIsDisplayed()
    }

    @Test
    fun `设置面板拖动字号滑块更新排版`() {
        val settingsState = mutableStateOf(ReaderSettings())
        composeRule.setContent {
            PaperReadTheme {
                ReaderSettingsSheet(
                    settings = settingsState.value,
                    onUpdate = { transform -> settingsState.value = transform(settingsState.value) },
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("18 sp").assertRendered()

        // sheet 内容高于视口，先滚到排版区再操作字号 slider
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

        assertTrue("字号应较默认 18 sp 增大", settingsState.value.fontSizeSp > 18)
        composeRule.onNodeWithText("18 sp").assertDoesNotExist()
        composeRule.onNodeWithText("${settingsState.value.fontSizeSp} sp").assertRendered()
    }

    @Test
    fun `书签Tab空态提示`() {
        val book = Book(
            id = 1L,
            title = "冒烟之书",
            author = "冒烟作者",
            format = BookFormat.TXT,
            encoding = "UTF-8",
            sha256 = "smoke",
            fileSize = 100L,
            dirName = "smoke",
            totalChars = 100L,
            chapterCount = 1,
            progressPercent = 0f,
            lastReadAt = null,
            addedAt = 1L,
        )
        val chapter = Chapter(
            bookId = 1L,
            index = 0,
            title = "第一章 起点",
            charStart = 0,
            charEnd = 100,
            byteStart = 0,
            byteEnd = 100,
            charCount = 100,
            cumulativeChars = 0,
        )
        val state = ReaderUiState(
            loading = false,
            book = book,
            chapters = listOf(chapter),
            chapterIndex = 0,
            bookmarks = emptyList(),
        )
        composeRule.setContent {
            PaperReadTheme {
                TocSheet(
                    state = state,
                    theme = ReaderThemes.byId("paper"),
                    onSelect = {},
                    onBookmarkSelect = {},
                    onBookmarkDelete = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithText("书签 · 0").performClick()
        composeRule.onNodeWithText("还没有书签").assertIsDisplayed()
        composeRule.onNodeWithText("阅读时点击工具栏星标即可添加").assertIsDisplayed()
    }

    /**
     * 传书页冒烟（AC-9.1）：断言只取「有 WiFi / 无 WiFi」两条服务路径下
     * 都必然渲染的静态内容——服务状态本身依赖宿主网络（CI 无 wlan 网卡走
     * 「未接入 WiFi」错误分支），不做状态断言，避免环境相关抖动。
     *
     * 待发送书目来自书架流（Room 异步），标题行为「· <title>」，
     * 故用 substring 匹配 + 显式等待。
     */
    @Test
    fun `传书页渲染本机标识与待发送书目`() {
        val id = seedBook()
        composeRule.setContent {
            PaperReadTheme { TransferScreen(bookIds = listOf(id), onBack = {}) }
        }
        composeRule.waitUntilAtLeastOneExists(hasText("局域网传书"), timeoutMillis = 30_000)
        composeRule.onNodeWithText("局域网传书").assertRendered()
        composeRule.waitUntilAtLeastOneExists(hasText("本机标识"), timeoutMillis = 30_000)
        composeRule.onNodeWithText("本机标识").assertRendered()
        composeRule.waitUntilAtLeastOneExists(hasText("待发送书籍"), timeoutMillis = 30_000)
        composeRule.onNodeWithText("待发送书籍").assertRendered()
        composeRule.waitUntilAtLeastOneExists(hasText("在线设备"), timeoutMillis = 30_000)
        composeRule.onNodeWithText("在线设备").assertRendered()
        // 书架流到达后，待发送列表出现所传书籍
        composeRule.waitUntilAtLeastOneExists(hasText("冒烟之书", substring = true), timeoutMillis = 30_000)
    }

    /**
     * 书架 → 传书的两个入口（AC-9.3 入口侧）：
     * 1. overflow「局域网传书」：未多选时传空列表，进页面仅待命发现；
     * 2. 长按进入多选后顶栏出现发送按钮，回调携带所选书籍 id。
     */
    @Test
    fun `书架多选后出现传书入口并可回调所选书籍`() {
        val id = seedBook()
        var transferred: List<Long>? = null
        composeRule.setContent {
            PaperReadTheme {
                BookshelfScreen(
                    onOpenSettings = {},
                    onOpenBook = {},
                    onStartTransfer = { transferred = it },
                )
            }
        }
        composeRule.waitUntilAtLeastOneExists(hasText("冒烟之书"))
        // overflow 菜单里的「局域网传书」：不进多选也能进入（待命发现模式）
        composeRule.onNodeWithContentDescription("排序").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("局域网传书"))
        composeRule.onNodeWithText("局域网传书").performClick()
        composeRule.waitForIdle()
        assertEquals("未多选时应传空列表（仅待命发现）", emptyList<Long>(), transferred)

        // 长按进入多选 → 顶栏出现发送入口
        composeRule.onNodeWithText("TXT").performTouchInput { longClick(center) }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("传书到其他设备").assertRendered()
        composeRule.onNodeWithContentDescription("传书到其他设备").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(id), transferred)
    }
}
