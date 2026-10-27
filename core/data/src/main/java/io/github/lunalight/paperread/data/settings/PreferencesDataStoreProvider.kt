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
 * 通用 Preferences DataStore 持有者：**一个落盘文件 = 一个实例**。
 *
 * 与 [SettingsDataStoreProvider] 同款约束（见该类的长注释）：DataStore 的
 * `FileStorage` 用进程级 `activeFiles` 判定「同一文件是否已有存活实例」，
 * 重复创建会在首读时抛 `IllegalStateException`。因此本类的实例必须由
 * 唯一持有者（[io.github.lunalight.paperread.di.AppContainer]）保管，
 * 且每个 `name` 只 new 一次。
 *
 * 为什么不让每个 Store 自己 `by preferencesDataStore(...)` 顶层委托：
 * 委托把实例缓存成进程级单例并忽略后续 Context，Robolectric 下会让后续
 * 测试类复用第一个测试类已销毁的目录 —— SettingsDataStoreProvider 的
 * 长注释记录过这个坑（UI 冒烟「正文永不渲染」的根因），此处不重蹈。
 *
 * @param context 任意 Context（内部取 applicationContext）
 * @param name 落盘文件名（`filesDir/datastore/<name>.preferences_pb`）
 */
class PreferencesDataStoreProvider(context: Context, name: String) {

    private val applicationContext: Context = context.applicationContext

    /** 实际落盘文件（路径由 AndroidX 的 `preferencesDataStoreFile` 求值）。 */
    val file: File = applicationContext.preferencesDataStoreFile(name)

    /**
     * DataStore 实例。
     *
     * 作用域与进程同生命周期（`SupervisorJob + Dispatchers.IO`，不取消）：
     * DataStore 的写盘与流发射都跑在它上面，取消即等于「该文件永久静默」。
     */
    val instance: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        produceFile = { file },
    )
}
