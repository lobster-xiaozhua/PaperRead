package io.github.lunalight.paperread.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * 系统 TextToSpeech 实现的 [SpeechController]。
 *
 * 经 2026-10-19 重构拆为两层，本类只保留 Android 胶水：
 * - [SpeechPlayback]：纯 Kotlin 状态机（单元推进 / 暂停锚点 / 参数热调），JVM 单测覆盖；
 * - [TtsSpeechEngine]：系统引擎薄封装。
 * 拆分前全部逻辑耦在引擎调用里，TextToSpeech 需真机引擎导致控制器几乎无法单测。
 *
 * 关键实现决策：
 * - **零新增第三方依赖、零网络**：只用 `android.speech.tts`，语音数据由系统引擎提供
 *   （优先离线引擎，不触发任何网络访问，隐私红线不受影响）。
 * - **构造与初始化切主线程**：TextToSpeech 构造要求主线程 Looper；[initEngine] 用
 *   [Handler] 投递，调用方（AppContainer.lazy）可在任意线程完成装配。
 * - **并发单一锁**：状态机内部自持锁串行化主线程变更与合成线程回调；本类的
 *   引擎装配（[initLock]）与状态机锁分开，装配只在主线程发生，不会与播放竞争。
 * - **逐单元推进 / pause = stop + 记住位置**：见 [SpeechPlayback] 逐条说明。
 */
class AndroidSpeechController(
    context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : SpeechController {

    private val appContext = context.applicationContext
    private val engine = TtsSpeechEngine()
    private val playback = SpeechPlayback(engine)

    /** 引擎装配互斥（初始化回调与 initEngine 均经 mainHandler 串行执行）。 */
    private val initLock = Any()
    private var engineAttached = false

    override val state: StateFlow<SpeechState>
        get() = playback.state

    override var onFinished: (() -> Unit)?
        get() = playback.onFinished
        set(value) {
            playback.onFinished = value
        }

    init {
        initEngine()
    }

    private fun initEngine() {
        mainHandler.post {
            synchronized(initLock) {
                if (engineAttached) return@synchronized
                runCatching {
                    val tts = TextToSpeech(appContext) { status -> onEngineInitialized(status) }
                    engine.attach(tts)
                    engineAttached = true
                }.onFailure {
                    playback.onEngineUnavailable("系统语音引擎不可用")
                }
            }
        }
    }

    private fun onEngineInitialized(status: Int) {
        synchronized(initLock) {
            if (status != TextToSpeech.SUCCESS) {
                playback.onEngineUnavailable("系统语音引擎初始化失败")
                return
            }
            val tts = engine.engine() ?: run {
                playback.onEngineUnavailable("系统语音引擎不可用")
                return
            }
            runCatching {
                tts.setOnUtteranceProgressListener(utteranceListener)
                val language = tts.setLanguage(Locale.CHINA)
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    playback.onEngineUnavailable("系统缺少中文语音数据，请先在系统设置中安装 TTS 语音")
                    return
                }
                playback.onEngineReady()
            }.onFailure {
                playback.onEngineUnavailable("系统语音引擎初始化失败")
            }
        }
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            playback.onUnitStart(utteranceId)
        }

        override fun onDone(utteranceId: String?) {
            playback.onUnitDone(utteranceId)
        }

        // 错误与「读完」同路径处理：跳到下一单元，不让单点失败中断整章朗读。
        // 单参重载自 API 21 弃用（框架改调双参），minSdk 26 上不会回调，
        // 但它是抽象成员必须实现；覆写弃用成员的告警在此显式抑制。
        @Suppress("OVERRIDE_DEPRECATION")
        @Deprecated("API 21 起框架改调双参 onError；仅为实现抽象成员保留")
        override fun onError(utteranceId: String?) {
            playback.onUnitError(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            playback.onUnitError(utteranceId)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            // pause/stop/speakFromUnit 主动打断是预期路径：不推进、不报错，
            // 等待新的指令重新定位（锁内的位置变更保证不会与推进竞争）。
        }
    }

    override fun speak(units: List<SpeechUnit>, startUnitIndex: Int) {
        requireMainThread()
        playback.speak(units, startUnitIndex)
    }

    override fun speakFromUnit(unitIndex: Int) {
        requireMainThread()
        playback.speakFromUnit(unitIndex)
    }

    override fun pause() {
        requireMainThread()
        playback.pause()
    }

    override fun resume() {
        requireMainThread()
        playback.resume()
    }

    override fun stop() {
        requireMainThread()
        playback.stop()
    }

    override fun setRate(rate: Float) {
        playback.setRate(rate)
    }

    override fun setPitch(pitch: Float) {
        playback.setPitch(pitch)
    }

    override fun release() {
        mainHandler.post { playback.release() }
    }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SpeechController 的变更方法必须在主线程调用"
        }
    }
}
