package io.github.lunalight.paperread.ui.reader

import android.app.Activity
import android.content.res.Configuration
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.data.settings.ReaderSettings
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

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val snackbarHostState = remember { SnackbarHostState() }

    // 一次性提示（书签添加等）
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissMessage()
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
                ReaderContent(
                    state = state,
                    viewModel = viewModel,
                    theme = readerTheme,
                    isLandscape = isLandscape,
                )
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
            )
        }

        // 底栏
        AnimatedVisibility(
            visible = state.chromeVisible && state.error == null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                state = state,
                theme = readerTheme,
                onPrevious = viewModel::previousChapter,
                onNext = viewModel::nextChapter,
            )
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

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val textStyle = TextStyle(
        fontFamily = if (state.settings.fontFamily == "serif") FontFamily.Serif else FontFamily.SansSerif,
        fontSize = state.settings.fontSizeSp.sp,
        lineHeight = (state.settings.fontSizeSp * state.settings.lineHeightRatio).sp,
        color = theme.onBackground,
    )

    // 正文也要避开安全区：系统栏可见时让位，隐藏时归零；
    // 横屏刘海/挖孔即使系统栏隐藏也不会消失，靠 safeDrawing 的 displayCutout 部分保住文字。
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current

    LazyColumn(
        state = listState,
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
        ) { _, paragraph ->
            Text(
                text = paragraph,
                style = textStyle,
                textAlign = TextAlign.Justify,
                modifier = Modifier.padding(bottom = state.settings.paragraphSpacingDp.dp),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(
    state: ReaderUiState,
    theme: ReaderTheme,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onAddBookmark: () -> Unit,
    onOpenSettings: () -> Unit,
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
private fun ChapterFooter(
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
private fun ImmersiveEffect(enabled: Boolean, chromeVisible: Boolean) {
    val view = LocalView.current
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
