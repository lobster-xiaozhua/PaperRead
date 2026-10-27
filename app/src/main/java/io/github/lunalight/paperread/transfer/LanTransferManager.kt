package io.github.lunalight.paperread.transfer

import android.content.Context
import io.github.lunalight.paperread.data.repository.BookImporter
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * 局域网传输管理器（UI 层只依赖本接口，单测以 fake 替代）。
 *
 * 生命周期与「传书页面」绑定：进入页面 [startService]，离开 [stopService]。
 */
interface LanTransferManager {

    val serviceState: StateFlow<TransferServiceState>
    val devices: StateFlow<List<DiscoveredDevice>>
    val sendState: StateFlow<TransferSendState>
    val receiveState: StateFlow<TransferReceiveState>
    val migration: StateFlow<TransferMigrationState>
    val sync: StateFlow<TransferSyncState>

    /** 配对状态（展示端码 / 已配对对端 / 输入侧探测进度与错误）。 */
    val pairing: StateFlow<TransferPairingState>

    /** 启动局域网服务（发现 + HTTP 服务）。已在运行时幂等返回。 */
    fun startService()

    /** 停止服务并清理状态。 */
    fun stopService()

    /**
     * 展示端：生成配对码并进入等待（PAIRING）。
     *
     * 对端在传书页输入该码即完成配对（服务端验签落定）。
     */
    fun beginPairing()

    /** 取消配对：解除已配对关系 / 中止等待，secret 清零，回到 UNPAIRED。 */
    fun cancelPairing()

    /**
     * 输入侧：以对端设备 + 输入的配对码发起配对探测（AC-9.x 之外的 R-01 流程）。
     *
     * 探测成功即配对落定（后续发送 / 迁移 / 同步均以该 secret 鉴权）；
     * 失败原因经 [pairing] 的 error 字段暴露。
     */
    fun pairWith(device: DiscoveredDevice, code: String)

    /** 向已发现设备发送多本书（逐本上传，任一失败即中止，AC-9.6）。 */
    fun sendBooks(target: DiscoveredDevice, books: List<Book>)

    /** 取消进行中的发送。 */
    fun cancelSend()

    /** 确认导入收到的书（走与本地导入完全相同的入库路径，AC-9.5）。 */
    fun confirmReceived()

    /** 取消导入收到的书（清理半成品）。 */
    fun cancelReceived()

    /** 重新解析收到的书（切换编码 / 切分策略，与本地导入同能力，AC-9.5）。 */
    fun reparseReceived(charsetName: String, strategy: SplitStrategy)

    /** 消费「已导入」一次性提示（Snackbar 展示后调用，避免重组重复弹出）。 */
    fun consumeImportedTitle()

    /**
     * 换机迁移（AC-10.1）：把整库（书籍源文件 + 进度 / 书签）打包为单一 zip
     * 发送至对端。已在迁移中时幂等忽略。
     */
    fun startMigration(target: DiscoveredDevice)

    /** 取消进行中的迁移（发送或接收）。 */
    fun cancelMigration()

    /** 关闭迁移结果报告（AC-10.3 清单展示后）。 */
    fun dismissMigrationReport()

    /**
     * 与指定设备双向同步阅读进度与书签（AC-11.1 ~ AC-11.3）。
     * 已在同步中时幂等忽略。
     */
    fun startSync(device: DiscoveredDevice)

    /** 关闭同步结果提示。 */
    fun dismissSyncReport()
}

/**
 * [LanTransferManager] 默认实现：**门面**（T-012 起 ≈ 100 行，原 1131 行）。
 *
 * 组合（装配全在 [TransferCoordinator]，本类只做转发）：
 * - [LanDiscovery]（UDP 发现状态机）+ [UdpSocketTransport]（发现端口）；
 * - [TransferHttpServer]（帧层）→ [TransferServer]（路由 + 鉴权）→
 *   [TransferHttpHandler]（[TransferCoordinator] 装配的四个流程）；
 * - [TransferClient]（发送；Bearer 令牌 + 摘要声明，配对成功后才拿得到 secret）；
 * - [PairingGate]（配对状态机 + secret 生命周期：仅内存，取消即清零）；
 * - 接收落盘 cacheDir/transfer/ → FileProvider content Uri → [BookImporter]
 *   （复用既有导入流水线，不出现第二条导入逻辑，AC-9.5）；
 * - 换机迁移（[MigrationBundleCodec] 打包 / 解包 + [MigrationProcessor] 编排，
 *   导入与回写同样复用 [BookImporter] / [BookRepository]，AC-10.2）。
 *
 * 构造签名自拆分前**逐字未变**：`AppContainer`、`TransferViewModel` /
 * `TransferScreen`、`FakeLanTransferManager`、以及 `TransferFlowTestBase`
 * 的全部流程单测都以它为准，门面的存在就是为了让这次拆分对它们零影响。
 */
