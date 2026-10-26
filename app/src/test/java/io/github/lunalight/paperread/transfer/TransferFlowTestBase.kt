package io.github.lunalight.paperread.transfer

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.fakes.FakeBookImporter
import io.github.lunalight.paperread.fakes.FakeBookRepository
import io.github.lunalight.paperread.fakes.FakeBookTextStore
import java.io.File
import java.net.InetAddress
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * transfer 编排层流程单测的公共夹具（T-004 / R-04 / G4）。
 *
 * 与协议层单测（TransferHttpServerTest 等）的分工：那里直接驱服务端 +
 * 匿名 handler，覆盖鉴权矩阵与 HTTP 语义；这里经 [DefaultLanTransferManager]
 * 真实编排路径（服务启动 → 配对 → 上传 / 迁移 / 同步 → 状态流）驱动，
 * 正面覆盖评审指出的「编排层 870 行零直测」缺口：传输不完整、对端处理失败、
 * 重复导入、DRM、端口占用、取消迁移。
 *
 * 基础设施选择：
 * - Robolectric 提供 Context（cacheDir / filesDir / FileProvider 清单）；
 * - [DefaultLanTransferManager] 其余依赖全部 fake（导入 / 仓储 / 文本存取）；
 * - scope 用 `Dispatchers.Unconfined`：管理器内的 launch 在调用线程内联推进到
 *   首个挂起点，使「启动 / 配对 / 状态迁移」免轮询即可 deterministic；
 * - HTTP 服务 / 客户端为真实 socket（loopback），与生产同一代码路径；
 * - 沙箱内 UDP 广播常不可达：发现循环异常由 SupervisorJob + 静默
 *   exception handler 吸收（本夹具不测发现，见 LanDiscoveryTest）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
abstract class TransferFlowTestBase {

    protected lateinit var context: Context
    protected lateinit var importer: FakeBookImporter
    protected lateinit var repository: FakeBookRepository
    protected lateinit var textStore: FakeBookTextStore
    protected lateinit var scope: CoroutineScope

    /** 本测试类的默认发现端口（每类唯一，避免同 JVM 多实例争抢固定端口）。 */
    protected abstract val discoveryPort: Int

