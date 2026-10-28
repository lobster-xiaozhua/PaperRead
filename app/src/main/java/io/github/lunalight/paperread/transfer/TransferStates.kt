package io.github.lunalight.paperread.transfer

import io.github.lunalight.paperread.data.repository.PendingImport

/**
 * 传输域的对外状态模型（T-012 从 `LanTransferManager.kt` 拆出，类名与包名不变，
 * UI / fake / 单测 import 无感）。
 *
 * 这里只放**纯数据**：UI 订阅的五条状态流 + 配对 / 迁移 / 同步的附加状态。
 * 状态机与转移逻辑在 `TransferCoordinator` 与各自的 Controller 里。
 */

/** 传书服务状态（AC-9.1：本机标识 = 设备名 + IP:端口）。 */
data class TransferServiceState(
    val starting: Boolean = false,
    val running: Boolean = false,
    val deviceName: String = "",
    val addressText: String = "",
    /** 启动失败原因（如未接入 WiFi）。 */
    val error: String? = null,
)

/** 发送状态（AC-9.4：进度 + 可取消；AC-9.6：失败原因）。 */
data class TransferSendState(
    val active: Boolean = false,
    val currentTitle: String = "",
    val index: Int = 0,
    val total: Int = 0,
    val fraction: Float = 0f,
    val error: String? = null,
)

/** 接收状态：落盘进度 → 复用导入流水线的待确认 / 失败。 */
data class TransferReceiveState(
    val receiving: Boolean = false,
    val fileName: String = "",
    val fraction: Float = 0f,
    val pending: PendingImport? = null,
    val error: String? = null,
    /** 确认导入后的成功提示（一次性）。 */
    val importedTitle: String? = null,
)

/** 迁移方向（发送端打包上行 / 接收端解包导入）。 */
enum class MigrationDirection { NONE, SEND, RECEIVE }

/**
 * 迁移「已发送」阶段标签（T-022）。
 *
 * 这个字符串仍归数据层：它由 [TransferMigrationController] 产生、随
 * [TransferMigrationState] 透传给 UI 展示（同 userMessage 口径，登记在
 * `scripts/strings-baseline.txt`）。UI 侧原先用字面量 `"已发送"` 判断
 * 「发送成功但无报告」的结果卡片，等于把数据层话术抄进了渲染层——
 * 改为引用本常量，UI 层不再持有该字面量。
 */
const val MIGRATION_PHASE_SENT = "已发送"

/** 进度同步状态（AC-11.1 ~ AC-11.3）。 */
data class TransferSyncState(
    val active: Boolean = false,
    val phaseLabel: String = "",
    val error: String? = null,
    /** 完成后的一次性结果（被覆盖 / 新增书签等计数）。 */
    val report: SyncReport? = null,
)

/**
 * 配对状态（R-01 / G1：UI 展示 + 对话框驱动）。
 *
 * @param status 配对状态机状态（[PairingGate.Status]）
 * @param displayCode 本机展示中的配对码（PAIRING 时非空，仅内存）
 * @param pairedDeviceId 已配对的对端设备 ID（PAIRED 时非空）
 * @param busy 配对探测请求进行中（输入侧；防重复提交）
 * @param error 配对失败原因（输入侧；下次尝试前保持展示）
 */
data class TransferPairingState(
    val status: PairingGate.Status = PairingGate.Status.UNPAIRED,
    val displayCode: String? = null,
    val pairedDeviceId: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * 换机迁移状态（AC-10.1 ~ AC-10.4）。
 *
 * @param report 完成后的成功 / 失败清单（AC-10.3）；仅接收端产出
 *   （发送端完成以 active 落潮 + VM 一次性提示表达）
 */
data class TransferMigrationState(
    val active: Boolean = false,
    val direction: MigrationDirection = MigrationDirection.NONE,
    val phaseLabel: String = "",
    val fraction: Float = 0f,
    val index: Int = 0,
    val total: Int = 0,
    val currentTitle: String = "",
    val error: String? = null,
    val report: MigrationReport? = null,
)
