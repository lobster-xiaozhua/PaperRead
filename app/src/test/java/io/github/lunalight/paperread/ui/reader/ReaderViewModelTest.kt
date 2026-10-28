package io.github.lunalight.paperread.ui.reader

import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.fakes.FakeBookRepository
import io.github.lunalight.paperread.fakes.FakeBookTextStore
import io.github.lunalight.paperread.fakes.FakeSettingsStore
import io.github.lunalight.paperread.fakes.FakeSpeechController
import io.github.lunalight.paperread.speech.SpeechPhase
import io.github.lunalight.paperread.speech.SpeechText
import io.github.lunalight.paperread.reader.ReaderProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.lunalight.paperread.ui.UiText

/**
 * ReaderViewModel 单测（纯 JVM，无 Robolectric）：
 * 防抖落库 / 切章立即落库 / 恢复 / 书签 / 设置流。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {

    /** 单章单元表的安全上界（测试数据远小于它，仅防死循环）。 */
    private val maxUnitsPerChapter = 64

    private val dispatcher = StandardTestDispatcher()

    private lateinit var repository: FakeBookRepository
    private lateinit var textStore: FakeBookTextStore
    private lateinit var settingsStore: FakeSettingsStore
    private lateinit var speechController: FakeSpeechController
    private lateinit var viewModel: ReaderViewModel

    private val book = Book(
        id = 1L,
        title = "测试书",
        author = "未知",
        format = BookFormat.TXT,
        encoding = "UTF-8",
        sha256 = "abc",
        fileSize = 1000L,
        dirName = "dir-1",
        totalChars = 3000L,
        chapterCount = 3,
        lastChapterIndex = 1,
        lastParagraphIndex = 2,
        lastScrollOffset = 15,
        lastCharOffset = 6,
        progressPercent = 0f,
        lastReadAt = 123L,
        addedAt = 100L,
    )

    private val chapters = listOf(
        chapter(index = 0, cumulative = 0L, charStart = 0L, byteStart = 0L),
        chapter(index = 1, cumulative = 1000L, charStart = 1000L, byteStart = 2000L),
        chapter(index = 2, cumulative = 2000L, charStart = 2000L, byteStart = 4000L),
    )

    private fun chapter(index: Int, cumulative: Long, charStart: Long, byteStart: Long) = Chapter(
        id = index.toLong(),
        bookId = 1L,
        index = index,
        title = "第 ${index + 1} 章",
        charStart = charStart,
        charEnd = charStart + 1000,
        byteStart = byteStart,
        byteEnd = byteStart + 2000,
        charCount = 1000L,
        cumulativeChars = cumulative,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeBookRepository().apply {
            this.book = this@ReaderViewModelTest.book
            this.chapters = this@ReaderViewModelTest.chapters
        }
        textStore = FakeBookTextStore().apply {
            // 四段，每段 2 字符：章内偏移表 = [0, 3, 6, 9, 12]
            chapterText = { _, _ -> "一段\n二段\n三段\n四段" }
        }
        settingsStore = FakeSettingsStore()
        speechController = FakeSpeechController()
        viewModel = ReaderViewModel(
            bookId = 1L,
            bookRepository = repository,
            bookTextStore = textStore,
            settingsStore = settingsStore,
            speechController = speechController,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 模拟引擎把当前单元表逐个读完，直到触发章尾收尾。
     *
     * 从阅读位置起播时单元表未必只剩一个单元（恢复段落在章中），
     * 单次 [FakeSpeechController.finishCurrentUnit] 只会向前推进一格；
     * 收尾后控制器回 IDLE，循环即停（onFinished 里排的协程尚未执行，
     * 不会顺势把下一章也读完）。
     */
    private fun finishAllUnits() {
        repeat(maxUnitsPerChapter) {
            if (!speechController.current().isActive) return
            speechController.finishCurrentUnit()
        }
    }

    @Test
    fun `加载后恢复章段与像素偏移并计算进度`() = runTest(dispatcher) {
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("加载完成", state.loading)
        assertNull(state.error)
        assertEquals(book, state.book)
        assertEquals(3, state.chapters.size)
        assertEquals("恢复章下标", 1, state.chapterIndex)
        assertEquals(listOf("一段", "二段", "三段", "四段"), state.paragraphs)
        assertEquals("恢复段下标", 2, state.initialParagraph)
        assertEquals("恢复像素偏移", 15, state.initialScrollOffset)
        assertEquals(
            ReaderProgress.percentOf(1000L, ReaderProgress.chapterCharOffset(state.paragraphStarts, 2), 3000L),
            state.progressPercent,
            1e-4f,
        )
    }

    @Test
    fun `越界的恢复位置被夹取到有效范围`() = runTest(dispatcher) {
        repository.book = book.copy(lastChapterIndex = 99, lastParagraphIndex = 99)
        viewModel = ReaderViewModel(1L, repository, textStore, settingsStore, speechController)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("章下标夹到末章", 2, state.chapterIndex)
        assertEquals("段下标夹到末段", 3, state.initialParagraph)
    }

    @Test
    fun `书籍不存在时进入错误态`() = runTest(dispatcher) {
        repository.book = null
        viewModel = ReaderViewModel(1L, repository, textStore, settingsStore, speechController)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals(UiText.res(R.string.reader_error_book_missing), state.error)
    }

    @Test
    fun `章节为空时进入错误态`() = runTest(dispatcher) {
        repository.chapters = emptyList()
        viewModel = ReaderViewModel(1L, repository, textStore, settingsStore, speechController)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertEquals(UiText.res(R.string.reader_error_no_chapters), state.error)
    }

    @Test
    fun `滚动位置防抖 500ms 只落库最后一次`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.onScrollPosition(3)
        advanceTimeBy(300)
        assertTrue("未到 500ms 不落库", repository.savedPositions.isEmpty())

        viewModel.onScrollPosition(2)
        advanceTimeBy(400)
        assertTrue("重新计时后仍未到点", repository.savedPositions.isEmpty())

        advanceTimeBy(150)
        assertEquals("只落库一次", 1, repository.savedPositions.size)
        val saved = repository.savedPositions.single()
        assertEquals("落库最后位置", 2, saved.position.paragraphIndex)
        assertEquals("像素偏移默认 0", 0, saved.position.scrollOffset)
        assertEquals(1L, saved.bookId)
        assertEquals("进度随位置更新", 1, viewModel.uiState.value.chapterIndex)
    }

    @Test
    fun `切章立即落库并取消防抖`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.onScrollPosition(3)
        advanceTimeBy(100) // 防抖计时中
        viewModel.nextChapter() // 加载后停在第 2 章（index=1）→ 第 3 章（index=2）
        advanceUntilIdle()

        assertEquals("防抖被取消，仅切章一次落库", 1, repository.savedPositions.size)
        val saved = repository.savedPositions.single()
        assertEquals("新章下标", 2, saved.position.chapterIndex)
        assertEquals("章首段", 0, saved.position.paragraphIndex)
        assertEquals("读了新章文本", chapters[2], textStore.readChapterCalls.last().second)
        val state = viewModel.uiState.value
        assertEquals(2, state.chapterIndex)
        assertEquals(0, state.currentParagraph)
        assertEquals(false, state.tocVisible)
    }

    @Test
    fun `同章内跳到首段也会重新定位`() = runTest(dispatcher) {
        advanceUntilIdle()
        val before = viewModel.uiState.value
        // 先滚动到第 3 段，模拟「用户读到章中，再点章首书签」
        viewModel.onScrollPosition(2)
        advanceUntilIdle()
        assertTrue("先离开章首", viewModel.uiState.value.currentParagraph == 2)

        viewModel.goToChapter(before.chapterIndex, paragraph = 0)
        advanceUntilIdle()

        val after = viewModel.uiState.value
        assertEquals("章不变", before.chapterIndex, after.chapterIndex)
        assertEquals("定位回章首", 0, after.initialParagraph)
        assertTrue(
            "恢复票据必须自增，否则 UI 的 LaunchedEffect 不会重放",
            after.restoreSeq > before.restoreSeq,
        )
        assertEquals("章首落库", 0, repository.savedPositions.last().position.paragraphIndex)
    }

    @Test
    fun `正文文件缺失时给出可操作错误态而非空白页`() = runTest(dispatcher) {
        // 章节索引还在，但 book.txt 不存在（云备份恢复后的典型状态）
        textStore.textAvailable = false
        textStore.chapterText = { _, _ -> "" }
        viewModel = ReaderViewModel(1L, repository, textStore, settingsStore, speechController)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loading)
        assertTrue("标记为正文缺失（错误页提供删除入口）", state.textMissing)
        assertEquals(ReaderViewModel.TEXT_MISSING_MESSAGE, state.error)
        assertTrue("书名等元信息仍可用", state.book != null)
    }

    @Test
    fun `正文在但本章为空时不引导删书`() = runTest(dispatcher) {
        // 文件存在、章节也声称有内容，却按偏移读不出字：属索引损坏，可重新解析
        textStore.textAvailable = true
        textStore.chapterText = { _, _ -> "" }
        viewModel = ReaderViewModel(1L, repository, textStore, settingsStore, speechController)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("不应提示删除条目", state.textMissing)
        assertEquals(ReaderViewModel.CHAPTER_EMPTY_MESSAGE, state.error)
    }

    @Test
    fun `空章不算正文缺失`() = runTest(dispatcher) {
        // charCount = 0 的章本来就该是空：不能被误判成文件丢失
        repository.chapters = chapters.map { it.copy(charCount = 0L, charEnd = it.charStart) }
        textStore.chapterText = { _, _ -> "" }
        viewModel = ReaderViewModel(1L, repository, textStore, settingsStore, speechController)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull("不报错", state.error)
        assertFalse(state.textMissing)
        assertTrue(state.paragraphs.isEmpty())
    }

    @Test
    fun `防抖窗口内退出可由 flush 补存位置`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.onScrollPosition(3)
        advanceTimeBy(100) // 防抖尚未触发
        assertTrue(repository.savedPositions.isEmpty())

        viewModel.flushPendingSave()
        advanceUntilIdle()

        assertEquals("补存一次", 1, repository.savedPositions.size)
        assertEquals(3, repository.savedPositions.single().position.paragraphIndex)

        // 已写出的位置不应被重复补存
        viewModel.flushPendingSave()
        advanceUntilIdle()
        assertEquals("无新位置时不重复写", 1, repository.savedPositions.size)
    }

    @Test
    fun `边界章不重复落库`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.goToChapter(0)
        advanceUntilIdle()
        viewModel.previousChapter() // 已在第一章：应被忽略
        advanceUntilIdle()

        assertEquals("仅一次切章落库", 1, repository.savedPositions.size)
        assertEquals(0, repository.savedPositions.single().position.chapterIndex)
        assertEquals(0, viewModel.uiState.value.chapterIndex)
    }

    @Test
    fun `书签增删与书签流更新`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.onScrollPosition(2)
        advanceUntilIdle() // 防抖落库一次（与书签无关）
        viewModel.addBookmark()
        advanceUntilIdle()

        val added = repository.addedBookmarks.single()
        assertEquals("书签锚在当前章", 1, added.chapterIndex)
        assertEquals("书签锚在当前段", 2, added.paragraphIndex)
        assertEquals("摘录取自当前段", "三段", added.excerpt)
        assertEquals(
            "字符偏移与进度口径一致",
            ReaderProgress.chapterCharOffset(viewModel.uiState.value.paragraphStarts, 2),
            added.charOffset,
        )
        assertEquals(1L, added.bookId)
        assertEquals(UiText.res(R.string.reader_bookmark_added), viewModel.uiState.value.message)

        // 仓储流推送 → UI 状态同步（倒序由仓储保证）
        val stored = added.copy(id = 7L)
        repository.emitBookmarks(listOf(stored))
        advanceUntilIdle()
        assertEquals(listOf(stored), viewModel.uiState.value.bookmarks)

        viewModel.deleteBookmark(7L)
        advanceUntilIdle()
        assertEquals(listOf(7L), repository.deletedBookmarkIds)

        viewModel.dismissMessage()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `跳转书签恢复章与段`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.goToBookmark(
            Bookmark(
                id = 7L,
                bookId = 1L,
                chapterIndex = 2,
                paragraphIndex = 1,
                charOffset = 0,
                excerpt = "摘录",
                createdAt = 1L,
            ),
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.chapterIndex)
        assertEquals(1, state.currentParagraph)
        val saved = repository.savedPositions.last()
        assertEquals(2, saved.position.chapterIndex)
        assertEquals(1, saved.position.paragraphIndex)
    }

    @Test
    fun `设置更新经 VM 写入并回流到状态`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.updateSettings { it.copy(fontSizeSp = 22) }
        advanceUntilIdle()

        assertEquals(22, settingsStore.current().fontSizeSp)
        assertEquals(22, viewModel.uiState.value.settings.fontSizeSp)
    }

    @Test
    fun `工具栏与目录面板开关`() = runTest(dispatcher) {
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.chromeVisible)
        viewModel.toggleChrome()
        assertFalse(viewModel.uiState.value.chromeVisible)

        viewModel.showToc()
        assertTrue(viewModel.uiState.value.tocVisible)
        assertTrue("打开目录时工具栏可见", viewModel.uiState.value.chromeVisible)
        viewModel.hideToc()
        assertFalse(viewModel.uiState.value.tocVisible)

        viewModel.showSettings()
        assertTrue(viewModel.uiState.value.settingsVisible)
        viewModel.hideSettings()
        assertFalse(viewModel.uiState.value.settingsVisible)
    }

    @Test
    fun `错误可被消除`() = runTest(dispatcher) {
        repository.book = null
        viewModel = ReaderViewModel(1L, repository, textStore, settingsStore, speechController)
        advanceUntilIdle()
        assertEquals(UiText.res(R.string.reader_error_book_missing), viewModel.uiState.value.error)

        viewModel.dismissError()
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `B4 进度按章内原文偏移计算而非 trim 后长度`() = runTest(dispatcher) {
        // 章原文：2 空行 + "  一段"(前导空白) + 空行 + "二段"
        // 原始起点：空行累计 2 → "一段" 段首 4；+4+1+1 → "二段" 起点 8
        textStore.chapterText = { _, _ -> "\n\n  一段\n\n二段" }
        viewModel = ReaderViewModel(1L, repository, textStore, settingsStore, speechController)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("一段", "二段"), state.paragraphs)
        assertEquals("段落携带原始起点", listOf(4, 8), state.paragraphStarts)
        // lastParagraphIndex=2 越界夹到 1 → 取「二段」起点 8（trim 口径会算成 0）
        assertEquals(
            ReaderProgress.percentOf(1000L, 8, 3000L),
            state.progressPercent,
            1e-4f,
        )
    }

    @Test
    fun `翻页模式按页起始段落库并折算字符偏移`() = runTest(dispatcher) {
        advanceUntilIdle()
        // 章内段落偏移表 = [0, 3, 6, 9]（四段，每段 2 字符），翻到第 4 段（下标 3）所在页
        assertEquals(listOf(0, 3, 6, 9), viewModel.uiState.value.paragraphStarts)
        viewModel.onPagePosition(3)
        advanceTimeBy(600)
        advanceUntilIdle()

        val saved = repository.savedPositions.last()
        assertEquals(3, saved.position.paragraphIndex)
        assertEquals(9, saved.position.charOffset) // paragraphStarts[3]，charOffset 为 Int
        assertEquals(1, saved.position.chapterIndex)
        assertEquals(3, viewModel.uiState.value.currentParagraph)
    }

    @Test
    fun `翻页模式越界起始段被夹取到末段`() = runTest(dispatcher) {
        advanceUntilIdle()
        // 段落下标越界（99）→ 夹到末段 3，不抛错、不乱写
        viewModel.onPagePosition(99)
        advanceTimeBy(600)
        advanceUntilIdle()
        assertEquals(3, repository.savedPositions.last().position.paragraphIndex)
    }

    // ---- 朗读（TTS）编排 ----

    @Test
    fun `开始朗读从当前可见段构建单元表并开播`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.onScrollPosition(2)
        advanceUntilIdle()

        viewModel.startSpeech()
        advanceUntilIdle()

        val call = speechController.speakCalls.single()
        assertEquals("四段各成一个单元", 4, call.units.size)
        assertEquals("从当前段（第 3 段）起读", 2, call.startUnitIndex)
        assertEquals("p2-0", call.units[2].utteranceId)
        val speech = viewModel.uiState.value.speech
        assertTrue(speech.isSpeaking)
        assertEquals("高亮跟随到朗读段", 2, speech.speakingParagraph)
    }

    @Test
    fun `播放暂停切换与停止`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.toggleSpeechPlayPause()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.speech.isSpeaking)

        viewModel.toggleSpeechPlayPause()
        advanceUntilIdle()
        assertTrue("再切一次应暂停", viewModel.uiState.value.speech.isPaused)
        assertEquals(1, speechController.pauseCalls)

        viewModel.toggleSpeechPlayPause()
        advanceUntilIdle()
        assertTrue("第三次恢复播放", viewModel.uiState.value.speech.isSpeaking)
        assertEquals(1, speechController.resumeCalls)

        viewModel.stopSpeech()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.speech.isActive)
    }

    @Test
    fun `空白段被跳过时从其后可读段开始`() = runTest(dispatcher) {
        textStore.chapterText = { _, _ -> "一段\n\n \n三段" }
        viewModel = ReaderViewModel(1L, repository, textStore, settingsStore, speechController)
        advanceUntilIdle()
        viewModel.startSpeech(fromParagraph = 1)
        advanceUntilIdle()

        val call = speechController.speakCalls.single()
        assertEquals("空白段不成单元", listOf("一段", "三段"), call.units.map { it.text })
        assertEquals("第 1 段是空白，落到第 2 个可读单元", 1, call.startUnitIndex)
    }

    @Test
    fun `章尾自动续读进入下一章并立即开播`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.startSpeech()
        advanceUntilIdle()

        // 模拟引擎读完本章最后一个单元（恢复段落在章中，需逐个读完）
        finishAllUnits()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("自动进入下一章", 2, state.chapterIndex)
        assertEquals("新章有新的 speak 调用", 2, speechController.speakCalls.size)
        assertTrue(state.speech.isSpeaking)
        assertEquals("新章从章首开播", 0, state.speech.currentUnitIndex)
    }

    @Test
    fun `关闭自动续读时章尾停止并提示`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.updateTtsAutoNextChapter(false)
        advanceUntilIdle()
        viewModel.startSpeech()
        advanceUntilIdle()

        finishAllUnits()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.speech.isActive)
        assertEquals(UiText.res(R.string.speech_chapter_finished), state.message)
    }

    @Test
    fun `朗读中切章不断播`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.startSpeech()
        advanceUntilIdle()

        viewModel.nextChapter()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.chapterIndex)
        assertTrue("新章继续朗读", state.speech.isSpeaking)
        assertEquals("新章重新开播", 2, speechController.speakCalls.size)
    }

    @Test
    fun `段步进按单元前后移动`() = runTest(dispatcher) {
        advanceUntilIdle()
        viewModel.startSpeech()
        advanceUntilIdle()
        val start = viewModel.uiState.value.speech.currentUnitIndex

        viewModel.speechSkipUnit(1)
        advanceUntilIdle()
        assertEquals(start + 1, viewModel.uiState.value.speech.currentUnitIndex)

        viewModel.speechSkipUnit(-1)
        advanceUntilIdle()
        assertEquals(start, viewModel.uiState.value.speech.currentUnitIndex)
    }

    @Test
    fun `语速音调改动持久化并同步控制器`() = runTest(dispatcher) {
        advanceUntilIdle()

        viewModel.updateTtsRate(1.5f)
        viewModel.updateTtsPitch(0.8f)
        advanceUntilIdle()

        assertEquals(1.5f, settingsStore.current().ttsRate, 1e-4f)
        assertEquals(0.8f, settingsStore.current().ttsPitch, 1e-4f)
        assertEquals("设置流回流后同步到控制器", 1.5f, speechController.current().rate, 1e-4f)
        assertEquals(0.8f, speechController.current().pitch, 1e-4f)
    }

    @Test
    fun `系统 TTS 不可用时状态透出原因`() = runTest(dispatcher) {
        advanceUntilIdle()
        // 模拟引擎初始化失败：控制器发布 unavailableReason
        speechController.simulateUnavailable("系统缺少中文语音数据")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("系统缺少中文语音数据", state.speech.unavailableReason)
    }

    @Test
    fun `长章按渲染段落逐段成单元且不超上限`() = runTest(dispatcher) {
        // 无标点超长章：ReaderProgress 先按 400 字符软切为多段（渲染层口径），
        // 每段再各自成朗读单元；段内二次切分（>3500 单段）由 SpeechTextTest
        // 纯单测覆盖——VM 层段落永远短于上限，这里只验集成口径。
        val long = "啊".repeat(SpeechText.MAX_UNIT_CHARS + 500)
        textStore.chapterText = { _, _ -> long }
        viewModel = ReaderViewModel(1L, repository, textStore, settingsStore, speechController)
        advanceUntilIdle()
        viewModel.startSpeech()
        advanceUntilIdle()

        val paragraphs = viewModel.uiState.value.paragraphs
        val call = speechController.speakCalls.single()
        assertEquals("每个渲染段落一个单元", paragraphs.size, call.units.size)
        assertTrue("每个单元不超过上限", call.units.all { it.text.length <= SpeechText.MAX_UNIT_CHARS })
        assertEquals("从恢复段落（第 3 段）起读", 2, call.startUnitIndex)
    }

    // ---- 朗读权限场景化（T-009 · AC-20.1 / AC-20.6） ----

    /** 构造「通知权限未授权」的 VM（AC-20.1 场景入口）。 */
    private fun viewModelWithoutNotificationPermission(): ReaderViewModel =
        ReaderViewModel(
            bookId = 1L,
            bookRepository = repository,
            bookTextStore = textStore,
            settingsStore = settingsStore,
            speechController = speechController,
            hasNotificationPermission = { false },
        )

    @Test
    fun `未授权时首次朗读先弹场景对话框而不起播`() = runTest(dispatcher) {
        viewModel = viewModelWithoutNotificationPermission()
        advanceUntilIdle()

        viewModel.startSpeech(fromParagraph = 1)
        advanceUntilIdle()

        val event = viewModel.uiState.value.speechPermissionEvent
        assertTrue("应发射场景对话框事件", event is SpeechPermissionEvent.ShowPrompt)
        assertEquals("对话框携带待朗读起始段", 1, (event as SpeechPermissionEvent.ShowPrompt).fromParagraph)
        assertTrue("对话框展示期间不得起播", speechController.speakCalls.isEmpty())
    }

    @Test
    fun `场景对话框确认后转入系统申请且延后起播`() = runTest(dispatcher) {
        viewModel = viewModelWithoutNotificationPermission()
        advanceUntilIdle()
        viewModel.startSpeech(fromParagraph = 1)
        advanceUntilIdle()

        viewModel.onSpeechPermissionPromptConfirm()
        advanceUntilIdle()

        val event = viewModel.uiState.value.speechPermissionEvent
        assertTrue("确认后应请求系统申请", event is SpeechPermissionEvent.LaunchSystemRequest)
        assertEquals(1, (event as SpeechPermissionEvent.LaunchSystemRequest).fromParagraph)
        assertTrue("系统申请结果回来前不得起播", speechController.speakCalls.isEmpty())

        // UI 拉起系统申请后消费事件（防重组重复触发）
        viewModel.onSpeechPermissionEventConsumed()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.speechPermissionEvent)
    }

    @Test
    fun `系统申请被拒后朗读照常且给出降级提示`() = runTest(dispatcher) {
        viewModel = viewModelWithoutNotificationPermission()
        advanceUntilIdle()
        viewModel.startSpeech(fromParagraph = 1)
        advanceUntilIdle()
        viewModel.onSpeechPermissionPromptConfirm()
        advanceUntilIdle()

        viewModel.onNotificationPermissionResult(granted = false)
        advanceUntilIdle()

        assertTrue("拒绝后朗读仍可播放", speechController.speakCalls.isNotEmpty())
        assertEquals(
            "应给出降级提示",
            ReaderViewModel.NOTIFICATION_PERMISSION_DEGRADED_MESSAGE,
            viewModel.uiState.value.message,
        )
    }

    @Test
    fun `系统申请授权后直接起播无降级提示`() = runTest(dispatcher) {
        var granted = false
        viewModel = ReaderViewModel(
            bookId = 1L,
            bookRepository = repository,
            bookTextStore = textStore,
            settingsStore = settingsStore,
            speechController = speechController,
            hasNotificationPermission = { granted },
        )
        advanceUntilIdle()
        viewModel.startSpeech()
        advanceUntilIdle()
        viewModel.onSpeechPermissionPromptConfirm()
        advanceUntilIdle()

        granted = true // 用户授权后探测翻绿
        viewModel.onNotificationPermissionResult(granted = true)
        advanceUntilIdle()

        assertTrue(speechController.speakCalls.isNotEmpty())
        assertNull("授权不应有降级提示", viewModel.uiState.value.message)
    }

    @Test
    fun `场景对话框取消后不申请权限但朗读照常`() = runTest(dispatcher) {
        viewModel = viewModelWithoutNotificationPermission()
        advanceUntilIdle()

        viewModel.startSpeech(fromParagraph = 1)
        advanceUntilIdle()
        viewModel.onSpeechPermissionPromptDismiss()
        advanceUntilIdle()

        assertNull("对话框应消失", viewModel.uiState.value.speechPermissionEvent)
        assertTrue("取消场景申请不阻塞朗读", speechController.speakCalls.isNotEmpty())
        assertEquals(
            ReaderViewModel.NOTIFICATION_PERMISSION_DEGRADED_MESSAGE,
            viewModel.uiState.value.message,
        )
    }

    @Test
    fun `问过之后再次朗读不再弹对话框直接起播`() = runTest(dispatcher) {
        viewModel = viewModelWithoutNotificationPermission()
        advanceUntilIdle()

        viewModel.startSpeech()
        advanceUntilIdle()
        viewModel.onSpeechPermissionPromptDismiss()
        advanceUntilIdle()

        viewModel.stopSpeech()
        advanceUntilIdle()
        speechController.speakCalls.clear()
        viewModel.startSpeech()
        advanceUntilIdle()

        assertNull("已问过则不再弹场景对话框", viewModel.uiState.value.speechPermissionEvent)
        assertTrue("再次朗读直接起播", speechController.speakCalls.isNotEmpty())
    }

    @Test
    fun `权限被系统撤销后起播降级提示且不崩`() = runTest(dispatcher) {
        // AC-20.6：已授权状态下起播后权限被撤（探测结果翻转为 false），
        // 再次起播应降级提示、朗读不受影响、无异常。
        var granted = true
        viewModel = ReaderViewModel(
            bookId = 1L,
            bookRepository = repository,
            bookTextStore = textStore,
            settingsStore = settingsStore,
            speechController = speechController,
            hasNotificationPermission = { granted },
        )
        advanceUntilIdle()
        viewModel.startSpeech()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.message)

        granted = false // 系统侧撤销
        viewModel.stopSpeech()
        advanceUntilIdle()
        viewModel.startSpeech()
        advanceUntilIdle()

        assertTrue("撤销后朗读仍可播放", speechController.speakCalls.isNotEmpty())
        assertEquals(
            "撤销后应提示降级",
            ReaderViewModel.NOTIFICATION_PERMISSION_DEGRADED_MESSAGE,
            viewModel.uiState.value.message,
        )
    }
}
