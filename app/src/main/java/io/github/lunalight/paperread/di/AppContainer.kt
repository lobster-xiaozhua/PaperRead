package io.github.lunalight.paperread.di

import android.content.Context
import io.github.lunalight.paperread.BuildConfig
import io.github.lunalight.paperread.backup.BackupCoordinator
import io.github.lunalight.paperread.backup.BackupExporter
import io.github.lunalight.paperread.backup.BackupFontReference
import io.github.lunalight.paperread.backup.BackupImporter
import io.github.lunalight.paperread.backup.FontAvailability
import io.github.lunalight.paperread.data.font.CustomFontStore
import io.github.lunalight.paperread.data.local.AppDatabase
import io.github.lunalight.paperread.data.local.BookDao
import io.github.lunalight.paperread.data.local.BookmarkDao
import io.github.lunalight.paperread.data.local.ChapterDao
import io.github.lunalight.paperread.data.repository.AndroidBookImportGateway
import io.github.lunalight.paperread.data.repository.BookImportGateway
import io.github.lunalight.paperread.data.repository.BookImporter
import io.github.lunalight.paperread.data.repository.BookRepositoryImpl
import io.github.lunalight.paperread.data.onboarding.OnboardingStore
import io.github.lunalight.paperread.data.repository.BookStateWriter
import io.github.lunalight.paperread.data.repository.DefaultBookImporter
import io.github.lunalight.paperread.data.settings.PreferencesSettingsStore
import io.github.lunalight.paperread.data.settings.ReaderSettings
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
import kotlinx.coroutines.flow.first

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
     * 自定义字体库（T-018 / US-16）。
     *
     * 文件落 `filesDir/fonts/<id>.<ext>`，元数据落独立 DataStore（`custom_fonts`）。
     * 与 reader_settings 分开：字体元数据是「用户资产」，阅读偏好是「设备偏好」，
     * 混在一个文件里会让将来的导出 / 清理互相牵连。
     *
     * `create` 内部建 DataStore，**全进程只能调一次**（同一文件不允许两个存活
     * 实例，见 PreferencesDataStoreProvider）——lazy 保证。
     */
    val customFontStore: CustomFontStore by lazy { CustomFontStore.create(applicationContext) }

    /**
     * 新手引导标记（T-020 / US-18）。
     *
     * 独立 DataStore（`app_prefs`）+ 注入「是否老用户」判据（AC-18.1：
     * 升级安装不属于首次）。判据放在这里而不是 OnboardingStore 内部，
     * 是因为「什么算用过」是装配层的知识（要知道 preference 与书架两处），
     * 而 OnboardingStore 只该知道「有没有痕迹」。
     */
    val onboardingStore: OnboardingStore by lazy {
        OnboardingStore.create(applicationContext) { hasLegacyUsage() }
    }

    /**
     * 老用户判据：**行为痕迹**而非「键是否存在」。
     *
     * - 阅读设置与默认值不同（用户真的改过字号 / 主题 / 朗读…）；
     * - 或书架非空（用户真的导入过书）。
     *
     * 刻意不用「DataStore 文件里有没有键」：写一遍全默认值（测试复位、
     * 或旧版本某些路径顺手写盘）也会留下键，但那不是使用痕迹；反过来，
     * 升级用户即使从没进过设置页，只要书架有书就一定不是新用户。
     *
     * `internal`：单测直接断言这条判据（装配漏接是最容易漏测的失败模式）。
     */
    internal suspend fun hasLegacyUsage(): Boolean {
        if (settingsStore.settings.first() != ReaderSettings()) return true
        return bookRepository.observeBooks().first().isNotEmpty()
    }

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
     * 共享导入网关（T-015 / AC-9.5 / AC-14.4）：备份恢复复用与传书 / 迁移
     * 完全相同的导入流水线（`prepare → confirm`），不存在第二条导入逻辑。
     */
    val bookImportGateway: BookImportGateway by lazy {
        AndroidBookImportGateway(applicationContext, bookImporter, bookRepository)
    }

    /** 阅读状态回写（迁移与备份共用，T-015）。 */
    private val bookStateWriter: BookStateWriter by lazy { BookStateWriter(bookRepository) }

    /**
     * 本地备份导出器（US-14）。
     *
     * 字体引用取自当前阅读设置（AC-14.9：只记录引用名，不打包字体文件）。
     */
    private val backupExporter: BackupExporter by lazy {
        BackupExporter(
            bookRepository = bookRepository,
            bookTextStore = bookTextStore,
            fontReferencesProvider = {
                val family = settingsStore.settings.first().fontFamily
                listOf(
                    BackupFontReference(
                        name = family,
                        displayName = family,
                        builtIn = family in FontAvailability.BUILT_IN_FONT_NAMES,
                    ),
                )
            },
            appVersion = { BuildConfig.VERSION_NAME },
        )
    }

    /**
     * 本地备份恢复器：临时文件落 cacheDir/backup/（FileProvider 已登记该路径，
     * 解包出的源文件才能转 content Uri 交给导入流水线）。
     */
    private val backupImporter: BackupImporter by lazy {
        BackupImporter(
            bookRepository = bookRepository,
            gateway = bookImportGateway,
            stateWriter = bookStateWriter,
            workDir = java.io.File(applicationContext.cacheDir, "backup/work"),
        )
    }

    /** 备份 / 恢复编排（设置页唯一入口，生命周期随 Application）。 */
    val backupCoordinator: BackupCoordinator by lazy {
        BackupCoordinator(applicationContext, backupExporter, backupImporter, appScope)
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
