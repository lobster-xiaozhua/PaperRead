package io.github.lunalight.paperread.ui.reader

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.lunalight.paperread.data.settings.ReaderSettings
import io.github.lunalight.paperread.data.settings.SettingsStore
import io.github.lunalight.paperread.di.AppContainer
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.domain.model.Chapter
import io.github.lunalight.paperread.domain.model.ReaderPosition
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore
import io.github.lunalight.paperread.reader.ReaderProgress
import io.github.lunalight.paperread.speech.SpeechController
import io.github.lunalight.paperread.speech.SpeechPhase
import io.github.lunalight.paperread.speech.SpeechState
import io.github.lunalight.paperread.speech.SpeechText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 朗读权限场景化的一次性 UI 事件（AC-20.1）。
 *
 * 事件而非布尔状态：对话框与系统申请都是「一次性票据」，用序号化字段
 * 会在旋转屏幕 / 重组时重复触发；UI 消费后经
 * [ReaderViewModel.onSpeechPermissionEventConsumed] 置回 null。
 */
sealed interface SpeechPermissionEvent {

    /** 展示场景化说明对话框（携带待朗读起始段，确认/取消后据此起播）。 */
    data class ShowPrompt(val fromParagraph: Int) : SpeechPermissionEvent

    /** 用户已确认对话框，请 UI 发起系统 POST_NOTIFICATIONS 申请。 */
    data class LaunchSystemRequest(val fromParagraph: Int) : SpeechPermissionEvent
}

/** 阅读器 UI 状态。 */
data class ReaderUiState(
    val loading: Boolean = true,
    val book: Book? = null,
    val chapters: List<Chapter> = emptyList(),
    /** 当前章在 chapters 中的下标。 */
    val chapterIndex: Int = 0,
    /** 当前章段落列表（渲染单元）。 */
    val paragraphs: List<String> = emptyList(),
    /** 每段在章内的原始字符偏移（与 paragraphs 等长；进度以原文口径为准）。 */
    val paragraphStarts: List<Int> = emptyList(),
    /** 首次渲染要滚动到的段落（恢复进度用）。 */
    val initialParagraph: Int = 0,
    /** 首次渲染的段内像素偏移（AC-5.2 段级 + 像素级恢复）。 */
    val initialScrollOffset: Int = 0,
    /**
     * 恢复请求序号：每次「加载完成 / 显式跳转」自增。
     *
     * UI 用它作为滚动副作用的一次性票据——同一章内跳到首段时
     * `chapterIndex` 与 `initialParagraph` 都可能不变，靠 key 无法触发重新滚动，
     * 书签落在章首就会点了没反应。序号单调递增保证每次都重新定位，
     * 且消费后不再变化，旋转屏幕不会把用户拖回旧位置。
     */
    val restoreSeq: Long = 0,
    /** 当前章首个可见段落（滚动上报 / 加书签锚点）。 */
    val currentParagraph: Int = 0,
    val settings: ReaderSettings = ReaderSettings(),
    val progressPercent: Float = 0f,
    /** 工具栏（顶/底栏）显隐；点击正文中央切换。 */
    val chromeVisible: Boolean = true,
    val tocVisible: Boolean = false,
    val settingsVisible: Boolean = false,
    /** 当前书签列表（创建时间倒序）。 */
    val bookmarks: List<Bookmark> = emptyList(),
    /** 一次性提示（Snackbar）。 */
    val message: String? = null,
    val error: String? = null,
    /**
     * 错误是否由「正文文件缺失 / 损坏」引起（云备份只恢复书架结构、不含 files/books/）。
     * 为 true 时错误页额外提供「删除该条目」动作，因为重试解析也无济于事。
     */
    val textMissing: Boolean = false,
    /** 朗读（TTS）状态快照；phase 非 IDLE 时正文按 [SpeechState.speakingParagraph] 高亮。 */
    val speech: SpeechState = SpeechState(),
    /**
     * 朗读权限场景化事件（AC-20.1，一次性票据，见 [SpeechPermissionEvent]）。
     *
     * 权限仅用于朗读控制通知：首次朗读且未授权时先弹场景说明，
     * 确认后转系统申请；拒绝 / 取消都不阻塞朗读本身。
     */
    val speechPermissionEvent: SpeechPermissionEvent? = null,
) {
    val chapterTitle: String get() = chapters.getOrNull(chapterIndex)?.title ?: ""
    val chapterPosition: String
        get() = if (chapters.isEmpty()) "" else "第 ${chapterIndex + 1}/${chapters.size} 章"
}

