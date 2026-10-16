package io.github.lunalight.paperread

import android.app.Application
import io.github.lunalight.paperread.di.AppContainer

/**
 * 纸阅 Application。
 *
 * 依赖通过 [AppContainer] 手动注入（服务定位器模式）。
 * M1 阶段容器为空壳，M2 起注入 Room 数据库与 Repository。
 */
class PaperReadApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
