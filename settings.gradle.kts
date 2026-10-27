pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PaperRead"

// 多模块拆分（T-010 / R-12，T-013 增补 :core:data）：
// - :app          Android 应用（UI / Android 相关实现 / 手动 DI 装配）
// - :core:reader  纯 JVM 阅读引擎（零 Android 依赖），api 依赖 :core:domain
// - :core:domain  纯 JVM 领域模型与仓储接口
// - :core:data    Android library 数据层（Room / DataStore / 导入流水线），
//                 api 依赖 :core:domain、implementation 依赖 :core:reader
//
// 四者共用同一组包名（io.github.lunalight.paperread.*），拆分对调用方透明：
// 所有 import 无需变更，diff 只体现文件位置与模块间依赖。
include(":app")
include(":core:domain")
include(":core:reader")
include(":core:data")
