package io.github.lunalight.paperread

import android.app.Application
import io.github.lunalight.paperread.di.AppContainer

/**
 * 纸阅 Application。
 *
 * 依赖通过 [AppContainer] 手动注入（服务定位器模式）。
 * M1 阶段容器为空壳，M2 起注入 Room 数据库与 Repository。
 *
 * T-021 起在这里安装崩溃钩子：必须在 `onCreate` **最早**处完成，否则进程
 * 启动阶段的崩溃（最值得看的那批）一个都记不到。顺序也有讲究——先建容器
 * （要拿 `crashLogStore`），再装钩子；钩子内部链式调用系统的原处理器，
 * 因此不改变「系统弹 FC 对话框」的行为。
 */
class PaperReadApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 链式崩溃处理：先记录，再无条件转发给原处理器（见 CrashLogStore.install）
        container.crashLogStore.install()
    }
}
