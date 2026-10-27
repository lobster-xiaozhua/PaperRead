package io.github.lunalight.paperread.ui.reader.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.domain.search.SEARCH_MIN_QUERY_LENGTH
import io.github.lunalight.paperread.domain.search.SearchHit
import io.github.lunalight.paperread.ui.theme.ReaderTheme

/**
 * 本书搜索面板（US-15）：搜索框 + 流式结果列表 + 空态 + 取消。
 *
 * 归属索引：
 * - 入口：`ReaderChrome.kt` 顶栏搜索图标（AC-15.1）
 * - 状态与防抖：`ReaderViewModel`（`searchQuery` / `searchResults` / `searching`）
 * - 命中词高亮：[SearchHighlighter]（面板摘要与正文共用同一份匹配口径）
 *
 * 本文件只做渲染：输入、选结果、取消三个动作一律以 lambda 上抛，
 * 面板自身不持有搜索状态（旋转屏幕 / 重组不会丢正在进行的扫描，也不会重复发起）。
 */

/** 结果为空但还没输入够长度时的提示门槛（与 VM 的过滤口径共用领域常量）。 */
private val minQueryLength = SEARCH_MIN_QUERY_LENGTH

/**
 * 搜索面板（模态底部弹层）。
 *
 * @param query 当前关键词（受控：来自 ViewModel，面板不自己存）
 * @param results 已到达的命中（流式追加，先章先出）
 * @param searching 扫描是否进行中（true 时显示进度条，不显示「未找到」）
 * @param onQueryChange 关键词变化（VM 内做 300ms 防抖 + 最短长度过滤）
 * @param onSelect 点击结果：跳章 + 定位段落 + 高亮（AC-15.3）
 * @param onCancel 取消：回到发起搜索前的位置，且不改动已保存进度（AC-15.5）
 * @param onDismiss 下滑 / 点击外部关闭（与取消同一语义，由调用方决定是否回位）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookSearchPanel(
    query: String,
    results: List<SearchHit>,
    searching: Boolean,
    theme: ReaderTheme,
    onQueryChange: (String) -> Unit,
    onSelect: (SearchHit) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = theme.background,
        contentColor = theme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.reader_search_title),
                style = MaterialTheme.typography.titleMedium,
                color = theme.accent,
            )
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.reader_search_placeholder)) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.reader_search_clear),
                                tint = theme.secondary,
                            )
                        }
                    }
                },
            )
            SearchStatusLine(
                query = query,
                resultCount = results.size,
                searching = searching,
                theme = theme,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
            if (searching) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = theme.accent,
                )
            }
            SearchResultList(
                query = query,
                results = results,
                searching = searching,
                theme = theme,
                onSelect = onSelect,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.reader_search_cancel), color = theme.secondary)
                }
            }
        }
    }
}

/** 状态行：搜索中 / N 条结果 / 关键词过短提示（三态互斥，避免空态闪烁）。 */
@Composable
private fun SearchStatusLine(
    query: String,
    resultCount: Int,
    searching: Boolean,
    theme: ReaderTheme,
    modifier: Modifier,
) {
    val trimmed = query.trim()
    val text = when {
        searching -> stringResource(R.string.reader_search_running)
        trimmed.length < minQueryLength -> stringResource(
            R.string.reader_search_min_length,
            minQueryLength,
        )
        resultCount > 0 -> stringResource(R.string.reader_search_results, resultCount)
        else -> stringResource(R.string.reader_search_empty)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = theme.secondary,
        modifier = modifier,
    )
}

/** 结果列表：命中条目（章名 + 摘要 + 命中次数）；无结果时的空态文案见 [SearchStatusLine]。 */
@Composable
private fun SearchResultList(
    query: String,
    results: List<SearchHit>,
    searching: Boolean,
    theme: ReaderTheme,
    onSelect: (SearchHit) -> Unit,
    modifier: Modifier,
) {
    if (results.isEmpty()) {
        // 空列表用占位文本占住剩余空间：AC-15.7 的「未找到相关内容」在状态行，
        // 这里只补一句引导，避免用户以为面板坏了。
        Text(
            text = if (searching) "" else stringResource(R.string.reader_search_hint),
            style = MaterialTheme.typography.bodySmall,
            color = theme.secondary,
            textAlign = TextAlign.Center,
            modifier = modifier.padding(vertical = 24.dp),
        )
        return
    }
    LazyColumn(modifier = modifier) {
        items(
            items = results,
            key = { it.positionKey },
        ) { hit ->
            ListItem(
                headlineContent = {
                    Text(
                        text = hit.chapterTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Text(
                        text = SearchHighlighter.highlight(
                            text = hit.snippet,
                            query = query.trim(),
                            background = theme.accent,
                            bold = false,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                overlineContent = {
                    Text(
                        text = stringResource(R.string.reader_search_hit_count, hit.matchCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.secondary,
                    )
                },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = theme.secondary,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = theme.background),
                modifier = Modifier.clickable { onSelect(hit) },
            )
        }
    }
}
