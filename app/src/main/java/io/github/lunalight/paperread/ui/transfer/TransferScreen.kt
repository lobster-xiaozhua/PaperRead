package io.github.lunalight.paperread.ui.transfer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.transfer.DiscoveredDevice
import io.github.lunalight.paperread.transfer.MigrationDirection
import io.github.lunalight.paperread.transfer.PairingCode
import io.github.lunalight.paperread.transfer.PairingGate
import io.github.lunalight.paperread.transfer.TransferMigrationState
import io.github.lunalight.paperread.transfer.TransferPairingState
import io.github.lunalight.paperread.transfer.TransferReceiveState
import io.github.lunalight.paperread.transfer.TransferSendState
import io.github.lunalight.paperread.transfer.TransferServiceState
import io.github.lunalight.paperread.transfer.TransferSyncState
import io.github.lunalight.paperread.transfer.MIGRATION_PHASE_SENT
import io.github.lunalight.paperread.ui.bookshelf.ImportResultDialog
import io.github.lunalight.paperread.ui.resolve

/**
 * 传书页（AC-9.1 ~ AC-9.7）：本机标识 → 已选书目 → 在线设备 → 发送进度 →
 * 接收进度 / 导入确认。UI 不直连 socket，全部经 [TransferViewModel]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    bookIds: List<Long>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as PaperReadApp).container
    val viewModel: TransferViewModel = viewModel(
        factory = TransferViewModel.factory(bookIds, container),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 一次性消息（发送 / 接收错误、导入成功）
    LaunchedEffect(state.message) {
        val message = state.message?.resolve(context) ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transfer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ---- 本机标识（AC-9.1） ----
            ServiceCard(
                service = state.service,
                onRetry = viewModel::retryService,
            )

            // ---- 配对（R-01 / G1：展示端码 / 已配对状态） ----
            PairingCard(
                pairing = state.pairing,
                pairedDeviceName = state.pairedDeviceName,
                onGenerateCode = viewModel::beginPairing,
                onCancel = viewModel::cancelPairing,
            )

            // ---- 待发送书目（AC-9.3 的多选来源） ----
            SelectedBooksCard(books = state.books, resolved = state.booksResolved)

            // ---- 发送进度（AC-9.4：进度 + 可取消） ----
            if (state.send.active) {
                SendProgressCard(
                    send = state.send,
                    onCancel = viewModel::cancelSend,
                )
            }

            // ---- 接收进度（AC-9.5 / AC-9.6） ----
            if (state.receive.receiving) {
                ReceiveProgressCard(receive = state.receive)
            }

            // ---- 换机迁移（AC-10.1 ~ AC-10.4） ----
            MigrationCard(
                migration = state.migration,
                onCancel = viewModel::cancelMigration,
                onDismissReport = viewModel::dismissMigrationReport,
            )

            // ---- 进度同步（AC-11.1 ~ AC-11.3） ----
            SyncCard(
                sync = state.sync,
                onDismissReport = viewModel::dismissSyncReport,
            )

            // ---- 在线设备（AC-9.2 / AC-9.3） ----
            DeviceListCard(
                devices = state.devices,
                sendEnabled = state.send.active.not() && state.books.isNotEmpty(),
                migrationEnabled = state.migration.active.not() && state.devices.isNotEmpty(),
                syncEnabled = state.sync.active.not() && state.devices.isNotEmpty(),
                isPaired = viewModel::isPairedWith,
                onDeviceClick = viewModel::sendTo,
                onMigrateClick = viewModel::startMigration,
                onSyncClick = viewModel::startSync,
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    // 接收待确认：复用与本地导入完全相同的对话框（AC-9.5，单一下导入逻辑）
    val pending = state.receive.pending
    if (pending != null) {
        ImportResultDialog(
            pending = pending,
            onReparse = viewModel::reparseReceived,
            onConfirm = viewModel::confirmReceived,
            onDismiss = viewModel::cancelReceived,
        )
    }

    // 配对对话框（输入侧：对未配对设备的动作先弹此框，成功后自动继续）
    val pairingTarget = state.pairingTarget
    if (pairingTarget != null) {
        PairingDialog(
            deviceName = pairingTarget.deviceName,
            code = state.pairingInput,
            error = state.pairing.error,
            busy = state.pairing.busy,
            onCodeChange = viewModel::updatePairingInput,
            onConfirm = viewModel::confirmPairing,
            onDismiss = viewModel::dismissPairingDialog,
        )
    }
}

/**
 * 配对卡片（展示端视角：生成配对码 / 展示等待 / 已配对状态）。
 *
 * 输入侧交互在设备行的配对对话框中（见 [PairingDialog]）。
 */
