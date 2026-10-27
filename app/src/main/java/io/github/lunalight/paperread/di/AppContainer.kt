package io.github.lunalight.paperread.di

import android.content.Context
import io.github.lunalight.paperread.data.local.AppDatabase
import io.github.lunalight.paperread.data.local.BookDao
import io.github.lunalight.paperread.data.local.BookmarkDao
import io.github.lunalight.paperread.data.local.ChapterDao
import io.github.lunalight.paperread.data.repository.BookImporter
import io.github.lunalight.paperread.data.repository.BookRepositoryImpl
import io.github.lunalight.paperread.data.repository.DefaultBookImporter
import io.github.lunalight.paperread.data.settings.PreferencesSettingsStore
import io.github.lunalight.paperread.data.settings.SettingsDataStoreProvider
import io.github.lunalight.paperread.data.settings.SettingsStore
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore
import io.github.lunalight.paperread.reader.FileBookTextStore
import io.github.lunalight.paperread.speech.AndroidSpeechController
import io.github.lunalight.paperread.speech.SpeechController
import io.github.lunalight.paperread.transfer.DefaultLanTransferManager
import io.github.lunalight.paperread.transfer.DeviceIdentityStore
import io.github.lunalight.paperread.transfer.LanTransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手动依赖容器（服务定位器）。
 *
 * 本项目依赖点约 5 个，选择手动 DI 而非 Hilt：构建更快、贡献者上手成本更低。
 * 若未来依赖规模增长，可平滑替换为 Hilt 而不影响上层。
 *
 * M2：装配 Room 数据库、BookRepository、BookTextStore、BookImporter。
 */
class AppContainer(context: Context) {

    /**
     * 应用级 Context（供权限探测等只读使用，如 ReaderViewModel 的
     * POST_NOTIFICATIONS 可用性检查）。
     */
    val applicationContext: Context = context.applicationContext

    private val database: AppDatabase by lazy { AppDatabase.build(applicationContext) }

    private val bookDao: BookDao by lazy { database.bookDao() }

    private val chapterDao: ChapterDao by lazy { database.chapterDao() }

    private val bookmarkDao: BookmarkDao by lazy { database.bookmarkDao() }

    val bookRepository: BookRepository by lazy {
        BookRepositoryImpl(bookDao, chapterDao, bookmarkDao)
    }

    val bookTextStore: BookTextStore by lazy { FileBookTextStore(applicationContext) }

    /**
     * 阅读设置 DataStore（每个 Application 一份，随容器创建、懒初始化）。
     *
     * 这里显式持有实例，替代此前的文件级 `Context.settingsDataStore by
     * preferencesDataStore(...)` 委托：该委托把 DataStore 缓存成进程级单例并
     * 忽略后续 Context，Robolectric 下会让后续测试类的独立 Application 复用
     * 第一个测试类已销毁的目录 / 作用域（UI 冒烟「正文永不渲染」抖动的根因）。
     * 交给容器托管后，实例生命周期 = Application 生命周期：真机仍是单例（等价），
     * 测试里各 Application 天然隔离。落盘路径与旧实现完全一致（见
     * [SettingsDataStoreProvider] 注释），升级用户数据不受影响。
     */
    private val settingsDataStore: SettingsDataStoreProvider by lazy {
        SettingsDataStoreProvider(applicationContext)
    }

    val settingsStore: SettingsStore by lazy { PreferencesSettingsStore(settingsDataStore.instance) }

    /**
     * 语音朗读控制器（进程单例）。
     *
     * 系统 TextToSpeech 封装（零新增依赖、零网络）；初始化在主线程 Handler 中
     * 异步完成，构造本身轻量。Service / Activity / 通知按钮共享同一实例，
     * 后台常驻播放（用户 2026-10-19 拍板）由 SpeechService 保活。
     *
     * setter 仅供测试注入 fake（生产代码只读）；避免为可测性引入 DI 框架。
     */
    var speechController: SpeechController = AndroidSpeechController(applicationContext)
        internal set

    val bookImporter: BookImporter by lazy {
        DefaultBookImporter(applicationContext, bookRepository, bookTextStore)
    }

    /**
     * 局域网传输管理器（传书 / 迁移 / 同步共用一套发现 + HTTP 基础设施）。
     *
     * 设备标识为随机 UUID（落盘 filesDir/transfer/device_id.txt），
     * 不使用 MAC / IMEI / ANDROID_ID（SPEC AC-11.4）。
     * HTTP 服务仅绑 WiFi 网卡地址、生命周期与传书页绑定（AC-9.7）。
     */
    val transferManager: LanTransferManager by lazy {
        DefaultLanTransferManager(
            context = applicationContext,
            bookImporter = bookImporter,
            bookRepository = bookRepository,
            bookTextStore = bookTextStore,
            identityStore = DeviceIdentityStore(applicationContext),
            scope = appScope,
        )
    }

    /**
     * 应用级协程作用域：只用于「ViewModel 已销毁仍必须落地」的收尾写入
     * （退出阅读器时补存最后一次阅读位置、删除损坏条目）。
     *
     * 与 viewModelScope 的区别：后者在 onCleared 后立即取消，
     * 用它做收尾写会静默丢数据。与进程同生命周期，不需要也不应该取消。
     */
    val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}
