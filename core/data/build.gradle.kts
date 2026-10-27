// :core:data —— Android library 数据层（T-013 / R-12）
//
// 承载 Room（local）/ DataStore（settings）/ 导入流水线（repository）。
// 之所以抽成独立 Android library 而非继续留在 :app：
//   1. Room schema 导出目录随模块走（core/data/schemas），数据层契约
//      （entity / dao / 迁移登记）在 diff 里与 UI 变更隔离；
//   2. data 层单测（Room 迁移 / 仓储 / 注册表）从 app 的 JacCoCo 分母
//      拆出后由 T-014 的三模块聚合口径重新兜底（exec 合并回 app 的
//      jacocoTestCoverageVerification），不是无门禁状态；
//   3. 「DataStore 实例由 app 的 AppContainer 创建」的装配边界保持不变——
//      本模块只提供 SettingsDataStoreProvider 类，不做任何单例缓存。
//
// 依赖约定：
//   - api(:core:domain)：BookRepositoryImpl 的公开签名暴露 domain 模型；
//     PreferencesSettingsStore 暴露 domain 的 SettingsStore 接口。
//   - implementation(:core:reader)：BookImporter 签名使用 BookTextStore /
//     SplitStrategy / BookParser，但这些类型不出现在本模块的公开 API 之外
//     消费方（:app）的编译口径里——:app 自己 implementation 依赖 reader
//     （FileBookTextStore 在那边），无需本模块转 api。
//
// 包名与拆分前完全一致（io.github.lunalight.paperread.data.*），
// :app 侧的 import 零改动。

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    id("jacoco")
}

android {
    namespace = "io.github.lunalight.paperread.data"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        // library 不产生 APK，consumer 无需 targetSdk（由 :app 声明）；
        // 显式置空避免 AGP 对 library targetSdk 的 lint 警告。
    }

    // 与 :app 同一 distribution 维度（client 正式版 / dev 调试版）：
    // 任务名保持 testClientDebugUnitTest（与 :app / CI / 验证清单一致），
    // :app 依赖本模块时 AGP 按 flavor 名自动匹配变体（client↔client）。
    flavorDimensions += "distribution"
    productFlavors {
        create("client") {
            dimension = "distribution"
        }
        create("dev") {
            dimension = "distribution"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Robolectric 需要合并后的资源 / assets：迁移测试夹具
            // （src/debug/assets/<db>/ 下的 schema JSON）由
            // MigrationTestHelper 从变体合并 assets 读取，此开关必须为 true。
            isIncludeAndroidResources = true
            all { test -> test.maxHeapSize = "2g" }
            all { test -> test.maxParallelForks = 2 }
            all { test ->
                // 与 :app 相同的 Robolectric android-all 镜像覆盖（国内加速）。
                project.findProperty("robolectric.dependency.repo.url")?.let {
                    test.systemProperty("robolectric.dependency.repo.url", it as String)
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Room schema 导出目录（`core/data/schemas/`，随代码入库）。
// 语义与迁移前一致：每次编译把当前 schema 写成 JSON，字段变更在 PR diff
// 里可见；scripts/check-room-migration.sh 的断言路径已同步指向本目录。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// JaCoCo：本模块测试的 exec（build/jacoco/testClientDebugUnitTest.exec）由
// :app 的 jacocoTestReport / jacocoTestCoverageVerification 聚合消费（T-014）。
// 两个非默认项与 :app 完全同源（勿随意删除，实测依据见 :app 同款注释）：
// 1. isIncludeNoLocationClasses = true：Robolectric sandbox classloader 下
//    默认配置覆盖率恒 0%；单独开启会插桩 JVM 反序列化动态类导致 worker 崩溃；
// 2. includes 白名单：把插桩收紧到本项目类，与上一项配套。
tasks.withType<Test>().configureEach {
    extensions.configure(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java) {
        isIncludeNoLocationClasses = true
        includes = listOf("io.github.lunalight.paperread.*")
    }
}

dependencies {
    // api：BookRepositoryImpl / PreferencesSettingsStore 的公开签名暴露
    // domain 模型与仓储接口，消费方（:app）编译期必须可见。
    api(project(":core:domain"))

    // BookImporter / BookParserRegistry 使用 BookTextStore / SplitStrategy /
    // BookParser（含 FileBookTextStore 的接口契约），不对外转嫁 → implementation。
    implementation(project(":core:reader"))

    // Room 全链路随本模块走：AppDatabase 的公开签名继承 RoomDatabase，
    // :app 的 AppContainer 持有 AppDatabase / 各 DAO，故 runtime 必须 api；
    // ktx 提供 suspend DAO 的协程桥；compiler 只在编译期生效。
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore：PreferencesSettingsStore 的构造签名暴露 DataStore<Preferences>，
    // :app 的 AppContainer 直接调用 → api。
    api(libs.androidx.datastore.preferences)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Room 迁移测试基建：MigrationTestHelper 建指定版本库 → 灌存量数据 →
    // runMigrationsAndValidate 走真实迁移链路。仅 testImplementation。
    testImplementation(libs.androidx.room.testing)
}

tasks.withType<Test>().configureEach {
    systemProperty("file.encoding", "UTF-8")
}
