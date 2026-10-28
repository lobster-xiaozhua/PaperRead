package io.github.lunalight.paperread.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.backup.BackupCrypto
import io.github.lunalight.paperread.backup.BackupImportPreview
import io.github.lunalight.paperread.backup.BackupPreview
import io.github.lunalight.paperread.backup.BackupRestoreReport
import io.github.lunalight.paperread.backup.BackupUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页「备份与恢复」区块（US-14 / AC-14.1 ~ AC-14.10 的 UI 面）。
 *
 * 分工：本文件只负责渲染与 SAF 选择器，所有业务在 `BackupCoordinator`。
 * 文案全部走 `strings.xml`（T-022 纪律）；动态内容（失败原因、书名）来自
 * 领域层数据，与迁移结果页口径一致。
 *
 * 导出流程：预览对话框（计数 / 字体引用 / 可选口令）→ `CreateDocument` →
 * 进度卡片（可取消）。选完保存位置才真正写文件，取消选择不会产生空文件。
 *
 * 恢复流程：`OpenDocument` → 预览对话框（加密包要求口令，错误可原地重试，
 * AC-14.7）→ 进度（可取消）→ 结果对话框（成功 / 失败明细 / 字体回退）。
 */
@Composable
fun BackupSection(
    state: BackupUiState,
    onLoadExportPreview: () -> Unit,
    onExport: (Uri, CharArray?) -> Unit,
    onOpenBackup: (Uri) -> Unit,
    onRestore: (CharArray?) -> Unit,
    onCancel: () -> Unit,
    onDismissExportPreview: () -> Unit,
    onDismissImportPreview: () -> Unit,
    onDismissReport: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 口令在「对话框确认 → 系统文件选择器返回」之间短暂驻留内存；
    // 用户取消选择时立即 zero-fill，不进入任何持久化状态。
    var pendingExportPassword by remember { mutableStateOf<CharArray?>(null) }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val password = pendingExportPassword
        pendingExportPassword = null
        if (uri != null) {
            onExport(uri, password)
        } else {
            password?.fill('\u0000')
        }
    }
    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onOpenBackup(uri)
    }

    Column(modifier) {
        Text(
            text = stringResource(R.string.settings_backup_section),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_backup_export_title)) },
            supportingContent = { Text(stringResource(R.string.settings_backup_export_subtitle)) },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            },
            modifier = Modifier.clickable(enabled = !state.busy, onClick = onLoadExportPreview),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_backup_import_title)) },
            supportingContent = { Text(stringResource(R.string.settings_backup_import_subtitle)) },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            },
            modifier = Modifier.clickable(enabled = !state.busy) {
                openDocument.launch(arrayOf("application/zip", "application/octet-stream"))
            },
        )
        if (state.busy) {
            BackupProgressCard(state = state, onCancel = onCancel)
        }
    }

    state.exportPreview?.let { preview ->
        BackupExportDialog(
            preview = preview,
            onDismiss = onDismissExportPreview,
            onConfirm = { password ->
                pendingExportPassword = password
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                createDocument.launch(
                    context.getString(R.string.backup_default_file_name, stamp),
                )
            },
        )
    }

    state.importPreview?.let { preview ->
        BackupImportDialog(
            state = state,
            preview = preview,
            onDismiss = onDismissImportPreview,
            onCancel = onCancel,
            onConfirm = onRestore,
        )
    }

    state.report?.let { report ->
        BackupReportDialog(report = report, onDismiss = onDismissReport)
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.settings_backup_section)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDismissError) {
                    Text(stringResource(R.string.backup_action_confirm))
                }
            },
        )
    }
}

/** 备份 / 恢复进度卡片（可取消）。 */
@Composable
private fun BackupProgressCard(state: BackupUiState, onCancel: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = state.detail.ifBlank { stringResource(R.string.backup_progress_exporting) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { state.fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.total > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.backup_progress_books, state.done, state.total),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.backup_action_cancel))
                }
            }
        }
    }
}