class DefaultLanTransferManager(
    context: Context,
    bookImporter: BookImporter,
    bookRepository: BookRepository,
    bookTextStore: BookTextStore,
    identityStore: DeviceIdentityStore,
    scope: CoroutineScope,
    /**
     * WiFi 网卡地址解析（生产见 [LanAddresses.wifiIPv4]）。
     *
     * 抽为注入点：编排层流程单测（G4）需要驱动真实 HTTP 服务，而测试环境
     * （Robolectric 沙箱）无 wlan 网卡，注入回环地址即可不改生产行为地
     * 覆盖「服务已启动」之后的全部接收 / 迁移 / 同步分支。
     */
    wifiAddressProvider: () -> InetAddress? = { LanAddresses.wifiIPv4() },
    /**
     * UDP 发现端口（生产固定 [DISCOVERY_PORT]）。
     *
     * 抽为注入点：同一 JVM 内并存多个管理器实例（同步 / 迁移的双端编排单测）
     * 时各持独立发现端口，避免固定端口争抢导致启动失败。
     */
    discoveryPort: Int = DISCOVERY_PORT,
) : LanTransferManager {

    private val coordinator = TransferCoordinator(
        context = context,
        identityStore = identityStore,
        scope = scope,
        wifiAddressProvider = wifiAddressProvider,
        discoveryPort = discoveryPort,
        bookImporter = bookImporter,
        bookRepository = bookRepository,
        bookTextStore = bookTextStore,
    )

    // ---- 状态流（转发中枢持有的唯一真相） ----

    override val serviceState: StateFlow<TransferServiceState> = coordinator.serviceState
    override val devices: StateFlow<List<DiscoveredDevice>> = coordinator.devices
    override val sendState: StateFlow<TransferSendState> = coordinator.sendState
    override val receiveState: StateFlow<TransferReceiveState> = coordinator.receiveState
    override val migration: StateFlow<TransferMigrationState> = coordinator.migration
    override val sync: StateFlow<TransferSyncState> = coordinator.sync
    override val pairing: StateFlow<TransferPairingState> = coordinator.pairing

    // ---- 服务生命周期 ----

    override fun startService() = coordinator.startService()

    override fun stopService() = coordinator.stopService()

    // ---- 配对（R-01 / G1） ----

    override fun beginPairing() = coordinator.beginPairing()

    override fun cancelPairing() = coordinator.cancelPairing()

    override fun pairWith(device: DiscoveredDevice, code: String) = coordinator.pairWith(device, code)

    // ---- 传书（发送 / 接收） ----

    override fun sendBooks(target: DiscoveredDevice, books: List<Book>) =
        coordinator.sendController.send(target, books)

    override fun cancelSend() = coordinator.sendController.cancel()

    override fun confirmReceived() = coordinator.receiveController.confirmReceived()

    override fun cancelReceived() = coordinator.receiveController.cancelReceived()

    override fun reparseReceived(charsetName: String, strategy: SplitStrategy) =
        coordinator.receiveController.reparseReceived(charsetName, strategy)

    override fun consumeImportedTitle() = coordinator.receiveController.consumeImportedTitle()

    // ---- 换机迁移 ----

    override fun startMigration(target: DiscoveredDevice) = coordinator.migrationController.start(target)

    override fun cancelMigration() = coordinator.migrationController.cancel()

    override fun dismissMigrationReport() = coordinator.migrationController.dismissReport()

    // ---- 进度同步 ----

    override fun startSync(device: DiscoveredDevice) = coordinator.syncController.start(device)

    override fun dismissSyncReport() = coordinator.syncController.dismissReport()
}
