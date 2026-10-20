package io.github.lunalight.paperread.speech

import android.speech.tts.TextToSpeech

/**
 * [SpeechEngine] 的生产实现：包裹系统 `android.speech.tts.TextToSpeech`。
 *
 * 只做「引擎调用 + 参数下发」的薄封装，零播放逻辑——状态机全在
 * [SpeechPlayback]，本类的不可测部分（真实引擎句柄）因此被压到最小。
 *
 * 线程约定：[attach] / [shutdown] 由主线程（经 [AndroidSpeechController] 的
 * mainHandler）调用；[speak] / [stop] / 参数 setter 可从主线程或合成线程
 * 回调路径进入，TextToSpeech 自身的方法均线程安全。
 */
internal class TtsSpeechEngine : SpeechEngine {

    override var listener: SpeechEngineListener? = null

    /** 系统引擎句柄；未就绪 / 已释放时为 null（此状态下 speak/stop 为空操作）。 */
    private var tts: TextToSpeech? = null

    private var speechRateValue = 1.0f
    private var pitchValue = 1.0f

    /** 挂载已构造的系统引擎（初始化回调可能先于本调用到达，见类注释）。 */
    fun attach(engine: TextToSpeech) {
        tts = engine
    }

    /** 当前引擎句柄（胶水层做就绪检查用）。 */
    fun engine(): TextToSpeech? = tts

    override var speechRate: Float
        get() = speechRateValue
        set(value) {
            speechRateValue = value
            runCatching { tts?.setSpeechRate(value) }
        }

    override var pitch: Float
        get() = pitchValue
        set(value) {
            pitchValue = value
            runCatching { tts?.setPitch(value) }
        }

    override fun speak(unit: SpeechUnit) {
        val engine = tts ?: return
        engine.speak(unit.text, TextToSpeech.QUEUE_FLUSH, null, unit.utteranceId)
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }
}
