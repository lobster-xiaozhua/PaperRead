package io.github.lunalight.paperread.speech

import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SpeechNotifications] 单测（Robolectric：通知渠道 / NotificationCompat 构建）。
 *
 * 覆盖 AC-12.8 的通知侧契约：渠道幂等、朗读中/暂停两种态的动作按钮集合、
 * 正文摘要截断、书名缺省与章名缺省回退。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechNotificationsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val book = Book(
        id = 7L,
        title = "山海纪闻",
        author = "测试作者",
        format = BookFormat.TXT,
        encoding = "UTF-8",
        sha256 = "abc",
        fileSize = 10L,
        dirName = "dir",
        totalChars = 10L,
        chapterCount = 1,
        addedAt = 1L,
    )

    @Before
    fun setUp() {
        SpeechNotifications.ensureChannel(context)
    }

    @Test
    fun `渠道创建幂等`() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val first = manager.getNotificationChannel(SpeechNotifications.CHANNEL_ID)
        assertNotNull("渠道应已创建", first)

        SpeechNotifications.ensureChannel(context)
        assertSame("重复创建不应替换渠道", first, manager.getNotificationChannel(SpeechNotifications.CHANNEL_ID))
    }

    @Test
    fun `朗读中通知_摘要截断40字_动作含暂停`() {
        val paragraphs = listOf("短段落", "第".repeat(60))
        val notification = SpeechNotifications.build(
            context = context,
            book = book,
            chapterTitle = "第三章 风起",
            paragraphs = paragraphs,
            state = SpeechState(
                phase = SpeechPhase.SPEAKING,
                speakingParagraph = 1,
                currentUnitIndex = 0,
            ),
        )

        assertEquals("山海纪闻", notification.extras.getString(Notification.EXTRA_TITLE))
        // 摘要截断上限即 SpeechNotifications 私有常量 EXCERPT_MAX_CHARS = 40
        assertEquals("第".repeat(40), notification.extras.getString(Notification.EXTRA_TEXT))
        assertTrue("常驻通知", notification.flags and Notification.FLAG_ONGOING_EVENT != 0)

        val actions = notification.actions
        assertEquals(4, actions.size)
        assertEquals("上一段", actions[0].title)
        assertEquals("暂停", actions[1].title)
        assertEquals("下一段", actions[2].title)
        assertEquals("停止", actions[3].title)
    }

    @Test
    fun `暂停态通知_内容为已暂停_动作含继续`() {
        val notification = SpeechNotifications.build(
            context = context,
            book = book,
            chapterTitle = "第三章 风起",
            paragraphs = listOf("短段落"),
            state = SpeechState(
                phase = SpeechPhase.PAUSED,
                speakingParagraph = 0,
                currentUnitIndex = 0,
            ),
        )

        assertEquals("已暂停", notification.extras.getString(Notification.EXTRA_TEXT))
        val actions = notification.actions
        assertEquals("继续", actions[1].title)
    }

    @Test
    fun `无书籍与章名缺省时回退纸阅与正在朗读`() {
        val notification = SpeechNotifications.build(
            context = context,
            book = null,
            chapterTitle = "",
            paragraphs = emptyList(),
            state = SpeechState(
                phase = SpeechPhase.SPEAKING,
                speakingParagraph = -1,
                currentUnitIndex = 0,
            ),
        )

        assertEquals("纸阅", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("正在朗读", notification.extras.getString(Notification.EXTRA_TEXT))
    }

    @Test
    fun `朗读段越界时回退章名作为内容`() {
        val notification = SpeechNotifications.build(
            context = context,
            book = book,
            chapterTitle = "第三章 风起",
            paragraphs = listOf("短段落"),
            state = SpeechState(
                phase = SpeechPhase.SPEAKING,
                speakingParagraph = 99,
                currentUnitIndex = 0,
            ),
        )

        assertEquals("第三章 风起", notification.extras.getString(Notification.EXTRA_TEXT))
    }
}
