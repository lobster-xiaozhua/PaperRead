package io.github.lunalight.paperread.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.lunalight.paperread.reader.PageBound
import io.github.lunalight.paperread.reader.pageIndexForStartParagraph
import io.github.lunalight.paperread.reader.paginate
import io.github.lunalight.paperread.ui.reader.search.SearchHighlighter
import io.github.lunalight.paperread.ui.asString
import io.github.lunalight.paperread.ui.theme.ReaderTheme
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlinx.coroutines.launch

/**
 * 翻页 / 跨页渲染（两种「非连续滚动」形态）。
 *
 * 归属索引：
 * - [ReaderPagerContent]：翻页模式入口（官方 HorizontalPager，按段边界切页）
 * - [ReaderPagerScaffold] / [PageContent] / [PageEdgeShadows]：翻页模式的脚手架、
 *   单页正文与仿真书页阴影
 * - [PaginatedSpread] / [PageColumn]：平板/横屏 Expanded 档的双页跨页
 *
 * 两种形态共用同一份进度口径（段落 + 像素偏移 → `ReaderPosition`），
 * 分页计算本身在 `Paginator.kt` / `rememberChapterPages.kt`（纯逻辑 + 度量，
 * 不在本文件）。
 */

/**
 * 翻页模式正文：官方 HorizontalPager 把章内段落按「段边界」切成一页页，左右滑动翻页。
 *
 * 与 [ReaderContent]（连续滚动）互斥、按 [io.github.lunalight.paperread.data.settings.ReaderSettings.pageTurnMode]
 * 切换共存——翻页模式默认关闭，滚动为开箱体验。
 *
 * 设计要点：
 * - 段落像素高度用 `TextMeasurer` 在 UI 层测出，喂给引擎纯函数 [paginate] 切页（零 Android 依赖）；
 * - 页码是 UI 层概念，**进度仍走既有 `ReaderPosition`**（落库按页起始段折算字符偏移），不动数据模型；
 * - 仿真质感：翻页时右缘抬起阴影 + 左缘书脊阴影随拖动增强；
 * - 点按左/中/右三区：上一页 / 唤出工具栏 / 下一页（观察者式 pointerInput，绝不 consume，避免吞掉 pager 的横向拖动）；
 * - 越章边界：末页继续翻 → 下一章；首页往回翻 → 上一章。
 */
@Composable
internal fun ReaderPagerContent(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    theme: ReaderTheme,
    isLandscape: Boolean,
    /** AC-22.2：把「当前页翻页能力」上抛给底栏按钮（TalkBack 手势替代入口）。 */
    pageTurn: PageTurnHandle? = null,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()

    val textStyle = readerTextStyle(state.settings, theme, rememberReaderFontFamily(state.settings))

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background),
    ) {
        val maxWidthPx = with(density) { maxWidth.roundToPx() }
        val maxHeightPx = with(density) { maxHeight.roundToPx() }
        val horizontalPaddingPx = with(density) {
            (state.settings.horizontalMarginDp.dp + safeInsets.calculateLeftPadding(layoutDirection)
                + safeInsets.calculateRightPadding(layoutDirection)).roundToPx()
        }
        val topPaddingPx = with(density) {
            ((if (isLandscape) 8.dp else 56.dp) + safeInsets.calculateTopPadding()).roundToPx()
        }
        val bottomPaddingPx = with(density) {
            ((if (isLandscape) 8.dp else 96.dp) + safeInsets.calculateBottomPadding()).roundToPx()
        }
        val contentWidthPx = max(maxWidthPx - horizontalPaddingPx, 1)
        val availableHeightPx = max(maxHeightPx - topPaddingPx - bottomPaddingPx, 1)
        val spacingPx = with(density) { state.settings.paragraphSpacingDp.dp.roundToPx() }

        val measurer = rememberTextMeasurer()
        // 段落高度只依赖「章 + 字号/行距/边距/宽度」，与页码无关，缓存避免每次翻页重测
        val paragraphHeights = remember(state.chapterIndex, state.paragraphs, textStyle, contentWidthPx) {
            state.paragraphs.map { para ->
                measurer.measure(
                    text = AnnotatedString(para),
                    style = textStyle,
                    constraints = Constraints(maxWidth = contentWidthPx, maxHeight = Constraints.Infinity),
                ).size.height
            }
        }
        val pages = remember(paragraphHeights, availableHeightPx, spacingPx) {
            paginate(paragraphHeights, availableHeightPx, spacingPx)
        }

        ReaderPagerScaffold(
            state = state,
            viewModel = viewModel,
            theme = theme,
            // 搜索命中高亮只对当前章生效（跳到别章后旧命中不再上色）
            highlight = state.searchHighlight?.takeIf { it.chapterIndex == state.chapterIndex },
            textStyle = textStyle,
            pages = pages,
            density = density,
            contentWidthPx = contentWidthPx,
            availableHeightPx = availableHeightPx,
            spacingPx = spacingPx,
            topPaddingPx = topPaddingPx,
            bottomPaddingPx = bottomPaddingPx,
            horizontalPaddingPx = horizontalPaddingPx,
            isLandscape = isLandscape,
            focusRequester = focusRequester,
            pageTurn = pageTurn,
        )
    }
}

