plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("jacoco")
}

android {
    namespace = "io.github.lunalight.paperread"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.lunalight.paperread"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.0"
    }

    // 分发渠道（单一维度 distribution），2026-10-18 用户拍板（ROADMAP C9 决策点 2）：
    // 项目允许申请权限、保持最小权限即可——INTERNET 进主包（Android 上任何
    // socket，含连接局域网 IP，都必须声明 INTERNET，系统无「仅局域网」权限），
    // 传书 / 迁移 / 同步均只在局域网内进行，不访问公网。原「standard 零权限 +
    // lanTransfer 传书版」双包策略取消，传输能力进主包，两个 flavor 仅区分
    // 交付形态：
    // - client：客户端正式版，唯一对外交付渠道。
    // - debug：调试版。applicationId 后缀保证与 client 可同机安装（一台设备
    //   即可自测传书互发），并携带 IS_DEBUG_BUILD 标记供调试能力判定。
    //   （AGP 禁止 flavor 与 build type 同名，故调试版 flavor 取名 dev，
    //   与 build type debug 组合出 devDebug 变体。）
    flavorDimensions += "distribution"
    productFlavors {
        create("client") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_DEBUG_BUILD", "false")
        }
        create("dev") {
            dimension = "distribution"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("boolean", "IS_DEBUG_BUILD", "true")
        }
    }

    // Release 签名：keystore 不入库，经 gradle properties / 环境变量注入：
    //   RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
    // 未配置时产出未签名 release APK（可用 apksigner 后续重签）。
    val releaseStoreFile: File? =
        (project.findProperty("RELEASE_STORE_FILE") as String?)?.let { file(it) }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        abortOnError = true
        checkDependencies = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 关于页展示版本号（AC-S1.1：BuildConfig.VERSION_NAME / VERSION_CODE）；
        // 单一来源仍是上方 defaultConfig 的 versionCode / versionName
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // 大文件导入测试（5MB/20MB 冒烟）自身需持有输入/输出全文做全量比对，
            // 512MB 默认堆处于临界；2GB 与 CI  runner（7GB）匹配
            all { test -> test.maxHeapSize = "2g" }
            // 双 fork 并行执行测试类：Gradle 默认单 fork，CI runner（4 核）上
            // Robolectric 类顺序执行会闲置 3 个核。不设更高值：每个 fork 是独立
            // JVM，峰值堆 2g × fork 数，2 是吞吐与内存的平衡点（本地 init 脚本
            // 亦为 2）。Robolectric 的 NATIVE 字体拷贝按测试类计费，与 fork 数无关。
            all { test -> test.maxParallelForks = 2 }
            all { test ->
                // Robolectric 下载 android-all 的仓库地址，可用
                // -Probolectric.dependency.repo.url=https://maven.aliyun.com/repository/public
                // 覆盖以加速国内网络；缺省用 Maven Central。
                project.findProperty("robolectric.dependency.repo.url")?.let {
                    test.systemProperty("robolectric.dependency.repo.url", it as String)
                }
            }
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Room schema 导出目录（`app/schemas/`，随代码入库）。
// 作用：每次编译把当前 schema 写成 JSON，字段变更在 PR diff 里可见，
// 也是 Room 编译期「迁移缺失」校验的依据——v1.1 之前 exportSchema=false，
// 加一个字段就会让升级用户崩在启动。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// JaCoCo 覆盖率：`./gradlew jacocoTestReport` → build/reports/jacoco/jacocoTestReport
//
// 两个非默认项的原因（都有实测依据，勿随意删除）：
// 1. inclNoLocationClasses = true：Robolectric 的 sandbox classloader 定义的类
//    ProtectionDomain 无 source location，默认配置下 agent 直接跳过 → 覆盖率恒 0%。
//    单独开启此项会让 agent 插桩 JVM 反序列化时动态生成的
//    GeneratedSerializationConstructorAccessor* 类，worker 启动即崩
//    （NoClassDefFoundError，测试根本不运行）。
// 2. includes 白名单：与上一项配套，把插桩范围收紧到本模块的类，
//    worker 基础设施与 JDK 内部类一概不碰，worker 崩溃与覆盖率兼得。
//
// flavor 口径：classDirectories / dependsOn 锁定 clientDebug 变体——
// 覆盖率门禁保护的是唯一对外交付版（client 客户端版）；debug 调试版除
// applicationId 后缀与 IS_DEBUG_BUILD 标记外与 client 同源同代码，其单测由
// CI 的 testDebugDebugUnitTest 同步执行，不重复计入本门禁。
tasks.withType<Test>().configureEach {
    extensions.configure(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java) {
        isIncludeNoLocationClasses = true
        includes = listOf("io.github.lunalight.paperread.*")
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "生成单元测试覆盖率报告（XML + HTML）"
    dependsOn("testClientDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    sourceDirectories.setFrom(files("${projectDir}/src/main/java"))
    classDirectories.setFrom(
        fileTree("${layout.buildDirectory.get().asFile}/tmp/kotlin-classes/clientDebug") {
            exclude(
                "**/R.class",
                "**/R$*.class",
                "**/BuildConfig.class",
                "**/Manifest.class",
                "**/*\$*.class",
            )
        },
    )
    // 只取 client 变体的 exec：build/jacoco 下同时存在 dev 变体（以及历史变体
    // 残留）的 exec 文件，通配 *.exec 会误吃 testDevDebugUnitTest 的输出——
    // 既触发 Gradle 隐式依赖校验失败，又会把旧变体数据混进本次度量。
    executionData.setFrom(
        fileTree("${layout.buildDirectory.get().asFile}/jacoco") { include("testClientDebugUnitTest.exec") },
        fileTree("${layout.buildDirectory.get().asFile}/outputs/unit_test_code_coverage") { include("**/testClientDebugUnitTest.exec") },
    )
}

// 覆盖率门禁：`./gradlew jacocoTestCoverageVerification`。
// 与上方 report 共用同一份 classDirectories / executionData / 过滤规则，
// 只读 exec 不做插桩，不会触发 report 注释里描述的 worker 崩溃问题。
//
// 阈值口径：行覆盖率（与 ROADMAP / README 口径一致）。当前实际约 87%
// （1,778 / 2,036），取 0.80 作为防回归底线：既挡住明显退化，又给新增代码
// 留足余量；随覆盖率提升可逐步上调（ratchet）。修改前先跑 jacocoTestReport
// 看真实值，切勿拍脑袋设高。
tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    group = "verification"
    description = "校验单元测试行覆盖率不低于阈值，防止覆盖率回归"
    dependsOn("testClientDebugUnitTest")
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
    sourceDirectories.setFrom(files("${projectDir}/src/main/java"))
    classDirectories.setFrom(
        fileTree("${layout.buildDirectory.get().asFile}/tmp/kotlin-classes/clientDebug") {
            exclude(
                "**/R.class",
                "**/R$*.class",
                "**/BuildConfig.class",
                "**/Manifest.class",
                "**/*\$*.class",
            )
        },
    )
    // 只取 client 变体的 exec：build/jacoco 下同时存在 dev 变体（以及历史变体
    // 残留）的 exec 文件，通配 *.exec 会误吃 testDevDebugUnitTest 的输出——
    // 既触发 Gradle 隐式依赖校验失败，又会把旧变体数据混进本次度量。
    executionData.setFrom(
        fileTree("${layout.buildDirectory.get().asFile}/jacoco") { include("testClientDebugUnitTest.exec") },
        fileTree("${layout.buildDirectory.get().asFile}/outputs/unit_test_code_coverage") { include("**/testClientDebugUnitTest.exec") },
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.window)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // B3 Compose UI 冒烟（Robolectric 模式）：BOM 统一版本，manifest 供
    // createComposeRule 的默认宿主 Activity
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
