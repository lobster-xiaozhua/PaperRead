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
import io.github.lunalight.paperread.data.settings.SettingsStore
import io.github.lunalight.paperread.domain.repository.BookRepository
import io.github.lunalight.paperread.reader.BookTextStore
import io.github.lunalight.paperread.reader.FileBookTextStore
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

    private val applicationContext = context.applicationContext

    private val database: AppDatabase by lazy { AppDatabase.build(applicationContext) }

    private val bookDao: BookDao by lazy { database.bookDao() }

    private val chapterDao: ChapterDao by lazy { database.chapterDao() }

    private val bookmarkDao: BookmarkDao by lazy { database.bookmarkDao() }

    val bookRepository: BookRepository by lazy {
        BookRepositoryImpl(bookDao, chapterDao, bookmarkDao)
    }

    val bookTextStore: BookTextStore by lazy { FileBookTextStore(applicationContext) }

    val settingsStore: SettingsStore by lazy { PreferencesSettingsStore(applicationContext) }

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
