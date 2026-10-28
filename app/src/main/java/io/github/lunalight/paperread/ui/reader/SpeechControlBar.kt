package io.github.lunalight.paperread.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.speech.SpeechState
import io.github.lunalight.paperread.ui.theme.ReaderTheme

/**
 * 朗读相关 UI：控制条 + 通知权限场景化对话框。
 *
 * 归属索引：
 * - [SpeechControlBar]：播放/暂停、段步进、停止，语速 / 音调 / 章尾自动续读
 * - [SpeechPermissionDialog]：首次朗读前的通知权限说明（AC-20.1）
 *
 * 本文件只做渲染与回调上抛：状态来自 [SpeechState] / [ReaderUiState]，
 * 权限申请本身由编排层（`ReaderScreen.kt`）发起的系统请求完成。
 */

/**
 * 朗读控制条：播放/暂停、段步进、停止，以及语速 / 音调 / 章尾自动续读。
 *
 * 与通知按钮（[io.github.lunalight.paperread.speech.SpeechActionReceiver]）
 * 操作同一控制器单例，两端状态天然一致；语速/音调改动经 VM 持久化到
 * DataStore 并即时同步引擎（AC-12.4）。
 */
@Composable
internal fun SpeechControlBar(
    speech: SpeechState,
    theme: ReaderTheme,
    autoNextChapter: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkip: (Int) -> Unit,
    onStop: () -> Unit,
    onRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onAutoNextChange: (Boolean) -> Unit,
) {
    Surface(color = theme.background, contentColor = theme.onBackground) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onSkip(-1) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_speech_prev),
                        contentDescription = stringResource(R.string.speech_prev_unit),
                        tint = theme.onBackground,
                    )
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        painter = painterResource(
                            if (speech.isSpeaking) R.drawable.ic_speech_pause else R.drawable.ic_speech_play,
                        ),
                        contentDescription = stringResource(
                            if (speech.isSpeaking) R.string.speech_pause else R.string.speech_resume,
                        ),
                        tint = theme.accent,
                    )
                }
                IconButton(onClick = { onSkip(1) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_speech_next),
                        contentDescription = stringResource(R.string.speech_next_unit),
                        tint = theme.onBackground,
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(
                        painter = painterResource(R.drawable.ic_speech_stop),
                        contentDescription = stringResource(R.string.speech_stop),
                        tint = theme.onBackground,
                    )
                }
                Text(
                    text = stringResource(
                        if (speech.isSpeaking) R.string.speech_state_speaking else R.string.speech_state_paused,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.secondary,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.speech_rate),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.secondary,
                )
                Slider(
                    value = speech.rate,
                    onValueChange = onRateChange,
                    valueRange = 0.5f..2.0f,
                    steps = 5,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text(
                    text = "%.2fx".format(speech.rate),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.secondary,
                    // AC-22.3：固定 44dp 在 200% 字号下会把「1.00x」截断，
                    // 改最小宽度：正常字号等价，放大时允许撑开。
                    modifier = Modifier.widthIn(min = 44.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.speech_pitch),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.secondary,
                )
                Slider(
                    value = speech.pitch,
                    onValueChange = onPitchChange,
                    valueRange = 0.5f..2.0f,
                    steps = 5,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text(
                    text = "%.2fx".format(speech.pitch),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.secondary,
                    modifier = Modifier.widthIn(min = 44.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // AC-22.4：整行可点，`padding(vertical = 2.dp)` 会让行高掉到
                    // 48dp 以下（Switch 视觉高 32dp），显式给最小触达高度。
                    .heightIn(min = 48.dp)
                    .clickable { onAutoNextChange(!autoNextChapter) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.speech_auto_next),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.secondary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = autoNextChapter,
                    onCheckedChange = onAutoNextChange,
                )
            }
        }
    }
}

/**
 * 朗读通知权限的场景化说明对话框（AC-20.1）。
 *
 * 文案全部走字符串资源（R-19 口径：新功能文案直接进 strings.xml）。
 */
@Composable
internal fun SpeechPermissionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speech_permission_prompt_title)) },
        text = { Text(stringResource(R.string.speech_permission_prompt_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.speech_permission_prompt_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.speech_permission_prompt_dismiss))
            }
        },
    )
}