    private val managers = mutableListOf<DefaultLanTransferManager>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        importer = FakeBookImporter()
        repository = FakeBookRepository()
        textStore = FakeBookTextStore()
        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, _ ->
                // 发现循环广播失败等非关键异常：吸收，不影响用例断言
            },
        )
    }

    @After
    fun tearDown() {
        managers.forEach { runCatching { it.stopService() } }
        managers.clear()
        scope.cancel()
        // androidx FileProvider 的 PathStrategy 按 authority 静态缓存
        // （sCache），且根路径在首次解析时与当时 Context 的 cacheDir 绑定：
        // Robolectric 每测试重建 cacheDir，不清缓存则后续测试的
        // getUriForFile 抛「找不到已配置根」，onUpload 整条路径失效。
        runCatching {
            val cache = androidx.core.content.FileProvider::class.java
                .getDeclaredField("sCache")
                .apply { isAccessible = true }
                .get(null)
            (cache as? HashMap<*, *>)?.clear()
        }
    }

    protected val loopbackHost: String get() = InetAddress.getLoopbackAddress().hostAddress!!

    /**
     * 独立 filesDir 的上下文（双管理器编排的身份隔离）。
     *
     * [DeviceIdentityStore] 的身份文件固定在 `filesDir/transfer/device_id.txt`
     * 且每次调用都重新读取：同一测试内两个实例会互相覆盖、最终拿到同一
     * 设备 ID（配对派生与进度合并语义退化）。以仅覆写
     * [ContextWrapper.getFilesDir] 的上下文供身份存储使用，生产代码与
     * 默认路径行为不变。
     */
    protected fun isolatedIdentityContext(name: String): Context =
        object : ContextWrapper(context) {
            override fun getFilesDir(): File = File(context.filesDir, "identity-$name")
        }

    /** 与默认身份隔离的 [DeviceIdentityStore]（双管理器各持不同设备 ID）。 */
    protected fun freshIdentity(name: String): DeviceIdentityStore =
        DeviceIdentityStore(isolatedIdentityContext(name))

    protected fun createManager(
        identityStore: DeviceIdentityStore = DeviceIdentityStore(context),
        importer: FakeBookImporter = this.importer,
        repository: FakeBookRepository = this.repository,
        wifiAddressProvider: () -> InetAddress? = { InetAddress.getLoopbackAddress() },
        discoveryPort: Int = this.discoveryPort,
    ): DefaultLanTransferManager {
        val manager = DefaultLanTransferManager(
            context = context,
            bookImporter = importer,
            bookRepository = repository,
            bookTextStore = textStore,
            identityStore = identityStore,
            scope = scope,
            wifiAddressProvider = wifiAddressProvider,
            discoveryPort = discoveryPort,
        )
        managers += manager
        return manager
    }

    /** 启动服务并等待 running（Unconfined 下 setup 同步完成，轮询兜底）。 */
    protected fun DefaultLanTransferManager.startAndAwaitRunning() {
        startService()
        waitUntil { serviceState.value.running }
    }

    /** HTTP 服务实际端口（AC-9.1 展示地址 "host:port" 的端口部分）。 */
    protected fun DefaultLanTransferManager.httpPort(): Int =
        serviceState.value.addressText.substringAfterLast(':').toInt()

    /** 对端设备条目（流程单测不走 UDP 发现，手工构造）。 */
    protected fun peerDevice(
        manager: DefaultLanTransferManager,
        deviceId: String,
        deviceName: String,
    ) = DiscoveredDevice(
        deviceId = deviceId,
        deviceName = deviceName,
        host = loopbackHost,
        httpPort = manager.httpPort(),
        lastSeenAt = 0L,
    )

    /**
     * 展示端（[this] 管理器）生成配对码，测试方以「发送端」身份完成配对。
     *
     * 与 TransferHttpServerTest.pairAsSender 同构：候选 secret 经
     * [TransferClient.probePairing] 探测落定，返回双方共享 secret。
     */
    protected fun DefaultLanTransferManager.pairAsSender(
        selfIdentity: DeviceIdentityStore,
        peerDeviceId: String = "peer-a",
    ): ByteArray {
        beginPairing()
        val code = pairing.value.displayCode ?: error("beginPairing 后应展示配对码")
        val candidate = deriveSecret(code, listOf(peerDeviceId, selfIdentity.deviceId()))
        val probe = TransferClient().probePairing(
            host = loopbackHost,
            port = httpPort(),
            candidateSecret = candidate,
            selfDeviceId = peerDeviceId,
        )
        check(probe == PairingProbeResult.Paired) { "配对探测应成功，实际：$probe" }
        return candidate
    }

    /** 已配对发送端的客户端（Bearer 令牌由 secret 签发）。 */
    protected fun clientFor(secret: ByteArray, peerDeviceId: String = "peer-a") =
        TransferClient(secretProvider = { secret }, selfDeviceId = { peerDeviceId })

    /** 轮询等待条件成立（真实 socket 编排下替代 advanceUntilIdle）。 */
    protected fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        error("等待条件超时（${timeoutMs}ms）")
    }

    /** 临时文件（Robolectric 缓存目录外，避免干扰被测落盘断言）。 */
    protected fun tempFile(name: String, content: String = "x"): File =
        File.createTempFile(name.padEnd(3, '_'), ".tmp").apply { writeText(content) }

    protected fun book(
        id: Long,
        title: String,
        sha256: String,
        lastReadAt: Long? = null,
        dirName: String = "dir-$id",
    ) = io.github.lunalight.paperread.domain.model.Book(
        id = id,
        title = title,
        author = "未知",
        format = io.github.lunalight.paperread.domain.model.BookFormat.TXT,
        encoding = "UTF-8",
        sha256 = sha256,
        fileSize = 100L,
        dirName = dirName,
        totalChars = 1000L,
        chapterCount = 2,
        lastChapterIndex = 0,
        lastParagraphIndex = 0,
        progressPercent = 0f,
        lastReadAt = lastReadAt,
        addedAt = 1L,
    )

    protected fun snapshot(
        sha256: String,
        title: String,
        lastReadAt: Long?,
        chapter: Int = 3,
        paragraph: Int = 4,
        charOffset: Int = 5,
        scrollOffset: Int = 6,
        progressPercent: Float = 0.5f,
        bookmarks: List<BookmarkSnapshot> = emptyList(),
    ) = BookStateSnapshot(
        sha256 = sha256,
        title = title,
        author = "作者",
        format = "TXT",
        encoding = "UTF-8",
        fileSize = 100L,
        totalChars = 1000L,
        chapterCount = 2,
        lastChapterIndex = chapter,
        lastParagraphIndex = paragraph,
        lastScrollOffset = scrollOffset,
        lastCharOffset = charOffset,
        progressPercent = progressPercent,
        lastReadAt = lastReadAt,
        addedAt = 1L,
        bookmarks = bookmarks,
    )
}
