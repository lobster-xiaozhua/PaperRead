// 顶层构建文件：插件在此声明但不应用，各模块按需引入
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    // 纯 JVM 模块（:core:domain / :core:reader）用；与 kotlin-android 共享
    // libs.versions.toml 的 kotlin 版本，保证 app 与 core 的编译器版本一致
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
