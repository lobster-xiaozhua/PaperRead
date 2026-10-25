package io.github.lunalight.paperread.speech

import io.github.lunalight.paperread.fakes.FakeSpeechEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [SpeechPlayback] 状态机单测（纯 JVM，fake 引擎驱动）。
 *
 * 覆盖从 [AndroidSpeechController] 抽取出来的全部播放逻辑：
 * 就绪前排队、越界收敛、逐单元推进、章尾回调、暂停锚点与防复活、
 * 参数热调重读、引擎不可用与合成失败路径。
 */
class SpeechPlaybackTest {

    private lateinit var engine: FakeSpeechEngine
    private lateinit var playback: SpeechPlayback

    /** 三段两单元：p0 / p1-0 / p1-1（第二段被软切分）。 */
    private val units = listOf(
        SpeechUnit("p0-0", "第一段", 0),
        SpeechUnit("p1-0", "第二段前半", 1),
        SpeechUnit("p1-1", "第二段后半", 1),
    )

    @Before
    fun setUp() {
        engine = FakeSpeechEngine()
        playback = SpeechPlayback(engine)
    }

    @Test
    fun `引擎未就绪时 speak 排队_就绪后从起始单元自动续播`() {
        playback.speak(units, 1)
        assertEquals(SpeechPhase.IDLE, playback.state.value.phase)
        assertTrue(engine.spoken.isEmpty())

        playback.onEngineReady()
        assertEquals(SpeechPhase.SPEAKING, playback.state.value.phase)
        assertEquals(1, playback.state.value.currentUnitIndex)
        assertEquals(1, playback.state.value.speakingParagraph)
        assertEquals(listOf("p1-0"), engine.spokenIds())
    }

    @Test
    fun `speak 起始下标越界时收敛到合法范围`() {
        playback.onEngineReady()
        playback.speak(units, 99)
        assertEquals(2, playback.state.value.currentUnitIndex)
        playback.stop()
        playback.speak(units, -5)
        assertEquals(0, playback.state.value.currentUnitIndex)
    }

