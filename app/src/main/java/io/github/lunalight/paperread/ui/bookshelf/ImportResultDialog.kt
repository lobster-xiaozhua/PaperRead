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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.reader.txt.CharsetDetector
import io.github.lunalight.paperread.reader.txt.SplitStrategy

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
        title = { Text("导入确认", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(pending.title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${pending.format.name} · ${formatFileSize(pending.fileSize)} · " +
                        "${pending.chapters.size} 章 · ${formatChars(pending.totalChars)}",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!isEpub) {
                    Spacer(Modifier.height(12.dp))
                    Text("编码", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
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
                    Text("切分策略", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
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
                                        if (option == SplitStrategy.CONSERVATIVE) "保守" else "激进",
                                        fontSize = 12.sp,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 供本文件内对话框复用的大小 / 字数格式化（与书架页同口径）。 */
internal fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

internal fun formatChars(chars: Long): String = when {
    chars >= 10_000 -> "%.1f 万字".format(chars / 10_000.0)
    else -> "$chars 字"
}