/**
 * 阅读器 ViewModel：加载书籍/章节、ReaderPosition 恢复、防抖进度保存、
 * 切章、目录与设置面板状态、设置读写、TTS 朗读编排。
 *
 * 朗读职责边界：本类只做「内容准备 + 状态编排」——把渲染段落切成朗读单元
 * （[SpeechText]）、把系统 TTS 不可用/章尾事件翻译成 UI 状态；
 * 引擎细节、线程安全、前台保活分别在 [io.github.lunalight.paperread.speech.AndroidSpeechController]
 * 与 [io.github.lunalight.paperread.speech.SpeechService]，本类不直接接触 android.speech.tts。
 *
 * 权限场景化（AC-20.1 / AC-20.2 / AC-20.6）：通知权限（POST_NOTIFICATIONS）
 * 不在冷启动申请，只在首次朗读前经 [SpeechPermissionEvent.ShowPrompt]
 * 场景化说明后由 UI 发起系统申请；拒绝仍朗读，权限被系统撤销时降级提示
 * 不崩。权限可用性经 [hasNotificationPermission] 注入（生产由
 * AppContainer 提供 ContextCompat 探测，测试可固定返回值）。
 *
 * @param appScope 应用级作用域，仅用于 onCleared 后仍需落地的写入
 *   （[flushPendingSave] 的收尾补存、[deleteBrokenBook]）。测试可用默认值。
 * @param hasNotificationPermission 通知权限可用性探测；默认 true（视为已授权），
 *   既有测试不感知通知权限、朗读主路径不变。
 */
