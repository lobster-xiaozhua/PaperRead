package io.github.lunalight.paperread.ui.bookshelf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.reader.txt.CharsetDetector
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import io.github.lunalight.paperread.ui.UiText
import io.github.lunalight.paperread.ui.asString

/**
 * 导入结果确认对话框（本地导入与局域网传书接收共用，AC-9.5）。
 *
 * TXT 可切换编码 / 切分策略后重新解析；EPUB 章节结构来自 OPF/spine，不展示这两组。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImportResultDialog(
    pending: PendingImport,
    onReparse: (String, SplitStrategy) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var charset by remember { mutableStateOf(pending.charsetName) }
    var strategy by remember { mutableStateOf(pending.strategy) }
    val isEpub = pending.format == BookFormat.EPUB
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.import_confirm_title),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column {
                Text(pending.title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        R.string.import_confirm_summary,
                        pending.format.name,
                        formatFileSize(pending.fileSize),
                        pending.chapters.size,
                        formatChars(pending.totalChars).asString(),
                    ),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!isEpub) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.import_charset),
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CharsetDetector.SUPPORTED_ENCODINGS.forEach { name ->
                            FilterChip(
                                selected = charset == name,
                                onClick = {
                                    if (charset != name) {
                                        charset = name
                                        onReparse(name, strategy)
                                    }
                                },
                                label = { Text(name, fontSize = 11.sp) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.import_split_strategy),
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SplitStrategy.entries.forEach { option ->
                            FilterChip(
                                selected = strategy == option,
                                onClick = {
                                    if (strategy != option) {
                                        strategy = option
                                        onReparse(charset, option)
                                    }
                                },
                                label = {
                                    Text(
                                        stringResource(
                                            if (option == SplitStrategy.CONSERVATIVE) {
                                                R.string.split_strategy_conservative
                                            } else {
                                                R.string.split_strategy_aggressive
                                            },
                                        ),
                                        fontSize = 12.sp,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_import)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** 供本文件内对话框复用的大小 / 字数格式化（与书架页同口径）。 */
internal fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 字数展示：≥1 万字用「x.x 万字」，否则「n 字」。
 *
 * T-022：单位也是文案，故返回 [UiText]（带资源 id）而不是成品字符串——
 * 本函数因此保持**纯函数**（单测直接断言返回了哪条资源 + 哪个参数），
 * 渲染期由调用方 `asString()` 解析。
 */
internal fun formatChars(chars: Long): UiText = when {
    chars >= 10_000 -> UiText.res(R.string.import_chars_wan, "%.1f".format(chars / 10_000.0))
    else -> UiText.res(R.string.import_chars_count, chars)
}
