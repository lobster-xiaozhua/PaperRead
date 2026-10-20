package io.github.lunalight.paperread.speech

import kotlinx.coroutines.flow.StateFlow

/** 朗读状态机阶段。 */
enum class SpeechPhase {
    /** 空闲（未开始或已停止）。 */
    IDLE,

    /** 正在朗读。 */
    SPEAKING,

    /** 已暂停（保留当前单元，恢复时重读）。 */
    PAUSED,
}

/**
 * 朗读 UI 状态（不可变快照，线程安全发布）。
 *
 * @param speakingParagraph 正在朗读的正文段下标；-1 表示无（空闲/未定位）。
 * @param currentUnitIndex 当前朗读单元在单元表中的下标；-1 表示无。
 *   「上一段 / 下一段」按钮用它定位（长段被软切分为多单元时按单元步进）。
 * @param unavailableReason 系统 TTS 不可用原因（无引擎 / 缺中文语音数据 / 初始化失败）；
 *   非 null 时 UI 应向用户明示，而不是静默无响应。
 */
data class SpeechState(
    val phase: SpeechPhase = SpeechPhase.IDLE,
    val speakingParagraph: Int = -1,
    val currentUnitIndex: Int = -1,
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val unavailableReason: String? = null,
) {
    val isSpeaking: Boolean get() = phase == SpeechPhase.SPEAKING
    val isPaused: Boolean get() = phase == SpeechPhase.PAUSED
    /** 是否占用前台服务/控制条（暂停也算，可随时恢复）。 */
    val isActive: Boolean get() = phase == SpeechPhase.SPEAKING || phase == SpeechPhase.PAUSED
}

/**
 * 语音朗读控制器（UI 层只依赖本接口，不直接接触 android.speech.tts）。
 *
 * 实现由外层装配（[AndroidSpeechController]）；单测以 fake 替代。
 *
 * 并发约定：实现必须保证 [state] 可跨线程安全发布（TTS 回调线程会更新它），
 * 其余方法应在主线程调用。
 */
interface SpeechController {

    val state: StateFlow<SpeechState>

    /**
     * 开始（或整表切换）朗读：读 [units] 中下标 [startUnitIndex] 起的全部单元。
     *
     * 调用方负责在切换章节/书籍时重建单元表；实现负责逐单元推进、
     * 单元读完回调 [onFinished]。
     */
    fun speak(units: List<SpeechUnit>, startUnitIndex: Int)

    /** 在当前已加载单元表内跳到 [unitIndex] 继续读（上一段/下一段按钮）。 */
    fun speakFromUnit(unitIndex: Int)

    /** 暂停：停止合成并记住当前单元，[resume] 从该单元重读（系统 TTS 无原生 pause）。 */
    fun pause()

    /** 从暂停点恢复。 */
    fun resume()

    /** 停止并清空单元表（回到 [SpeechPhase.IDLE]）。 */
    fun stop()

    /** 语速（0.5 ~ 2.0）；朗读中调用即时生效（重读当前单元）。 */
    fun setRate(rate: Float)

    /** 音调（0.5 ~ 2.0）；朗读中调用即时生效（重读当前单元）。 */
    fun setPitch(pitch: Float)

    /** 当前单元表全部读完（章尾）。由使用方挂载，决定续读/停止。 */
    var onFinished: (() -> Unit)?

    /** 释放引擎（进程退出/控制器不再使用）。幂等。 */
    fun release()
}
