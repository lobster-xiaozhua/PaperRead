package io.github.lunalight.paperread.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lunalight.paperread.data.settings.ReaderSettings
import io.github.lunalight.paperread.ui.adaptive.LocalWindowWidthClass
import io.github.lunalight.paperread.ui.adaptive.WindowWidthClass
import io.github.lunalight.paperread.ui.theme.ReaderTheme

/**
 * 正文渲染（连续滚动）与共享的正文排版部件。
 *
 * 归属索引：
 * - [ReaderContent]：单栏 LazyColumn 段落排版（默认阅读形态）
 * - [ChapterFooter]：章节尾（章位置 + 进度 + 「下一章」），单栏 / 跨页 / 翻页共用
 * - [ReaderError]：错误页（正文缺失时附「删除该条目」出口）
 * - [readerTextStyle] / [paragraphHighlight]：字体应用与朗读高亮槽——两条渲染
 *   路径（滚动 / 翻页）共用同一份口径，避免两处各写一遍而漂移
 *
 * 本文件只做渲染：状态由 [ReaderViewModel] 提供，事件经 `viewModel::xxx` 回调回抛。
 */

/**
 * 正文字体应用：字号 / 行距 / 字族 / 前景色，滚动与翻页两条路径共用。
 *
 * @param settings 当前阅读设置（字号 / 行距 / 字族来源）
 * @param theme 当前阅读主题（正文色来源）
 */
internal fun readerTextStyle(settings: ReaderSettings, theme: ReaderTheme): TextStyle = TextStyle(
    fontFamily = if (settings.fontFamily == "serif") FontFamily.Serif else FontFamily.SansSerif,
    fontSize = settings.fontSizeSp.sp,
    lineHeight = (settings.fontSizeSp * settings.lineHeightRatio).sp,
    color = theme.onBackground,
)

/**
 * 段落渲染槽：段距 padding + 朗读高亮的统一视觉（accent 18% 圆角底）。
 *
 * @param spacing 段距（底部 padding）
 * @param speaking 该段是否正在朗读（暂停态保留高亮，与朗读条口径一致）
 */
internal fun Modifier.paragraphHighlight(
    theme: ReaderTheme,
    spacing: Dp,
    speaking: Boolean,
): Modifier = this
    .padding(bottom = spacing)
    .then(
        if (speaking) {
            Modifier
                .fillMaxWidth()
                .background(
                    color = theme.accent.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 4.dp, vertical = 2.dp)
        } else {
            Modifier
        },
    )

/**
 * 单栏连续滚动正文。
 *
 * 双页跨页（[PaginatedSpread]）在 Expanded 大屏 + 开关开启时优先接管，
 * 其余情况走本函数：LazyColumn 段落排版 + 音量键翻章 + 点击正文切换工具栏 +
 * ReaderPosition 恢复与滚动上报。
 */
