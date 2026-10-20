package io.github.lunalight.paperread.speech

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 朗读状态机（纯 Kotlin，零 Android 依赖，可 JVM 单测）。
 *
 * 从 [AndroidSpeechController] 抽出的全部播放逻辑，只通过 [SpeechEngine]
 * 接缝触碰引擎——原类里「状态机 + 引擎调用」耦合导致控制器 80% 以上逻辑
 * 无法单测（TextToSpeech 需真机/引擎），抽取后由 fake 引擎完整覆盖。
 *
 * 关键实现决策（与抽取前逐条保持一致）：
 * - **并发单一锁**：播放位置（units / currentUnitIndex / pausedUnitIndex）由
 *   主线程的变更方法与合成线程的回调共同访问，全部收敛到 [lock]；对外状态
 *   只经 [MutableStateFlow] 原子发布，onFinished 在锁外回调，避免持锁执行
 *   调用方逻辑。
 * - **逐单元推进**：一次只 speak 一个单元，onDone 再发下一个。相比整表入队，
 *   暂停/跳转/调速都能精确定位到单元，且超长章不会一次性堆砌队列。
 * - **pause = stop + 记住位置**：系统 TTS 没有 pause API，停止后从当前单元重读
 *   （听感上从当前段开头重复一句，换取状态可控）。
 * - **引擎就绪前排队**：初始化未完成时 speak 只记录 pendingStart，就绪后自动续播。
 */
