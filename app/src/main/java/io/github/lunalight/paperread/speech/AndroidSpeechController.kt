package io.github.lunalight.paperread.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 系统 TextToSpeech 实现的 [SpeechController]。
 *
 * 关键实现决策：
 * - **零新增第三方依赖、零网络**：只用 `android.speech.tts`，语音数据由系统引擎提供
 *   （优先离线引擎，不触发任何网络访问，隐私红线不受影响）。
 * - **构造与初始化切主线程**：TextToSpeech 构造要求主线程 Looper；[initEngine] 用
 *   [Handler] 投递，调用方（AppContainer.lazy）可在任意线程完成装配。
 * - **并发单一锁**：播放位置（units / currentUnitIndex / pausedUnitIndex）由
 *   主线程的变更方法与合成线程的 UtteranceProgressListener 回调共同访问，
 *   全部收敛到 [lock]；对外状态只经 [MutableStateFlow] 原子发布，
 *   onFinished 在锁外回调，避免持锁执行调用方逻辑。
 * - **逐单元推进**：一次只 speak 一个单元，onDone 再发下一个。相比整表入队，
 *   暂停/跳转/调速都能精确定位到单元，且超长章不会一次性堆砌队列。
 * - **pause = stop + 记住位置**：系统 TTS 没有 pause API，停止后从当前单元重读
 *   （听感上从当前段开头重复一句，换取状态可控）。
 */
