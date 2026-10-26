package io.github.lunalight.paperread.ui.smoke

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.isToggleable
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.fakes.FakeSpeechController
import io.github.lunalight.paperread.reader.FileBookTextStore
import io.github.lunalight.paperread.speech.SpeechPhase
import io.github.lunalight.paperread.speech.SpeechState
import io.github.lunalight.paperread.speech.SpeechUnit
import io.github.lunalight.paperread.ui.reader.ReaderScreen
import io.github.lunalight.paperread.ui.theme.PaperReadTheme
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 阅读器朗读 UI + 翻页模式交互测试（Robolectric + createComposeRule）。
 *
 * 与 UiSmokeTest 的分工：那边用真实 AppContainer 做冒烟；这里向
 * AppContainer.speechController（internal set，测试注入点）装入
 * [FakeSpeechController]，以确定态驱动 ReaderScreen 的朗读控制条全链路，
 * 并用多段正文把翻页模式撑出多页，覆盖点按分区 / 翻页落库 / 页码指示。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ReaderSpeechSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: PaperReadApp
    private lateinit var controller: FakeSpeechController

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        controller = FakeSpeechController()
        app.container.speechController = controller
        // 朗读权限场景化（T-009 · AC-20.1）：ReaderViewModel 经工厂注入真实
        // POST_NOTIFICATIONS 探测，Robolectric 默认未授权会让首次朗读落入
        // 场景对话框。本类聚焦朗读控制链路，setUp 预授权保持主路径不变
        // （对话框流程由 ReaderSpeechPermissionTest 专项覆盖）。
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun seedBook(title: String, paragraphs: List<String>): Long = runBlocking {
        val dirName = "speech-$title"
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
            sha256 = "speech-$title",
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

    @Test
    fun `顶栏开始朗读_控制条暂停继续步进停止全链路`() {
        val id = seedBook("链路", paragraphs(6))
        composeRule.setContent { PaperReadTheme { ReaderScreen(bookId = id, onBack = {}) } }
        composeRule.waitUntilAtLeastOneExists(hasText("这是第 1 段正文，长度足够参与排版计算。"))

        // 顶栏入口：开始朗读
        composeRule.onNodeWithContentDescription("开始朗读").performClick()
        composeRule.waitForIdle()

        // VM 用正文段落构建单元表交给控制器，进入 SPEAKING。
        // 章正文含标题行（readChapter 按 byteStart~byteEnd 整章读取，
        // scanParagraphs 不去标题行——真实书籍口径，朗读者先听到章名），
        // 故 6 段正文 + 标题行 = 7 个单元。
        assertEquals(1, controller.speakCalls.size)
        assertEquals(7, controller.speakCalls.single().units.size)
        assertEquals(0, controller.speakCalls.single().startUnitIndex)
        assertTrue(controller.state.value.isSpeaking)

        // 控制条出现：与顶栏「停止朗读」并存（两个同名入口）
        composeRule.onAllNodesWithContentDescription("停止朗读").assertCountEquals(2)
        composeRule.onNodeWithContentDescription("上一段").assertRendered()
        composeRule.onNodeWithContentDescription("暂停朗读").assertRendered()
        composeRule.onNodeWithContentDescription("下一段").assertRendered()
        composeRule.onNodeWithText("正在朗读").assertRendered()

        // 暂停 → 按钮态切换为「继续朗读」
        composeRule.onNodeWithContentDescription("暂停朗读").performClick()
        composeRule.waitForIdle()
        assertEquals(SpeechPhase.PAUSED, controller.state.value.phase)
        composeRule.onNodeWithText("已暂停").assertRendered()

        // 继续 → 回到 SPEAKING
        composeRule.onNodeWithContentDescription("继续朗读").performClick()
        composeRule.waitForIdle()
        assertEquals(SpeechPhase.SPEAKING, controller.state.value.phase)

        // 下一段：按发布态 currentUnitIndex(0) + 1 步进到单元 1
        composeRule.onNodeWithContentDescription("下一段").performClick()
        composeRule.waitForIdle()
        assertEquals(1, controller.state.value.currentUnitIndex)
        assertEquals(1, controller.state.value.speakingParagraph)

        // 上一段：回到单元 0
        composeRule.onNodeWithContentDescription("上一段").performClick()
        composeRule.waitForIdle()
        assertEquals(0, controller.state.value.currentUnitIndex)

        // 停止（控制条键，索引 1；索引 0 是顶栏同名入口）→ 控制条收起、顶栏回到开始态
        composeRule.onAllNodesWithContentDescription("停止朗读")[1].performClick()
        composeRule.waitForIdle()
        assertEquals(SpeechPhase.IDLE, controller.state.value.phase)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("上一段").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithContentDescription("开始朗读").assertRendered()
    }

    /**
     * 等待 TTS 热调异步链到位：滑杆 onValueChange → VM 写 DataStore（IO 线程
     * 异步 edit）→ settings 流回流 → VM 同步控制器状态。
     *
     * waitForIdle 只排空「当前」主 looper，DataStore 写完成回调可能仍在 IO
     * 线程飞行中（全套跑时线程竞争更明显，曾在此处偶发断言抢跑、看到旧值），
     * 故 waitForIdle + 真实睡眠轮询兜底，10s 预算内不到位即报错。
     */
    private fun awaitTtsState(rate: Float, pitch: Float) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            composeRule.waitForIdle()
            val state = controller.state.value
            if (abs(state.rate - rate) < 0.001f && abs(state.pitch - pitch) < 0.001f) return
            Thread.sleep(10)
        }
        throw AssertionError(
            "TTS 热调状态未在 10s 内到位：期望 rate=$rate pitch=$pitch，" +
                "实际 rate=${controller.state.value.rate} pitch=${controller.state.value.pitch}",
        )
    }

    /** 轮询等待语义节点满足条件（异步回流兜底，同 [awaitTtsState] 的思路）。 */
    private fun awaitSemantics(timeoutSeconds: Long = 10, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            composeRule.waitForIdle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("语义条件未在 ${timeoutSeconds}s 内到位")
    }

    @Test
    fun `语速音调滑杆热调与章尾自动续读开关`() {
        val id = seedBook("滑杆", paragraphs(4))
        controller.speak(
            listOf(
                SpeechUnit("p0-0", paragraphs(4)[0], 0),
                SpeechUnit("p1-0", paragraphs(4)[1], 1),
                SpeechUnit("p2-0", paragraphs(4)[2], 2),
            ),
            0,
        )

        composeRule.setContent { PaperReadTheme { ReaderScreen(bookId = id, onBack = {}) } }
        composeRule.waitUntilAtLeastOneExists(hasText("这是第 1 段正文，长度足够参与排版计算。"))

        // 两根滑杆均在北京默认 1.0（ReaderSettings 默认值），按「组合顺序」区分：
        // 控制条语速 Row 先于音调 Row 组合，语义遍历序即 [语速, 音调]。
        // 不预置语速的另一个好处：down 落点在中点（50% 量程 = 1.25）位于起点
        // 拇指（33%）右侧，拖拽各次 onValueChange 单调递增，末次落点吸附 1.75。
        val sliders = composeRule.onAllNodes(
            hasProgressBarRangeInfo(ProgressBarRangeInfo(1.0f, 0.5f..2.0f, 5)),
        )
        sliders.assertCountEquals(2)
        sliders[0].performTouchInput {
            down(center)
            moveTo(center + percentOffset(0.1f, 0f))
            moveTo(center + percentOffset(0.2f, 0f))
            moveTo(center + percentOffset(0.3f, 0f))
            up()
        }
        awaitTtsState(rate = 1.75f, pitch = 1.0f)
        composeRule.waitUntilAtLeastOneExists(hasText("1.75x"))
        assertEquals(1.75f, controller.state.value.rate, 0.001f)
        // 持久化腿：DataStore 已落 1.75（控制器状态正是从该回流同步来的）
        val persisted = runBlocking { app.container.settingsStore.settings.first() }
        assertEquals(1.75f, persisted.ttsRate, 0.001f)

        // 音调滑杆：语速已是 1.75，仅剩音调为 1.0，按值唯一定位。
        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(1.0f, 0.5f..2.0f, 5)))
            .performTouchInput {
                down(center)
                moveTo(center + percentOffset(0.1f, 0f))
                moveTo(center + percentOffset(0.2f, 0f))
                moveTo(center + percentOffset(0.3f, 0f))
                up()
            }
        awaitTtsState(rate = 1.75f, pitch = 1.75f)
        composeRule.waitUntilAtLeastOneExists(hasText("1.75x"))
        assertEquals(1.75f, controller.state.value.pitch, 0.001f)
        // 两根滑杆的数值文本都已刷新为 1.75x
        composeRule.onAllNodesWithText("1.75x").assertCountEquals(2)

        // 章尾自动续读：默认开 → 点开关关闭 → 开关态翻转 + 落库。
        // 交互点选 Switch 节点而非整行文本：行 clickable 与正文 LazyColumn 的
        // clickable 在该区域重叠，合成手势下 up 的消费归属不稳定（实测行内点击
        // 时通时断）；Switch 是叶子节点，performClick 稳定落到 onCheckedChange，
        // 与行内点击走同一条产品路径（onAutoNextChange → VM → DataStore → UI）。
        awaitSemantics {
            composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes()
                .any { it.config[SemanticsProperties.ToggleableState] == ToggleableState.On }
        }
        composeRule.onNode(isToggleable()).assertIsOn()
        composeRule.onNode(isToggleable()).performClick()
        awaitSemantics {
            composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes()
                .none { it.config[SemanticsProperties.ToggleableState] == ToggleableState.On }
        }
        composeRule.onNode(isToggleable()).assertIsOff()
        val autoNextOff = runBlocking { app.container.settingsStore.settings.first() }
        assertEquals(false, autoNextOff.ttsAutoNextChapter)
    }

    @Test
    fun `翻页模式_点按分区翻页与页码指示`() {
        runBlocking { app.container.settingsStore.update { it.copy(pageTurnMode = true) } }
        val id = seedBook("翻页", paragraphs(30))
        composeRule.setContent { PaperReadTheme { ReaderScreen(bookId = id, onBack = {}) } }
        composeRule.waitUntilAtLeastOneExists(hasText("这是第 1 段正文，长度足够参与排版计算。"))

        val bounds = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val width = bounds.width
        val midY = bounds.height / 2f

        // 多页：页码指示在位（「1 / N」）
        composeRule.waitUntilAtLeastOneExists(hasText(" / ", substring = true))
        composeRule.onNode(hasText("1 / ", substring = true)).assertRendered()

        // 点按右 1/3 → 下一页（页码指示变 2 / N）
        composeRule.onRoot().performTouchInput {
            down(Offset(width * 0.85f, midY))
            up()
        }
        composeRule.waitUntilAtLeastOneExists(hasText("2 / ", substring = true))

        // 点按左 1/3 → 回上一页
        composeRule.onRoot().performTouchInput {
            down(Offset(width * 0.1f, midY))
            up()
        }
        composeRule.waitUntilAtLeastOneExists(hasText("1 / ", substring = true))

        // 横向滑动（右→左）→ 下一页（pager 自身消费滑动，观察者不拦）。
        // 分多步 move：单步大跳转时 pager 的拖拽检测拿不到足够的速度样本，
        // 半页偏移 + 零速度会停回原页（真实设备上同样是连续事件流）。
        composeRule.onRoot().performTouchInput {
            down(center)
            moveTo(center - Offset(width * 0.2f, 0f))
            moveTo(center - Offset(width * 0.4f, 0f))
            moveTo(center - Offset(width * 0.55f, 0f))
            up()
        }
        composeRule.waitUntilAtLeastOneExists(hasText("2 / ", substring = true))

        // 点按中区 → 工具栏显隐切换（目录图标消失）
        composeRule.onRoot().performTouchInput {
            down(Offset(width * 0.5f, midY))
            up()
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithContentDescription("目录").fetchSemanticsNodes().isEmpty()
        }
    }
}