@Composable
internal fun ReaderContent(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    theme: ReaderTheme,
    isLandscape: Boolean,
) {
    // 章节切换时重置滚动状态；配置变更时经 rememberSaveable 恢复
    val listState = rememberSaveable(state.chapterIndex, saver = LazyListState.Saver) {
        LazyListState()
    }

    // 进度恢复 / 跳转定位：以 restoreSeq 作为一次性票据。
    // 用序号而非 (章节, 段落) 做 key，才能覆盖「同章跳到首段」；
    // 消费后记入 consumedSeq，旋转屏幕重建组合时不会把用户拖回旧位置。
    var consumedSeq by rememberSaveable(state.chapterIndex) { mutableStateOf(-1L) }
    LaunchedEffect(state.restoreSeq) {
        if (state.restoreSeq > consumedSeq) {
            consumedSeq = state.restoreSeq
            listState.scrollToItem(
                index = state.initialParagraph.coerceAtLeast(0),
                scrollOffset = state.initialScrollOffset.coerceAtLeast(0),
            )
        }
    }

    // 滚动位置上报（防抖保存在 VM 内完成）
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            viewModel.onScrollPosition(index, offset)
        }
    }

    // 朗读高亮跟随：正在朗读的段变化时把正文滚到它（听书时无需手动翻页）。
    // 只在 speakingParagraph 变化时触发，用户手动滚动不会被打断。
    LaunchedEffect(state.speech.speakingParagraph) {
        val speaking = state.speech.speakingParagraph
        if (state.speech.isActive && speaking >= 0 && speaking <= listState.layoutInfo.totalItemsCount - 1) {
            listState.animateScrollToItem(index = speaking + 1, scrollOffset = 0)
        }
    }

    val textStyle = readerTextStyle(state.settings, theme)

    // 双页跨页：仅 Expanded 大屏（平板/横屏）且开关开启时启用；
    // 否则走下方单栏连续滚动（与 Compact/Medium 行为完全一致）。
    // 焦点请求在单栏路径内发起：focusRequester 只挂在 LazyColumn 上，
    // 双页路径由 PaginatedSpread 自管焦点（提前 requestFocus 会因
    // FocusRequester 未附加到任何节点而抛 IllegalStateException）。
    val widthClass = LocalWindowWidthClass.current
    val twoPage = state.settings.twoPageSpread && widthClass == WindowWidthClass.EXPANDED
    if (twoPage) {
        PaginatedSpread(
            state = state,
            viewModel = viewModel,
            theme = theme,
            textStyle = textStyle,
            paragraphSpacingDp = state.settings.paragraphSpacingDp,
        )
        return
    }

    // 单栏路径：焦点请求与 LazyColumn 的 focusRequester 配套（音量键翻章需要焦点）。
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // 正文也要避开安全区：系统栏可见时让位，隐藏时归零；
    // 横屏刘海/挖孔即使系统栏隐藏也不会消失，靠 safeDrawing 的 displayCutout 部分保住文字。
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .widthIn(max = 840.dp)
            .wrapContentWidth(Alignment.CenterHorizontally)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (!state.settings.volumeKeyTurnPage) return@onPreviewKeyEvent false
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.VolumeDown -> {
                        viewModel.nextChapter()
                        true
                    }

                    Key.VolumeUp -> {
                        viewModel.previousChapter()
                        true
                    }

                    else -> false
                }
            }
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { viewModel.toggleChrome() },
        contentPadding = PaddingValues(
            start = state.settings.horizontalMarginDp.dp + safeInsets.calculateLeftPadding(layoutDirection),
            end = state.settings.horizontalMarginDp.dp + safeInsets.calculateRightPadding(layoutDirection),
            top = (if (isLandscape) 8.dp else 56.dp) + safeInsets.calculateTopPadding(),
            bottom = (if (isLandscape) 8.dp else 96.dp) + safeInsets.calculateBottomPadding(),
        ),
    ) {
        item(key = "chapter-header") {
            Text(
                text = state.chapterTitle,
                style = MaterialTheme.typography.titleMedium,
                color = theme.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            )
        }
        itemsIndexed(
            items = state.paragraphs,
            key = { index, _ -> "p$index" },
        ) { index, paragraph ->
            val speaking = state.speech.isActive && index == state.speech.speakingParagraph
            Text(
                text = paragraph,
                style = textStyle,
                textAlign = TextAlign.Justify,
                modifier = Modifier.paragraphHighlight(
                    theme = theme,
                    spacing = state.settings.paragraphSpacingDp.dp,
                    speaking = speaking,
                ),
            )
        }
        item(key = "chapter-footer") {
            ChapterFooter(
                chapterPosition = state.chapterPosition,
                progressPercent = state.progressPercent,
                theme = theme,
                hasNext = state.chapterIndex < state.chapters.lastIndex,
                onNext = viewModel::nextChapter,
            )
        }
    }
}

/** 章节尾：章位置 + 已读进度；有下一章时给「下一章」出口，末章显示「已读完」。 */
@Composable
internal fun ChapterFooter(
    chapterPosition: String,
    progressPercent: Float,
    theme: ReaderTheme,
    hasNext: Boolean,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = chapterPosition,
            style = MaterialTheme.typography.labelMedium,
            color = theme.secondary,
        )
        Spacer(Modifier.height(8.dp))
        if (hasNext) {
            TextButton(onClick = onNext) {
                Text("下一章", color = theme.accent)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = theme.accent)
            }
        } else {
            Text(
                text = "已读完 · ${"%.1f".format(progressPercent)}%",
                style = MaterialTheme.typography.labelMedium,
                color = theme.secondary,
            )
        }
    }
}

/** 错误页：统一给出「返回书架」；正文文件缺失时额外给「删除该条目」。 */
@Composable
internal fun ReaderError(
    message: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("返回书架") }
        if (onDelete != null) {
            // 正文文件缺失时「重新解析」无用且可能是破坏性操作，只给删除出口
            TextButton(onClick = onDelete) {
                Text("删除该条目", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
