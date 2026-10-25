package io.github.lunalight.paperread.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lunalight.paperread.data.settings.ReaderSettings
import io.github.lunalight.paperread.ui.theme.ReaderThemes

/**
 * 阅读偏好面板（S2）：主题 / 字体 / 排版 / 亮度 / 行为开关。
 *
 * 从 ReaderSettingsSheet 抽取，供设置页与阅读器底部弹层复用——同一套控件与
 * 数据源，不得出现两套实现（AC-8.3）。完全无状态：所有修改经 [onUpdate]
 * 立即写入 SettingsStore（VM 内 Flow 回流刷新 UI）。
 *
 * 面板自身不含滚动容器，由宿主提供（设置页 Column.verticalScroll /
 * 弹层 Column.verticalScroll），避免嵌套滚动。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadingPreferencesPanel(
    settings: ReaderSettings,
    onUpdate: ((ReaderSettings) -> ReaderSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
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
        // -1f 表跟随系统；开启覆盖时默认 0.5f。仅写数据，窗口级亮度效果
        // 仍只在阅读器内生效（TASKS 已知限制，不随 S2 扩大范围）。
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
        SettingsSwitch(
            title = "翻页模式",
            subtitle = "关闭为连续滚动，开启为左右滑动翻页（仿真书页，可点按左/右/中区）",
            checked = settings.pageTurnMode,
            onCheckedChange = { onUpdate { s -> s.copy(pageTurnMode = it) } },
        )
        SettingsSwitch(
            title = "双页跨页",
            subtitle = "平板/横屏大屏自动左右两页；手机竖屏不受影响",
            checked = settings.twoPageSpread,
            onCheckedChange = { onUpdate { s -> s.copy(twoPageSpread = it) } },
        )
    }
}

// ---- 面板零件 ----

@Composable
internal fun SettingsLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
internal fun SettingsDivider() {
    Spacer(Modifier.height(4.dp))
    HorizontalDivider()
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun SettingsSlider(
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
internal fun SettingsSwitch(
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
