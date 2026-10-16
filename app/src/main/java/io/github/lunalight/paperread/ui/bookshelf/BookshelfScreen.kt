@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package io.github.lunalight.paperread.ui.bookshelf

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.reader.txt.CharsetDetector
import io.github.lunalight.paperread.reader.txt.SplitStrategy


/**
 * 书架页：继续阅读、书籍网格、导入流水线（进度 / 结果确认 / 重新解析）、多选删除。
 */
@Composable
fun BookshelfScreen(
    onOpenSettings: () -> Unit,
    onOpenBook: (Long) -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as PaperReadApp).container
    val viewModel: BookshelfViewModel = viewModel(factory = BookshelfViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    val selectionMode = selectedIds.isNotEmpty()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importBook(uri)
    }

    // 消息 Snackbar
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                CenterAlignedTopAppBar(
                    title = { Text("已选 ${selectedIds.size} 本") },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = "退出多选")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            state.books.filter { it.id in selectedIds }
                                .forEach { viewModel.deleteBook(it) }
                            selectedIds = emptySet()
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除所选")
                        }
                    },
                )
            } else {
                CenterAlignedTopAppBar(
                    title = { Text("纸阅") },
                    actions = {
                        // 排序菜单
                        var menuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "排序")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(order.label) },
                                        onClick = {
                                            viewModel.setSortOrder(order)
                                            menuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("导入书籍") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state.books.isEmpty() -> {
                    EmptyBookshelf(
                        onImport = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // 继续阅读卡（有阅读记录时展示，跨两列）
                        val lastRead = state.books
                            .filter { it.lastReadAt != null }
                            .maxByOrNull { it.lastReadAt ?: 0L }
                        if (lastRead != null) {
                            item(span = { GridItemSpan(2) }) {
                                ContinueReadingCard(
                                    book = lastRead,
                                    onClick = { onOpenBook(lastRead.id) },
                                )
                            }
                        }
                        items(state.books, key = { it.id }) { book ->
                            BookCard(
                                book = book,
                                selected = book.id in selectedIds,
                                onClick = {
                                    if (selectionMode) {
                                        selectedIds = if (book.id in selectedIds) {
                                            selectedIds - book.id
                                        } else {
                                            selectedIds + book.id
                                        }
                                    } else {
                                        onOpenBook(book.id)
                                    }
                                },
                                onLongClick = {
                                    selectedIds = selectedIds + book.id
                                },
                                onReparse = { viewModel.requestReparse(book) },
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- 对话框 ----

    val import = state.import
    if (import.running) {
        ImportProgressDialog(stageLabel = import.stageLabel, fraction = import.fraction)
    }

    val pending = import.pending
    if (pending != null) {
        ImportResultDialog(
            pending = pending,
            onReparse = { charset, strategy -> viewModel.reparsePending(charset, strategy) },
            onConfirm = viewModel::confirmImport,
            onDismiss = viewModel::dismissImport,
        )
    }

    val reparseTarget = state.reparseTarget
    if (reparseTarget != null) {
        ReparseDialog(
            book = reparseTarget,
            onConfirm = { strategy ->
                viewModel.reparseBook(strategy)
            },
            onDismiss = viewModel::dismissReparse,
        )
    }
}

@Composable
private fun EmptyBookshelf(onImport: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_book),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("书架空空如也", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "导入本地 TXT 小说开始阅读\n支持 GBK / UTF-8 自动识别，可手动切换",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onImport) { Text("选择文件") }
    }
}

@Composable
private fun ContinueReadingCard(book: Book, onClick: () -> Unit) {
    val cover = CoverGenerator.styleFor(book)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(78.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Brush.linearGradient(listOf(cover.start, cover.end))),
                contentAlignment = Alignment.Center,
            ) {
                Text(cover.glyph, color = Color.White, fontSize = 24.sp)
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "继续阅读",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { book.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "已读 ${formatPercent(book.progressPercent)} · 第 ${book.lastChapterIndex + 1} 章",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BookCover(book: Book, modifier: Modifier = Modifier) {
    val cover = CoverGenerator.styleFor(book)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(listOf(cover.start, cover.end))),
        contentAlignment = Alignment.Center,
    ) {
        Text(cover.glyph, color = Color.White, fontSize = 40.sp)
        // 格式角标
        Text(
            text = book.format.name,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp),
        )
    }
}

@Composable
private fun BookCard(
    book: Book,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onReparse: () -> Unit,
) {
    Column {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            colors = if (selected) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            } else {
                CardDefaults.cardColors()
            },
        ) {
            BookCover(book, modifier = Modifier.padding(8.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (book.lastReadAt != null) {
                    "${formatPercent(book.progressPercent)} · ${book.chapterCount} 章"
                } else {
                    "${book.chapterCount} 章"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (book.lastReadAt == null) {
                // 仅未读过的书提供「重新解析」快捷入口；读过的从设置/详情进入
                TextButton(onClick = onReparse, contentPadding = PaddingValues(0.dp)) {
                    Text("重解析", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ImportProgressDialog(stageLabel: String, fraction: Float) {
    AlertDialog(
        onDismissRequest = { /* 导入中不可取消 */ },
        title = { Text("正在导入") },
        text = {
            Column {
                Text(stageLabel, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ImportResultDialog(
    pending: PendingImport,
    onReparse: (String, SplitStrategy) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var charset by remember { mutableStateOf(pending.charsetName) }
    var strategy by remember { mutableStateOf(pending.strategy) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入确认", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(pending.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${pending.format.name} · ${formatFileSize(pending.fileSize)} · " +
                        "${pending.chapters.size} 章 · ${formatChars(pending.totalChars)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text("编码", style = MaterialTheme.typography.labelMedium)
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
                Text("切分策略", style = MaterialTheme.typography.labelMedium)
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
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ReparseDialog(
    book: Book,
    onConfirm: (SplitStrategy) -> Unit,
    onDismiss: () -> Unit,
) {
    var strategy by remember { mutableStateOf(SplitStrategy.CONSERVATIVE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重新解析《${book.title}》") },
        text = {
            Column {
                Text(
                    "将按所选策略重新切分章节，阅读进度会重置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SplitStrategy.entries.forEach { option ->
                        FilterChip(
                            selected = strategy == option,
                            onClick = { strategy = option },
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
        },
        confirmButton = { TextButton(onClick = { onConfirm(strategy) }) { Text("重新解析") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---- 格式化 ----

private fun formatPercent(percent: Float): String =
    if (percent >= 99.5f) "100%" else "${percent.toInt()}%"

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatChars(chars: Long): String = when {
    chars >= 10_000 -> "%.1f 万字".format(chars / 10_000.0)
    else -> "$chars 字"
}
