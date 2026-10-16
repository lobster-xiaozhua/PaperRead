package io.github.lunalight.paperread.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lunalight.paperread.data.settings.ReaderSettings
import io.github.lunalight.paperread.ui.theme.ReaderTheme
import io.github.lunalight.paperread.ui.theme.ReaderThemes

/**
 * 目录弹层：章节列表 + 当前章高亮，点击跳转。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(
    state: ReaderUiState,
    theme: ReaderTheme,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = theme.background,
        contentColor = theme.onBackground,
    ) {
        Text(
            text = "目录 · 共 ${state.chapters.size} 章",
            style = MaterialTheme.typography.titleSmall,
            color = theme.secondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
        ) {
            itemsIndexed(
                items = state.chapters,
                key = { index, _ -> index },
            ) { index, chapter ->
                val selected = index == state.chapterIndex
                ListItem(
                    headlineContent = {
                        Text(
                            text = chapter.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = "第 ${index + 1} 章",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    leadingContent = {
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "当前章节",
                                tint = theme.accent,
                            )
                        } else {
                            Spacer(Modifier.width(24.dp))
                        }
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = if (selected) {
                            theme.accent.copy(alpha = 0.10f)
                        } else {
                            theme.background
                        },
                    ),
                    modifier = Modifier.clickable { onSelect(index) },
                )
            }
        }
    }
}

/**
 * 阅读设置弹层：主题 / 字体 / 排版 / 亮度 / 行为开关。
 *
 * 所有修改经 [onUpdate] 立即写入 DataStore（VM 内 Flow 回流刷新正文）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // ---- 主题 ----
            SettingsLabel("主题")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderThemes.presets.forEach { preset ->
                    FilterChip(
                        selected = settings.themeId == preset.id,
                        onClick = { onUpdate { it.copy(themeId = preset.id) } },
                        label = { Text(preset.displayName) },
                    )
                }
            }

            SettingsDivider()

            // ---- 字体 ----
            SettingsLabel("字体")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.fontFamily == "serif",
                    onClick = { onUpdate { it.copy(fontFamily = "serif") } },
                    label = { Text("衬线") },
                )
                FilterChip(
                    selected = settings.fontFamily == "sans",
                    onClick = { onUpdate { it.copy(fontFamily = "sans") } },
                    label = { Text("黑体") },
                )
            }

            SettingsDivider()

            // ---- 排版 ----
            SettingsLabel("排版")
            SettingsSlider(
                label = "字号",
                value = settings.fontSizeSp.toFloat(),
                valueRange = 14f..28f,
                steps = 13,
                display = "${settings.fontSizeSp} sp",
                onValueChange = { onUpdate { s -> s.copy(fontSizeSp = it.toInt()) } },
            )
            SettingsSlider(
                label = "行距",
                value = settings.lineHeightRatio,
                valueRange = 1.3f..2.2f,
                steps = 8,
                display = "%.1f 倍".format(settings.lineHeightRatio),
                onValueChange = { onUpdate { s -> s.copy(lineHeightRatio = it) } },
            )
            SettingsSlider(
                label = "段距",
                value = settings.paragraphSpacingDp.toFloat(),
                valueRange = 0f..24f,
                steps = 11,
                display = "${settings.paragraphSpacingDp} dp",
                onValueChange = { onUpdate { s -> s.copy(paragraphSpacingDp = it.toInt()) } },
            )
            SettingsSlider(
                label = "页边距",
                value = settings.horizontalMarginDp.toFloat(),
                valueRange = 8f..48f,
                steps = 9,
                display = "${settings.horizontalMarginDp} dp",
                onValueChange = { onUpdate { s -> s.copy(horizontalMarginDp = it.toInt()) } },
            )

            SettingsDivider()

            // ---- 亮度 ----
            SettingsLabel("亮度")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (settings.brightnessFollowsSystem) "跟随系统" else "${(settings.brightness * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = !settings.brightnessFollowsSystem,
                    onCheckedChange = { override ->
                        onUpdate { it.copy(brightness = if (override) 0.5f else -1f) }
                    },
                )
            }
            Slider(
                value = if (settings.brightnessFollowsSystem) 0.5f else settings.brightness,
                onValueChange = { onUpdate { s -> s.copy(brightness = it) } },
                valueRange = 0.05f..1f,
                enabled = !settings.brightnessFollowsSystem,
                modifier = Modifier.fillMaxWidth(),
            )

            SettingsDivider()

            // ---- 行为 ----
            SettingsLabel("行为")
            SettingsSwitch(
                title = "阅读时常亮",
                subtitle = "阅读过程中保持屏幕点亮",
                checked = settings.keepScreenOn,
                onCheckedChange = { onUpdate { s -> s.copy(keepScreenOn = it) } },
            )
            SettingsSwitch(
                title = "沉浸模式",
                subtitle = "隐藏系统状态栏与导航栏",
                checked = settings.immersive,
                onCheckedChange = { onUpdate { s -> s.copy(immersive = it) } },
            )
            SettingsSwitch(
                title = "音量键翻章",
                subtitle = "音量下键下一章、上键上一章（防误触默认关闭）",
                checked = settings.volumeKeyTurnPage,
                onCheckedChange = { onUpdate { s -> s.copy(volumeKeyTurnPage = it) } },
            )
        }
    }
}

// ---- 设置面板零件 ----

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun SettingsDivider() {
    Spacer(Modifier.height(4.dp))
    HorizontalDivider()
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SettingsSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(
                text = display,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
