package io.github.lunalight.paperread.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.ui.asString
import io.github.lunalight.paperread.ui.theme.ReaderTheme

/**
 * 阅读器「外壳」部件：顶栏 / 底栏 / 目录与书签弹层 / 书签备注编辑。
 *
 * 归属索引：
 * - [ReaderTopBar]：返回 / 书名·章名 / 加书签 / 朗读开关 / 本书搜索 / 目录 / 设置
 * - [ReaderBottomBar]：上一页 / 上一章 / 已读进度 / 下一章 / 下一页
 * - [TocSheet]：目录 + 书签双 Tab 弹层（[ChapterList] / [BookmarkList]）
 * - [BookmarkNoteDialog]：书签备注编辑（AC-17.1 / AC-17.3）
 *
 * 搜索面板本身不在本文件（`ui/reader/search/BookSearchPanel.kt`），这里只放入口
 * ——顶栏一个图标，避免外壳文件继续膨胀。书签分组的**纯逻辑**已抽到
 * `BookmarkPresentation.kt`（T-022 顺手项），本文件只做渲染：状态由
 * [ReaderUiState] 提供，动作全部由调用方以 lambda 传入。
 *
 * 文案资源化（T-022 · AC-21.4）：本文件所有用户可见文字走 `stringResource`，
 * 无中文字面量残留；`ReaderUiState.chapterPosition` 等 VM 侧文案经
 * [io.github.lunalight.paperread.ui.UiText] 携带资源 id，渲染时才解析。
 *
 * 无障碍（T-023）：
 * - AC-22.1 顶栏 7 个入口全部有 `contentDescription`（图标按钮的唯一可读标签）；
 * - AC-22.2 「三区点按 + 滑动翻页」的手势由底栏两侧的 [ReaderBottomBar] 翻页
 *   按钮兜底（TalkBack 下自定义手势不可靠，必须有可聚焦按钮替代）；
 * - AC-22.4 底栏按钮容器 `heightIn(min = 48.dp)`，避免大字号下按钮被压到 48dp 以下。
 */

/**
 * 翻页动作句柄（AC-22.2）。
 *
 * 翻页能力住在 `ReaderPager` 的 `PagerState` 里（页由正文度量动态装箱），
 * 底栏却在 `ReaderScreen` 的另一处组合中——句柄把「当前页能不能翻 / 怎么翻」
 * 上抛给外壳按钮，双向各自持有：`ReaderPagerContent` 负责 [bind]，
 * `ReaderBottomBar` 负责调用。没有翻页模式（连续滚动）时句柄为 null，
 * 底栏不渲染翻页按钮。
 */
@Stable
internal class PageTurnHandle {
    /** 当前页之前是否还有页（首页禁用「上一页」）。 */
    var canPreviousPage by mutableStateOf(false)
        private set

    /** 当前页之后是否还有页（末页禁用「下一页」）。 */
    var canNextPage by mutableStateOf(false)
        private set

    private var previousAction: () -> Unit = {}
    private var nextAction: () -> Unit = {}

    /**
     * 由翻页容器在页状态变化时重绑（页数 / 当前页 / 章节切换都会变）。
     *
     * 传 lambda 而非状态：翻页动作依赖 `PagerState` 与协程作用域，随组合更新。
     */
    fun bind(
        canPrevious: Boolean,
        canNext: Boolean,
        previous: () -> Unit,
        next: () -> Unit,
    ) {
        canPreviousPage = canPrevious
        canNextPage = canNext
        previousAction = previous
        nextAction = next
    }

    fun previousPage() = previousAction()

    fun nextPage() = nextAction()
}

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
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.book?.title ?: "",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${state.chapterPosition.asString()} · ${state.chapterTitle}",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onAddBookmark) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = stringResource(R.string.reader_add_bookmark),
                    tint = theme.accent,
                )
            }
            IconButton(onClick = onToggleSpeech) {
                Icon(
                    painter = painterResource(R.drawable.ic_speech),
                    contentDescription = stringResource(
                        if (state.speech.isActive) R.string.speech_stop else R.string.speech_start,
                    ),
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
                Icon(
                    Icons.AutoMirrored.Filled.List,
                    contentDescription = stringResource(R.string.reader_toc),
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.reader_settings),
                )
            }
        }
    }
}

