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
        versionCode = 2
        versionName = "1.0.1"
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
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // 大文件导入测试（5MB/20MB 冒烟）自身需持有输入/输出全文做全量比对，
            // 512MB 默认堆处于临界；2GB 与 CI  runner（7GB）匹配
            all { test -> test.maxHeapSize = "2g" }
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
tasks.withType<Test>().configureEach {
    extensions.configure(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java) {
        isIncludeNoLocationClasses = true
        includes = listOf("io.github.lunalight.paperread.*")
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    group = "verification"
    description = "生成单元测试覆盖率报告（XML + HTML）"
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    sourceDirectories.setFrom(files("${projectDir}/src/main/java"))
    classDirectories.setFrom(
        fileTree("${layout.buildDirectory.get().asFile}/tmp/kotlin-classes/debug") {
            exclude(
                "**/R.class",
                "**/R$*.class",
                "**/BuildConfig.class",
                "**/Manifest.class",
                "**/*\$*.class",
            )
        },
    )
    executionData.setFrom(
        fileTree("${layout.buildDirectory.get().asFile}/jacoco") { include("*.exec") },
        fileTree("${layout.buildDirectory.get().asFile}/outputs/unit_test_code_coverage") { include("**/*.exec") },
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
