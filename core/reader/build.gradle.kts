// :core:reader —— 纯 JVM 阅读引擎（T-010 / R-12）
//
// 承载「零 Android 依赖的阅读引擎」：TXT 编码探测 / 章节切分 / 双偏移索引 /
// EPUB 解析 / XHTML 正文提取 / 分页与进度纯计算 / BookTextStore 接口。
//
// 硬约束：
//   1. 不得出现任何 Android / AndroidX 依赖（含 Robolectric）——
//      本模块的单测必须能在纯 JVM 上跑，这是 T-010 的 DoD 核心；
//   2. 需要 Context / Android SDK 的实现（FileBookTextStore / AndroidXmlParser /
//      XmlParsers）留在 :app，本模块只保留接口与纯 JVM 实现。
//
// 包名与拆分前完全一致（io.github.lunalight.paperread.reader.*），调用方 import 无感。

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// 与 :app 的 jvmTarget 对齐（compileOptions 17 + kotlin JVM_17）。
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
    // api：BookTextStore 的方法签名暴露 domain 模型（Book / Chapter），
    // 消费方（:app）需要它们在编译期可见。
    api(project(":core:domain"))

    // TxtChapterStream / InlineChapterScanner 内部用 coroutines 的
    // CancellationException 语义（可取消的流式切章），不对外暴露类型 → implementation。
    implementation(libs.kotlinx.coroutines.core)

    // 纯 JVM 测试：JUnit4 即可。**刻意不引入 Robolectric**——引入即意味着
    // 引擎又粘上了 Android。
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    systemProperty("file.encoding", "UTF-8")
}
