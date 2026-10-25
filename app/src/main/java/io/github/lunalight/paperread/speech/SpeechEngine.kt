package io.github.lunalight.paperread.speech

/**
 * TTS 引擎接缝（纯 Kotlin，零 Android 依赖）。
 *
 * [SpeechPlayback] 只依赖本接口：全部播放状态机逻辑（单元表推进、暂停锚点、
 * 参数热调、章尾收尾）与引擎完全解耦，可用内存 fake 做 JVM 单测；
 * 生产实现 [TtsSpeechEngine] 包裹 `android.speech.tts.TextToSpeech`。
 */
interface SpeechEngine {

    /** 合成回调监听（[SpeechPlayback] 在 init 时挂载）。 */
    var listener: SpeechEngineListener?

    /** 语速（0.5 ~ 2.0）；设置即下发引擎。 */
    var speechRate: Float

    /** 音调（0.5 ~ 2.0）；设置即下发引擎。 */
    var pitch: Float

    /** 朗读一个单元（实现负责 QUEUE_FLUSH + utteranceId 回传）。 */
    fun speak(unit: SpeechUnit)

    /** 打断当前合成（暂停 / 停止 / 参数热调重读）。 */
    fun stop()

    /** 释放引擎（进程退出 / 控制器不再使用）。幂等。 */
    fun shutdown()
}

/**
 * 引擎合成回调（合成线程 → 状态机）。
 *
 * utteranceId 口径与 [SpeechUnit.utteranceId] 一致，状态机靠它还原正文段下标。
 */
interface SpeechEngineListener {
    /** 单元开始合成（可能带 null / 无法解析的 utteranceId，状态机自行忽略）。 */
    fun onUnitStart(utteranceId: String?)

    /** 单元合成完成。 */
    fun onUnitDone(utteranceId: String?)

    /** 单元合成失败（与「读完」同路径：跳下一单元，不让单点失败中断整章）。 */
    fun onUnitError(utteranceId: String?)
}