class AndroidSpeechController(
    context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : SpeechController {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(SpeechState())
    override val state: StateFlow<SpeechState> = _state.asStateFlow()

    /** 播放位置与引擎调度的互斥锁（主线程变更 ↔ 合成线程回调）。 */
    private val lock = Any()

    private var tts: TextToSpeech? = null
    private var engineReady = false
    private var engineBroken = false

    /** 当前单元表与播放位置（[lock] 保护）。 */
    private var units: List<SpeechUnit> = emptyList()
    private var currentUnitIndex = -1
    private var pausedUnitIndex = -1

    /** 引擎初始化未完成时的排队请求（初始化完成后自动续播，[lock] 保护）。 */
    private var pendingStart: Pair<List<SpeechUnit>, Int>? = null

    override var onFinished: (() -> Unit)? = null

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val paragraph = utteranceId?.let(SpeechText::paragraphOf) ?: -1
            if (paragraph < 0) return
            _state.update { current ->
                if (current.phase == SpeechPhase.IDLE) {
                    current
                } else {
                    current.copy(speakingParagraph = paragraph)
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            advanceToNextUnit()
        }

        // 错误与「读完」同路径处理：跳到下一单元，不让单点失败中断整章朗读。
        // 单参重载自 API 21 弃用（框架改调双参），minSdk 26 上不会回调，
        // 但它是抽象成员必须实现；覆写弃用成员的告警在此显式抑制。
        @Suppress("OVERRIDE_DEPRECATION")
        @Deprecated("API 21 起框架改调双参 onError；仅为实现抽象成员保留")
        override fun onError(utteranceId: String?) {
            advanceToNextUnit()
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            advanceToNextUnit()
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // pause/stop/speakFromUnit 主动打断是预期路径：不推进、不报错，
            // 等待新的指令重新定位（锁内的位置变更保证不会与推进竞争）。
        }
    }

    init {
        initEngine()
    }

    private fun initEngine() {
        mainHandler.post {
            synchronized(lock) {
                if (tts != null || engineBroken) return@synchronized
                runCatching {
                    val engine = TextToSpeech(appContext) { status -> onEngineInitialized(status) }
                    tts = engine
                }.onFailure {
                    markUnavailable("系统语音引擎不可用")
                }
            }
        }
    }

    private fun onEngineInitialized(status: Int) {
        synchronized(lock) {
            if (status != TextToSpeech.SUCCESS) {
                markUnavailable("系统语音引擎初始化失败")
                return
            }
            val engine = tts ?: run {
                markUnavailable("系统语音引擎不可用")
                return
            }
            runCatching {
                engine.setOnUtteranceProgressListener(utteranceListener)
                val language = engine.setLanguage(Locale.CHINA)
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    markUnavailable("系统缺少中文语音数据，请先在系统设置中安装 TTS 语音")
                    return
                }
                engineReady = true
                pendingStart?.let { pending ->
                    pendingStart = null
                    startSpeaking(pending.first, pending.second)
                }
            }.onFailure {
                markUnavailable("系统语音引擎初始化失败")
            }
        }
    }

    private fun markUnavailable(reason: String) {
        engineBroken = true
        pendingStart = null
        units = emptyList()
        currentUnitIndex = -1
        pausedUnitIndex = -1
        _state.value = SpeechState(
            phase = SpeechPhase.IDLE,
            speakingParagraph = -1,
            currentUnitIndex = -1,
            unavailableReason = reason,
        )
    }

    override fun speak(units: List<SpeechUnit>, startUnitIndex: Int) {
        requireMainThread()
        synchronized(lock) {
            if (engineBroken) return
            if (!engineReady) {
                pendingStart = units to startUnitIndex.coerceIn(0, maxOf(0, units.lastIndex))
                return
            }
            startSpeaking(units, startUnitIndex)
        }
    }

    private fun startSpeaking(units: List<SpeechUnit>, startUnitIndex: Int) {
        if (units.isEmpty()) {
            stop()
            return
        }
        this.units = units
        currentUnitIndex = startUnitIndex.coerceIn(0, units.lastIndex)
        pausedUnitIndex = -1
        publish(SpeechPhase.SPEAKING, currentUnitIndex)
        speakCurrentUnit()
    }

    override fun speakFromUnit(unitIndex: Int) {
        requireMainThread()
        synchronized(lock) {
            if (engineBroken || units.isEmpty()) return
            if (unitIndex !in units.indices) return
            currentUnitIndex = unitIndex
            pausedUnitIndex = -1
            publish(SpeechPhase.SPEAKING, currentUnitIndex)
            speakCurrentUnit()
        }
    }

    override fun pause() {
        requireMainThread()
        synchronized(lock) {
            if (_state.value.phase != SpeechPhase.SPEAKING) return
            pausedUnitIndex = currentUnitIndex
            currentUnitIndex = -1
            tts?.stop()
            // 发布态保留暂停位（currentUnitIndex / speakingParagraph 指向即将
            // 续读的单元）：通知栏与朗读条的「上一段 / 下一段」在暂停态才有
            // 合法锚点，正文高亮也不应在暂停时消失（恢复位置对用户可见）。
            publish(SpeechPhase.PAUSED, pausedUnitIndex)
        }
    }

    override fun resume() {
        requireMainThread()
        synchronized(lock) {
            if (_state.value.phase != SpeechPhase.PAUSED) return
            if (pausedUnitIndex !in units.indices) {
                stop()
                return
            }
            currentUnitIndex = pausedUnitIndex
            pausedUnitIndex = -1
            publish(SpeechPhase.SPEAKING, currentUnitIndex)
            speakCurrentUnit()
        }
    }

    override fun stop() {
        requireMainThread()
        synchronized(lock) {
            units = emptyList()
            currentUnitIndex = -1
            pausedUnitIndex = -1
            pendingStart = null
            tts?.stop()
            publish(SpeechPhase.IDLE, -1)
        }
    }

    override fun setRate(rate: Float) {
        val clamped = rate.coerceIn(RATE_MIN, RATE_MAX)
        _state.update { it.copy(rate = clamped) }
        synchronized(lock) { applySpeechParams() }
    }

    override fun setPitch(pitch: Float) {
        val clamped = pitch.coerceIn(PITCH_MIN, PITCH_MAX)
        _state.update { it.copy(pitch = clamped) }
        synchronized(lock) { applySpeechParams() }
    }

    override fun release() {
        mainHandler.post {
            synchronized(lock) {
                units = emptyList()
                currentUnitIndex = -1
                pausedUnitIndex = -1
                pendingStart = null
                _state.value = SpeechState()
                runCatching { tts?.stop() }
                runCatching { tts?.shutdown() }
                tts = null
                engineReady = false
            }
        }
    }

    // ---- 内部（[lock] 保护） ----

    /** 推进到下一单元；表尾则收尾并回调 [onFinished]（锁外执行）。 */
    private fun advanceToNextUnit() {
        val finished: Boolean
        synchronized(lock) {
            // 非 SPEAKING（已暂停/停止/打断）时忽略在途回调，避免 pause 被复活。
            if (_state.value.phase != SpeechPhase.SPEAKING) return
            val next = currentUnitIndex + 1
            if (next in units.indices) {
                currentUnitIndex = next
                speakCurrentUnit()
                return
            }
            currentUnitIndex = -1
            publish(SpeechPhase.IDLE, -1)
            finished = true
        }
        if (finished) onFinished?.invoke()
    }

    /** 统一发布阶段与播放位置（[lock] 保护下调用）。 */
    private fun publish(phase: SpeechPhase, unitIndex: Int) {
        val paragraph = units.getOrNull(unitIndex)?.paragraphIndex ?: -1
        _state.update { current ->
            current.copy(
                phase = phase,
                speakingParagraph = paragraph,
                currentUnitIndex = unitIndex,
                unavailableReason = if (phase == SpeechPhase.SPEAKING) null else current.unavailableReason,
            )
        }
    }

    /** 应用语速/音调；朗读中则重读当前单元使新参数立即生效。 */
    private fun applySpeechParams() {
        val engine = tts ?: return
        runCatching {
            engine.setSpeechRate(_state.value.rate)
            engine.setPitch(_state.value.pitch)
        }
        if (_state.value.phase == SpeechPhase.SPEAKING && currentUnitIndex in units.indices) {
            runCatching { engine.stop() }
            speakCurrentUnit()
        }
    }

    private fun speakCurrentUnit() {
        val engine = tts ?: return
        val unit = units.getOrNull(currentUnitIndex) ?: return
        runCatching {
            engine.setSpeechRate(_state.value.rate)
            engine.setPitch(_state.value.pitch)
            engine.speak(unit.text, TextToSpeech.QUEUE_FLUSH, null, unit.utteranceId)
        }.onFailure {
            markUnavailable("语音合成失败")
        }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SpeechController 的变更方法必须在主线程调用"
        }
    }

    private companion object {
        const val RATE_MIN = 0.5f
        const val RATE_MAX = 2.0f
        const val PITCH_MIN = 0.5f
        const val PITCH_MAX = 2.0f
    }
}
