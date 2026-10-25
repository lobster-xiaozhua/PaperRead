package io.github.lunalight.paperread.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.lunalight.paperread.MainActivity
import io.github.lunalight.paperread.R
import io.github.lunalight.paperread.domain.model.Book

/**
 * 朗读前台服务的通知构建（媒体播放风格：上一段 / 播放暂停 / 下一段 / 停止）。
 *
 * 通知点击回到阅读器（携带 bookId）；控制按钮走 [SpeechActionReceiver] 广播，
 * 与 AppContainer 中的 SpeechController 单例交互——Service、Activity、
 * Receiver 同进程，控制器天然共享。
 */
object SpeechNotifications {

    const val CHANNEL_ID = "speech_playback"
    const val NOTIFICATION_ID = 1001

    private const val EXCERPT_MAX_CHARS = 40

    /** 确保通知渠道存在（幂等，重复创建无副作用）。 */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "朗读播放",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "朗读进行中的播放控制"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 构建/更新通知。
     *
     * @param state 当前朗读状态快照；[SpeechPhase.IDLE] 时调用方应停止服务，不会到这里。
     */
    fun build(
        context: Context,
        book: Book?,
        chapterTitle: String,
        paragraphs: List<String>,
        state: SpeechState,
    ): Notification {
        ensureChannel(context)
        val title = book?.title ?: "纸阅"
        val content = when {
            state.isPaused -> "已暂停"
            state.speakingParagraph in paragraphs.indices ->
                paragraphs[state.speakingParagraph].take(EXCERPT_MAX_CHARS)

            else -> chapterTitle.ifEmpty { "正在朗读" }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speech)
            .setContentTitle(title)
            .setContentText(content)
            .setSubText(chapterTitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent(context, book?.id))

        builder.addAction(
            action(context, SpeechActionReceiver.ACTION_PREV, "上一段", R.drawable.ic_speech_prev),
        )
        if (state.isSpeaking) {
            builder.addAction(
                action(context, SpeechActionReceiver.ACTION_PAUSE, "暂停", R.drawable.ic_speech_pause),
            )
        } else {
            builder.addAction(
                action(context, SpeechActionReceiver.ACTION_RESUME, "继续", R.drawable.ic_speech_play),
            )
        }
        builder.addAction(
            action(context, SpeechActionReceiver.ACTION_NEXT, "下一段", R.drawable.ic_speech_next),
        )
        builder.addAction(
            action(context, SpeechActionReceiver.ACTION_STOP, "停止", R.drawable.ic_speech_stop),
        )
        return builder.build()
    }

    private fun contentIntent(context: Context, bookId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (bookId != null) putExtra(MainActivity.EXTRA_BOOK_ID, bookId)
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun action(
        context: Context,
        action: String,
        title: String,
        iconRes: Int,
    ): NotificationCompat.Action {
        val intent = Intent(context, SpeechActionReceiver::class.java).setAction(action)
        val pending = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(iconRes, title, pending).build()
    }
}
