package io.github.lunalight.paperread.ui.reader

import android.Manifest
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.speech.SpeechService
import io.github.lunalight.paperread.ui.reader.search.BookSearchPanel
import io.github.lunalight.paperread.ui.theme.ReaderThemes

/**
 * 阅读器页（编排层）：状态收集 + 一次性事件分发 + 各部件组合。
 *
 * 本文件只做「接线」，不承载任何渲染细节（见 CONTRIBUTING.md 的分层约定：
 * 渲染文件不写逻辑、逻辑不进渲染文件）。各部件归属：
 * - 正文渲染 / 字体 / 高亮槽：[ReaderContent]（`ReaderContent.kt`）
 * - 翻页与跨页：[ReaderPagerContent] / [PaginatedSpread]（`ReaderPager.kt`）
 * - 工具栏 / 目录 / 书签：[ReaderTopBar] / [ReaderBottomBar] / [TocSheet]（`ReaderChrome.kt`）
 * - 系统效果（沉浸 / 亮度 / 常亮 / 系统栏图标 / 朗读前台服务）：`ReaderEffects.kt`
 * - 朗读控制条与权限说明：[SpeechControlBar] / [SpeechPermissionDialog]（`SpeechControlBar.kt`）
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

    // ---- 系统效果 ----
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
    SpeechServiceLifecycleEffect(isActive = state.speech.isActive)

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
                onOpenSearch = viewModel::openSearch,
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
            onBookmarkEditNote = viewModel::editBookmarkNote,
        )
    }

    // 本书搜索面板（AC-15.1）：入口在顶栏，面板自身独立文件（`search/BookSearchPanel.kt`）
    if (state.searchVisible) {
        BookSearchPanel(
            query = state.searchQuery,
            results = state.searchResults,
            searching = state.searching,
            theme = readerTheme,
            onQueryChange = viewModel::onSearchQueryChange,
            onSelect = viewModel::selectSearchHit,
            onCancel = viewModel::cancelSearch,
            onDismiss = viewModel::dismissSearch,
        )
    }

    // 书签备注编辑（AC-17.1 / AC-17.3）
    val bookmarkEditor = state.bookmarkEditor
    if (bookmarkEditor != null) {
        BookmarkNoteDialog(
            editor = bookmarkEditor,
            theme = readerTheme,
            onNoteChange = viewModel::onBookmarkNoteChange,
            onSave = viewModel::saveBookmarkNote,
            onCancel = viewModel::cancelBookmarkNoteEdit,
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
