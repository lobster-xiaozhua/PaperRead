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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import io.github.lunalight.paperread.ui.resolve


/**
 * 书架页：继续阅读、书籍网格、导入流水线（进度 / 结果确认 / 重新解析）、多选删除 / 传书。
 */
@Composable
fun BookshelfScreen(
    onOpenSettings: () -> Unit,
    onOpenBook: (Long) -> Unit,
    onStartTransfer: (List<Long>) -> Unit,
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
        val message = state.message?.resolve(context) ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(stringResource(R.string.bookshelf_selection_count, selectedIds.size))
                    },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.bookshelf_selection_exit),
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { onStartTransfer(selectedIds.toList()) },
                            enabled = selectedIds.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.bookshelf_send_selected),
                            )
                        }
                        IconButton(onClick = {
                            state.books.filter { it.id in selectedIds }
                                .forEach { viewModel.deleteBook(it) }
                            selectedIds = emptySet()
                        }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.bookshelf_delete_selected),
                            )
                        }
                    },
                )
            } else {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        // 排序菜单
                        var menuExpanded by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.bookshelf_sort),
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(order.labelRes)) },
                                        onClick = {
                                            viewModel.setSortOrder(order)
                                            menuExpanded = false
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.transfer_title)) },
                                    onClick = {
                                        menuExpanded = false
                                        onStartTransfer(selectedIds.toList())
                                    },
                                )
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.settings_title),
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            // 空态时隐藏 FAB：空态自己有主行动（导入书籍）+ 次行动（局域网传书），
            // 两个「导入书籍」按钮并存既重复又让自动化测试无法唯一定位（AC-18.5）
            if (!selectionMode && state.books.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.bookshelf_import_button)) },
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
                        onStartTransfer = { onStartTransfer(emptyList()) },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
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
        ImportProgressDialog(
            stageLabel = import.stageLabel,
            fraction = import.fraction,
            onCancel = viewModel::cancelRunningImport,
        )
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

    val duplicateTarget = state.duplicateTarget
    if (duplicateTarget != null) {
        DuplicateDialog(
            existing = duplicateTarget,
            onReplace = viewModel::replaceDuplicate,
            onDismiss = viewModel::dismissDuplicate,
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
private fun EmptyBookshelf(
    onImport: () -> Unit,
    onStartTransfer: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        Text(stringResource(R.string.bookshelf_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        // AC-18.5：一句话说明 + 主「导入书籍」+ 次「局域网传书」
        Text(
            text = stringResource(R.string.bookshelf_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onImport) {
            Text(stringResource(R.string.bookshelf_import_button))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onStartTransfer) {
            Text(stringResource(R.string.transfer_title))
        }
    }
}

@Composable
private fun ContinueReadingCard(book: Book, onClick: () -> Unit) {
    val cover = CoverGenerator.styleFor(book, stringResource(R.string.bookshelf_cover_glyph_fallback))
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
                    stringResource(R.string.bookshelf_continue_reading),
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
                    stringResource(
                        R.string.bookshelf_continue_progress,
                        formatPercent(book.progressPercent),
                        book.lastChapterIndex + 1,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BookCover(book: Book, modifier: Modifier = Modifier) {
    val cover = CoverGenerator.styleFor(book, stringResource(R.string.bookshelf_cover_glyph_fallback))
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
    val progressText = if (book.lastReadAt != null) {
        stringResource(
            R.string.bookshelf_card_progress,
            formatPercent(book.progressPercent),
            book.chapterCount,
        )
    } else {
        stringResource(R.string.bookshelf_card_chapters, book.chapterCount)
    }
    // AC-22.1：书籍项必须被 TalkBack 读成一句「书名 + 进度」。
    // 封面 / 书名 / 进度是三个视觉节点，合并成一个可点击语义节点后
    // 焦点只落在卡片上，朗读内容由 contentDescription 兜住。
    val itemDescription = stringResource(R.string.a11y_bookshelf_book_item, book.title, progressText)
    val selectedDescription = stringResource(R.string.a11y_bookshelf_book_selected)
    Column {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .semantics(mergeDescendants = true) {
                    contentDescription = itemDescription
                    stateDescription = if (selected) selectedDescription else ""
                },
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
                text = progressText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (book.lastReadAt == null) {
                // 仅未读过的书提供「重新解析」快捷入口；读过的从设置/详情进入
                TextButton(
                    onClick = onReparse,
                    contentPadding = PaddingValues(0.dp),
                    // AC-22.4：`contentPadding = 0` 会把按钮压到 48dp 以下，
                    // 这里显式给最小高度，触达区域不随字号 / 内边距缩水。
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(R.string.bookshelf_reparse_short),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportProgressDialog(stageLabel: String, fraction: Float, onCancel: () -> Unit) {
    AlertDialog(
        modifier = Modifier.widthIn(max = 560.dp),
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.bookshelf_import_running)) },
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
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.bookshelf_import_cancel))
            }
        },
    )
}

/** 判重对话框（AC-1.5）：SHA-256 命中，替换 / 取消。 */
@Composable
private fun DuplicateDialog(
    existing: Book,
    onReplace: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.widthIn(max = 560.dp),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookshelf_duplicate_title)) },
        text = {
            Text(stringResource(R.string.bookshelf_duplicate_message, existing.title))
        },
        confirmButton = {
            TextButton(onClick = onReplace) {
                Text(stringResource(R.string.bookshelf_duplicate_replace))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
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
        modifier = Modifier.widthIn(max = 560.dp),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookshelf_reparse_title, book.title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.bookshelf_reparse_message),
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
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(strategy) }) {
                Text(stringResource(R.string.bookshelf_reparse_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

// ---- 格式化 ----

private fun formatPercent(percent: Float): String =
    if (percent >= 99.5f) "100%" else "${percent.toInt()}%"
