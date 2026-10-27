package io.github.lunalight.paperread.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 阅读设置 DataStore 的持有者：**每个 Application 实例一个**。
 *
 * ## 为什么不用 `Context.preferencesDataStore(name = ...)` 顶层委托
 *
 * `preferencesDataStore(name)` 返回的是 `PreferenceDataStoreSingletonDelegate`，它在
 * `getValue(thisRef, property)` 里把创建结果缓存进**自己的** `INSTANCE` 字段
 * （`INSTANCE` 一旦非空就完全忽略 `thisRef`）。顶层属性委托对象与进程同生命周期，
 * 于是「进程级单例」被硬绑到**第一个**访问它的 Context 上：
 *
 * - 真机：Application 只有一个，等价单例，行为正确；
 * - Robolectric：每个测试类新建 Application（新 filesDir、新协程作用域），委托却把
 *   后续测试类继续指回第一个测试类那个**已被销毁的目录 / 作用域**，`settings` 流
 *   因此不再正常发射 → `ReaderViewModel.uiState` 缺 settings → 正文永不渲染 →
 *   `ComposeTimeoutException`（加大超时无效：不是慢，是永远不来）。
 *
 * 改为「由持有者（[io.github.lunalight.paperread.di.AppContainer]，随 Application
 * 创建）显式创建」后，实例生命周期 = Application 生命周期：真机仍是单例（等价），
 * 测试里每个 Application 天然各持一份，跨测试类零泄漏。
 *
 * ## 落盘路径必须与旧实现逐字节一致（已发布用户数据不可丢）
 *
 * 旧委托的路径由两个公开 API 决定，本次**直接调用同一个函数**，不复制字符串拼接逻辑：
 *
 * 1. `PreferenceDataStoreFile.preferencesDataStoreFile(name)` =
 *    `context.dataStoreFile("$name.preferences_pb")`；
 * 2. `DataStoreFile.dataStoreFile(fileName)` =
 *    `File(context.applicationContext.filesDir, "datastore/$fileName")`。
 *
 * 代入 `name = "reader_settings"` 即 [LEGACY_RELATIVE_PATH]
 * （`filesDir/datastore/reader_settings.preferences_pb`）。因此升级用户的既有偏好
 * 文件被原样读到，不存在「换了个空文件、设置全丢」的风险；
 * `SettingsDataStoreProviderTest` 以「预置旧路径文件 → 新实现读到」的断言把该契约锁死。
 */
internal class SettingsDataStoreProvider(context: Context) {

    /**
     * 应用级 Context：DataStore 的生命周期与 Application 对齐，持有 Activity 会泄漏。
     */
    private val applicationContext: Context = context.applicationContext

    /**
     * 实际落盘文件（与旧委托同一路径，见类注释的推导）。
     *
     * 暴露给测试断言「路径未漂移」，生产代码不直接使用。
     */
    val file: File = applicationContext.preferencesDataStoreFile(DATA_STORE_NAME)

    /**
     * DataStore 实例。
     *
     * 同一进程内**同一文件只能有一个存活的 DataStore**（`FileStorage` 用进程级
     * `activeFiles` 集合判定，重复创建会在首读时抛 `IllegalStateException`），
     * 故本类的实例必须由唯一持有者（[io.github.lunalight.paperread.di.AppContainer]）
     * 保管，不要在多处 new。
     *
     * 作用域与旧委托的默认值一致（`SupervisorJob + Dispatchers.IO`，与进程同生命周期，
     * 不取消）：DataStore 的写盘与流发射都跑在它上面，取消即等于「设置永久静默」。
     */
    val instance: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { file },
        )

    internal companion object {

        /** 与旧 `preferencesDataStore(name = ...)` 的 name 完全一致。 */
        const val DATA_STORE_NAME = "reader_settings"

        /**
         * 旧实现的实际落盘相对路径（`filesDir` 之下）。
         *
         * 仅用于文档与测试断言；生产代码经 [preferencesDataStoreFile] 求值，
         * 避免「自己拼字符串」与库实现漂移。
         */
        const val LEGACY_RELATIVE_PATH = "datastore/reader_settings.preferences_pb"
    }
}