/** 导出预览 + 可选口令（AC-14.2 / AC-14.8）。 */
@Composable
private fun BackupExportDialog(
    preview: BackupPreview,
    onDismiss: () -> Unit,
    onConfirm: (CharArray?) -> Unit,
) {
    var encrypt by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    // P3-3：口令判据**复用领域层**（`BackupCrypto.isValidPassword` = 长度 + 非全空白），
    // 不再就地写 `length >= 8`——否则「8 位全空格」在 UI 通过、到导出时抛
    // IllegalArgumentException，用户只看到一句泛化的「备份导出失败」。
    val passwordAccepted = BackupCrypto.isValidPassword(password)
    val passwordInvalid = encrypt && password.isNotEmpty() && !passwordAccepted
    val mismatch = encrypt && confirmPassword.isNotEmpty() && password != confirmPassword
    val canConfirm = preview.bookCount > 0 && (!encrypt || (passwordAccepted && password == confirmPassword))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_export_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(
                        R.string.backup_export_summary,
                        preview.bookCount,
                        preview.progressCount,
                        preview.bookmarkCount,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (preview.fontReferences.isEmpty()) {
                        stringResource(R.string.backup_export_fonts_none)
                    } else {
                        stringResource(
                            R.string.backup_export_fonts,
                            preview.fontReferences.joinToString(
                                stringResource(R.string.common_enumeration_separator),
                            ) { it.displayName },
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (preview.bookCount == 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.backup_export_empty),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = encrypt, onCheckedChange = { encrypt = it })
                    Text(stringResource(R.string.backup_export_encrypt))
                }
                if (encrypt) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.backup_export_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = passwordInvalid,
                        supportingText = if (passwordInvalid) {
                            { Text(stringResource(R.string.backup_password_invalid)) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it },
                        label = { Text(stringResource(R.string.backup_export_password_confirm)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = mismatch,
                        supportingText = if (mismatch) {
                            { Text(stringResource(R.string.backup_password_mismatch)) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(if (encrypt) password.toCharArray() else null) },
                enabled = canConfirm,
            ) {
                Text(stringResource(R.string.backup_export_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.backup_action_cancel))
            }
        },
    )
}

/** 恢复预览 + 口令（AC-14.3 / AC-14.7 / AC-14.8）。 */
@Composable
private fun BackupImportDialog(
    state: BackupUiState,
    preview: BackupImportPreview,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: (CharArray?) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val needsPassword = preview.passwordRequired
    val canConfirm = !state.busy && (!needsPassword || password.isNotEmpty())

    AlertDialog(
        onDismissRequest = { if (state.busy) onCancel() else onDismiss() },
        title = { Text(stringResource(R.string.backup_import_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(
                        R.string.backup_import_summary,
                        formatBackupTime(preview.manifest.createdAt),
                        preview.manifest.bookCount,
                        preview.manifest.progressCount,
                        preview.manifest.bookmarkCount,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = importMetaText(preview),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.backup_import_books,
                        preview.bookTitles.joinToString(
                            stringResource(R.string.common_enumeration_separator),
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
                if (preview.missingEntries > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(
                            R.string.backup_import_incomplete,
                            preview.missingEntries,
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (needsPassword) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        text = stringResource(R.string.backup_import_locked),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.backup_import_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = state.wrongPassword,
                        supportingText = if (state.wrongPassword) {
                            { Text(stringResource(R.string.backup_import_wrong_password)) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.busy) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = state.detail.ifBlank {
                            stringResource(R.string.backup_progress_restoring)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(if (needsPassword) password.toCharArray() else null) },
                enabled = canConfirm,
            ) {
                Text(stringResource(R.string.backup_import_start))
            }
        },
        dismissButton = {
            TextButton(onClick = { if (state.busy) onCancel() else onDismiss() }) {
                Text(stringResource(R.string.backup_action_cancel))
            }
        },
    )
}

/**
 * 恢复预览副标题（AC-14.8 / P3-1）：备份格式版本 + 字体引用清单。
 *
 * AC-14.8 要求备份清单记录「版本号与内容摘要（书籍数 / 进度数 / 书签数 /
 * 字体引用列表）」，预览侧原先只展示*缺失*字体——字体全部可用时什么都不显示，
 * 「版本」则从未露出。这里把三项合并成一行：`备份格式 v1 · 字体：serif、sans`
 * （有缺失时追加 `（缺失：xxx，将回退默认字体）`，AC-14.9 的提示不丢）。
 */
@Composable
private fun importMetaText(preview: BackupImportPreview): String {
    val version = preview.manifest.version
    val fonts = preview.manifest.fontReferences.map { it.displayName }
    // 枚举连接符同样属于文案（T-022：`、` 也进 strings.xml）
    val separator = stringResource(R.string.common_enumeration_separator)
    return when {
        fonts.isEmpty() -> stringResource(R.string.backup_import_meta_fonts_none, version)

        preview.fontFallbacks.isNotEmpty() -> stringResource(
            R.string.backup_import_meta_fonts_missing,
            version,
            fonts.joinToString(separator),
            preview.fontFallbacks.joinToString(separator),
        )

        else -> stringResource(
            R.string.backup_import_meta,
            version,
            fonts.joinToString(separator),
        )
    }
}

/** 恢复结果（AC-14.5：成功计数 + 失败明细 + 字体回退）。 */
@Composable
private fun BackupReportDialog(report: BackupRestoreReport, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_report_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(
                        R.string.backup_report_summary,
                        report.imported,
                        report.duplicates,
                        report.progressWritten,
                    ),
                )
                if (report.total == 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.backup_report_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (report.failed.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(
                            R.string.backup_report_failed_title,
                            report.failed.size,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    report.failed.forEach { entry ->
                        Text(
                            text = stringResource(
                                R.string.backup_report_failed_item,
                                entry.title,
                                entry.reason,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (report.fontFallbacks.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(
                            R.string.backup_report_fonts,
                            report.fontFallbacks.joinToString(
                                stringResource(R.string.common_enumeration_separator),
                            ),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.backup_action_close))
            }
        },
    )
}

/** 备份创建时间展示（0 / 非法值显示占位）。 */
internal fun formatBackupTime(epochMillis: Long): String =
    if (epochMillis <= 0L) {
        "-"
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
    }
