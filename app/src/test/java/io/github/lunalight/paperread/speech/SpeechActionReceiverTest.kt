package io.github.lunalight.paperread.speech

import io.github.lunalight.paperread.fakes.FakeSpeechController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpeechActionReceiver.dispatch] 纯分发单测（JVM，无需 Robolectric）。
 *
 * 覆盖通知栏四按钮 + 播放暂停的转发口径，以及未知 action 的忽略；
 * 上一段 / 下一段按发布态 currentUnitIndex 步进（暂停态锚点语义）。
 */
class SpeechActionReceiverTest {

    private val units = listOf(
        SpeechUnit("p0-0", "第一段", 0),
        SpeechUnit("p1-0", "第二段前半", 1),
        SpeechUnit("p1-1", "第二段后半", 1),
    )

    private val controller = FakeSpeechController()

    @Test
    fun `PAUSE 转发暂停`() {
        SpeechActionReceiver.dispatch(controller, SpeechActionReceiver.ACTION_PAUSE) {}
        assertEquals(1, controller.pauseCalls)
    }

    @Test
    fun `RESUME 转发恢复`() {
        SpeechActionReceiver.dispatch(controller, SpeechActionReceiver.ACTION_RESUME) {}
        assertEquals(1, controller.resumeCalls)
    }

    @Test
    fun `STOP 转发停止并收尾前台服务`() {
        var stoppedService = false
        SpeechActionReceiver.dispatch(controller, SpeechActionReceiver.ACTION_STOP) { stoppedService = true }
        assertEquals(1, controller.stopCalls)
        assertTrue("STOP 应额外收尾服务", stoppedService)
    }

    @Test
    fun `PREV_NEXT 按当前单元步进`() {
        controller.speak(units, 1)
        SpeechActionReceiver.dispatch(controller, SpeechActionReceiver.ACTION_PREV) {}
        assertEquals(0, controller.state.value.currentUnitIndex)
        assertEquals(0, controller.state.value.speakingParagraph)

        SpeechActionReceiver.dispatch(controller, SpeechActionReceiver.ACTION_NEXT) {}
        assertEquals(1, controller.state.value.currentUnitIndex)
    }

    @Test
    fun `暂停态仍可按锚点步进`() {
        controller.speak(units, 1)
        controller.pause()
        // 暂停态发布位保留：PREV 从暂停位（1）跳到 0
        SpeechActionReceiver.dispatch(controller, SpeechActionReceiver.ACTION_PREV) {}
        assertEquals(0, controller.state.value.currentUnitIndex)
        assertEquals(SpeechPhase.SPEAKING, controller.state.value.phase)
    }

    @Test
    fun `未知 action 全部忽略`() {
        SpeechActionReceiver.dispatch(controller, "io.github.lunalight.paperread.speech.UNKNOWN") {}
        SpeechActionReceiver.dispatch(controller, null) {}
        assertEquals(0, controller.pauseCalls)
        assertEquals(0, controller.resumeCalls)
        assertEquals(0, controller.stopCalls)
    }
}