class ReaderViewModel(
    private val bookId: Long,
    private val bookRepository: BookRepository,
    private val bookTextStore: BookTextStore,
    private val settingsStore: SettingsStore,
    private val speechController: SpeechController,
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val hasNotificationPermission: () -> Boolean = { true },
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var saveJob: Job? = null

    /**
     * 本轮进程生命周期内是否已问过通知权限。
     *
     * 问过之后不再重复弹场景对话框（Android 自身在两次拒绝后也不再展示
     * 系统弹窗，重复申请只会打扰用户）；撤销场景由 [beginSpeech] 的
     * 降级提示覆盖（AC-20.6）。
     */
    private var notificationPermissionAsked = false

    /** 场景对话框确认后、系统权限申请结果返回前，待起播的起始段。 */
    private var pendingSpeechFromParagraph: Int? = null

    /**
     * 尚未落库的最新位置。
     *
     * 防抖窗口内的位置只存在这里：进程被回收 / 用户立刻返回时，
     * 由 [flushPendingSave]（ON_STOP）与 [onCleared] 兜底写出，
     * 不再出现「滑一下立刻退出，最后 500ms 位置丢失」。
     */
    private var pendingSave: PendingSave? = null

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
                // 语速/音调即时同步到控制器（控制条滑杆改动即时生效）；
                // 控制器内部会夹取到 0.5~2.0 并在朗读中重读当前单元。
                if (settings.ttsRate != speechController.state.value.rate) {
                    speechController.setRate(settings.ttsRate)
                }
                if (settings.ttsPitch != speechController.state.value.pitch) {
                    speechController.setPitch(settings.ttsPitch)
                }
            }
        }
        viewModelScope.launch {
            bookRepository.observeBookmarks(bookId).collect { bookmarks ->
                _uiState.update { it.copy(bookmarks = bookmarks) }
            }
        }
        viewModelScope.launch {
            speechController.state.collect { speech ->
                _uiState.update { it.copy(speech = speech) }
            }
        }
        // 章尾续读决策：控制器单元表读完 → 按设置决定自动下一章或收尾。
        speechController.onFinished = {
            viewModelScope.launch {
                val state = _uiState.value
                val hasNext = state.chapterIndex < state.chapters.lastIndex
                if (state.settings.ttsAutoNextChapter && hasNext) {
                    // 走 goToChapter 的 restartSpeech 而非「切完再 startSpeech」：
                    // 此刻单元表已耗尽、控制器已回 IDLE，靠 wasSpeaking 判断会漏掉
                    // 重启；且重启发生在状态更新之后，不会读到旧章的段落表。
                    goToChapter(state.chapterIndex + 1, restartSpeech = true)
                } else {
                    // 续读关闭，或已是全书末章：收尾并告知（本章已读完）。
                    speechController.stop()
                    _uiState.update { it.copy(message = "已朗读完本章") }
                }
            }
        }
        viewModelScope.launch { load() }
    }

    // ---- 加载与恢复 ----

    private suspend fun load() {
        val book = bookRepository.getBook(bookId)
        if (book == null) {
            _uiState.update { it.copy(loading = false, error = "书籍不存在或已删除") }
            return
        }
        val chapters = bookRepository.getChapters(bookId)
        if (chapters.isEmpty()) {
            _uiState.update { it.copy(loading = false, error = "章节为空，请尝试重新解析") }
            return
        }
        val index = book.lastChapterIndex.coerceIn(0, chapters.lastIndex)
        // 「空壳书」：章节索引与进度都还在，但 book.txt 不存在。
        // 典型成因是从云端备份恢复——备份规则刻意排除 files/books/（隐私 + 25MB 配额），
        // 于是书架/进度/书签都回来了，正文却没回来。必须显式告知，
        // 否则用户看到的是一页只有章节标题的空白，无从判断是文件丢了。
        if (!bookTextStore.hasText(book.dirName)) {
            _uiState.update {
                it.copy(
                    loading = false,
                    book = book,
                    chapters = chapters,
                    chapterIndex = index,
                    error = TEXT_MISSING_MESSAGE,
                    textMissing = true,
                )
            }
            return
        }
        val scan = paragraphsOf(book, chapters[index])
        // 文件在、这一章却读不出字：可能被截断或章节偏移错乱。
        // 与「文件丢失」区分开——这里不该引导用户删书。
        if (scan.texts.isEmpty() && chapters[index].charCount > 0L) {
            _uiState.update {
                it.copy(
                    loading = false,
                    book = book,
                    chapters = chapters,
                    chapterIndex = index,
                    error = CHAPTER_EMPTY_MESSAGE,
                    textMissing = false,
                )
            }
            return
        }
        val paragraphIndex = book.lastParagraphIndex.coerceIn(0, maxOf(0, scan.texts.lastIndex))
        val scrollOffset = book.lastScrollOffset.coerceAtLeast(0)
        _uiState.update {
            it.copy(
                loading = false,
                book = book,
                chapters = chapters,
                chapterIndex = index,
                paragraphs = scan.texts,
                paragraphStarts = scan.starts,
                initialParagraph = paragraphIndex,
                initialScrollOffset = scrollOffset,
                restoreSeq = it.restoreSeq + 1,
                currentParagraph = paragraphIndex,
                progressPercent = ReaderProgress.percentOf(
                    cumulativeChars = chapters[index].cumulativeChars,
                    chapterCharOffset = ReaderProgress.chapterCharOffset(scan.starts, paragraphIndex),
                    totalChars = book.totalChars,
                ),
                error = null,
                textMissing = false,
            )
        }
    }

    private fun paragraphsOf(book: Book, chapter: Chapter): ReaderProgress.ParagraphScan =
        ReaderProgress.scanParagraphs(bookTextStore.readChapter(book.dirName, chapter))

    // ---- 滚动位置与进度保存 ----

    /** 正文滚动变化（首个可见段落 + 段内像素偏移）。防抖 500ms 后落库。 */
    fun onScrollPosition(firstVisibleParagraph: Int, pixelOffset: Int = 0) {
        val state = _uiState.value
        if (state.loading || state.book == null || state.paragraphs.isEmpty()) return
        val clamped = firstVisibleParagraph.coerceIn(0, state.paragraphs.lastIndex)
        if (clamped != state.currentParagraph) {
            _uiState.update { it.copy(currentParagraph = clamped) }
        }
        val pending = PendingSave(
            chapterIndex = state.chapterIndex,
            paragraphIndex = clamped,
            scrollOffset = pixelOffset.coerceAtLeast(0),
            state = state,
        )
        pendingSave = pending
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            if (pendingSave !== pending) return@launch // 已被更新的滚动位置取代
            pendingSave = null
            persist(pending)
        }
    }

    /**
     * 立即写出尚未落库的位置（不等防抖）。
     *
     * 调用点：宿主 Activity 进入 ON_STOP。此时 ViewModel 仍然存活，
     * 用 viewModelScope 即可；真正「退出即销毁」的场景由 [onCleared] 兜底。
     */
    fun flushPendingSave() {
        val pending = pendingSave ?: return
        pendingSave = null
        saveJob?.cancel()
        saveJob = viewModelScope.launch { persist(pending) }
    }

    /**
     * 翻页模式专用：用户翻到某页（该页起始段 [startParagraph]）时上报进度。
     *
     * 与 [onScrollPosition] 走同一条进度落库路径——按 `paragraphStarts` 折算字符偏移，
     * 复用既有 `ReaderPosition(chapterIndex, paragraphIndex, scrollOffset, charOffset)` 模型，
     * **不新增任何进度字段 / 不破坏段落级进度恢复**。翻页模式滚动偏移恒为 0
     * （页从段首起），段内像素偏移在翻页语义下无意义。
     */
    fun onPagePosition(startParagraph: Int) {
        val state = _uiState.value
        if (state.loading || state.book == null || state.paragraphs.isEmpty()) return
        val clamped = startParagraph.coerceIn(0, state.paragraphs.lastIndex)
        if (clamped != state.currentParagraph) {
            _uiState.update { it.copy(currentParagraph = clamped) }
        }
        val pending = PendingSave(
            chapterIndex = state.chapterIndex,
            paragraphIndex = clamped,
            scrollOffset = 0,
            state = state,
        )
        pendingSave = pending
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            if (pendingSave !== pending) return@launch // 已被更新的翻页位置取代
            pendingSave = null
            persist(pending)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope 此刻已取消，必须换作用域，否则最后一次位置静默丢失
        val pending = pendingSave ?: return
        pendingSave = null
        appScope.launch { persist(pending) }
        // 离开阅读器即停止朗读：控制器是进程单例，不显式停止会在
        // 返回书架/退出应用后继续发声。前台服务由 UI 层状态驱动收尾。
        speechController.onFinished = null
        speechController.stop()
    }

    // ---- 朗读（TTS） ----

    /**
     * 从 [fromParagraph]（默认当前可见段）开始朗读当前章。
     *
     * 权限门（AC-20.1）：首次朗读且通知权限未授权时，不直接起播，而是
     * 发射 [SpeechPermissionEvent.ShowPrompt] 由 UI 展示场景化说明
     * （「用于朗读控制通知」）；用户确认后走系统申请、取消则直接起播，
     * 两条路径都不阻塞朗读本身。
     *
     * 单元表由当前渲染段落构建（长段已按句软切分，见 [SpeechText]）；
     * 引擎未初始化完成时控制器内部排队，初始化后自动开播。
     */
    fun startSpeech(fromParagraph: Int = _uiState.value.currentParagraph) {
        if (hasNotificationPermission()) {
            // 已授权（或 API < 33 无运行时权限语义）：无需场景化申请，
            // 同时标记「问过」——若权限之后被系统撤销，走降级提示而非
            // 重新弹场景对话框（AC-20.6）。
            notificationPermissionAsked = true
        } else if (!notificationPermissionAsked) {
            _uiState.update {
                it.copy(speechPermissionEvent = SpeechPermissionEvent.ShowPrompt(fromParagraph))
            }
            return
        }
        beginSpeech(fromParagraph)
    }

    /**
     * 场景对话框「同意并申请」：转入系统 POST_NOTIFICATIONS 申请。
     *
     * 起播延后到 [onNotificationPermissionResult]——系统申请结果回来
     * 后再开播，保证「先告知、再申请、后朗读」的顺序可被用户感知。
     */
    fun onSpeechPermissionPromptConfirm() {
        val event = _uiState.value.speechPermissionEvent as? SpeechPermissionEvent.ShowPrompt ?: return
        notificationPermissionAsked = true
        pendingSpeechFromParagraph = event.fromParagraph
        _uiState.update {
            it.copy(
                speechPermissionEvent = SpeechPermissionEvent.LaunchSystemRequest(event.fromParagraph),
            )
        }
    }

    /** 场景对话框「暂不申请」：不发起系统申请，直接开始朗读（降级：通知控制不可见）。 */
    fun onSpeechPermissionPromptDismiss() {
        val event = _uiState.value.speechPermissionEvent as? SpeechPermissionEvent.ShowPrompt ?: return
        notificationPermissionAsked = true
        _uiState.update { it.copy(speechPermissionEvent = null) }
        beginSpeech(event.fromParagraph)
    }

    /**
     * 系统权限申请结果回传。
     *
     * AC-20.1：拒绝后朗读仍可播放，仅通知栏控制不可见（降级提示经
     * [beginSpeech] 按权限可用性发出）；授权则无提示直接起播。
     */
    fun onNotificationPermissionResult(granted: Boolean) {
        notificationPermissionAsked = true
        val from = pendingSpeechFromParagraph ?: _uiState.value.currentParagraph
        pendingSpeechFromParagraph = null
        _uiState.update { it.copy(speechPermissionEvent = null) }
        beginSpeech(from)
    }

    /** 一次性事件消费完毕（UI 拉起系统申请后调用，防止重组重复触发）。 */
    fun onSpeechPermissionEventConsumed() {
        _uiState.update { it.copy(speechPermissionEvent = null) }
    }

    /** 真正起播：内容校验 + 单元表构建 + 权限降级提示（AC-20.6）。 */
    private fun beginSpeech(fromParagraph: Int) {
        val state = _uiState.value
        if (state.loading || state.book == null || state.paragraphs.isEmpty()) {
            _uiState.update { it.copy(message = "本章暂无可朗读的内容") }
            return
        }
        val units = SpeechText.buildUnits(state.paragraphs)
        if (units.isEmpty()) {
            _uiState.update { it.copy(message = "本章暂无可朗读的内容") }
            return
        }
        // AC-20.6：权限不可用（曾拒绝 / 被系统撤销）→ 降级提示，不崩、
        // 不阻塞朗读；通知通道侧（SpeechService）对无权限 notify 天然容错。
        if (!hasNotificationPermission()) {
            _uiState.update { it.copy(message = NOTIFICATION_PERMISSION_DEGRADED_MESSAGE) }
        }
        val start = SpeechText.firstUnitAtOrAfter(units, fromParagraph)
        speechController.speak(units, start)
    }

    /** 播放/暂停切换（朗读条主按钮）。 */
    fun toggleSpeechPlayPause() {
        when (_uiState.value.speech.phase) {
            SpeechPhase.IDLE -> startSpeech()
            SpeechPhase.SPEAKING -> speechController.pause()
            SpeechPhase.PAUSED -> speechController.resume()
        }
    }

    /** 停止朗读并回到空闲态。 */
    fun stopSpeech() {
        speechController.stop()
    }

    /** 朗读条「上一段 / 下一段」：按单元步进（长段分 part 时先在一段内移动）。 */
    fun speechSkipUnit(delta: Int) {
        val current = _uiState.value.speech.currentUnitIndex
        if (current < 0) return
        speechController.speakFromUnit(current + delta)
    }

    /** 朗读语速（0.5 ~ 2.0）：持久化 + 即时同步控制器。 */
    fun updateTtsRate(rate: Float) {
        viewModelScope.launch { settingsStore.update { it.copy(ttsRate = rate.coerceIn(0.5f, 2f)) } }
    }

    /** 朗读音调（0.5 ~ 2.0）：持久化 + 即时同步控制器。 */
    fun updateTtsPitch(pitch: Float) {
        viewModelScope.launch { settingsStore.update { it.copy(ttsPitch = pitch.coerceIn(0.5f, 2f)) } }
    }

    /** 章尾自动续读开关（持久化）。 */
    fun updateTtsAutoNextChapter(enabled: Boolean) {
        viewModelScope.launch { settingsStore.update { it.copy(ttsAutoNextChapter = enabled) } }
    }

    private suspend fun persist(pending: PendingSave) {
        val state = pending.state
        val book = state.book ?: return
        val chapter = state.chapters.getOrNull(pending.chapterIndex) ?: return
        val charOffset = ReaderProgress.chapterCharOffset(state.paragraphStarts, pending.paragraphIndex)
        val percent = ReaderProgress.percentOf(
            chapter.cumulativeChars,
            charOffset,
            book.totalChars,
        )
        bookRepository.saveReaderPosition(
            bookId = bookId,
            position = ReaderPosition(
                chapterIndex = pending.chapterIndex,
                paragraphIndex = pending.paragraphIndex,
                scrollOffset = pending.scrollOffset,
                charOffset = charOffset,
            ),
            progressPercent = percent,
        )
        _uiState.update { it.copy(progressPercent = percent) }
    }

    /** 一次待落库的位置快照（含当时的 UI 状态，避免异步读写错位）。 */
    private class PendingSave(
        val chapterIndex: Int,
        val paragraphIndex: Int,
        val scrollOffset: Int,
        val state: ReaderUiState,
    )

    // ---- 切章 ----

    fun nextChapter() {
        val state = _uiState.value
        if (state.chapterIndex >= state.chapters.lastIndex) return
        goToChapter(state.chapterIndex + 1)
    }

    fun previousChapter() {
        val state = _uiState.value
        if (state.chapterIndex <= 0) return
        goToChapter(state.chapterIndex - 1)
    }

    /**
     * 跳转章节；[paragraph] 供书签恢复使用（默认章首）。
     *
     * 不再对「目标 == 当前章」早退：书签常落在章首，
     * 而单段落章（无换行 TXT）里段下标恒为 0，早退会让跳转彻底失效。
     * 每次跳转都自增 [ReaderUiState.restoreSeq]，由 UI 消费成一次滚动指令。
     *
     * 朗读中切章：目标章的朗读单元表整体重建并从章首续读（AC-12.6
     * 「朗读中跳转不断播」的章级口径——目录跳转不中断听书）。
     * [restartSpeech] 供章尾自动续读使用：此时控制器已回 IDLE
     * （单元表耗尽），`wasSpeaking` 判断不到，需显式要求重启。
     */
    fun goToChapter(index: Int, paragraph: Int = 0, restartSpeech: Boolean = false) {
        val state = _uiState.value
        if (state.loading || state.chapters.isEmpty()) return
        val target = index.coerceIn(0, state.chapters.lastIndex)
        val book = state.book ?: return
        if (!bookTextStore.hasText(book.dirName)) {
            _uiState.update {
                it.copy(tocVisible = false, error = TEXT_MISSING_MESSAGE, textMissing = true)
            }
            return
        }
        val scan = paragraphsOf(book, state.chapters[target])
        if (scan.texts.isEmpty() && state.chapters[target].charCount > 0L) {
            _uiState.update {
                it.copy(tocVisible = false, error = CHAPTER_EMPTY_MESSAGE, textMissing = false)
            }
            return
        }
        val targetParagraph = paragraph.coerceIn(0, maxOf(0, scan.texts.lastIndex))
        val wasSpeaking = state.speech.isActive || restartSpeech
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    chapterIndex = target,
                    paragraphs = scan.texts,
                    paragraphStarts = scan.starts,
                    initialParagraph = targetParagraph,
                    initialScrollOffset = 0,
                    restoreSeq = it.restoreSeq + 1,
                    currentParagraph = targetParagraph,
                    tocVisible = false,
                    progressPercent = ReaderProgress.percentOf(
                        state.chapters[target].cumulativeChars,
                        ReaderProgress.chapterCharOffset(scan.starts, targetParagraph),
                        book.totalChars,
                    ),
                )
            }
            // 切章立即落库（不等防抖）
            saveJob?.cancel()
            val current = _uiState.value
            pendingSave = null
            persist(
                PendingSave(
                    chapterIndex = target,
                    paragraphIndex = targetParagraph,
                    scrollOffset = 0,
                    state = current,
                ),
            )
            if (wasSpeaking) {
                startSpeech(fromParagraph = targetParagraph)
            }
        }
    }

    // ---- 面板与工具栏 ----

    fun toggleChrome() = _uiState.update { it.copy(chromeVisible = !it.chromeVisible) }

    fun showToc() = _uiState.update { it.copy(tocVisible = true, chromeVisible = true) }

    fun hideToc() = _uiState.update { it.copy(tocVisible = false) }

    fun showSettings() = _uiState.update { it.copy(settingsVisible = true, chromeVisible = true) }

    fun hideSettings() = _uiState.update { it.copy(settingsVisible = false) }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    fun dismissError() = _uiState.update { it.copy(error = null, textMissing = false) }

    /**
     * 删除正文缺失的条目（错误页「删除该条目」）。
     *
     * 走 appScope：调用后界面立刻返回书架，ViewModel 随即销毁，
     * 用 viewModelScope 会写一半被取消，留下数据库有行、目录已删的烂账。
     */
    fun deleteBrokenBook() {
        val book = _uiState.value.book ?: return
        appScope.launch {
            bookRepository.deleteBook(book)
            bookTextStore.deleteBookDir(book.dirName)
        }
    }

    // ---- 书签 ----

    /** 在当前首个可见段落处添加书签（含摘录）。 */
    fun addBookmark() {
        val state = _uiState.value
        val book = state.book ?: return
        if (state.loading || state.paragraphs.isEmpty()) return
        val paragraphIndex = state.currentParagraph.coerceIn(0, state.paragraphs.lastIndex)
        val excerpt = state.paragraphs[paragraphIndex].take(EXCERPT_MAX_CHARS)
        val charOffset = ReaderProgress.chapterCharOffset(state.paragraphStarts, paragraphIndex)
        viewModelScope.launch {
            bookRepository.addBookmark(
                Bookmark(
                    bookId = bookId,
                    chapterIndex = state.chapterIndex,
                    paragraphIndex = paragraphIndex,
                    charOffset = charOffset,
                    excerpt = excerpt,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            _uiState.update { it.copy(message = "已添加书签") }
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { bookRepository.deleteBookmark(id) }
    }

    /** 跳转到书签位置（章节 + 段级）。 */
    fun goToBookmark(bookmark: Bookmark) =
        goToChapter(bookmark.chapterIndex, bookmark.paragraphIndex)

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    companion object {
        private const val SAVE_DEBOUNCE_MS = 500L

        /** 书签摘录最大字符数。 */
        const val EXCERPT_MAX_CHARS = 60

        /** 正文缺失错误文案（书架恢复备份后最常见）。 */
        const val TEXT_MISSING_MESSAGE =
            "正文文件已丢失或损坏，无法阅读。\n删除该条目后可重新导入。"

        /** 正文在、本章却为空（截断/偏移错乱）：可重新解析，不必删书。 */
        const val CHAPTER_EMPTY_MESSAGE =
            "本章内容为空，可能章节索引已损坏。\n请回书架用「重新解析章节」后重试。"

        /**
         * 通知权限不可用时的降级提示（AC-20.6）。
         *
         * 覆盖两种成因：用户曾拒绝系统申请；朗读中权限被系统撤销。
         * 朗读本身不受影响（前台服务照常运行），仅通知栏控制不可见。
         */
        const val NOTIFICATION_PERMISSION_DEGRADED_MESSAGE =
            "未获得通知权限，朗读控制通知不可用；朗读仍可正常播放。"

        fun factory(container: AppContainer, bookId: Long): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ReaderViewModel(
                        bookId = bookId,
                        bookRepository = container.bookRepository,
                        bookTextStore = container.bookTextStore,
                        settingsStore = container.settingsStore,
                        speechController = container.speechController,
                        appScope = container.appScope,
                        // 通知权限探测走 applicationContext（AC-20.1）：
                        // Android 13+ 运行时权限，API < 33 时 checkSelfPermission
                        // 恒返回已授权（安装期权限语义），无需额外按版本分流。
                        hasNotificationPermission = {
                            ContextCompat.checkSelfPermission(
                                container.applicationContext,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) == PackageManager.PERMISSION_GRANTED
                        },
                    )
                }
            }
    }
}
