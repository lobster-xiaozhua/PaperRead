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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.data.settings.ReaderSettings
import io.github.lunalight.paperread.ui.theme.ReaderTheme
import io.github.lunalight.paperread.ui.theme.ReaderThemes

/**
 * 阅读器页：LazyColumn 段落排版 + 工具栏显隐 + 切章 + 目录/设置面板 +
 * ReaderPosition 恢复与防抖保存 + 亮度/常亮/沉浸/音量键系统效果。
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

        // 一次性提示
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
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

    // 进度恢复：滚到记忆段落 + 段内像素偏移（AC-5.2）
    LaunchedEffect(state.chapterIndex, state.initialParagraph) {
        if (state.initialParagraph > 0 || state.initialScrollOffset > 0) {
            listState.scrollToItem(state.initialParagraph, state.initialScrollOffset)
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
            start = state.settings.horizontalMarginDp.dp,
            end = state.settings.horizontalMarginDp.dp,
            top = if (isLandscape) 8.dp else 56.dp,
            bottom = if (isLandscape) 8.dp else 96.dp,
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
    Surface(color = theme.background, contentColor = theme.onBackground) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
private fun ReaderError(message: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
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
