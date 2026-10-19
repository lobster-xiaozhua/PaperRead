package io.github.lunalight.paperread.speech

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.lunalight.paperread.PaperReadApp
import io.github.lunalight.paperread.domain.model.Book
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 朗读前台服务：用户 2026-10-19 拍板「后台常驻播放」——锁屏 / 切到其他应用时
 * 朗读不中断，代价是新增前台服务相关权限（FOREGROUND_SERVICE /
 * FOREGROUND_SERVICE_MEDIA_PLAYBACK / POST_NOTIFICATIONS），
 * 已在 SPEC 第 10 节与隐私文案中单独说明理由。
 *
 * 职责边界（保持与 transfer/ 一致的接口隔离）：
 * - 本服务**不持有** SpeechController 逻辑，只负责「前台保活 + 通知呈现」；
 *   控制器是 AppContainer 中的进程单例，Service / Activity / Receiver 共享同一实例。
 * - 播放控制走 [SpeechActionReceiver] 广播（通知按钮）或阅读器 UI。
 * - 生命周期：开始朗读时启动，停止 / 退出阅读器时销毁（START_NOT_STICKY，
 *   进程重建不恢复播放，避免后台自动发声的惊吓）。
 */
class SpeechService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        observeAndRefresh()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 12+：startForegroundService 后必须尽快调用 startForeground，
        // 否则抛 ForegroundServiceDidNotStartInTimeException。
        promoteToForeground()
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        val snapshot = renderSnapshot()
        val notification = SpeechNotifications.build(
            context = this,
            book = snapshot.book,
            chapterTitle = snapshot.chapterTitle,
            paragraphs = snapshot.paragraphs,
            state = snapshot.state,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                SpeechNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(SpeechNotifications.NOTIFICATION_ID, notification)
        }
    }

    /** 状态 / 元数据变化时刷新通知（服务存活期内单任务收集）。 */
    private fun observeAndRefresh() {
        val container = (application as PaperReadApp).container
        val job: Job = combine(
            container.speechController.state,
            metadataFlow,
        ) { state, metadata ->
            renderSnapshot(state, metadata)
        }.onEach { snapshot ->
            val manager = getSystemService(NotificationManager::class.java) ?: return@onEach
            if (!snapshot.state.isActive) {
                // 控制器已停止（外部 stop / 章尾结束且不续读）：服务无存在意义。
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@onEach
            }
            manager.notify(
                SpeechNotifications.NOTIFICATION_ID,
                SpeechNotifications.build(
                    context = this,
                    book = snapshot.book,
                    chapterTitle = snapshot.chapterTitle,
                    paragraphs = snapshot.paragraphs,
                    state = snapshot.state,
                ),
            )
        }.launchIn(container.appScope)
        refreshJob = job
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        refreshJob = null
        super.onDestroy()
    }

    private var refreshJob: Job? = null

    private data class Snapshot(
        val state: SpeechState,
        val book: Book?,
        val chapterTitle: String,
        val paragraphs: List<String>,
    )

    private fun renderSnapshot(
        state: SpeechState = (application as PaperReadApp).container.speechController.state.value,
        metadata: SpeechMetadata? = metadataFlow.value,
    ): Snapshot = Snapshot(
        state = state,
        book = metadata?.book,
        chapterTitle = metadata?.chapterTitle ?: "",
        paragraphs = metadata?.paragraphs ?: emptyList(),
    )

    companion object {

        /** 通知元数据（进程内单例）：由阅读器页在内容/状态变化时更新。 */
        private val metadataFlow = MutableStateFlow<SpeechMetadata?>(null)

        /** 更新通知元数据；服务未运行时节流无害（下次启动即用）。 */
        fun updateMetadata(book: Book?, chapterTitle: String, paragraphs: List<String>) {
            metadataFlow.value = SpeechMetadata(book, chapterTitle, paragraphs)
        }

        /** 开始朗读时启动前台服务（幂等）。 */
        fun start(context: Context) {
            val intent = Intent(context, SpeechService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 停止朗读配套服务（不移除通知，由控制器 stop 后的状态流触发收尾）。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, SpeechService::class.java))
        }
    }
}

/** 通知元数据快照。 */
data class SpeechMetadata(
    val book: Book?,
    val chapterTitle: String,
    val paragraphs: List<String>,
)