/**
 * 底栏：翻页（AC-22.2 手势替代入口）+ 切章 + 已读进度。
 *
 * 布局承压点（AC-22.3 · 200% 字号）：中间进度文本 `weight(1f)` + 单行省略，
 * 两侧按钮固定 48dp 触达尺寸，字号放大时先牺牲进度文本的可读长度，
 * 不出现文字互相重叠。
 */
@Composable
internal fun ReaderBottomBar(
    state: ReaderUiState,
    theme: ReaderTheme,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    pageTurn: PageTurnHandle? = null,
) {
    val progressText = stringResource(
        R.string.reader_progress_read,
        "%.1f".format(state.progressPercent),
    )
    val previousPageDescription = stringResource(R.string.reader_prev_page)
    val nextPageDescription = stringResource(R.string.reader_next_page)
    Surface(color = theme.background, contentColor = theme.onBackground) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                    ),
                )
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pageTurn != null) {
                IconButton(
                    onClick = { pageTurn.previousPage() },
                    enabled = pageTurn.canPreviousPage,
                    modifier = Modifier.semantics { contentDescription = previousPageDescription },
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                }
            }
            TextButton(
                onClick = onPrevious,
                enabled = state.chapterIndex > 0,
            ) {
                Text(stringResource(R.string.reader_prev_chapter))
            }
            Text(
                text = progressText,
                style = MaterialTheme.typography.labelMedium,
                color = theme.secondary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                onClick = onNext,
                enabled = state.chapterIndex < state.chapters.lastIndex,
            ) {
                Text(stringResource(R.string.reader_next_chapter))
            }
            if (pageTurn != null) {
                IconButton(
                    onClick = { pageTurn.nextPage() },
                    enabled = pageTurn.canNextPage,
                    modifier = Modifier.semantics { contentDescription = nextPageDescription },
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }
    }
}

/**
 * 目录 / 书签弹层：双 Tab。
 *
 * - 目录 Tab：章节列表 + 当前章高亮，点击跳转（AC-6.1）
 * - 书签 Tab：**按章分组**的书签列表（组头 = 章名 + 条数），条目显示备注摘要 /
 *   摘录 + 位置（段序号 + 时间），点击跳转、铅笔编辑备注、尾部删除
 *   （AC-6.2 / AC-6.3 / AC-17.2 / AC-17.3）
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
    onBookmarkEditNote: (Bookmark) -> Unit = {},
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
                text = { Text(stringResource(R.string.reader_toc_tab_chapters, state.chapters.size)) },
                selectedContentColor = theme.accent,
                unselectedContentColor = theme.secondary,
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(stringResource(R.string.reader_toc_tab_bookmarks, state.bookmarks.size)) },
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
                onEditNote = onBookmarkEditNote,
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
            val currentDescription = stringResource(R.string.reader_chapter_current)
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
                        text = stringResource(R.string.reader_chapter_ordinal, index + 1),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                leadingContent = {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = currentDescription,
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

/**
 * 书签列表（AC-17.2）：按章分组。
 *
 * 分组口径见 `BookmarkPresentation.bookmarkGroups`（纯函数，单测覆盖）。
 */
