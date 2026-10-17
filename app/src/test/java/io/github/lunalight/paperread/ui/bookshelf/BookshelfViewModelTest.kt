package io.github.lunalight.paperread.ui.bookshelf

import android.net.Uri
import app.cash.turbine.test
import io.github.lunalight.paperread.data.repository.ImportError
import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.data.repository.PrepareResult
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.fakes.FakeBookImporter
import io.github.lunalight.paperread.fakes.FakeBookRepository
import io.github.lunalight.paperread.fakes.FakeBookTextStore
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BookshelfViewModel 单测（Robolectric 提供 android.net.Uri）：
 * 导入状态机（进度 / 取消 / 判重替换 / 确认 / 取消待确认）、
 * 重新解析、排序、书架流。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookshelfViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = UnconfinedTestDispatcher(scheduler)

    private lateinit var repository: FakeBookRepository
    private lateinit var importer: FakeBookImporter
    private lateinit var textStore: FakeBookTextStore
    private lateinit var viewModel: BookshelfViewModel

    private val uri: Uri = Uri.parse("content://io.github.lunalight.paperread.test/novel.txt")

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        repository = FakeBookRepository()
        importer = FakeBookImporter()
        textStore = FakeBookTextStore()
        viewModel = BookshelfViewModel(repository, importer, textStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- 工具 ----

    private fun pending(
        dirName: String = "dir-new",
        title: String = "测试书",
        strategy: SplitStrategy = SplitStrategy.CONSERVATIVE,
    ) = PendingImport(
        dirName = dirName,
        displayName = "$title.txt",
        title = title,
        author = "未知",
        format = BookFormat.TXT,
        charsetName = "UTF-8",
        strategy = strategy,
        sha256 = "abc",
        fileSize = 1000L,
        totalChars = 3000L,
        chapters = emptyList(),
    )

    private fun book(
        id: Long,
        title: String,
        lastReadAt: Long?,
        addedAt: Long,
        dirName: String = "dir-$id",
        progressPercent: Float = 0f,
    ) = Book(
        id = id,
        title = title,
        author = "未知",
        format = BookFormat.TXT,
        encoding = "UTF-8",
        sha256 = "sha-$id",
        fileSize = 1000L,
        dirName = dirName,
        totalChars = 3000L,
        chapterCount = 3,
        progressPercent = progressPercent,
        lastReadAt = lastReadAt,
        addedAt = addedAt,
    )

    // ---- 导入状态机 ----

    @Test
    fun `导入运行态汇报阶段并在成功后转入待确认`() = runTest(mainDispatcher) {
        val gate = CompletableDeferred<Unit>()
        importer.prepareGate = gate
        val expected = pending()
        importer.prepareResult = PrepareResult.Success(expected)

        viewModel.uiState.test {
            assertEquals(false, awaitItem().import.running)

            viewModel.importBook(uri)
            val running = awaitItem().import
            assertTrue("进入运行态", running.running)
            assertEquals("复制文件", running.stageLabel)

            gate.complete(Unit)
            advanceUntilIdle()
            val done = awaitItem().import
            assertFalse("导入结束退出运行态", done.running)
            assertEquals(expected, done.pending)

            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, importer.prepareCalls.size)
    }

    @Test
    fun `取消导入重置状态并提示`() = runTest(mainDispatcher) {
        importer.prepareGate = CompletableDeferred() // 挂起 prepare
        viewModel.importBook(uri)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.import.running)

        viewModel.cancelRunningImport()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("退出运行态", state.import.running)
        assertNull("无待确认", state.import.pending)
        assertEquals("已取消导入", state.message)
    }

    @Test
    fun `导入运行中忽略重复触发`() = runTest(mainDispatcher) {
        importer.prepareGate = CompletableDeferred()
        viewModel.importBook(uri)
        viewModel.importBook(uri)
        advanceUntilIdle()

        assertEquals("只启动一次导入", 1, importer.prepareCalls.size)
    }

    @Test
    fun `判重命中弹出替换对话框`() = runTest(mainDispatcher) {
        val existing = book(id = 2L, title = "旧书", lastReadAt = 50L, addedAt = 5L)
        importer.prepareResult = PrepareResult.Duplicate(existing)

        viewModel.importBook(uri)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(existing, state.duplicateTarget)
        assertFalse("退出运行态", state.import.running)
    }

    @Test
    fun `替换导入先删旧书再用原参数重跑`() = runTest(mainDispatcher) {
        val existing = book(id = 2L, title = "旧书", lastReadAt = 50L, addedAt = 5L)
        importer.prepareResult = PrepareResult.Duplicate(existing)
        viewModel.importBook(uri, SplitStrategy.AGGRESSIVE)
        advanceUntilIdle()

        importer.prepareResult = PrepareResult.Success(pending())
        viewModel.replaceDuplicate()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull("对话框关闭", state.duplicateTarget)
        assertEquals("旧书被删除", listOf(existing), repository.deletedBooks)
        assertEquals("旧书目录被清理", listOf("dir-2"), textStore.deletedDirs)
        assertEquals("原参数重跑", 2, importer.prepareCalls.size)
        assertEquals(uri, importer.prepareCalls[1].uri)
        assertEquals(SplitStrategy.AGGRESSIVE, importer.prepareCalls[1].strategy)
        assertEquals("已替换《旧书》，正在导入", state.message)
    }

    @Test
    fun `取消判重仅关闭对话框`() = runTest(mainDispatcher) {
        val existing = book(id = 2L, title = "旧书", lastReadAt = 50L, addedAt = 5L)
        importer.prepareResult = PrepareResult.Duplicate(existing)
        viewModel.importBook(uri)
        advanceUntilIdle()

        viewModel.dismissDuplicate()

        assertNull(viewModel.uiState.value.duplicateTarget)
        assertTrue("不触发删除", repository.deletedBooks.isEmpty())
        assertEquals("不重跑导入", 1, importer.prepareCalls.size)
    }

    @Test
    fun `确认入库清空待确认并提示`() = runTest(mainDispatcher) {
        val expected = pending(title = "测试书")
        importer.prepareResult = PrepareResult.Success(expected)
        viewModel.importBook(uri)
        advanceUntilIdle()

        viewModel.confirmImport()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("入库的是待确认书籍", listOf(expected), importer.confirmed)
        assertNull(state.import.pending)
        assertFalse(state.import.running)
        assertEquals("已导入《测试书》", state.message)
    }

    @Test
    fun `取消待确认删除临时目录`() = runTest(mainDispatcher) {
        val expected = pending()
        importer.prepareResult = PrepareResult.Success(expected)
        viewModel.importBook(uri)
        advanceUntilIdle()

        viewModel.cancelImport()

        assertEquals("删除临时目录", listOf(expected), importer.cancelledPendings)
        assertNull(viewModel.uiState.value.import.pending)
        assertFalse(viewModel.uiState.value.import.running)
    }

    @Test
    fun `dismissImport 同样取消待确认`() = runTest(mainDispatcher) {
        val expected = pending()
        importer.prepareResult = PrepareResult.Success(expected)
        viewModel.importBook(uri)
        advanceUntilIdle()

        viewModel.dismissImport()

        assertEquals(listOf(expected), importer.cancelledPendings)
        assertNull(viewModel.uiState.value.import.pending)
    }

    @Test
    fun `不支持的格式映射用户话术`() = runTest(mainDispatcher) {
        importer.prepareResult =
            PrepareResult.Unsupported(ImportError.UnsupportedFormat("pdf"))

        viewModel.importBook(uri)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("仅支持 TXT / EPUB 格式", state.message)
        assertFalse(state.import.running)
    }

    @Test
    fun `失败原因映射用户话术`() = runTest(mainDispatcher) {
        importer.prepareResult = PrepareResult.Failure(ImportError.InsufficientSpace)

        viewModel.importBook(uri)
        advanceUntilIdle()

        assertEquals("存储空间不足，无法导入", viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.import.running)
    }

    @Test
    fun `DRM 与空间不足失败话术可区分`() = runTest(mainDispatcher) {
        // B7 验收：不同失败类型在结果页（Snackbar）文案可区分
        importer.prepareResult = PrepareResult.Failure(
            ImportError.DrmEncrypted("EncryptedData found"),
        )
        viewModel.importBook(uri)
        advanceUntilIdle()
        val drmMessage = viewModel.uiState.value.message
        assertEquals("该 EPUB 含 DRM 加密，无法导入", drmMessage)

        viewModel.dismissMessage()
        importer.prepareResult = PrepareResult.Failure(ImportError.InsufficientSpace)
        viewModel.importBook(uri)
        advanceUntilIdle()
        val spaceMessage = viewModel.uiState.value.message

        assertTrue("两类失败话术应不同", drmMessage != spaceMessage)
    }

    @Test
    fun `编码失败话术可区分`() = runTest(mainDispatcher) {
        // B7 验收：编码失败（BOM 与内容不符）既区别于 DRM 也区别于空间不足
        importer.prepareResult = PrepareResult.Failure(ImportError.DecodeFailed("UTF-8"))

        viewModel.importBook(uri)
        advanceUntilIdle()

        val message = viewModel.uiState.value.message
        assertEquals("文件编码异常，请转换编码后重试", message)
        assertTrue(message != ImportError.InsufficientSpace.userMessage)
        assertTrue(message != ImportError.DrmEncrypted("x").userMessage)
    }

    @Test
    fun `结果页切换编码与策略后重新解析`() = runTest(mainDispatcher) {
        val original = pending()
        importer.prepareResult = PrepareResult.Success(original)
        viewModel.importBook(uri)
        advanceUntilIdle()

        val reparsed = original.copy(charsetName = "GB18030", strategy = SplitStrategy.AGGRESSIVE)
        importer.reparseResult = reparsed
        viewModel.reparsePending("GB18030", SplitStrategy.AGGRESSIVE)
        advanceUntilIdle()

        assertEquals(reparsed, viewModel.uiState.value.import.pending)
        val call = importer.reparseCalls.single()
        assertEquals(original, call.first)
        assertEquals("GB18030", call.second)
        assertEquals(SplitStrategy.AGGRESSIVE, call.third)
    }

    @Test
    fun `重新解析失败提示且保留原待确认`() = runTest(mainDispatcher) {
        val original = pending()
        importer.prepareResult = PrepareResult.Success(original)
        viewModel.importBook(uri)
        advanceUntilIdle()

        importer.reparseResult = null
        viewModel.reparsePending("UTF-16LE", SplitStrategy.CONSERVATIVE)
        advanceUntilIdle()

        assertEquals("重新解析失败，请重试", viewModel.uiState.value.message)
        assertEquals("原待确认保留", original, viewModel.uiState.value.import.pending)
    }

    // ---- 书架管理 ----

    @Test
    fun `书籍流更新并按排序展示`() = runTest(mainDispatcher) {
        // 书名用 ASCII，避免中文按码点排序（丙<乙<甲）造成的歧义
        val b1 = book(id = 1L, title = "Delta", lastReadAt = 100L, addedAt = 10L)
        val b2 = book(id = 2L, title = "Alpha", lastReadAt = null, addedAt = 20L)
        val b3 = book(id = 3L, title = "Charlie", lastReadAt = 200L, addedAt = 30L)

        repository.emitBooks(listOf(b1, b2, b3))
        advanceUntilIdle()
        assertEquals("默认最近阅读", listOf(b3, b1, b2), viewModel.uiState.value.books)
        assertFalse(viewModel.uiState.value.loading)

        viewModel.setSortOrder(SortOrder.TITLE)
        assertEquals("按书名", listOf(b2, b3, b1), viewModel.uiState.value.books)

        viewModel.setSortOrder(SortOrder.ADDED)
        assertEquals("按导入时间", listOf(b3, b2, b1), viewModel.uiState.value.books)

        viewModel.setSortOrder(SortOrder.PROGRESS)
        val p1 = b1.copy(progressPercent = 0.1f)
        val p2 = b2.copy(progressPercent = 0.9f)
        val p3 = b3.copy(progressPercent = 0.5f)
        repository.emitBooks(listOf(p1, p2, p3))
        advanceUntilIdle()
        assertEquals("按进度", listOf(p2, p3, p1), viewModel.uiState.value.books)
    }

    @Test
    fun `删除书籍联动仓储与文本目录`() = runTest(mainDispatcher) {
        val target = book(id = 1L, title = "乙", lastReadAt = 100L, addedAt = 10L)

        viewModel.deleteBook(target)
        advanceUntilIdle()

        assertEquals(listOf(target), repository.deletedBooks)
        assertEquals(listOf("dir-1"), textStore.deletedDirs)
    }

    @Test
    fun `重新解析书籍透传策略与结果`() = runTest(mainDispatcher) {
        val target = book(id = 1L, title = "乙", lastReadAt = 100L, addedAt = 10L)
        importer.reparseBookResult = ImportError.SourceMissing("dir-1")

        viewModel.requestReparse(target)
        advanceUntilIdle()
        assertEquals(target, viewModel.uiState.value.reparseTarget)

        viewModel.reparseBook(SplitStrategy.AGGRESSIVE)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull("对话框关闭", state.reparseTarget)
        assertEquals("失败原因映射", "源文件已丢失，请删除后重新导入", state.message)
        assertEquals(Pair(target, SplitStrategy.AGGRESSIVE), importer.reparsedBooks.single())
    }

    @Test
    fun `重新解析成功提示`() = runTest(mainDispatcher) {
        val target = book(id = 1L, title = "乙", lastReadAt = 100L, addedAt = 10L)
        importer.reparseBookResult = null

        viewModel.requestReparse(target)
        advanceUntilIdle()
        viewModel.reparseBook(SplitStrategy.AGGRESSIVE)
        advanceUntilIdle()

        assertEquals(
            "已按新策略重新解析《乙》（进度与书签已重置）",
            viewModel.uiState.value.message,
        )
        assertNull(viewModel.uiState.value.reparseTarget)
    }

    @Test
    fun `取消重新解析仅关闭对话框`() = runTest(mainDispatcher) {
        val target = book(id = 1L, title = "乙", lastReadAt = 100L, addedAt = 10L)

        viewModel.requestReparse(target)
        advanceUntilIdle()
        assertEquals(target, viewModel.uiState.value.reparseTarget)

        viewModel.dismissReparse()

        assertNull(viewModel.uiState.value.reparseTarget)
        assertTrue("不触发重新解析", importer.reparsedBooks.isEmpty())
    }

    @Test
    fun `消息可被消除`() = runTest(mainDispatcher) {
        importer.prepareResult =
            PrepareResult.Unsupported(ImportError.UnsupportedFormat("pdf"))
        viewModel.importBook(uri)
        advanceUntilIdle()
        assertEquals("仅支持 TXT / EPUB 格式", viewModel.uiState.value.message)

        viewModel.dismissMessage()
        assertNull(viewModel.uiState.value.message)
    }
}