@Composable
private fun ReaderPagerScaffold(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    theme: ReaderTheme,
    textStyle: TextStyle,
    highlight: SearchHighlightTarget?,
    pages: List<PageBound>,
    density: Density,
    contentWidthPx: Int,
    availableHeightPx: Int,
    spacingPx: Int,
    topPaddingPx: Int,
    bottomPaddingPx: Int,
    horizontalPaddingPx: Int,
    isLandscape: Boolean,
    focusRequester: FocusRequester,
    pageTurn: PageTurnHandle? = null,
) {
    val pagerState = rememberPagerState(initialPage = 0) { pages.size.coerceAtLeast(1) }
    val scope = rememberCoroutineScope()

    // 恢复 / 切章定位：restoreSeq 自增时把 pager 滚到「含初始段的页」；
    // 字号/边距等设置变更导致重排时，尽量停留在当前页号、越界则夹取，避免 pager 越界
    var consumedSeq by rememberSaveable(state.chapterIndex) { mutableStateOf(-1L) }
    LaunchedEffect(pages, state.restoreSeq) {
        if (pages.isEmpty()) return@LaunchedEffect
        val target = if (state.restoreSeq > consumedSeq) {
            consumedSeq = state.restoreSeq
            pageIndexForStartParagraph(pages, state.initialParagraph).coerceAtMost(pages.lastIndex)
        } else {
            pagerState.currentPage.coerceIn(0, pages.lastIndex)
        }
        pagerState.scrollToPage(target)
    }

    // 翻页落库：页面稳定后上报该页起始段（折算字符偏移，复用既有进度模型）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (pages.isEmpty()) return@collect
            viewModel.onPagePosition(pages[page.coerceIn(0, pages.lastIndex)].startParagraph)
        }
    }

    // 朗读跟随（AC-12.4，翻页模式口径）：朗读段变化时把 pager 滚到含该段的页。
    // 与滚动模式的 animateScrollToItem 同口径——只在 speakingParagraph 变化时
    // 触发，用户手动翻页不会被打断；朗读停止（段下标归 -1）时不动作。
    LaunchedEffect(state.speech.speakingParagraph) {
        if (!state.speech.isActive) return@LaunchedEffect
        val speaking = state.speech.speakingParagraph
        if (speaking < 0 || pages.isEmpty()) return@LaunchedEffect
        val target = pageIndexForStartParagraph(pages, speaking).coerceIn(0, pages.lastIndex)
        if (target != pagerState.currentPage) {
            pagerState.animateScrollToPage(target)
        }
    }

    val currentPage by remember { derivedStateOf { pagerState.currentPage } }

    // AC-22.2：把翻页能力暴露给外壳按钮。用 snapshotFlow 观察页码，
    // 换章（pages 变）或翻页时重新绑定可用态，按钮据此启用 / 禁用。
    // 首页 / 末页不做「跨章兜底」：切章有独立的「上一章 / 下一章」按钮，
    // 翻页按钮的语义严格限定在「同一章内翻页」，避免两个入口行为重叠。
    LaunchedEffect(pagerState, pages, pageTurn) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val lastIndex = pages.lastIndex
            pageTurn?.bind(
                canPrevious = page > 0,
                canNext = page in 0 until lastIndex,
                previous = {
                    if (pagerState.currentPage > 0) {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    }
                },
                next = {
                    if (pagerState.currentPage < pages.lastIndex) {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
            )
        }
    }

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
                        if (pagerState.currentPage < pages.lastIndex) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        } else {
                            viewModel.nextChapter()
                        }
                        true
                    }

                    Key.VolumeUp -> {
                        if (pagerState.currentPage > 0) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                        } else {
                            viewModel.previousChapter()
                        }
                        true
                    }

                    else -> false
                }
            }
            // 观察者式点按：只读指针、绝不 consume，避免吞掉 pager 的横向滑动
            .pointerInput(pages) {
                awaitPointerEventScope {
                    var downX = 0f
                    var downY = 0f
                    var downTime = 0L
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.changedToDown()) {
                            downX = change.position.x
                            downY = change.position.y
                            downTime = change.uptimeMillis
                        } else if (change.changedToUp()) {
                            val moved = (change.position - Offset(downX, downY)).getDistance()
                            val elapsed = change.uptimeMillis - downTime
                            if (moved < 24f && elapsed < 300L) {
                                val w = size.width.toFloat()
                                when {
                                    change.position.x < w / 3f -> {
                                        if (pagerState.currentPage > 0) {
                                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                                        } else {
                                            viewModel.previousChapter()
                                        }
                                    }

                                    change.position.x > w * 2f / 3f -> {
                                        if (pagerState.currentPage < pages.lastIndex) {
                                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                        } else {
                                            viewModel.nextChapter()
                                        }
                                    }

                                    else -> viewModel.toggleChrome()
                                }
                            }
                        }
                    }
                }
            },
    ) {
        if (pages.isNotEmpty()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { "page-$it" },
            ) { page ->
                PageContent(
                    page = page,
                    pages = pages,
                    paragraphs = state.paragraphs,
                    textStyle = textStyle,
                    theme = theme,
                    density = density,
                    contentWidthPx = contentWidthPx,
                    availableHeightPx = availableHeightPx,
                    spacingPx = spacingPx,
                    topPaddingPx = topPaddingPx,
                    bottomPaddingPx = bottomPaddingPx,
                    horizontalPaddingPx = horizontalPaddingPx,
                    showHeader = page == 0,
                    chapterTitle = state.chapterTitle,
                    speakingParagraph = state.speech.speakingParagraph,
                    highlight = highlight,
                )
            }

            // 仿真翻页边缘阴影（随拖动进度增强）
            PageEdgeShadows(pagerState = pagerState, theme = theme)

            // 页码指示（翻页模式）
            if (pages.size > 1) {
                Text(
                    text = "${currentPage + 1} / ${pages.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.secondary,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = with(density) { bottomPaddingPx.toDp() } - 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PageContent(
    page: Int,
    pages: List<PageBound>,
    paragraphs: List<String>,
    textStyle: TextStyle,
    theme: ReaderTheme,
    density: Density,
    contentWidthPx: Int,
    availableHeightPx: Int,
    spacingPx: Int,
    topPaddingPx: Int,
    bottomPaddingPx: Int,
    horizontalPaddingPx: Int,
    showHeader: Boolean,
    chapterTitle: String,
    /** 正在朗读的正文段下标（-1 = 未朗读；暂停态保留，与滚动模式高亮口径一致）。 */
    speakingParagraph: Int = -1,
    /** 搜索命中高亮目标（AC-15.3；null = 本页无命中）。 */
    highlight: SearchHighlightTarget? = null,
) {
    val start = pages[page].startParagraph
    val end = if (page + 1 < pages.size) pages[page + 1].startParagraph else paragraphs.size
    val pageParagraphs = paragraphs.subList(start, end)

    val topPad = with(density) { topPaddingPx.toDp() }
    val bottomPad = with(density) { bottomPaddingPx.toDp() }
    val hPad = with(density) { horizontalPaddingPx.toDp() }
    val spacing = with(density) { spacingPx.toDp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = hPad, end = hPad, top = topPad, bottom = bottomPad),
    ) {
        // 正常页内容恰好铺满可用高度；单段高于整屏的极端情况允许纵向滚动，避免裁切丢字
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            if (showHeader) {
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
            pageParagraphs.forEachIndexed { i, para ->
                // 高亮与滚动模式同一视觉（theme.accent 18% 圆角底），绝对段下标对齐
                val speaking = start + i == speakingParagraph
                val hitQuery = highlight?.takeIf { it.paragraphIndex == start + i }?.query
                Text(
                    text = SearchHighlighter.annotate(para, hitQuery, theme.accent),
                    style = textStyle,
                    textAlign = TextAlign.Justify,
                    modifier = Modifier.paragraphHighlight(
                        theme = theme,
                        spacing = spacing,
                        speaking = speaking,
                    ),
                )
            }
        }
    }
}

/** 翻页时右缘抬起阴影 + 左缘书脊阴影，随拖动进度增强，营造仿真书页厚度。 */
@Composable
private fun PageEdgeShadows(pagerState: PagerState, theme: ReaderTheme) {
    val offset by remember { derivedStateOf { pagerState.currentPageOffsetFraction } }
    val maxAlpha = 0.28f
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        // 静态书脊底阴影（轻微，营造厚度）
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Black.copy(alpha = 0.05f), Color.Transparent),
                startX = 0f,
                endX = w * 0.04f,
            ),
        )
        val drag = offset.absoluteValue.coerceIn(0f, 1f)
        if (drag > 0.001f) {
            // 右缘翻起阴影（前向翻页：右侧页缘抬起）
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = maxAlpha * drag)),
                    startX = w * 0.82f,
                    endX = w,
                ),
            )
            // 左缘书脊阴影（往回翻：左侧压暗）
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black.copy(alpha = (maxAlpha - 0.1f) * drag), Color.Transparent),
                    startX = 0f,
                    endX = w * 0.06f,
                ),
            )
        }
    }
}

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

        // 搜索命中高亮（AC-15.3）：双页跨页与单栏/翻页同一份 target
        val highlight = state.searchHighlight?.takeIf { it.chapterIndex == state.chapterIndex }

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
                    chapterPosition = state.chapterPosition.asString(),
                    onNextChapter = viewModel::nextChapter,
                    highlight = highlight,
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
                        chapterPosition = state.chapterPosition.asString(),
                        onNextChapter = viewModel::nextChapter,
                        highlight = highlight,
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
    /** 搜索命中高亮目标（双页跨页路径；AC-15.3）。 */
    highlight: SearchHighlightTarget? = null,
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
            val hitQuery = highlight?.takeIf { it.paragraphIndex == i }?.query
            Text(
                text = SearchHighlighter.annotate(paragraphs[i], hitQuery, theme.accent),
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
