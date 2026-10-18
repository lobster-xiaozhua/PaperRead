package io.github.lunalight.paperread.ui.transfer

import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.fakes.FakeBookRepository
import io.github.lunalight.paperread.fakes.FakeLanTransferManager
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import io.github.lunalight.paperread.transfer.DiscoveredDevice
import io.github.lunalight.paperread.transfer.TransferReceiveState
import io.github.lunalight.paperread.transfer.TransferSendState
import io.github.lunalight.paperread.transfer.TransferServiceState
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

/**
 * TransferViewModel 单测（纯 JVM，无 Android 依赖）：
 * 服务生命周期（AC-9.1）、书目解析（AC-9.3）、发送 / 接收委托（AC-9.4 ~ AC-9.6）、
 * 一次性消息。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransferViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val mainDispatcher = UnconfinedTestDispatcher(scheduler)

    private lateinit var repository: FakeBookRepository
    private lateinit var manager: FakeLanTransferManager
    private lateinit var viewModel: TransferViewModel

    private val device = DiscoveredDevice(
        deviceId = "peer-1",
        deviceName = "对端设备",
        host = "192.168.1.5",
        httpPort = 45678,
        lastSeenAt = 0L,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        repository = FakeBookRepository()
        manager = FakeLanTransferManager()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(bookIds: List<Long> = emptyList()): TransferViewModel =
        TransferViewModel(bookIds, repository, manager)

    private fun book(id: Long, title: String) = Book(
        id = id,
        title = title,
        author = "未知",
        format = BookFormat.TXT,
        encoding = "UTF-8",
        sha256 = "sha-$id",
        fileSize = 1000L,
        dirName = "dir-$id",
        totalChars = 5000L,
        chapterCount = 10,
        addedAt = 100L,
    )

    // ---- 服务生命周期（AC-9.1） ----

    @Test
    fun `进入页面即启动服务并透传本机标识`() = runTest(mainDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        manager.emitService(
            TransferServiceState(
                running = true,
                deviceName = "我的手机",
                addressText = "192.168.1.2:39123",
            ),
        )
        advanceUntilIdle()

        assertEquals(1, manager.startServiceCount)
        assertTrue(viewModel.uiState.value.service.running)
        assertEquals("我的手机", viewModel.uiState.value.service.deviceName)
        assertEquals("192.168.1.2:39123", viewModel.uiState.value.service.addressText)
    }

    @Test
    fun `离开页面停止服务`() = runTest(mainDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.onCleared()

        assertEquals(1, manager.stopServiceCount)
    }

    @Test
    fun `启动失败后可重试`() = runTest(mainDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        manager.emitService(TransferServiceState(error = "未接入 WiFi，无法启动传书服务"))
        advanceUntilIdle()

        viewModel.retryService()

        assertEquals(2, manager.startServiceCount)
        assertEquals("未接入 WiFi，无法启动传书服务", viewModel.uiState.value.service.error)
    }

    // ---- 书目解析（AC-9.3） ----

    @Test
    fun `书目按传入顺序解析并随书架变化更新`() = runTest(mainDispatcher) {
        val books = listOf(book(1, "书一"), book(2, "书二"), book(3, "书三"))
        viewModel = createViewModel(bookIds = listOf(3, 1))
        advanceUntilIdle()

        // 初始空快照：已解析（书架确实没有），但列表为空
        assertTrue(viewModel.uiState.value.booksResolved)
        assertTrue(viewModel.uiState.value.books.isEmpty())

        repository.emitBooks(books)
        advanceUntilIdle()
        assertEquals(listOf(3L, 1L), viewModel.uiState.value.books.map { it.id })

        // 书一被删除后自动从待发送列表消失
        repository.emitBooks(books.filter { it.id != 1L })
        advanceUntilIdle()
        assertEquals(listOf(3L), viewModel.uiState.value.books.map { it.id })
    }

    @Test
    fun `空选择进入传书页时书目为空`() = runTest(mainDispatcher) {
        viewModel = createViewModel(bookIds = emptyList())
        repository.emitBooks(listOf(book(1, "书一")))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.books.isEmpty())
    }

    // ---- 发送（AC-9.3 / AC-9.4） ----

    @Test
    fun `选择设备后发送解析出的书籍`() = runTest(mainDispatcher) {
        viewModel = createViewModel(bookIds = listOf(1, 2))
        repository.emitBooks(listOf(book(1, "书一"), book(2, "书二")))
        advanceUntilIdle()

        viewModel.sendTo(device)

        val sent = manager.sentTargets.single()
        assertEquals(device, sent.target)
        assertEquals(listOf(1L, 2L), sent.books.map { it.id })
    }

    @Test
    fun `取消发送委托管理器`() = runTest(mainDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.cancelSend()

        assertEquals(1, manager.cancelSendCount)
    }

    @Test
    fun `发送进度与错误透传为一次性消息`() = runTest(mainDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        manager.emitSend(
            TransferSendState(active = true, currentTitle = "书一", index = 1, total = 2, fraction = 0.5f),
        )
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.send.active)
        assertEquals(0.5f, viewModel.uiState.value.send.fraction, 0.001f)

        manager.emitSend(TransferSendState(error = "《书一》发送失败：连接重置"))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.send.active)
        assertEquals("《书一》发送失败：连接重置", viewModel.uiState.value.message)

        viewModel.dismissMessage()
        assertNull(viewModel.uiState.value.message)
    }

    // ---- 接收（AC-9.5 / AC-9.6） ----

    @Test
    fun `接收进度透传`() = runTest(mainDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        manager.emitReceive(
            TransferReceiveState(receiving = true, fileName = "书一.txt", fraction = 0.25f),
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.receive.receiving)
        assertEquals("书一.txt", viewModel.uiState.value.receive.fileName)
        assertEquals(0.25f, viewModel.uiState.value.receive.fraction, 0.001f)
    }

    @Test
    fun `导入成功提示一次性消费`() = runTest(mainDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        manager.emitReceive(TransferReceiveState(importedTitle = "书一"))
        advanceUntilIdle()

        assertEquals("已导入《书一》", viewModel.uiState.value.message)
        assertEquals(1, manager.consumeImportedTitleCount)

        viewModel.dismissMessage()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun `接收错误转为一次性消息`() = runTest(mainDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        manager.emitReceive(TransferReceiveState(error = "传输不完整，已丢弃"))
        advanceUntilIdle()

        assertEquals("传输不完整，已丢弃", viewModel.uiState.value.message)
    }

    @Test
    fun `确认与取消接收委托管理器`() = runTest(mainDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.confirmReceived()
        viewModel.cancelReceived()

        assertEquals(1, manager.confirmReceivedCount)
        assertEquals(1, manager.cancelReceivedCount)
    }

    @Test
    fun `重新解析收到的书委托管理器`() = runTest(mainDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        viewModel.reparseReceived("GBK", SplitStrategy.AGGRESSIVE)

        val reparse = manager.reparses.single()
        assertEquals("GBK", reparse.charsetName)
        assertEquals(SplitStrategy.AGGRESSIVE, reparse.strategy)
    }

    @Test
    fun `设备列表透传`() = runTest(mainDispatcher) {
        viewModel = createViewModel()
        advanceUntilIdle()
        manager.emitDevices(listOf(device, device.copy(deviceId = "peer-2", deviceName = "另一台")))
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.devices.size)
        assertEquals("对端设备", viewModel.uiState.value.devices.first().deviceName)
    }
}