@Composable
private fun PairingCard(
    pairing: TransferPairingState,
    pairedDeviceName: String?,
    onGenerateCode: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.transfer_pairing_card_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            when (pairing.status) {
                PairingGate.Status.UNPAIRED -> {
                    Text(
                        stringResource(R.string.transfer_pairing_unpaired_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onGenerateCode) {
                        Text(stringResource(R.string.transfer_pairing_generate))
                    }
                }

                PairingGate.Status.PAIRING -> {
                    Text(
                        pairing.displayCode.orEmpty(),
                        style = MaterialTheme.typography.headlineLarge,
                        letterSpacing = 12.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.transfer_pairing_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.transfer_pairing_cancel))
                    }
                }

                PairingGate.Status.PAIRED -> {
                    Text(
                        stringResource(
                            R.string.transfer_pairing_paired,
                            pairedDeviceName ?: stringResource(R.string.common_peer_device),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.transfer_pairing_cancel))
                    }
                }
            }
        }
    }
}

/** 配对对话框（输入侧：输入对端展示的 6 位配对码）。 */
@Composable
private fun PairingDialog(
    deviceName: String,
    code: String,
    error: String?,
    busy: Boolean,
    onCodeChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transfer_pairing_dialog_title, deviceName)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.transfer_pairing_dialog_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    label = { Text(stringResource(R.string.transfer_pairing_dialog_input)) },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = code.length == PairingCode.LENGTH && !busy,
            ) {
                Text(
                    if (busy) {
                        stringResource(R.string.transfer_pairing_busy)
                    } else {
                        stringResource(R.string.transfer_pairing_dialog_confirm)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.transfer_pairing_dialog_cancel))
            }
        },
    )
}

