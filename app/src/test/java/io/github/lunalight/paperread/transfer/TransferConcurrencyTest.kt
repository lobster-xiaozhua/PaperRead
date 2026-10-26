package io.github.lunalight.paperread.transfer

import io.github.lunalight.paperread.data.repository.ImportError
import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.data.repository.PrepareResult
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 传输服务线程模型并发单测（T-005 / R-10 / G10）。
 *
 * 回归「大书解析占着 accept 循环」的旧结构：改造前 [TransferHttpServer.serve]
 * 顺序处理连接，实现方（[DefaultLanTransferManager]）又在 handler 内
 * runBlocking 跑导入解析——一次大书接收期间，对端的第二本书 / 同步请求
 * 只能排队干等，直至客户端读超时。
 *
 * 覆盖三个层面：
 * 1. 协议层：handler 挂起期间服务继续 accept 新连接（limitedParallelism 派发）；
 * 2. 编排层：onUpload 落盘即应答，prepare 转后台且各结局错误上浮不丢；
 * 3. 编排层：取消接收会取消后台 prepare，放行后不得回写状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferConcurrencyTest : TransferFlowTestBase() {

    override val discoveryPort = 45474

    private lateinit var rawServer: TransferHttpServer
    private lateinit var serverThread: Thread
    private lateinit var pool: ExecutorService
    private lateinit var spillDir: File

    /** 协议层服务端的鉴权门（配对走它，与 TransferHttpServerTest 同构）。 */
    private lateinit var serverGate: PairingGate

    /** 协议层测试的挂起门：handler 进入后放行前一直挂起（模拟大书解析）。 */
    private lateinit var release: CompletableDeferred<Unit>
    private lateinit var entered: CountDownLatch

    /** 已进入 handler 的上传次数（仅第一次挂起，后续连接放行）。 */
    private val uploadCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 协议层 gated handler：首个上传挂起，其余立即应答。 */
    private val gatedHandler = object : TransferHttpHandler {
        override suspend fun meta(): String = """{"ok":true,"deviceName":"并发机"}"""

        override suspend fun onUpload(fileName: String, contentLength: Long, bodyFile: File): String? {
            if (uploadCount.incrementAndGet() == 1) {
                entered.countDown()
                release.await()
            }
            return null
        }

        override suspend fun onBundle(fileName: String, contentLength: Long, bodyFile: File): String? = null

        override suspend fun syncState(): String = """{"version":1,"deviceId":"me","books":[]}"""

        override suspend fun onSyncApply(payload: String): String? = null
    }

    @Before
    fun setUpConcurrency() {
        spillDir = Files.createTempDirectory("concurrency-spill").toFile()
        serverGate = PairingGate(selfDeviceId = { "self-device" })
        release = CompletableDeferred()
        entered = CountDownLatch(1)
        rawServer = TransferHttpServer(
            bindAddress = InetAddress.getLoopbackAddress(),
            port = 0,
            handler = gatedHandler,
            tokenVerifier = serverGate,
            spillDir = spillDir,
        )
        serverThread = Thread { rawServer.serve() }.apply { start() }
        pool = Executors.newFixedThreadPool(2)
    }

    @After
    fun tearDownConcurrency() {
        pool.shutdownNow()
        rawServer.close()
        serverThread.join(5_000)
        spillDir.deleteRecursively()
    }

    private fun rawPort(): Int = rawServer.actualPort

    @Test
    fun `handler 挂起期间服务继续 accept 新连接（limitedParallelism 派发）`() {
        // 展示端配对：候选 secret 探测落定（与 TransferHttpServerTest 同构）
        val code = serverGate.beginPairing()
        val secret = deriveSecret(code, listOf("peer-a", "self-device"))
        val probe = TransferClient().probePairing("127.0.0.1", rawPort(), secret, "peer-a")
        assertEquals(PairingProbeResult.Paired, probe)
        val client = TransferClient(secretProvider = { secret }, selfDeviceId = { "peer-a" })

        val big = tempFile("conc_big", "x".repeat(64 * 1024))
        val small = tempFile("conc_small", "y".repeat(1024))

        // 连接 1：字节落盘后 handler 挂在门上（模拟大书 TXT 解析）
        val first = pool.submit<UploadResult> {
            client.upload("127.0.0.1", rawPort(), big, "挂起书.txt")
        }
        assertTrue("handler 应进入挂起点", entered.await(5, TimeUnit.SECONDS))

        // 连接 1 尚未应答：连接 2 必须能被 accept 并走完全程
        // （改造前顺序处理连接，此处会阻塞至客户端读超时）
        val second = client.upload("127.0.0.1", rawPort(), small, "并发书.txt")
        assertEquals("连接 1 挂起期间连接 2 应成功：$second", UploadResult.Success, second)

        // 放行连接 1：应答 200
        release.complete(Unit)
        assertEquals(UploadResult.Success, first.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun `落盘即应答后 prepare 失败错误上浮（DRM）`() {
        val identity = DeviceIdentityStore(context)
        val manager = createManager(identityStore = identity)
        manager.startAndAwaitRunning()
        val secret = manager.pairAsSender(identity)
        val client = clientFor(secret)

        importer.prepareGate = CompletableDeferred()
        importer.prepareResult = PrepareResult.Failure(ImportError.DrmEncrypted("encrypted"))
        val file = tempFile("drm_up", "x".repeat(2048))

        // 落盘即应答：prepare 挂在门上，上传仍应立即成功（R-10 / G10）
        assertEquals(
            UploadResult.Success,
            client.upload(loopbackHost, manager.httpPort(), file, "DRM 书.txt"),
        )
        // prepare 已在后台跑起来但被门挂住：尚无结论，状态不被抢先写坏
        waitUntil { importer.prepareCalls.size == 1 }
        assertNull(manager.receiveState.value.pending)
        assertNull(manager.receiveState.value.error)

        // 放行：失败原因经 receiveState 上浮（对端早已拿到 200，提示不丢）
        importer.prepareGate?.complete(Unit)
        waitUntil { manager.receiveState.value.error != null }
        assertEquals("该 EPUB 含 DRM 加密，无法导入", manager.receiveState.value.error)
        assertNull(manager.receiveState.value.pending)
    }

    @Test
    fun `连续上传顶替未确认的上一本（单槽语义）`() {
        val identity = DeviceIdentityStore(context)
        val manager = createManager(identityStore = identity)
        manager.startAndAwaitRunning()
        val secret = manager.pairAsSender(identity)
        val client = clientFor(secret)

        importer.prepareResult = PrepareResult.Success(pendingImport("slot-1"))
        val first = tempFile("slot_one", "x".repeat(512))
        assertEquals(
            UploadResult.Success,
            client.upload(loopbackHost, manager.httpPort(), first, "第一本.txt"),
        )
        waitUntil { manager.receiveState.value.pending?.sha256 == "slot-1" }

        // 第二本交叠到达：顶替第一本的待确认槽（取消其 pending、清理其临时文件）
        importer.prepareResult = PrepareResult.Success(pendingImport("slot-2"))
        val second = tempFile("slot_two", "y".repeat(512))
        assertEquals(
            UploadResult.Success,
            client.upload(loopbackHost, manager.httpPort(), second, "第二本.txt"),
        )
        waitUntil { manager.receiveState.value.pending?.sha256 == "slot-2" }
        assertEquals("第一本的 pending 应被作废", listOf("slot-1"), importer.cancelledPendings.map { it.sha256 })
        assertEquals(2, importer.prepareCalls.size)
    }

    @Test
    fun `后台 prepare 崩溃时错误上浮且不影响已应答的上传`() {
        val identity = DeviceIdentityStore(context)
        val manager = createManager(identityStore = identity)
        manager.startAndAwaitRunning()
        val secret = manager.pairAsSender(identity)
        val client = clientFor(secret)

        importer.failNextImport = true // prepare 抛 IOException（导入流水线崩溃）
        val file = tempFile("crash_up", "x".repeat(1024))
        // 字节已送达：对端拿到 200；崩溃的是本机解析，经 receiveState 上浮
        assertEquals(
            UploadResult.Success,
            client.upload(loopbackHost, manager.httpPort(), file, "崩溃书.txt"),
        )
        waitUntil { manager.receiveState.value.error != null }
        assertEquals("解析失败：模拟导入流水线崩溃", manager.receiveState.value.error)
        assertNull(manager.receiveState.value.pending)
    }

    @Test
    fun `取消接收取消后台 prepare，放行后不回写状态`() {
        val identity = DeviceIdentityStore(context)
        val manager = createManager(identityStore = identity)
        manager.startAndAwaitRunning()
        val secret = manager.pairAsSender(identity)
        val client = clientFor(secret)

        importer.prepareGate = CompletableDeferred()
        importer.prepareResult = PrepareResult.Success(pendingImport("cancel-up"))
        val file = tempFile("cancel_up", "x".repeat(1024))
        assertEquals(
            UploadResult.Success,
            client.upload(loopbackHost, manager.httpPort(), file, "取消书.txt"),
        )
        waitUntil { importer.prepareCalls.size == 1 }

        // 用户在 prepare 出结果前取消：后台作业一并取消
        manager.cancelReceived()
        assertEquals(TransferReceiveState(), manager.receiveState.value)

        // 放行被取消的 prepare：不得把状态改写回 pending / error
        importer.prepareGate?.complete(Unit)
        val deadline = System.currentTimeMillis() + 300
        while (System.currentTimeMillis() < deadline) {
            assertEquals(TransferReceiveState(), manager.receiveState.value)
            Thread.sleep(20)
        }
    }

    private fun pendingImport(sha256: String) = PendingImport(
        dirName = "dir-$sha256",
        displayName = "书.txt",
        title = "取消的书",
        author = "未知",
        format = BookFormat.TXT,
        charsetName = "UTF-8",
        strategy = SplitStrategy.CONSERVATIVE,
        sha256 = sha256,
        fileSize = 16L,
        totalChars = 16L,
        chapters = emptyList(),
    )
}