    @Test
    fun `空单元表 speak 直接回到 IDLE 不发声`() {
        playback.onEngineReady()
        playback.speak(emptyList(), 0)
        assertEquals(SpeechPhase.IDLE, playback.state.value.phase)
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun `逐单元推进_表尾读完回调 onFinished`() {
        var finished = 0
        playback.onFinished = { finished++ }
        playback.onEngineReady()
        playback.speak(units, 0)
        assertEquals(listOf("p0-0"), engine.spokenIds())

        playback.onUnitStart("p0-0")
        assertEquals(0, playback.state.value.speakingParagraph)

        playback.onUnitDone("p0-0")
        assertEquals(listOf("p0-0", "p1-0"), engine.spokenIds())
        assertEquals(1, playback.state.value.currentUnitIndex)

        playback.onUnitDone("p1-0")
        assertEquals(listOf("p0-0", "p1-0", "p1-1"), engine.spokenIds())

        playback.onUnitDone("p1-1")
        assertEquals(SpeechPhase.IDLE, playback.state.value.phase)
        assertEquals(-1, playback.state.value.currentUnitIndex)
        assertEquals(1, finished)
    }

    @Test
    fun `单元合成失败与读完同路径_跳下一单元不中断整章`() {
        playback.onEngineReady()
        playback.speak(units, 0)
        playback.onUnitError("p0-0")
        assertEquals(listOf("p0-0", "p1-0"), engine.spokenIds())
        assertEquals(SpeechPhase.SPEAKING, playback.state.value.phase)
    }

    @Test
    fun `onUnitStart 的非法 utteranceId 被忽略`() {
        playback.onEngineReady()
        playback.speak(units, 0)
        playback.onUnitStart(null)
        playback.onUnitStart("garbage")
        playback.onUnitStart("pX-0")
        playback.onUnitStart("p1") // 缺 part 分隔符
        assertEquals(0, playback.state.value.speakingParagraph)
    }

    @Test
    fun `IDLE 态的 onUnitStart 不更新高亮段`() {
        playback.onEngineReady()
        playback.onUnitStart("p0-0")
        assertEquals(-1, playback.state.value.speakingParagraph)
    }

    @Test
    fun `暂停记住当前单元_恢复重读该单元_暂停中回调不复活`() {
        playback.onEngineReady()
        playback.speak(units, 1)

        playback.pause()
        assertEquals(SpeechPhase.PAUSED, playback.state.value.phase)
        // 发布态保留暂停位：通知栏步进与正文高亮有合法锚点
        assertEquals(1, playback.state.value.currentUnitIndex)
        assertEquals(1, playback.state.value.speakingParagraph)
        assertEquals(1, engine.stopCalls)

        // 在途回调被忽略：暂停不会被引擎回调复活
        val spokenAtPause = engine.spokenIds()
        playback.onUnitDone("p1-0")
        assertEquals(spokenAtPause, engine.spokenIds())
        assertEquals(SpeechPhase.PAUSED, playback.state.value.phase)

        playback.resume()
        assertEquals(SpeechPhase.SPEAKING, playback.state.value.phase)
        assertEquals(listOf("p1-0", "p1-0"), engine.spokenIds())
    }

    @Test
    fun `非 SPEAKING 态 pause 无效`() {
        playback.onEngineReady()
        playback.pause()
        assertEquals(0, engine.stopCalls)
        assertEquals(SpeechPhase.IDLE, playback.state.value.phase)
    }

    @Test
    fun `非 PAUSED 态 resume 无效`() {
        playback.onEngineReady()
        playback.resume()
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun `speakFromUnit 合法下标跳读_非法下标忽略`() {
        playback.onEngineReady()
        playback.speak(units, 0)
        engine.spoken.clear()

        playback.speakFromUnit(2)
        assertEquals(listOf("p1-1"), engine.spokenIds())
        assertEquals(2, playback.state.value.currentUnitIndex)

        playback.speakFromUnit(99)
        assertEquals(listOf("p1-1"), engine.spokenIds())
        assertEquals(2, playback.state.value.currentUnitIndex)
    }

    @Test
    fun `stop 清空位置并回 IDLE_停止后回调不再推进`() {
        playback.onEngineReady()
        playback.speak(units, 0)
        playback.stop()
        assertEquals(SpeechPhase.IDLE, playback.state.value.phase)
        assertEquals(-1, playback.state.value.currentUnitIndex)
        assertEquals(1, engine.stopCalls)

        playback.onUnitDone("p0-0")
        assertEquals(listOf("p0-0"), engine.spokenIds())
    }

    @Test
    fun `语速越界收敛_朗读中热调重读当前单元`() {
        playback.onEngineReady()
        playback.speak(units, 1)
        engine.spoken.clear()

        playback.setRate(9.9f)
        assertEquals(2.0f, playback.state.value.rate, 1e-4f)
        assertEquals(2.0f, engine.speechRate, 1e-4f)
        // 即时生效 = 打断后重读当前单元
        assertEquals(1, engine.stopCalls)
        assertEquals(listOf("p1-0"), engine.spokenIds())
    }

    @Test
    fun `语速下限收敛`() {
        playback.onEngineReady()
        playback.setRate(0.1f)
        assertEquals(0.5f, playback.state.value.rate, 1e-4f)
    }

    @Test
    fun `音调越界收敛`() {
        playback.onEngineReady()
        playback.setPitch(9.9f)
        assertEquals(2.0f, playback.state.value.pitch, 1e-4f)
        playback.setPitch(0.1f)
        assertEquals(0.5f, playback.state.value.pitch, 1e-4f)
    }

    @Test
    fun `暂停态调参不重读`() {
        playback.onEngineReady()
        playback.speak(units, 0)
        playback.pause()
        engine.spoken.clear()

        playback.setPitch(1.5f)
        assertEquals(1.5f, playback.state.value.pitch, 1e-4f)
        assertEquals(1.5f, engine.pitch, 1e-4f)
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun `引擎不可用后 speak 被忽略且状态带原因`() {
        playback.speak(units, 0) // 先排队
        playback.onEngineUnavailable("系统缺少中文语音数据")
        playback.speak(units, 1)
        assertEquals("系统缺少中文语音数据", playback.state.value.unavailableReason)
        assertEquals(SpeechPhase.IDLE, playback.state.value.phase)
        assertTrue(engine.spoken.isEmpty())

        // 排队请求已被清掉：后续就绪也不会补播
        playback.onEngineReady()
        assertTrue(engine.spoken.isEmpty())
    }

    @Test
    fun `合成抛错标记引擎不可用`() {
        playback.onEngineReady()
        engine.speakError = IllegalStateException("boom")
        playback.speak(units, 0)
        assertEquals("语音合成失败", playback.state.value.unavailableReason)
        assertEquals(SpeechPhase.IDLE, playback.state.value.phase)
    }

    @Test
    fun `release 重置状态并关闭引擎_之后 speak 不再发声`() {
        playback.onEngineReady()
        playback.speak(units, 0)
        val spokenBefore = engine.spokenIds()
        playback.release()
        assertEquals(SpeechState(), playback.state.value)
        assertEquals(1, engine.shutdownCalls)

        // release 后引擎未就绪，speak 只排队不发声
        playback.speak(units, 1)
        assertEquals(spokenBefore, engine.spokenIds())
        assertFalse(playback.state.value.isActive)
    }

    @Test
    fun `onFinished 锁外回调_回调内可直接操作状态机不死锁`() {
        playback.onFinished = { playback.stop() }
        playback.onEngineReady()
        playback.speak(listOf(SpeechUnit("p0-0", "只有一段", 0)), 0)
        playback.onUnitDone("p0-0")
        assertEquals(SpeechPhase.IDLE, playback.state.value.phase)
    }

    @Test
    fun `暂停后步进锚点仍可用_speakFromUnit 从暂停位前后跳读`() {
        playback.onEngineReady()
        playback.speak(units, 1)
        playback.pause()
        engine.spoken.clear()

        // 暂停态 currentUnitIndex 仍指向暂停位：上一段 = 0
        playback.speakFromUnit(playback.state.value.currentUnitIndex - 1)
        assertEquals(SpeechPhase.SPEAKING, playback.state.value.phase)
        assertEquals(listOf("p0-0"), engine.spokenIds())
    }
}