@Composable
private fun ServiceCard(
    service: TransferServiceState,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_transfer_phone),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.transfer_service_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            when {
                service.starting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.transfer_service_starting),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                service.running -> {
                    Text(
                        service.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        service.addressText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.transfer_service_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Text(
                        service.error ?: stringResource(R.string.transfer_service_not_running),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.common_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectedBooksCard(books: List<Book>, resolved: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_transfer_send),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.transfer_selected_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            when {
                !resolved -> {
                    Text(
                        stringResource(R.string.transfer_selected_loading),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                books.isEmpty() -> {
                    Text(
                        stringResource(R.string.transfer_selected_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Text(
                        stringResource(R.string.transfer_selected_count, books.size),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    books.forEach { book ->
                        Text(
                            "· ${book.title}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SendProgressCard(
    send: TransferSendState,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.transfer_sending, send.currentTitle, send.index, send.total),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { send.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.transfer_send_cancel))
                }
            }
        }
    }
}

@Composable
private fun ReceiveProgressCard(
    receive: TransferReceiveState,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_transfer_receive),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.transfer_receiving, receive.fileName),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { receive.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MigrationCard(
    migration: TransferMigrationState,
    onCancel: () -> Unit,
    onDismissReport: () -> Unit,
) {
    // `buildString` 里要拼「（3/10）」这类纯格式片段，取 context 解析资源
    val context = LocalContext.current
    // 进行中 / 出错 / 结果清单：三者都无则不占位
    val visible = migration.active ||
        migration.error != null ||
        migration.report != null ||
        migration.phaseLabel == MIGRATION_PHASE_SENT
    if (!visible) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_transfer_send),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (migration.direction == MigrationDirection.RECEIVE) {
                            R.string.transfer_migration_receive_title
                        } else {
                            R.string.transfer_migration_send_title
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            when {
                migration.error != null -> Text(
                    migration.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                migration.active -> {
                    Text(
                        buildString {
                            append(migration.phaseLabel)
                            if (migration.total > 0) {
                                append(
                                    context.getString(
                                        R.string.transfer_migration_progress,
                                        migration.index,
                                        migration.total,
                                    ),
                                )
                            }
                            if (migration.currentTitle.isNotBlank()) {
                                append(" · ${migration.currentTitle}")
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { migration.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.transfer_migration_cancel))
                        }
                    }
                }

                else -> {
                    val report = migration.report
                    if (report != null) {
                        Text(
                            stringResource(
                                R.string.transfer_migration_report,
                                report.imported,
                                report.duplicates,
                                report.progressWritten,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (report.failed.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.transfer_migration_failed, report.failed.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            report.failed.forEach { entry ->
                                Text(
                                    stringResource(
                                        R.string.transfer_migration_failed_entry,
                                        entry.title,
                                        entry.reason,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    } else {
                        Text(
                            stringResource(R.string.transfer_migration_sent),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismissReport) {
                            Text(stringResource(R.string.common_got_it))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncCard(
    sync: TransferSyncState,
    onDismissReport: () -> Unit,
) {
    val visible = sync.active || sync.error != null || sync.report != null
    if (!visible) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_transfer_receive),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.transfer_sync_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            when {
                sync.error != null -> Text(
                    sync.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                sync.active -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        sync.phaseLabel.ifBlank { stringResource(R.string.transfer_sync_loading) },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                else -> {
                    val report = sync.report
                    if (report != null) {
                        Text(
                            stringResource(
                                R.string.transfer_sync_report,
                                report.localOverwritten,
                                report.localBookmarksAdded,
                                report.pushBack.size,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (report.remoteOnlyBooks > 0 || report.localOnlyBooks > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(
                                    R.string.transfer_sync_diff,
                                    report.remoteOnlyBooks,
                                    report.localOnlyBooks,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    } else {
                        Text(
                            stringResource(R.string.transfer_sync_done),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismissReport) {
                            Text(stringResource(R.string.common_got_it))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceListCard(
    devices: List<DiscoveredDevice>,
    sendEnabled: Boolean,
    migrationEnabled: Boolean,
    syncEnabled: Boolean,
    isPaired: (DiscoveredDevice) -> Boolean,
    onDeviceClick: (DiscoveredDevice) -> Unit,
    onMigrateClick: (DiscoveredDevice) -> Unit,
    onSyncClick: (DiscoveredDevice) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_transfer_devices),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.transfer_devices_title),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (devices.isEmpty()) {
                Text(
                    stringResource(R.string.transfer_devices_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                devices.forEach { device ->
                    // AC-22.1 / AC-22.4：设备行整行可点（发送），必须能被 TalkBack
                    // 读成「设备名 + 地址」；触达高度给到 48dp 下限。
                    // 不做 mergeDescendants——行内还有「同步进度 / 迁移整库」两个按钮，
                    // 合并会把它们吞进同一个焦点。
                    val deviceLabel = stringResource(
                        R.string.a11y_transfer_device_item,
                        device.deviceName,
                        "${device.host}:${device.httpPort}",
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = deviceLabel }
                            .clickable(enabled = sendEnabled) { onDeviceClick(device) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                device.deviceName,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${device.host}:${device.httpPort}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (isPaired(device)) {
                                Text(
                                    stringResource(R.string.transfer_pairing_badge_paired),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else if (device.pairingRequired) {
                                Text(
                                    stringResource(R.string.transfer_pairing_badge_required),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (!sendEnabled) {
                            Text(
                                stringResource(R.string.transfer_devices_select_first),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { onSyncClick(device) },
                            enabled = syncEnabled,
                        ) {
                            Text(
                                stringResource(R.string.transfer_device_sync),
                                fontSize = 12.sp,
                            )
                        }
                        TextButton(
                            onClick = { onMigrateClick(device) },
                            enabled = migrationEnabled,
                        ) {
                            Text(
                                stringResource(R.string.transfer_device_migrate),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.transfer_devices_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