@Composable
private fun BookmarkList(
    state: ReaderUiState,
    theme: ReaderTheme,
    onSelect: (Bookmark) -> Unit,
    onEditNote: (Bookmark) -> Unit,
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
                text = stringResource(R.string.reader_bookmark_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = theme.secondary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.reader_bookmark_empty_hint),
                style = MaterialTheme.typography.labelSmall,
                color = theme.secondary,
            )
        }
        return
    }
    // 组顺序 = 章节顺序（第 1 章在前），不随书签添加时间跳动
    val groups = remember(state.bookmarks) { bookmarkGroups(state.bookmarks) }
    LazyColumn(modifier = modifier) {
        groups.forEach { (chapterIndex, bookmarks) ->
            item(key = "chapter-$chapterIndex") {
                BookmarkGroupHeader(
                    title = state.chapters.getOrNull(chapterIndex)?.title
                        ?: stringResource(R.string.reader_chapter_ordinal, chapterIndex + 1),
                    count = bookmarks.size,
                    theme = theme,
                )
            }
            itemsIndexed(
                items = bookmarks,
                key = { _, bookmark -> bookmark.id },
            ) { _, bookmark ->
                BookmarkRow(
                    bookmark = bookmark,
                    theme = theme,
                    onSelect = onSelect,
                    onEditNote = onEditNote,
                    onDelete = onDelete,
                )
            }
        }
    }
}

/** 分组标题：章名 + 该章书签数（AC-17.2 的「按章分组」视觉锚点）。 */
@Composable
private fun BookmarkGroupHeader(title: String, count: Int, theme: ReaderTheme) {
    Surface(color = theme.accent.copy(alpha = 0.08f), contentColor = theme.onBackground) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = theme.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.reader_bookmark_group_count, count),
                style = MaterialTheme.typography.labelSmall,
                color = theme.secondary,
            )
        }
    }
}

/**
 * 单条书签：备注摘要（无备注则显示摘录）+ 位置（段序号 + 时间）。
 *
 * 交互沿用改动前语义——点条目跳转、尾部删除（AC-17.3 不回归），
 * 新增铅笔按钮进入备注编辑（不与「点击跳转」抢同一个手势）。
 */
@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    theme: ReaderTheme,
    onSelect: (Bookmark) -> Unit,
    onEditNote: (Bookmark) -> Unit,
    onDelete: (Long) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(
                text = bookmark.note.ifBlank { bookmark.excerpt },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            // 有备注时，摘录降到第二行做「这是哪一段」的上下文；无备注时不必重复
            if (bookmark.hasNote) {
                Text(
                    text = bookmark.excerpt,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        overlineContent = {
            Text(
                text = stringResource(
                    R.string.reader_bookmark_position,
                    bookmark.paragraphIndex + 1,
                ) + " · " + formatBookmarkTime(bookmark.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = theme.secondary,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onEditNote(bookmark) }) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.reader_bookmark_edit_note),
                        tint = if (bookmark.hasNote) theme.accent else theme.secondary,
                    )
                }
                IconButton(onClick = { onDelete(bookmark.id) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.reader_bookmark_delete),
                        tint = theme.secondary,
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = theme.background),
        modifier = Modifier.clickable { onSelect(bookmark) },
    )
}

/**
 * 书签备注编辑对话框（AC-17.1：可空、≤ 500 字；AC-17.3：保存后列表刷新）。
 *
 * 输入框下方常驻计数提示 `n/500`，达到上限时保存按钮仍可用（内容已被
 * [Bookmark.clampNote] 截断到上限），避免「黏在 500 字上却点不了保存」。
 */
@Composable
fun BookmarkNoteDialog(
    editor: BookmarkEditorState,
    theme: ReaderTheme,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = theme.background,
        titleContentColor = theme.onBackground,
        textContentColor = theme.onBackground,
        title = {
            Text(
                text = stringResource(
                    if (editor.isNew) R.string.reader_bookmark_note_title_new
                    else R.string.reader_bookmark_note_title_edit,
                ),
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = editor.draft,
                    onValueChange = onNoteChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.reader_bookmark_note_hint)) },
                    minLines = 3,
                    maxLines = 6,
                )
                Text(
                    text = stringResource(
                        R.string.reader_bookmark_note_counter,
                        editor.draft.length,
                        Bookmark.NOTE_MAX_LENGTH,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.secondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    textAlign = TextAlign.End,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = editor.canSave) {
                Text(stringResource(R.string.reader_bookmark_note_save), color = theme.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.common_cancel), color = theme.secondary)
            }
        },
    )
}
