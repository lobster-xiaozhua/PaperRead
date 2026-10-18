package io.github.lunalight.paperread.ui.transfer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import io.github.lunalight.paperread.transfer.TransferMigrationState
import io.github.lunalight.paperread.transfer.TransferReceiveState
import io.github.lunalight.paperread.transfer.TransferSendState
import io.github.lunalight.paperread.transfer.TransferServiceState
import io.github.lunalight.paperread.ui.bookshelf.ImportResultDialog

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
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("局域网传书") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
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

            // ---- 在线设备（AC-9.2 / AC-9.3） ----
            DeviceListCard(
                devices = state.devices,
                sendEnabled = state.send.active.not() && state.books.isNotEmpty(),
                migrationEnabled = state.migration.active.not() && state.devices.isNotEmpty(),
                onDeviceClick = viewModel::sendTo,
                onMigrateClick = viewModel::startMigration,
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
                Text("本机标识", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            when {
                service.starting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("正在启动传书服务…", style = MaterialTheme.typography.bodyMedium)
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
                        "同一 WiFi 下的其他设备打开传书页即可发现本机（不访问公网）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Text(
                        service.error ?: "服务未启动",
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
                        Text("重试")
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
                Text("待发送书籍", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            when {
                !resolved -> {
                    Text("正在读取书目…", style = MaterialTheme.typography.bodyMedium)
                }

                books.isEmpty() -> {
                    Text(
                        "未选择书籍。返回书架，长按多选后点右上角发送图标，" +
                            "即可把所选书籍传到在线设备。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    Text(
                        "共 ${books.size} 本，选择下方设备开始发送：",
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
                "正在发送《${send.currentTitle}》（${send.index}/${send.total}）",
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
                TextButton(onClick = onCancel) { Text("取消发送") }
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
                    "正在接收 ${receive.fileName}",
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
    // 进行中 / 出错 / 结果清单：三者都无则不占位
    val visible = migration.active ||
        migration.error != null ||
        migration.report != null ||
        migration.phaseLabel == "已发送"
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
                    if (migration.direction == MigrationDirection.RECEIVE) "接收整库迁移" else "换机迁移",
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
                                append("（${migration.index}/${migration.total}）")
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
                        TextButton(onClick = onCancel) { Text("取消迁移") }
                    }
                }

                else -> {
                    val report = migration.report
                    if (report != null) {
                        Text(
                            "新导入 ${report.imported} 本 · 已存在 ${report.duplicates} 本 · " +
                                "回写进度 ${report.progressWritten} 本",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (report.failed.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "失败 ${report.failed.size} 本：",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            report.failed.forEach { entry ->
                                Text(
                                    "· ${entry.title}：${entry.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    } else {
                        Text("迁移包已发送", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismissReport) { Text("知道了") }
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
    onDeviceClick: (DiscoveredDevice) -> Unit,
    onMigrateClick: (DiscoveredDevice) -> Unit,
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
                Text("在线设备", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            if (devices.isEmpty()) {
                Text(
                    "未发现在线设备。请确认两台设备处于同一 WiFi，" +
                        "且对端已打开传书页（发现通常不超过 3 秒）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                devices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
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
                        }
                        if (!sendEnabled) {
                            Text(
                                "先选书",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { onMigrateClick(device) },
                            enabled = migrationEnabled,
                        ) {
                            Text("迁移整库", fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "点设备名 = 发送上方所选书籍；「迁移整库」= 把全部书籍与进度 / 书签打包发给该设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
