package io.github.lunalight.paperread.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lunalight.paperread.BuildConfig
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.R

/**
 * 设置页（S2）：阅读偏好 + 备份与恢复（US-14）+ 关于入口。
 *
 * 阅读偏好控件为 [ReadingPreferencesPanel]——与阅读器底部弹层
 * （ReaderSettingsSheet）共用同一套控件与数据源（AC-8.3），
 * 经 SettingsViewModel 读写 SettingsStore（AC-8.2）。
 *
 * 备份 / 恢复走独立的 [BackupViewModel] → `BackupCoordinator`：导出 / 恢复是
 * 长任务且需要跨页面存活，与阅读偏好是两条互不相干的数据流。
 *
 * 注意：亮度 / 常亮 / 沉浸 / 音量键在此仅持久化，窗口级效果仍只在
 * 阅读器内生效（TASKS 已知限制，不随本工单扩大范围）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    onReplayOnboarding: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as PaperReadApp).container
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val backupViewModel: BackupViewModel = viewModel(
        factory = BackupViewModel.factory(container),
    )
    val backupState by backupViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 一次性提示（备份导出完成 / 已取消）：展示后清空，避免重组重复弹出
    LaunchedEffect(backupState.message) {
        val message = backupState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        backupViewModel.clearMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .verticalScroll(rememberScrollState()),
        ) {
            ReadingPreferencesPanel(
                settings = state.settings,
                onUpdate = viewModel::updateSettings,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 备份与恢复（US-14 / T-015 / T-016） ----
            BackupSection(
                state = backupState,
                onLoadExportPreview = backupViewModel::loadExportPreview,
                onExport = backupViewModel::export,
                onOpenBackup = backupViewModel::openBackup,
                onRestore = backupViewModel::restore,
                onCancel = backupViewModel::cancel,
                onDismissExportPreview = backupViewModel::dismissExportPreview,
                onDismissImportPreview = backupViewModel::dismissImportPreview,
                onDismissReport = backupViewModel::dismissReport,
                onDismissError = backupViewModel::clearError,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // ---- 重新查看引导（T-020 · AC-18.4） ----
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_replay_onboarding_title)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_replay_onboarding_subtitle))
                },
                leadingContent = {
                    Icon(Icons.Filled.Info, contentDescription = null)
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable(onClick = onReplayOnboarding),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            ListItem(
                headlineContent = { Text(stringResource(R.string.about_title)) },
                supportingContent = {
                    Text(stringResource(R.string.about_version_short, BuildConfig.VERSION_NAME))
                },
                leadingContent = {
                    Icon(Icons.Filled.Info, contentDescription = null)
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable(onClick = onOpenAbout),
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
