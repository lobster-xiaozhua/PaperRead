package io.github.lunalight.paperread.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.lunalight.paperread.R
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
        SettingsLabel(stringResource(R.string.settings_section_theme))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderThemes.presets.forEach { preset ->
                FilterChip(
                    selected = settings.themeId == preset.id,
                    onClick = { onUpdate { it.copy(themeId = preset.id) } },
                    label = { Text(stringResource(preset.displayNameRes)) },
                )
            }
        }

        SettingsDivider()

        // ---- 字体 ----
        // 内置字族 + 自定义字体库（T-018 / US-16）。整块自带数据源，见
        // CustomFontSection 的注释：设置页与阅读器弹层共用同一套控件与数据。
        SettingsLabel(stringResource(R.string.settings_section_font))
        CustomFontSection(settings = settings, onUpdate = onUpdate)

        SettingsDivider()

        // ---- 排版 ----
        SettingsLabel(stringResource(R.string.settings_section_typography))
        SettingsSlider(
            label = stringResource(R.string.settings_font_size),
            value = settings.fontSizeSp.toFloat(),
            valueRange = 14f..28f,
            steps = 13,
            display = "${settings.fontSizeSp} sp",
            onValueChange = { onUpdate { s -> s.copy(fontSizeSp = it.toInt()) } },
        )
        SettingsSlider(
            label = stringResource(R.string.settings_line_height),
            value = settings.lineHeightRatio,
            valueRange = 1.3f..2.2f,
            steps = 8,
            display = stringResource(
                R.string.settings_line_height_value,
                "%.1f".format(settings.lineHeightRatio),
            ),
            onValueChange = { onUpdate { s -> s.copy(lineHeightRatio = it) } },
        )
        SettingsSlider(
            label = stringResource(R.string.settings_paragraph_spacing),
            value = settings.paragraphSpacingDp.toFloat(),
            valueRange = 0f..24f,
            steps = 11,
            display = "${settings.paragraphSpacingDp} dp",
            onValueChange = { onUpdate { s -> s.copy(paragraphSpacingDp = it.toInt()) } },
        )
        SettingsSlider(
            label = stringResource(R.string.settings_margin),
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
        SettingsLabel(stringResource(R.string.settings_brightness_label))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (settings.brightnessFollowsSystem) {
                    stringResource(R.string.settings_brightness_follow_system)
                } else {
                    "${(settings.brightness * 100).toInt()}%"
                },
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
        SettingsLabel(stringResource(R.string.settings_section_behavior))
        SettingsSwitch(
            title = stringResource(R.string.settings_keep_screen_on),
            subtitle = stringResource(R.string.settings_keep_screen_on_subtitle),
            checked = settings.keepScreenOn,
            onCheckedChange = { onUpdate { s -> s.copy(keepScreenOn = it) } },
        )
        SettingsSwitch(
            title = stringResource(R.string.settings_immersive),
            subtitle = stringResource(R.string.settings_immersive_subtitle),
            checked = settings.immersive,
            onCheckedChange = { onUpdate { s -> s.copy(immersive = it) } },
        )
        SettingsSwitch(
            title = stringResource(R.string.settings_volume_key),
            subtitle = stringResource(R.string.settings_volume_key_subtitle),
            checked = settings.volumeKeyTurnPage,
            onCheckedChange = { onUpdate { s -> s.copy(volumeKeyTurnPage = it) } },
        )
        SettingsSwitch(
            title = stringResource(R.string.settings_page_turn),
            subtitle = stringResource(R.string.settings_page_turn_subtitle),
            checked = settings.pageTurnMode,
            onCheckedChange = { onUpdate { s -> s.copy(pageTurnMode = it) } },
        )
        SettingsSwitch(
            title = stringResource(R.string.settings_two_page),
            subtitle = stringResource(R.string.settings_two_page_subtitle),
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
    // AC-22.1：开关行整体朗读为「标题，副标题」（Switch 自己只报开/关）。
    // 不合并子节点：副标题在小屏上会折行，合并后 TalkBack 焦点框会跨两行。
    val description = stringResource(R.string.a11y_settings_switch_item, title, subtitle)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description }
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
