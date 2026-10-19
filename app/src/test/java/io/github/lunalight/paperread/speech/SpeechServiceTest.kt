package io.github.lunalight.paperread.speech

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.fakes.FakeSpeechController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowService

/**
 * 前台服务与通知的 Robolectric 冒烟：
 * - 启动服务后进入前台并发布常驻通知（含 4 个控制按钮）；
 * - 通知按钮的广播动作被接收器翻译为控制器调用（与 VM 层共享同一实例）。
 *
 * 控制器注入 fake（[PaperReadApp.container.speechController] 的 internal setter），
 * 引擎回调线程安全由 [AndroidSpeechController] 单测接缝覆盖（VM 测试 + fake 推进）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechServiceTest {

    private lateinit var app: PaperReadApp
    private lateinit var controller: FakeSpeechController

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        controller = FakeSpeechController()
        app.container.speechController = controller
        SpeechService.updateMetadata(
            book = null,
            chapterTitle = "第一章 起点",
            paragraphs = listOf("第一段正文。", "第二段正文。"),
        )
    }

    private fun startService(): ShadowService {
        val holder = Robolectric.buildService(SpeechService::class.java).create().startCommand(0, 0)
        holder.get()
        return shadowOf(holder.get())
    }

    @Test
    fun `启动服务后进入前台并发布通知`() {
        controller.speak(SpeechText.buildUnits(listOf("第一段正文。", "第二段正文。")), 0)
        val shadow = startService()

        val notification = shadow.lastForegroundNotification
        assertNotNull("前台通知应已发布（前台服务激活态由通知证明）", notification)
        val actions = notification!!.actions
        assertEquals("上一段/播放暂停/下一段/停止", 4, actions?.size)
    }

    @Test
    fun `通知按钮广播翻译为控制器动作`() {
        controller.speak(SpeechText.buildUnits(listOf("甲段。", "乙段。")), 0)

        // Robolectric 的 sendBroadcast 走主 looper 异步派发，需显式 idle。
        app.sendBroadcast(
            Intent(app, SpeechActionReceiver::class.java).setAction(SpeechActionReceiver.ACTION_PAUSE),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("暂停广播应进入 PAUSED", controller.current().isPaused)

        // 暂停态发布位保留（fake 与真实控制器同口径），下一段从其后单元续读。
        app.sendBroadcast(
            Intent(app, SpeechActionReceiver::class.java).setAction(SpeechActionReceiver.ACTION_NEXT),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("下一段广播应推进单元", 1, controller.current().currentUnitIndex)

        app.sendBroadcast(
            Intent(app, SpeechActionReceiver::class.java).setAction(SpeechActionReceiver.ACTION_STOP),
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("停止广播应回 IDLE", controller.current().isActive)
    }
}
