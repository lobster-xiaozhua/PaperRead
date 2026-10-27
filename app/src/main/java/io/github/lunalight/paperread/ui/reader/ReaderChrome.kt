package io.github.lunalight.paperread.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.ui.theme.ReaderTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 阅读器「外壳」部件：顶栏 / 底栏 / 目录与书签弹层。
 *
 * 归属索引：
 * - [ReaderTopBar]：返回 / 书名·章名 / 加书签 / 朗读开关 / 本书搜索 / 目录 / 设置
 * - [ReaderBottomBar]：上一章 / 已读进度 / 下一章
 * - [TocSheet]：目录 + 书签双 Tab 弹层（[ChapterList] / [BookmarkList]）
 *
 * 搜索面板本身不在本文件（`ui/reader/search/BookSearchPanel.kt`），这里只放入口
 * ——顶栏一个图标，避免外壳文件继续膨胀。
 * 本文件只做渲染：状态由 [ReaderUiState] 提供，动作全部由调用方以 lambda 传入。
 */

@Composable
internal fun ReaderTopBar(
    state: ReaderUiState,
    theme: ReaderTheme,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onAddBookmark: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleSpeech: () -> Unit,
) {
    // 背景铺到屏幕边缘（沉浸观感），交互内容让进安全区（不被状态栏吃掉点击/图标）。
    // padding 加在内层 Row 而不是 Surface：Surface 仍全宽绘制，系统栏区域不会露出底层正文。
    Surface(color = theme.background, contentColor = theme.onBackground) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.book?.title ?: "",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    text = "${state.chapterPosition} · ${state.chapterTitle}",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.secondary,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onAddBookmark) {
                Icon(Icons.Filled.Star, contentDescription = "添加书签", tint = theme.accent)
            }
            IconButton(onClick = onToggleSpeech) {
                Icon(
                    painter = painterResource(R.drawable.ic_speech),
                    contentDescription = if (state.speech.isActive) "停止朗读" else "开始朗读",
                    tint = if (state.speech.isActive) theme.accent else theme.onBackground,
                )
            }
            // 本书搜索入口（AC-15.1：工具栏图标）
            IconButton(onClick = onOpenSearch) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.reader_search_open),
                )
            }
            IconButton(onClick = onOpenToc) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "目录")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "阅读设置")
            }
        }
    }
}

@Composable
internal fun ReaderBottomBar(
    state: ReaderUiState,
    theme: ReaderTheme,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(color = theme.background, contentColor = theme.onBackground) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onPrevious,
                enabled = state.chapterIndex > 0,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                Text("上一章")
            }
            Text(
                text = "已读 ${"%.1f".format(state.progressPercent)}%",
                style = MaterialTheme.typography.labelMedium,
                color = theme.secondary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            TextButton(
                onClick = onNext,
                enabled = state.chapterIndex < state.chapters.lastIndex,
            ) {
                Text("下一章")
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

/**
 * 目录 / 书签弹层：双 Tab。
 *
 * - 目录 Tab：章节列表 + 当前章高亮，点击跳转（AC-6.1）
 * - 书签 Tab：书签列表（章节 + 摘录 + 时间），点击跳转、尾钮删除（AC-6.2 / AC-6.3）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(
    state: ReaderUiState,
    theme: ReaderTheme,
    onSelect: (Int) -> Unit,
    onBookmarkSelect: (Bookmark) -> Unit,
    onBookmarkDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = theme.background,
        contentColor = theme.onBackground,
    ) {
        TabRow(
            selectedTabIndex = tab,
            containerColor = theme.background,
            contentColor = theme.onBackground,
        ) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text("目录 · ${state.chapters.size}") },
                selectedContentColor = theme.accent,
                unselectedContentColor = theme.secondary,
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text("书签 · ${state.bookmarks.size}") },
                selectedContentColor = theme.accent,
                unselectedContentColor = theme.secondary,
            )
        }
        when (tab) {
            0 -> ChapterList(
                state = state,
                theme = theme,
                onSelect = onSelect,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            )

            else -> BookmarkList(
                state = state,
                theme = theme,
                onSelect = onBookmarkSelect,
                onDelete = onBookmarkDelete,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun ChapterList(
    state: ReaderUiState,
    theme: ReaderTheme,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(modifier = modifier) {
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

@Composable
private fun BookmarkList(
    state: ReaderUiState,
    theme: ReaderTheme,
    onSelect: (Bookmark) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier,
) {
    if (state.bookmarks.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "还没有书签",
                style = MaterialTheme.typography.bodyMedium,
                color = theme.secondary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "阅读时点击工具栏星标即可添加",
                style = MaterialTheme.typography.labelSmall,
                color = theme.secondary,
            )
        }
        return
    }
    LazyColumn(modifier = modifier) {
        itemsIndexed(
            items = state.bookmarks,
            key = { _, bookmark -> bookmark.id },
        ) { _, bookmark ->
            val chapterTitle = state.chapters
                .getOrNull(bookmark.chapterIndex)?.title ?: "第 ${bookmark.chapterIndex + 1} 章"
            ListItem(
                headlineContent = {
                    Text(
                        text = chapterTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        text = bookmark.excerpt,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                overlineContent = {
                    Text(
                        text = formatBookmarkTime(bookmark.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.secondary,
                    )
                },
                trailingContent = {
                    IconButton(onClick = { onDelete(bookmark.id) }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除书签",
                            tint = theme.secondary,
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = theme.background),
                modifier = Modifier.clickable { onSelect(bookmark) },
            )
        }
    }
}

private fun formatBookmarkTime(epochMillis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
