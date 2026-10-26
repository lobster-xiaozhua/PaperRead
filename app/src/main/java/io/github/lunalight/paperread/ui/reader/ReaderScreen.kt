package io.github.lunalight.paperread.ui.reader

import android.Manifest
import android.app.Activity
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import io.github.lunalight.paperread.ui.adaptive.WindowWidthClass
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlinx.coroutines.launch
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.data.settings.ReaderSettings
import io.github.lunalight.paperread.reader.PageBound
import io.github.lunalight.paperread.reader.pageIndexForStartParagraph
import io.github.lunalight.paperread.reader.paginate
import io.github.lunalight.paperread.speech.SpeechService
import io.github.lunalight.paperread.speech.SpeechState
import io.github.lunalight.paperread.ui.adaptive.LocalWindowWidthClass
import io.github.lunalight.paperread.ui.theme.ReaderTheme
import io.github.lunalight.paperread.ui.theme.ReaderThemes

/**
 * 阅读器页：LazyColumn 段落排版 + 工具栏显隐 + 切章 + 目录/设置面板 +
 * ReaderPosition 恢复与防抖保存 + 亮度/常亮/沉浸/音量键系统效果。
 *
 * 系统栏适配（顶栏/底栏/正文/提示条）统一走 [WindowInsets.safeDrawing]：
 * 系统栏可见时自动避让，被隐藏（沉浸）或手势导航自动缩回时安全区归零、
 * 布局自动铺满——同一份代码覆盖「三键导航 / 手势导航 / 沉浸隐藏」三种形态，
 * 不依赖任何屏幕尺寸或机型的硬编码高度。
 */
@Composable
fun ReaderScreen(
    bookId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as PaperReadApp).container
    val viewModel: ReaderViewModel = viewModel(factory = ReaderViewModel.factory(container, bookId))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val readerTheme = ReaderThemes.byId(state.settings.themeId)

    val snackbarHostState = remember { SnackbarHostState() }

    BrightnessEffect(state.settings.brightness)
    KeepScreenOnEffect(state.settings.keepScreenOn)
    ImmersiveEffect(enabled = state.settings.immersive, chromeVisible = state.chromeVisible)
    SystemBarIconEffect(darkBackground = readerTheme.isDark)

    // 退到后台立即补存最后一次位置（防抖窗口内退出不丢进度）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.flushPendingSave()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 朗读通知元数据（前台服务通知内容：书名 / 章名 / 段落预览）
    LaunchedEffect(state.book, state.chapterTitle, state.paragraphs, state.chapterIndex) {
        SpeechService.updateMetadata(
            book = state.book,
            chapterTitle = state.chapterTitle,
            paragraphs = state.paragraphs,
        )
    }

    // 前台服务生命周期跟随朗读状态（后台常驻播放，用户 2026-10-19 拍板）：
    // 开始朗读即保活，停止 / 退出阅读器即收尾。
    LaunchedEffect(state.speech.isActive) {
        if (state.speech.isActive) {
            SpeechService.start(context)
        } else {
            SpeechService.stop(context)
        }
    }

    // 系统 TTS 不可用（无引擎 / 缺中文语音数据）一次性提示
    LaunchedEffect(state.speech.unavailableReason) {
        val reason = state.speech.unavailableReason ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(reason)
        viewModel.stopSpeech()
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 一次性提示（书签添加等）
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
    }

    // 朗读通知权限场景化（T-009 · AC-20.1）：
    // 首次朗读且未授权时 VM 先发射 ShowPrompt（场景对话框），用户确认后
    // 转 LaunchSystemRequest，由这里发起系统 POST_NOTIFICATIONS 申请；
    // 结果（含拒绝）回传 VM，朗读照常开始，仅通知栏控制不可见。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }
    LaunchedEffect(state.speechPermissionEvent) {
        if (state.speechPermissionEvent is SpeechPermissionEvent.LaunchSystemRequest) {
            viewModel.onSpeechPermissionEventConsumed()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(readerTheme.background),
    ) {
        when {
            state.loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            state.error != null -> {
                ReaderError(
                    message = state.error!!,
                    onBack = onBack,
                    onDelete = if (state.textMissing) {
                        {
                            viewModel.deleteBrokenBook()
                            onBack()
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            else -> {
                if (state.settings.pageTurnMode) {
                    ReaderPagerContent(
                        state = state,
                        viewModel = viewModel,
                        theme = readerTheme,
                        isLandscape = isLandscape,
                    )
                } else {
                    ReaderContent(
                        state = state,
                        viewModel = viewModel,
                        theme = readerTheme,
                        isLandscape = isLandscape,
                    )
                }
            }
        }

        // 顶栏
        AnimatedVisibility(
            visible = state.chromeVisible && state.error == null,
            enter = slideInVertically { -it },
            exit = slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                state = state,
                theme = readerTheme,
                onBack = onBack,
                onOpenToc = viewModel::showToc,
                onAddBookmark = viewModel::addBookmark,
                onOpenSettings = viewModel::showSettings,
                onToggleSpeech = {
                    if (state.speech.isActive) viewModel.stopSpeech() else viewModel.startSpeech()
                },
            )
        }

        // 底部区域：底栏（切章）+ 朗读控制条（播放中悬在其上方）
        Column(modifier = Modifier.align(Alignment.BottomCenter)) {
            AnimatedVisibility(
                visible = state.speech.isActive && state.error == null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                SpeechControlBar(
                    speech = state.speech,
                    theme = readerTheme,
                    autoNextChapter = state.settings.ttsAutoNextChapter,
                    onTogglePlayPause = viewModel::toggleSpeechPlayPause,
                    onSkip = viewModel::speechSkipUnit,
                    onStop = viewModel::stopSpeech,
                    onRateChange = viewModel::updateTtsRate,
                    onPitchChange = viewModel::updateTtsPitch,
                    onAutoNextChange = viewModel::updateTtsAutoNextChapter,
                )
            }
            AnimatedVisibility(
                visible = state.chromeVisible && state.error == null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                ReaderBottomBar(
                    state = state,
                    theme = readerTheme,
                    onPrevious = viewModel::previousChapter,
                    onNext = viewModel::nextChapter,
                )
            }
        }

        // 一次性提示（避开导航栏/手势条）
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                    ),
                ),
        )
    }

    if (state.tocVisible) {
        TocSheet(
            state = state,
            theme = readerTheme,
            onSelect = viewModel::goToChapter,
            onBookmarkSelect = viewModel::goToBookmark,
            onBookmarkDelete = viewModel::deleteBookmark,
            onDismiss = viewModel::hideToc,
        )
    }

    if (state.settingsVisible) {
        ReaderSettingsSheet(
            settings = state.settings,
            onUpdate = viewModel::updateSettings,
            onDismiss = viewModel::hideSettings,
        )
    }

    // 朗读权限场景化说明对话框（AC-20.1）：告知通知权限用于「朗读控制通知」，
    // 确认才发起系统申请；「暂不」直接朗读（降级：通知栏控制不可见）。
    if (state.speechPermissionEvent is SpeechPermissionEvent.ShowPrompt) {
        SpeechPermissionDialog(
            onConfirm = viewModel::onSpeechPermissionPromptConfirm,
            onDismiss = viewModel::onSpeechPermissionPromptDismiss,
        )
    }
}

