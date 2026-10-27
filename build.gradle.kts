// 顶层构建文件：插件在此声明但不应用，各模块按需引入
plugins {
    alias(libs.plugins.android.application) apply false
    // 数据层 Android library（T-013 抽出 :core:data）：必须在根工程以 apply false
    // 声明一次——AGP 的 application 与 library 插件同 jar 发布，子工程单独请求
    // library 版本会因「插件已在 classpath 但版本未知」而解析失败
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    // 纯 JVM 模块（:core:domain / :core:reader）用；与 kotlin-android 共享
    // libs.versions.toml 的 kotlin 版本，保证 app 与 core 的编译器版本一致
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
