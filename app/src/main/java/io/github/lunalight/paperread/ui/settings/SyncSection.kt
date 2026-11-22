package io.github.lunalight.paperread.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.backup.BackupCrypto
import io.github.lunalight.paperread.sync.SyncUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页「云同步」区块（US-22，Cloudflare 后端接入）。
 *
 * 分工（对照 [BackupSection]）：本文件只渲染控件与文案，所有业务在 `SyncCoordinator`，
 * 文案全部走 `strings.xml`。零知识快照复用本地备份的加密（[BackupCrypto]），故密码
 * 强度校验也复用同一判据（[BackupCrypto.isValidPassword]）。
 *
 * 布局：总开关 → 关闭时仅一句说明；开启后未登录显示邮箱 / 密码与注册 / 登录；已登录
 * 显示账户、最近同步状态、「立即同步」与退出登录；进行中显示进度卡；错误以对话框呈现。
 */
@Composable
fun SyncSection(
    state: SyncUiState,
    onRegister: (email: String, password: CharArray) -> Unit,
    onLogin: (email: String, password: CharArray) -> Unit,
    onLogout: () -> Unit,
    onSyncNow: () -> Unit,
    onSetSyncEnabled: (Boolean) -> Unit,
    onClearError: () -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val passwordAccepted = BackupCrypto.isValidPassword(password)
    val passwordInvalid = password.isNotEmpty() && !passwordAccepted
    val canAuth = email.isNotBlank() && passwordAccepted && !state.busy

    Column(modifier) {
        Text(
            text = stringResource(R.string.sync_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.sync_enable)) },
            supportingContent = { Text(stringResource(R.string.sync_enable_subtitle)) },
            trailingContent = {
                Switch(
                    checked = state.syncEnabled,
                    onCheckedChange = onSetSyncEnabled,
                    enabled = !state.busy,
                )
            },
        )

        if (state.syncEnabled) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

            if (!state.loggedIn) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(R.string.sync_email)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.sync_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = passwordInvalid,
                        supportingText = if (passwordInvalid) {
                            { Text(stringResource(R.string.sync_password_invalid)) }
                        } else {
                            { Text(stringResource(R.string.sync_password_hint)) }
                        },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val pw = password.toCharArray()
                                password = ""
                                onRegister(email, pw)
                            },
                            enabled = canAuth,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.sync_register)) }
                        OutlinedButton(
                            onClick = {
                                val pw = password.toCharArray()
                                password = ""
                                onLogin(email, pw)
                            },
                            enabled = canAuth,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.sync_login)) }
                    }
                }
            } else {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        text = stringResource(
                            R.string.sync_logged_in_as,
                            state.accountEmail ?: "",
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val lastSync = state.lastSyncAt
                    if (lastSync != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.sync_last_sync,
                                formatSyncTime(lastSync),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    state.lastSyncResult?.let { result ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onSyncNow,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.sync_now)) }
                        OutlinedButton(
                            onClick = onLogout,
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.sync_logout)) }
                    }
                }
            }

            state.message?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            if (state.busy) {
                Spacer(Modifier.height(8.dp))
                SyncProgressCard(state = state)
            }
        }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = onClearError,
            title = { Text(stringResource(R.string.sync_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onClearError) {
                    Text(stringResource(R.string.backup_action_confirm))
                }
            },
        )
    }
}

/** 同步进度卡（上传 / 下载中）。 */
@Composable
private fun SyncProgressCard(state: SyncUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = state.detail.ifBlank { stringResource(R.string.sync_status_syncing) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 同步时间展示（0 / 非法值显示占位）。 */
internal fun formatSyncTime(epochMillis: Long): String =
    if (epochMillis <= 0L) {
        "-"
    } else {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
    }
