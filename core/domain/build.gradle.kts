// :core:domain —— 纯领域模型与仓储接口（T-010 / R-12）
//
// 硬约束：本模块**不得**出现任何 Android / AndroidX 依赖。
// 抽成独立 Gradle 模块的价值就在这里——「领域层零 Android 依赖」从口头约定
// 变成编译期断言：谁在 io.github.lunalight.paperread.domain.* 里写 import android.*，
// 编译立刻失败，而不是等到某次重构才发现领域模型被 Context 污染。
//
// 包名与拆分前完全一致（io.github.lunalight.paperread.domain.model / .repository），
// 因此 :app / :core:reader 的 import 一行都不用改。

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 与 :app 的 jvmTarget 对齐（app/build.gradle.kts：compileOptions 17 +
// kotlin compilerOptions JVM_17）。不一致会导致 app 引用本模块 class 时
// 出现 "class file has wrong version" 或 Kotlin 的跨 target 兼容性告警。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // BookRepository 的公开签名直接暴露 Flow<List<Book>>，因此必须是 api：
    // 用 implementation 会让 :core:reader / :app 在编译期看不到 coroutines 类型。
    api(libs.kotlinx.coroutines.core)

    // 本模块当前无独立单测（拆分前也没有 domain 专项测试），保留声明以便
    // 后续为领域模型补测时无需再改构建脚本。
    testImplementation(libs.junit)
}

// 名称说明：任务名是 test（JVM 模块），不是 app 的 testClientDebugUnitTest。
tasks.withType<Test>().configureEach {
    // 纯 JVM，不加载 Android 资源、不启动 Robolectric sandbox。
    systemProperty("file.encoding", "UTF-8")
}