class SpeechPlayback(
    private val engine: SpeechEngine,
) : SpeechEngineListener {

    private val _state = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    /** 播放位置与引擎调度的互斥锁（主线程变更 ↔ 合成线程回调）。 */
    private val lock = Any()

    /** 引擎是否就绪（已初始化且语言可用）。 */
    private var ready = false

    /** 引擎是否已标记不可用（不可逆，后续 speak 一律忽略）。 */
    private var broken = false

    /** 当前单元表与播放位置（[lock] 保护）。 */
    private var units: List<SpeechUnit> = emptyList()
    private var currentUnitIndex = -1
    private var pausedUnitIndex = -1

    /** 引擎初始化未完成时的排队请求（初始化完成后自动续播，[lock] 保护）。 */
    private var pendingStart: Pair<List<SpeechUnit>, Int>? = null

    /** 当前单元表全部读完（章尾）。由使用方挂载，决定续读/停止。 */
    var onFinished: (() -> Unit)? = null

    init {
        engine.listener = this
    }

    // ---- 引擎生命周期（由胶水层在 TextToSpeech 初始化回调里调用） ----

    /** 引擎初始化完成（监听器与语言检查已由胶水层做好）。 */
    fun onEngineReady() {
        synchronized(lock) {
            ready = true
            pendingStart?.let { pending ->
                pendingStart = null
                startSpeaking(pending.first, pending.second)
            }
        }
    }

    /** 引擎不可用（无引擎 / 缺中文语音数据 / 初始化失败 / 合成抛错）。 */
    fun onEngineUnavailable(reason: String) {
        synchronized(lock) {
            broken = true
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
    }

    // ---- 播放控制（主线程调用） ----

    /** 开始（或整表切换）朗读：读 [units] 中下标 [startUnitIndex] 起的全部单元。 */
    fun speak(units: List<SpeechUnit>, startUnitIndex: Int) {
        synchronized(lock) {
            if (broken) return
            if (!ready) {
                pendingStart = units to startUnitIndex.coerceIn(0, maxOf(0, units.lastIndex))
                return
            }
            startSpeaking(units, startUnitIndex)
        }
    }

    /** 在当前已加载单元表内跳到 [unitIndex] 继续读（上一段/下一段按钮）。 */
    fun speakFromUnit(unitIndex: Int) {
        synchronized(lock) {
            if (broken || units.isEmpty()) return
            if (unitIndex !in units.indices) return
            currentUnitIndex = unitIndex
            pausedUnitIndex = -1
            publish(SpeechPhase.SPEAKING, currentUnitIndex)
            speakCurrentUnit()
        }
    }

    /** 暂停：停止合成并记住当前单元，[resume] 从该单元重读（系统 TTS 无原生 pause）。 */
    fun pause() {
        synchronized(lock) {
            if (_state.value.phase != SpeechPhase.SPEAKING) return
            pausedUnitIndex = currentUnitIndex
            currentUnitIndex = -1
            engine.stop()
            // 发布态保留暂停位（currentUnitIndex / speakingParagraph 指向即将
            // 续读的单元）：通知栏与朗读条的「上一段 / 下一段」在暂停态才有
            // 合法锚点，正文高亮也不应在暂停时消失（恢复位置对用户可见）。
            publish(SpeechPhase.PAUSED, pausedUnitIndex)
        }
    }

    /** 从暂停点恢复。 */
    fun resume() {
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

    /** 停止并清空单元表（回到 [SpeechPhase.IDLE]）。 */
    fun stop() {
        synchronized(lock) {
            units = emptyList()
            currentUnitIndex = -1
            pausedUnitIndex = -1
            pendingStart = null
            engine.stop()
            publish(SpeechPhase.IDLE, -1)
        }
    }

    /** 语速（0.5 ~ 2.0）；朗读中调用即时生效（重读当前单元）。 */
    fun setRate(rate: Float) {
        val clamped = rate.coerceIn(RATE_MIN, RATE_MAX)
        _state.update { it.copy(rate = clamped) }
        synchronized(lock) { applySpeechParams() }
    }

    /** 音调（0.5 ~ 2.0）；朗读中调用即时生效（重读当前单元）。 */
    fun setPitch(pitch: Float) {
        val clamped = pitch.coerceIn(PITCH_MIN, PITCH_MAX)
        _state.update { it.copy(pitch = clamped) }
        synchronized(lock) { applySpeechParams() }
    }

    /** 释放引擎（进程退出/控制器不再使用）。幂等。 */
    fun release() {
        synchronized(lock) {
            units = emptyList()
            currentUnitIndex = -1
            pausedUnitIndex = -1
            pendingStart = null
            ready = false
            _state.value = SpeechState()
            engine.shutdown()
        }
    }

    // ---- 引擎回调（合成线程） ----

    override fun onUnitStart(utteranceId: String?) {
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

    override fun onUnitDone(utteranceId: String?) {
        advanceToNextUnit()
    }

    override fun onUnitError(utteranceId: String?) {
        advanceToNextUnit()
    }

    // ---- 内部（[lock] 保护） ----

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

    /** 推进到下一单元；表尾则收尾并回调 [onFinished]（锁外执行）。 */
    private fun advanceToNextUnit() {
        val finished: Boolean
        synchronized(lock) {
            // 非 SPEAKING（已暂停/停止/打断）时忽略在途回调，避免 pause 被复活。
            if (_state.value.phase != SpeechPhase.SPEAKING) return
            val next = currentUnitIndex + 1
            if (next in units.indices) {
                currentUnitIndex = next
                // 发布态必须跟随推进：朗读条与通知栏的「上一段 / 下一段」按
                // state.currentUnitIndex 步进，漏发布会导致连续播放中锚点
                // 滞留起始单元（读到第 N 单元时按「下一段」跳回第 1 个）。
                publish(SpeechPhase.SPEAKING, next)
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
        engine.speechRate = _state.value.rate
        engine.pitch = _state.value.pitch
        if (_state.value.phase == SpeechPhase.SPEAKING && currentUnitIndex in units.indices) {
            runCatching { engine.stop() }
            speakCurrentUnit()
        }
    }

    private fun speakCurrentUnit() {
        val unit = units.getOrNull(currentUnitIndex) ?: return
        runCatching {
            engine.speechRate = _state.value.rate
            engine.pitch = _state.value.pitch
            engine.speak(unit)
        }.onFailure {
            onEngineUnavailable("语音合成失败")
        }
    }

    private companion object {
        const val RATE_MIN = 0.5f
        const val RATE_MAX = 2.0f
        const val PITCH_MIN = 0.5f
        const val PITCH_MAX = 2.0f
    }
}
