package io.github.lunalight.paperread.speech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.lunalight.paperread.PaperReadApp

/**
 * 朗读通知按钮的广播接收器（播放 / 暂停 / 上一段 / 下一段 / 停止）。
 *
 * 与 Service、Activity 同进程，直接操作 AppContainer 中的 SpeechController
 * 单例——不引入第二套播放状态。控制动作均转发到控制器，由
 * [SpeechService] 的状态流观察者负责刷新通知与收尾。
 */
class SpeechActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as PaperReadApp).container
        val controller = container.speechController
        when (intent.action) {
            ACTION_PAUSE -> controller.pause()
            ACTION_RESUME -> controller.resume()
            ACTION_PREV -> controller.speakFromUnit(controller.state.value.currentUnitIndex - 1)
            ACTION_NEXT -> controller.speakFromUnit(controller.state.value.currentUnitIndex + 1)
            ACTION_STOP -> {
                controller.stop()
                SpeechService.stop(context)
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "io.github.lunalight.paperread.speech.PAUSE"
        const val ACTION_RESUME = "io.github.lunalight.paperread.speech.RESUME"
        const val ACTION_PREV = "io.github.lunalight.paperread.speech.PREV"
        const val ACTION_NEXT = "io.github.lunalight.paperread.speech.NEXT"
        const val ACTION_STOP = "io.github.lunalight.paperread.speech.STOP"
    }
}
