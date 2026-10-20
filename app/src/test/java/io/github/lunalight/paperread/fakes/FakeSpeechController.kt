package io.github.lunalight.paperread.fakes

import io.github.lunalight.paperread.speech.SpeechController
import io.github.lunalight.paperread.speech.SpeechPhase
import io.github.lunalight.paperread.speech.SpeechState
import io.github.lunalight.paperread.speech.SpeechUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [SpeechController] 的内存 fake：状态即时生效，调用与当前位置可被观测。
 *
 * 与真实实现的行为约定保持一致：
 * - [speak] 进入 SPEAKING 并定位到起始单元；
 * - [pause] 进入 PAUSED；[resume] 回到 SPEAKING；
 * - [stop] 回 IDLE 并清空单元表；
 * - 单测通过 [finishCurrentUnit] / [failCurrentUnit] 模拟引擎回调推进。
 */
class FakeSpeechController(initial: SpeechState = SpeechState()) : SpeechController {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<SpeechState> = _state.asStateFlow()

    private var units: List<SpeechUnit> = emptyList()
    private var currentUnitIndex = -1
    private var pausedUnitIndex = -1

    /** 调用记录（断言用）。 */
    val speakCalls = mutableListOf<SpeakCall>()
    var pauseCalls = 0
        private set
    var resumeCalls = 0
        private set
    var stopCalls = 0
        private set
    var releaseCalls = 0
        private set

    data class SpeakCall(val units: List<SpeechUnit>, val startUnitIndex: Int)

    override fun speak(units: List<SpeechUnit>, startUnitIndex: Int) {
        speakCalls += SpeakCall(units, startUnitIndex)
        if (units.isEmpty()) {
            stop()
            return
        }
        this.units = units
        currentUnitIndex = startUnitIndex.coerceIn(0, units.lastIndex)
        pausedUnitIndex = -1
        _state.update {
            it.copy(
                phase = SpeechPhase.SPEAKING,
                speakingParagraph = units[currentUnitIndex].paragraphIndex,
                currentUnitIndex = currentUnitIndex,
                unavailableReason = null,
            )
        }
    }

    override fun speakFromUnit(unitIndex: Int) {
        if (units.isEmpty() || unitIndex !in units.indices) return
        currentUnitIndex = unitIndex
        _state.update {
            it.copy(
                phase = SpeechPhase.SPEAKING,
                speakingParagraph = units[currentUnitIndex].paragraphIndex,
                currentUnitIndex = currentUnitIndex,
            )
        }
    }

    override fun pause() {
        pauseCalls++
        if (_state.value.phase != SpeechPhase.SPEAKING) return
        // 与真实实现同口径：内部位置转入 pausedUnitIndex，发布态保留暂停位
        // （currentUnitIndex / speakingParagraph 仍指向即将续读的单元），
        // 否则暂停态下「下一段」失去锚点、正文高亮也会消失。
        pausedUnitIndex = currentUnitIndex
        currentUnitIndex = -1
        _state.update {
            it.copy(
                phase = SpeechPhase.PAUSED,
                currentUnitIndex = pausedUnitIndex,
                speakingParagraph = units[pausedUnitIndex].paragraphIndex,
            )
        }
    }

    override fun resume() {
        resumeCalls++
        if (_state.value.phase != SpeechPhase.PAUSED) return
        if (pausedUnitIndex !in units.indices) {
            stop()
            return
        }
        currentUnitIndex = pausedUnitIndex
        pausedUnitIndex = -1
        _state.update {
            it.copy(
                phase = SpeechPhase.SPEAKING,
                currentUnitIndex = currentUnitIndex,
                speakingParagraph = units[currentUnitIndex].paragraphIndex,
            )
        }
    }

    override fun stop() {
        stopCalls++
        units = emptyList()
        currentUnitIndex = -1
        pausedUnitIndex = -1
        _state.update { it.copy(phase = SpeechPhase.IDLE, speakingParagraph = -1, currentUnitIndex = -1) }
    }

    override fun setRate(rate: Float) {
        _state.update { it.copy(rate = rate.coerceIn(0.5f, 2.0f)) }
    }

    override fun setPitch(pitch: Float) {
        _state.update { it.copy(pitch = pitch.coerceIn(0.5f, 2.0f)) }
    }

    override var onFinished: (() -> Unit)? = null

    override fun release() {
        releaseCalls++
        units = emptyList()
        currentUnitIndex = -1
        pausedUnitIndex = -1
        _state.value = SpeechState()
    }

    /** 模拟引擎回调：当前单元读完，推进到下一单元或收尾。 */
    fun finishCurrentUnit() {
        val next = currentUnitIndex + 1
        if (next in units.indices) {
            currentUnitIndex = next
            _state.update {
                it.copy(
                    speakingParagraph = units[currentUnitIndex].paragraphIndex,
                    currentUnitIndex = currentUnitIndex,
                )
            }
        } else {
            currentUnitIndex = -1
            pausedUnitIndex = -1
            _state.update { it.copy(phase = SpeechPhase.IDLE, speakingParagraph = -1, currentUnitIndex = -1) }
            onFinished?.invoke()
        }
    }

    /** 模拟引擎不可用（初始化失败 / 缺中文语音数据）。 */
    fun simulateUnavailable(reason: String) {
        units = emptyList()
        currentUnitIndex = -1
        pausedUnitIndex = -1
        _state.value = _state.value.copy(
            phase = SpeechPhase.IDLE,
            speakingParagraph = -1,
            currentUnitIndex = -1,
            unavailableReason = reason,
        )
    }

    /** 当前状态快照（断言用）。 */
    fun current(): SpeechState = _state.value
}