/**
 * 朗读通知权限的场景化说明对话框（AC-20.1）。
 *
 * 文案全部走字符串资源（R-19 口径：新功能文案直接进 strings.xml）。
 */
@Composable
private fun SpeechPermissionDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speech_permission_prompt_title)) },
        text = { Text(stringResource(R.string.speech_permission_prompt_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.speech_permission_prompt_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.speech_permission_prompt_dismiss))
            }
        },
    )
}

@Composable
private fun ReaderContent(
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

    val textStyle = TextStyle(
        fontFamily = if (state.settings.fontFamily == "serif") FontFamily.Serif else FontFamily.SansSerif,
        fontSize = state.settings.fontSizeSp.sp,
        lineHeight = (state.settings.fontSizeSp * state.settings.lineHeightRatio).sp,
        color = theme.onBackground,
    )

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
                modifier = Modifier
                    .padding(bottom = state.settings.paragraphSpacingDp.dp)
                    .then(
                        if (speaking) {
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    color = theme.accent.copy(alpha = 0.18f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        } else {
                            Modifier
                        },
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
private fun ReaderPagerContent(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    theme: ReaderTheme,
    isLandscape: Boolean,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()

    val textStyle = TextStyle(
        fontFamily = if (state.settings.fontFamily == "serif") FontFamily.Serif else FontFamily.SansSerif,
        fontSize = state.settings.fontSizeSp.sp,
        lineHeight = (state.settings.fontSizeSp * state.settings.lineHeightRatio).sp,
        color = theme.onBackground,
    )

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
        )
    }
}

@Composable
private fun ReaderPagerScaffold(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    theme: ReaderTheme,
    textStyle: TextStyle,
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
) {
    val start = pages[page].startParagraph
    val end = if (page + 1 < pages.size) pages[page + 1].startParagraph else paragraphs.size
    val pageParagraphs = paragraphs.subList(start, end)

    val topPad = with(density) { topPaddingPx.toDp() }
    val bottomPad = with(density) { bottomPaddingPx.toDp() }
    val hPad = with(density) { horizontalPaddingPx.toDp() }

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
                Text(
                    text = para,
                    style = textStyle,
                    textAlign = TextAlign.Justify,
                    modifier = Modifier
                        .padding(bottom = with(density) { spacingPx.toDp() })
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(
    state: ReaderUiState,
    theme: ReaderTheme,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onAddBookmark: () -> Unit,
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
private fun ReaderBottomBar(
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

@Composable
private fun ReaderError(
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

// ---- 系统效果 ----

/** 亮度覆盖：-1 跟随系统；退出阅读器时恢复。 */
@Composable
private fun BrightnessEffect(brightness: Float) {
    val view = LocalView.current
    DisposableEffect(brightness) {
        val activity = view.context as? Activity
        val window = activity?.window
        val layoutParams = window?.attributes
        val previous = layoutParams?.screenBrightness ?: Float.NaN
        if (window != null && layoutParams != null) {
            layoutParams.screenBrightness =
                if (brightness < 0f) {
                    android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                } else {
                    brightness
                }
            window.attributes = layoutParams
        }
        onDispose {
            if (window != null && layoutParams != null && !previous.isNaN()) {
                layoutParams.screenBrightness = previous
                window.attributes = layoutParams
            }
        }
    }
}

/** 阅读时常亮。 */
@Composable
private fun KeepScreenOnEffect(keepScreenOn: Boolean) {
    val view = LocalView.current
    SideEffect { view.keepScreenOn = keepScreenOn }
}

/**
 * 系统栏图标明暗跟随阅读背景。
 *
 * `enableEdgeToEdge()` 只按系统深浅色决定图标颜色，而阅读区配色与 Material 主题
 * 完全解耦（5 套预设在任何系统主题下都一致）：系统浅色 + 「墨黑」阅读主题时，
 * 深色图标压在深色背景上等于消失。这里以阅读背景为唯一依据，
 * 离开阅读器时按系统深浅色还原，避免污染书架页。
 */
@Composable
private fun SystemBarIconEffect(darkBackground: Boolean) {
    val view = LocalView.current
    val systemDark = isSystemInDarkTheme()
    DisposableEffect(darkBackground, systemDark) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowInsetsControllerCompat(window, view)
            controller.isAppearanceLightStatusBars = !darkBackground
            controller.isAppearanceLightNavigationBars = !darkBackground
        }
        onDispose {
            val w = (view.context as? Activity)?.window
            if (w != null) {
                val controller = WindowInsetsControllerCompat(w, view)
                controller.isAppearanceLightStatusBars = !systemDark
                controller.isAppearanceLightNavigationBars = !systemDark
            }
        }
    }
}

/** 沉浸模式：隐藏系统栏，工具栏显示时临时唤出。 */
@Composable
private fun ImmersiveEffect(enabled: Boolean, chromeVisible: Boolean) {    val view = LocalView.current
    DisposableEffect(enabled, chromeVisible) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowInsetsControllerCompat(window, view)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (enabled && !chromeVisible) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val w = (view.context as? Activity)?.window
            if (w != null) {
                WindowInsetsControllerCompat(w, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

/**
 * 朗读控制条：播放/暂停、段步进、停止，以及语速 / 音调 / 章尾自动续读。
 *
 * 与通知按钮（[io.github.lunalight.paperread.speech.SpeechActionReceiver]）
 * 操作同一控制器单例，两端状态天然一致；语速/音调改动经 VM 持久化到
 * DataStore 并即时同步引擎（AC-12.4）。
 */
@Composable
private fun SpeechControlBar(
    speech: SpeechState,
    theme: ReaderTheme,
    autoNextChapter: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkip: (Int) -> Unit,
    onStop: () -> Unit,
    onRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onAutoNextChange: (Boolean) -> Unit,
) {
    Surface(color = theme.background, contentColor = theme.onBackground) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onSkip(-1) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_speech_prev),
                        contentDescription = "上一段",
                        tint = theme.onBackground,
                    )
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        painter = painterResource(
                            if (speech.isSpeaking) R.drawable.ic_speech_pause else R.drawable.ic_speech_play,
                        ),
                        contentDescription = if (speech.isSpeaking) "暂停朗读" else "继续朗读",
                        tint = theme.accent,
                    )
                }
                IconButton(onClick = { onSkip(1) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_speech_next),
                        contentDescription = "下一段",
                        tint = theme.onBackground,
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(
                        painter = painterResource(R.drawable.ic_speech_stop),
                        contentDescription = "停止朗读",
                        tint = theme.onBackground,
                    )
                }
                Text(
                    text = if (speech.isSpeaking) "正在朗读" else "已暂停",
                    style = MaterialTheme.typography.labelMedium,
                    color = theme.secondary,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "语速",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.secondary,
                )
                Slider(
                    value = speech.rate,
                    onValueChange = onRateChange,
                    valueRange = 0.5f..2.0f,
                    steps = 5,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text(
                    text = "%.2fx".format(speech.rate),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.secondary,
                    modifier = Modifier.width(44.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "音调",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.secondary,
                )
                Slider(
                    value = speech.pitch,
                    onValueChange = onPitchChange,
                    valueRange = 0.5f..2.0f,
                    steps = 5,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                )
                Text(
                    text = "%.2fx".format(speech.pitch),
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.secondary,
                    modifier = Modifier.width(44.dp),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAutoNextChange(!autoNextChapter) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "读完一章后自动继续下一章",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.secondary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = autoNextChapter,
                    onCheckedChange = onAutoNextChange,
                )
            }
        }
    }
}
