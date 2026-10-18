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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lunalight.paperread.BuildConfig
import io.github.lunalight.paperread.PaperReadApp

/**
 * 设置页（S2）：阅读偏好 + 关于入口。
 *
 * 阅读偏好控件为 [ReadingPreferencesPanel]——与阅读器底部弹层
 * （ReaderSettingsSheet）共用同一套控件与数据源（AC-8.3），
 * 经 SettingsViewModel 读写 SettingsStore（AC-8.2）。
 *
 * 注意：亮度 / 常亮 / 沉浸 / 音量键在此仅持久化，窗口级效果仍只在
 * 阅读器内生效（TASKS 已知限制，不随本工单扩大范围）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as PaperReadApp).container
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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

            ListItem(
                headlineContent = { Text("关于") },
                supportingContent = { Text("版本 ${BuildConfig.VERSION_NAME}") },
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
