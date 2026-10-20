package io.github.lunalight.paperread.fakes

import io.github.lunalight.paperread.speech.SpeechEngine
import io.github.lunalight.paperread.speech.SpeechEngineListener
import io.github.lunalight.paperread.speech.SpeechUnit

/**
 * [SpeechEngine] 的内存 fake：记录 speak/stop/shutdown 调用与当前参数，
 * 可注入 speak 抛错模拟合成失败——让 [io.github.lunalight.paperread.speech.SpeechPlayback]
 * 的全部状态机逻辑（含引擎异常路径）无需 Robolectric 即可覆盖。
 */
class FakeSpeechEngine : SpeechEngine {

    override var listener: SpeechEngineListener? = null

    override var speechRate = 1.0f

    override var pitch = 1.0f

    /** 已发出的单元（按顺序）。 */
    val spoken = mutableListOf<SpeechUnit>()

    var stopCalls = 0
        private set

    var shutdownCalls = 0
        private set

    /** 非 null 时 speak 抛错（模拟合成失败）。 */
    var speakError: Throwable? = null

    override fun speak(unit: SpeechUnit) {
        speakError?.let { throw it }
        spoken += unit
    }

    override fun stop() {
        stopCalls++
    }

    override fun shutdown() {
        shutdownCalls++
    }

    /** 已发出单元的 utteranceId 序列（断言用）。 */
    fun spokenIds(): List<String> = spoken.map { it.utteranceId }
}
