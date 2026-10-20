package io.github.lunalight.paperread.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import io.github.lunalight.paperread.ui.theme.ReaderTheme

/**
 * 平板/横屏大屏的双页跨页阅读：一屏左右两页（左=pages[i]，右=pages[i+1]），
 * 点按右侧翻下一 spread、左侧翻上一 spread、中央切换工具栏。
 *
 * 进度复用单栏同一口径：左页首段 [PageBreak.startParagraph] + [PageBreak.startOffsetPx]
 * 经 [ReaderViewModel.onScrollPosition] 上报，与
 * [io.github.lunalight.paperread.domain.model.ReaderPosition] 完全一致，
 * 因此切章/恢复/旋屏保位置逻辑零改动。
 *
 * 段落为原子块（不跨页拆分）；单段超过页高时该段独占一页（渲染层裁切，已知限制）。
 */
@Composable
fun PaginatedSpread(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    theme: ReaderTheme,
    textStyle: TextStyle,
    paragraphSpacingDp: Int,
    modifier: Modifier = Modifier,
    gutterDp: Dp = 24.dp,
) {
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val contentWidthPx = with(density) { maxWidth.roundToPx() }
        val contentHeightPx = with(density) { maxHeight.roundToPx() }
        val gutterPx = with(density) { gutterDp.roundToPx() }
        val pageWidthPx = ((contentWidthPx - gutterPx) / 2f).coerceAtLeast(1f).toInt()
        val pageHeightPx = contentHeightPx.coerceAtLeast(1)
        val paragraphSpacingPx = with(density) { paragraphSpacingDp.dp.roundToPx() }

        val pages = rememberChapterPages(
            paragraphs = state.paragraphs,
            textStyle = textStyle,
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
            density = density,
            fontFamilyResolver = fontFamilyResolver,
            paragraphSpacingPx = paragraphSpacingPx,
        )

        if (pages.isEmpty()) return@BoxWithConstraints

        val lastPage = pages.lastIndex
        var leftPage by rememberSaveable(state.chapterIndex) { mutableIntStateOf(0) }

        // 恢复 / 跳转：与单栏 scrollToItem 对应，以 restoreSeq 作为一次性票据
        var consumedSeq by rememberSaveable(state.chapterIndex) { mutableLongStateOf(-1L) }
        LaunchedEffect(state.restoreSeq) {
            if (state.restoreSeq > consumedSeq) {
                consumedSeq = state.restoreSeq
                val target = (pageIndexForPosition(pages, state.initialParagraph) / 2) * 2
                leftPage = target.coerceIn(0, maxOf(0, lastPage - 1))
            }
        }

        // 进度上报：左页首段即当前位置（与单栏 firstVisibleItemIndex 同口径）
        LaunchedEffect(leftPage, pages) {
            val idx = leftPage.coerceIn(0, lastPage)
            viewModel.onScrollPosition(pages[idx].startParagraph, pages[idx].startOffsetPx)
        }

        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        Box(
            modifier = Modifier
                .fillMaxSize()
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
                },
        ) {
            // 两页内容：右页仅在确实存在（rightIndex≠leftPage）时渲染，
            // 单页章节（lastPage==0）不会把同一页左右重复渲染。
            val rightIndex = (leftPage + 1).coerceAtMost(lastPage)
            val showRightPage = rightIndex != leftPage
            Row(modifier = Modifier.fillMaxSize()) {
                PageColumn(
                    pages = pages,
                    pageIndex = leftPage,
                    paragraphs = state.paragraphs,
                    textStyle = textStyle,
                    theme = theme,
                    showTitle = leftPage == 0,
                    chapterTitle = state.chapterTitle,
                    paragraphSpacingDp = paragraphSpacingDp,
                    modifier = Modifier.weight(1f),
                    showFooter = lastPage == 0,
                    hasNext = state.chapterIndex < state.chapters.lastIndex,
                    progressPercent = state.progressPercent,
                    chapterPosition = state.chapterPosition,
                    onNextChapter = viewModel::nextChapter,
                )
                if (showRightPage) {
                    Spacer(Modifier.width(gutterDp))
                    PageColumn(
                        pages = pages,
                        pageIndex = rightIndex,
                        paragraphs = state.paragraphs,
                        textStyle = textStyle,
                        theme = theme,
                        showTitle = false,
                        chapterTitle = "",
                        paragraphSpacingDp = paragraphSpacingDp,
                        modifier = Modifier.weight(1f),
                        showFooter = rightIndex == lastPage,
                        hasNext = state.chapterIndex < state.chapters.lastIndex,
                        progressPercent = state.progressPercent,
                        chapterPosition = state.chapterPosition,
                        onNextChapter = viewModel::nextChapter,
                    )
                }
            }

            // 三区点按：左=上一 spread，中=切换工具栏，右=下一 spread
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // 空 Box 在 Row 内高度 wrapContent 会塌陷成 0，交互层将点不中；
                        // 三区是覆盖全屏的手势热区，必须撑满高度。
                        .fillMaxHeight()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            if (leftPage > 0) leftPage = (leftPage - 2).coerceAtLeast(0)
                        },
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { viewModel.toggleChrome() },
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            if (leftPage + 1 <= lastPage) {
                                leftPage = (leftPage + 2).coerceAtMost(maxOf(0, lastPage - 1))
                            }
                        },
                )
            }
        }
    }
}

/** 单页：渲染 [pages] 中第 [pageIndex] 页所覆盖的段落区间。 */
@Composable
private fun PageColumn(
    pages: List<PageBreak>,
    pageIndex: Int,
    paragraphs: List<String>,
    textStyle: TextStyle,
    theme: ReaderTheme,
    showTitle: Boolean,
    chapterTitle: String,
    paragraphSpacingDp: Int,
    modifier: Modifier = Modifier,
    showFooter: Boolean = false,
    hasNext: Boolean = false,
    progressPercent: Float = 0f,
    chapterPosition: String = "",
    onNextChapter: () -> Unit = {},
) {
    val start = pages.getOrNull(pageIndex)?.startParagraph ?: 0
    val end = pages.getOrNull(pageIndex + 1)?.startParagraph ?: paragraphs.size
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
    ) {
        if (showTitle) {
            Text(
                text = chapterTitle,
                style = MaterialTheme.typography.titleMedium,
                color = theme.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
            )
        }
        for (i in start until end) {
            Text(
                text = paragraphs[i],
                style = textStyle,
                textAlign = TextAlign.Justify,
                modifier = Modifier.padding(bottom = paragraphSpacingDp.dp),
            )
        }
        if (showFooter) {
            ChapterFooter(
                chapterPosition = chapterPosition,
                progressPercent = progressPercent,
                theme = theme,
                hasNext = hasNext,
                onNext = onNextChapter,
            )
        }
    }
}
