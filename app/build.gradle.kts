plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    id("jacoco")
}

// 大文件样本门控属性（T-007 · R-05）：Gradle 属性 → 测试 JVM 系统属性。
// 缺省（不传）时 LargeBookImportTest 跑 20MB 中等样本；发版前手动开 200MB：
//   ./gradlew testClientDebugUnitTest --tests "*LargeBookImportTest" \
//     -Ppaperread.largeBook.200mb=true -Ppaperread.largeBook.mb=200
val largeBookTestProperties = listOf(
    "paperread.largeBook.mb",
    "paperread.largeBook.200mb",
    "paperread.samplePath",
)

android {
    namespace = "io.github.lunalight.paperread"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.lunalight.paperread"
        minSdk = 26
        targetSdk = 35
        // versionCode 只能递增、不可回退（回退会导致 INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
        // 每次发版 +1；规则见 docs/SIGNING.md §9 与 docs/release-notes-template.md。
        versionCode = 5
        versionName = "0.2.0"
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
    //
    // 密钥即应用身份（docs/SIGNING.md §1）：同一密钥才能覆盖安装旧版本，密钥一旦丢失，
    // 该包名（io.github.lunalight.paperread）下已装用户将永久无法更新，且无法找回。
    // 本地签名：在 local.properties（或 ~/.gradle/gradle.properties）写入上述四个属性
    // （模板见仓库根 keystore.properties.template），再 ./gradlew assembleClientRelease。
    // F-Droid 渠道从源码自行构建并自签（docs/SIGNING.md §7），与我们的 release 密钥无关，
    // 不需要、也不应交出密钥给第三方。
    val releaseStoreFile: File? =
        (project.findProperty("RELEASE_STORE_FILE") as String?)?.let { file(it) }

    // 发布护栏（docs/SIGNING.md §5）：置 RELEASE_ENFORCE_SIGNING=true 时若仍未配置签名，
    // 直接失败，杜绝把未签名包当发布包发出去。CI 的 assemble 作业不签名、不置该属性，保持绿。
    val enforceSigning = project.findProperty("RELEASE_ENFORCE_SIGNING") == "true"
    if (releaseStoreFile == null && enforceSigning) {
        throw GradleException(
            "发布构建要求签名，但未找到 RELEASE_STORE_FILE。请按 docs/SIGNING.md 配置签名属性后再发布。",
        )
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
                // 显式开启全签名方案（docs/SIGNING.md §6）：
                // v3 支持密钥轮转，未来密钥泄露可平滑轮换而不丢更新能力。
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
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
            all { test ->
                // CI 单元门禁传 -PexcludeUiSmokeTests=true 排除 ui/smoke 冒烟：
                // Robolectric UI 渲染在内存受限环境下存在超时抖动（G9 已知基线
                // 现象，非回归），移交 robolectric-ui 独立作业（重试 ≤ 1）承载，
                // 不阻塞主测试门禁。本地构建不传该属性，全量照跑，行为不变。
                if (project.findProperty("excludeUiSmokeTests") == "true") {
                    test.filter.excludeTestsMatching("io.github.lunalight.paperread.ui.smoke.*")
                }
            }
            // 大文件样本体积门控（T-007 · R-05）：Gradle 属性 → 测试 JVM 系统属性。
            // 不转发则 LargeBookImportTest 只能读缺省值（20MB），200MB 用例无法手动开启。
            all { test ->
                largeBookTestProperties.forEach { key ->
                    project.findProperty(key)?.let { test.systemProperty(key, it.toString()) }
                }
            }
            // release 变体单测收窄（T-008 · R-07，架构 §6.2 方案 B）：
            // clientRelease 变体只跑 quality.* ——R8 后的「序列化仍可用」JVM 侧近似验证
            // （ReleaseSerializationTest）。不放全量：release 变体的 BuildConfig /
            // 资源差异会让既有 debug 口径的 UI / 变体断言失败，与 R8 无关，
            // 收窄后该作业的失败必然是序列化契约问题，信号干净。
            all { test ->
                if (test.name.contains("Release")) {
                    test.filter.includeTestsMatching("io.github.lunalight.paperread.quality.*")
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

// ---- 覆盖率分母口径（report 与 verification 共用同一组 val，改一处即两处生效）----
//
// 三模块聚合口径（T-014 / R-12 收尾）：
// T-010 拆出 `:core:reader`、T-013 拆出 `:core:data` 后，引擎与数据层的
// class / 测试 exec 都不在本模块——若只度量 app 侧，那 99 例引擎测试与
// 迁移后的 data 层测试（Room 迁移 / 仓储 / DataStore）将**没有任何覆盖率
// 兜底**（T-010 曾登记为「已知空窗」，由本项消除）。现把两个 core 模块的
// class 目录 + 测试 exec + 源码根一并纳入分母：
//   - class：core/data 的 tmp/kotlin-classes/clientDebug（library 变体，
//     与 :app 同 flavor 口径）+ core/reader 的 classes/kotlin/main（纯 JVM）；
//   - exec：core/data/build/jacoco/testClientDebugUnitTest.exec +
//     core/reader/build/jacoco/test.exec（jacoco 插件默认输出位置）；
//   - dependsOn：verification / report 显式依赖两个 core 模块的测试任务，
//     保证 exec 不是上一轮构建的陈旧产物。
// 排除清单跨模块沿用（R / BuildConfig / 白名单渲染类）：`coverageExcludedRenderClasses`
// 里的类全在 app（core 侧无 Compose 渲染树），对 core 的 class 目录套同一组
// exclude 只是让 `**/R.class` / BuildConfig / 内部类排除规则继续生效。
// CI 口径：`-PexcludeUiSmokeTests=true` 只过滤 app 的 ui.smoke 用例，core 模块
// 的测试不受该属性影响，全部计入分子。
//
// 为什么要动分母（2026-10 第 0 阶段出口验收的阻塞项 P1）：
// `28d70fc`（T-001 七作业拆分）把 `ui.smoke.*`（Robolectric Compose 渲染冒烟）
// 从 `unit` 作业移交给 `robolectric-ui` 作业，但覆盖率分母没有同步——于是
// 「只由渲染冒烟覆盖」的纯 Compose 渲染类留在分母里、覆盖数却是 0：
// 排除 ui.smoke 后实测 LINE 4164/6226 = 66.88% < 80%，门禁长期红着，只能靠人肉忽略。
//
// 判据：**Compose 渲染树的行覆盖率只有 instrumented（androidTest）口径才有意义**。
// 单测能覆盖的是状态逻辑（ViewModel / Controller / Manager / Repository / 纯函数）；
// 渲染函数体是 `@Composable` 的布局指令——在 Robolectric 冒烟里「跑过」不等于
// 「被测过」。故：少数**经登记确认 CI 口径 0% 且无测试逻辑引用**的纯渲染类不进分母；
// 其余一律度量，UI 行为由 `robolectric-ui` 作业的行为冒烟承载。
//
// 为什么由「模式通配」改为「显式白名单」（2026-10 批次 4 覆盖率口径整改）：
// 旧实现用 `*ScreenKt` / `*DialogKt` 等通配剔除整类文件，制造了两条后门：
//   1) 被剔除的文件里一旦有**被测试直接调用的逻辑函数**（如 ImportResultDialogKt
//      的 formatFileSize、LicenseScreenKt 的 readLicenseText），那些行的覆盖数据
//      会随文件一起从分母消失——被测行凭空蒸发，比率虚高；
//   2) 通配让**新增**的 `FooScreen.kt` 自动脱离度量：往 Screen.kt 里加逻辑，
//      分母不涨、比率不降、门禁看不出来。
// 现改为逐类登记的白名单：新增文件默认进分母，要划出去必须在此显式加一行。
//
// 登记条件（两条同时满足，缺一不可）：
//   1) CI 口径（`-PexcludeUiSmokeTests=true`）下该类 LINE 覆盖率为 0%（完全未执行）；
//   2) 无任何 CI 测试代码 import / 调用其（逻辑）成员。**仅被 ui.smoke 渲染用例
//      引用不算「被测」**——那批用例正是被排除在 CI 门禁之外的 Robolectric 渲染冒烟，
//      它们「跑过」某 Composable 不等于该文件的行被外部逻辑验证。
// 登记前请用 `jacocoTestReport` 的 XML 逐类核对 missed / covered 行数，并用
// `grep app/src/test` 确认无直接调用。当前登记 7 个（合计 1373 行 0%）：
//   ReaderScreenKt / ReaderContentKt / ReaderPagerKt / ReaderChromeKt /
//   SpeechControlBarKt / TransferScreenKt / ReaderSettingsSheetKt。
//
// 刻意**不**剔除（它们覆盖率 > 0 或含被测试引用的逻辑）：
// - `ui/reader/ReaderEffectsKt`：T-011 拆分时**刻意**从 ReaderScreenKt 挪出来的
//   50 行（亮度覆盖与还原 / 常亮 / 沉浸 / 系统栏图标 / 朗读前台服务生命周期）。
//   它们是窗口与服务状态机而非布局指令——属于「该补测的逻辑」，因此如实计入分母
//   （CI 口径 0/50），不靠剔除掩盖；技术债 #2 里「亮度保存还原 / SpeechService
//   编排不在门禁内」一条由此消解；
// - `ImportResultDialogKt`（formatFileSize / formatChars 被测）、`LicenseScreenKt`
//   （readLicenseText 被测）、`SettingsScreenKt`、`AboutScreenKt`、`PrivacyScreenKt`、
//   `ReadingPreferencesPanelKt`、`ThemeKt`、`BookshelfScreenKt`、`PaperReadNavHostKt`
//   ——CI 口径下覆盖率均 > 0%（被非 smoke 的 UI 用例渲染，含 MainActivity 启动链路）；
// - `theme/ColorKt`、`theme/ReaderThemes`：调色板与主题**数据**，非渲染树，有单测覆盖；
// - `reader/PaginatorKt`、`reader/PaginationKt`：纯函数分页计算，属逻辑；
// - `ui/reader/RememberChapterPagesKt`（composable + 章节加载状态）、
//   `ui/bookshelf/CoverGenerator`（Canvas 封面绘制）：CI 口径实测 0%，但属逻辑/绘制
//   且下一步应补测，如实留在分母里、不靠剔除掩盖。
//
// 维护约定：
// - 新增纯渲染类需要划出分母 → 在此登记，并写明「0% + 无测试逻辑引用」的核对结果；
// - 已登记类若后续新增逻辑 → 它仍在分母外（白名单按类名生效，这是显式白名单的
//   固有权衡），因此**应将其从白名单移除**，或把逻辑挪到独立文件后再登记；
// - 逻辑一律不写进这些文件（见 CONTRIBUTING.md）。
private val coverageExcludedRenderClasses = listOf(
    // 阅读器编排层（CI 口径 134 行 0%：仅 ReaderSpeech / Spread / UiSmoke 等 ui.smoke
    // 用例渲染 ReaderScreen）。T-011 前它是 645 行的 God File，正文 / 翻页 / 外壳 /
    // 效果 / 朗读条已拆到下列各类——本类只剩状态收集 + 事件分发 + 部件组合，
    // 无任何可单测的逻辑成员（grep app/src/test 仅 ui.smoke 引用 ReaderScreen 本身）。
    "io/github/lunalight/paperread/ui/reader/ReaderScreenKt.class",
    // 正文渲染 + 字体应用 + 朗读高亮槽（CI 口径 126 行 0%）：ReaderContent /
    // ChapterFooter / ReaderError / readerTextStyle / paragraphHighlight。
    // 无 CI 测试直接引用（ChapterFooter 仅被同包渲染文件调用）。
    "io/github/lunalight/paperread/ui/reader/ReaderContentKt.class",
    // 翻页 + 双页跨页渲染（CI 口径 316 行 0%）：ReaderPagerContent / ReaderPagerScaffold /
    // PageContent / PageEdgeShadows / PaginatedSpread / PageColumn。
    // 分页的**逻辑**不在此文件：纯函数装箱在 Paginator.kt、度量在 rememberChapterPages.kt，
    // 两者都留在分母内计账（下条「刻意不剔除」亦已说明）。
    "io/github/lunalight/paperread/ui/reader/ReaderPagerKt.class",
    // 阅读器外壳（CI 口径 141 行 0%）：ReaderTopBar / ReaderBottomBar / TocSheet /
    // ChapterList / BookmarkList / formatBookmarkTime。无 CI 测试直接引用
    // （TocSheet 仅被 UiSmokeTest 渲染；formatBookmarkTime 为文件内私有）。
    "io/github/lunalight/paperread/ui/reader/ReaderChromeKt.class",
    // 朗读控制条 + 通知权限说明对话框（CI 口径 112 行 0%）：仅 ReaderSpeech /
    // ReaderSpeechPermission 等 ui.smoke 用例渲染。
    "io/github/lunalight/paperread/ui/reader/SpeechControlBarKt.class",
    // 传书 / 迁移 / 同步页渲染树（CI 口径 529 行 0%：仅 UiSmokeTest 渲染）
    "io/github/lunalight/paperread/ui/transfer/TransferScreenKt.class",
    // 阅读设置弹层（CI 口径 15 行 0%：仅 UiSmokeTest 渲染）。T-011 起 TocSheet 已
    // 移出到 ReaderChromeKt，本类只剩 ModalBottomSheet 外壳 + ReadingPreferencesPanel 容器。
    "io/github/lunalight/paperread/ui/reader/ReaderSettingsSheetKt.class",
)

val coverageClassExcludes = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.class",
    "**/Manifest.class",
    // Kotlin 闭包 / 匿名类（含 Compose 的 ComposableSingletons$*）：无独立行号，度量无意义
    "**/*\$*.class",
) + coverageExcludedRenderClasses

val coverageClassDirectories = files(
    // :app——client 变体（唯一对外交付版，覆盖率门禁保护的口径）
    fileTree("${layout.buildDirectory.get().asFile}/tmp/kotlin-classes/clientDebug") {
        exclude(coverageClassExcludes)
    },
    // :core:data——Android library，clientDebug 变体的 Kotlin class 目录
    fileTree("${rootProject.projectDir}/core/data/build/tmp/kotlin-classes/clientDebug") {
        exclude(coverageClassExcludes)
    },
    // :core:reader——纯 JVM 模块，主源码 class 目录
    fileTree("${rootProject.projectDir}/core/reader/build/classes/kotlin/main") {
        exclude(coverageClassExcludes)
    },
)

val coverageSourceDirectories = files(
    "${projectDir}/src/main/java",
    "${rootProject.projectDir}/core/data/src/main/java",
    "${rootProject.projectDir}/core/reader/src/main/kotlin",
)

// 各模块的测试 exec 来源（与 classDirectories 一一对应）：
//   app      → jacoco 插件默认输出 build/jacoco/testClientDebugUnitTest.exec
//              （outputs/unit_test_code_coverage 为 AGP 单测覆盖率兼容路径，历史口径保留）
//   core:data → jacoco 插件 + clientDebug 变体 → core/data/build/jacoco/testClientDebugUnitTest.exec
//   core:reader → jacoco 插件 + JVM 模块 → core/reader/build/jacoco/test.exec
// 只精确匹配变体对应文件名，不通配 *.exec（否则 dev 变体 / 历史残留 exec
// 会混进本次度量）。
val coverageExecutionData = files(
    fileTree("${layout.buildDirectory.get().asFile}/jacoco") { include("testClientDebugUnitTest.exec") },
    fileTree("${layout.buildDirectory.get().asFile}/outputs/unit_test_code_coverage") { include("**/testClientDebugUnitTest.exec") },
    fileTree("${rootProject.projectDir}/core/data/build/jacoco") { include("testClientDebugUnitTest.exec") },
    fileTree("${rootProject.projectDir}/core/reader/build/jacoco") { include("test.exec") },
)

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "生成单元测试覆盖率报告（XML + HTML，app + :core:data + :core:reader 三模块聚合）"
    dependsOn("testClientDebugUnitTest")
    // 各模块测试任务必须先进图，否则 core 侧 exec 可能是上一轮构建的陈旧产物
    dependsOn(":core:data:testClientDebugUnitTest")
    dependsOn(":core:reader:test")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    sourceDirectories.setFrom(coverageSourceDirectories)
    classDirectories.setFrom(coverageClassDirectories)
    executionData.setFrom(coverageExecutionData)
}

// 覆盖率门禁：`./gradlew jacocoTestCoverageVerification`。
// 与上方 report 共用同一组 `coverageClassDirectories` / executionData / 过滤规则
// （同一个 val，不是两处复制），只读 exec 不做插桩，不会触发 report 注释里
// 描述的 worker 崩溃问题。
//
// 口径声明（与 README / docs/TASKS.md / CONTRIBUTING.md 表述一致）：
// 「行覆盖率 ≥ 80%：聚合口径为 app + :core:data + :core:reader 三模块；
// 经登记的零覆盖纯渲染类不计入分母（当前 7 个，见上方
// `coverageExcludedRenderClasses` 清单，新增需登记并说明理由）；其余所有类
// 一律计入分母。UI 行为由 robolectric-ui 作业的行为冒烟承载」。CI `unit` 作业以
// `-PexcludeUiSmokeTests=true` 口径校验本门禁——两个作业口径互补，不重叠。
tasks.register<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    group = "verification"
    description = "校验单元测试行覆盖率不低于阈值（三模块聚合口径），防止覆盖率回归"
    dependsOn("testClientDebugUnitTest")
    // 各模块测试任务必须先进图，否则 core 侧 exec 可能是上一轮构建的陈旧产物
    dependsOn(":core:data:testClientDebugUnitTest")
    dependsOn(":core:reader:test")
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
    sourceDirectories.setFrom(coverageSourceDirectories)
    classDirectories.setFrom(coverageClassDirectories)
    executionData.setFrom(coverageExecutionData)
}

dependencies {
    // 多模块拆分（T-010 / R-12，T-013 增补 :core:data）：
    // - :core:data 数据层（Room / DataStore / 导入流水线），api 依赖 domain
    //   会把领域模型传递进来，故这里不再显式声明 :core:domain；
    // - :core:reader 纯 JVM 阅读引擎（api 依赖 :core:domain）。
    // 仍留在本模块的 reader 相关实现：FileBookTextStore（需 Context.filesDir）。
    // AndroidXmlParser / XmlParsers 随其唯一生产消费方（data 层的 EPUB 解析器）
    // 落在 :core:data（T-013）。
    implementation(project(":core:data"))
    implementation(project(":core:reader"))

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
