package io.github.lunalight.paperread.privacy

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 构建依赖坐标白名单（QA 补测 · T-009 / R-02 / G2① 的静态断言补强）。
 *
 * 为什么需要这条：`scripts/check-network-behavior.sh` 的三方 SDK 检出是
 * **关键字黑名单**（okhttp / retrofit / coil / firebase / umeng …）。变异
 * 验证表明，只要换一个不在名单里的真实公网 SDK（例如 `io.sentry:sentry-android`、
 * `com.amplitude:analytics-android`、`com.google.android.gms:play-services-ads`），
 * 黑名单脚本会全绿放行——这与隐私页「不集成任何第三方 SDK 与统计库」
 * 的承诺不一致。本测试把黑名单补成**白名单**：任何新增依赖坐标都必须
 * 先在此登记（等价于过一次隐私评审），未登记的坐标直接失败。
 *
 * 覆盖面：
 *  1. `gradle/libs.versions.toml` 里 [libraries] 的每个坐标；
 *  2. 所有 `*.gradle.kts` 里以字面量形式（`"group:name:version"`）直接写的
 *     依赖坐标——绕过版本目录也拦得住；
 *  3. 依赖声明里的 `libs.*` 别名必须能在版本目录中解析（防止拼错别名
 *     导致白名单失效）；
 *  4. 不得以本地二进制形式旁路坐标检查（`app/libs` 下的 jar / aar、
 *     `src/main/jniLibs` 下的 so）——预编译产物不受坐标白名单管辖。
 *
 * 新增依赖的正确姿势：先在 `docs/NETWORK_AUDIT.md` 说明用途，再把坐标
 * 前缀加进 [ALLOWED_GROUP_PREFIXES]（本文件顶部），评审通过才合入。
 */
class BuildDependencyAllowlistTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /**
     * 已评审的依赖来源（分组前缀白名单）。
     *
     * 口径：只允许 AndroidX / Kotlin 官方 / KSP / 测试基建（JUnit、
     * Robolectric、androidx.test、Turbine）。任何 `com.xxx` 三方 SDK
     * 都不在列——这正是隐私页对用户承诺的「无第三方 SDK」。
     */
    private val allowedGroupPrefixes = listOf(
        "androidx.",
        "org.jetbrains.kotlin",
        "org.jetbrains.kotlinx",
        "com.google.devtools.ksp",
        "junit",
        "org.robolectric",
        "androidx.test",
        "app.cash.turbine",
    )

    private val dependencyDeclarationKeywords = listOf(
        "implementation", "api", "compileOnly", "runtimeOnly", "ksp", "kapt",
        "testImplementation", "testCompileOnly", "testRuntimeOnly",
        "debugImplementation", "releaseImplementation",
        "androidTestImplementation", "androidTestRuntimeOnly",
    )

    /** 仓库根目录：从当前工作目录向上找 `settings.gradle.kts`（CI 与本机均适用）。 */
    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError(
            "QA-依赖白名单：未找到仓库根目录（向上查找 settings.gradle.kts 失败），" +
                "工作目录=${File("").absolutePath}",
        )
    }

    /**
     * 解析版本目录：[libraries] 段里别名 → `group:name`。
     *
     * 同时接受 `module = "group:name"` 与 `group = …, name = …` 两种写法。
     */
    private fun parseVersionCatalog(root: File): Map<String, String> {
        val toml = File(root, "gradle/libs.versions.toml")
        assertTrue("QA-依赖白名单：版本目录缺失 ${toml.path}", toml.isFile)

        val catalog = mutableMapOf<String, String>()
        var inLibraries = false
        // 条目可能跨行（多行 inline table），按行累积后再解析
        val buffer = StringBuilder()

        fun flushEntry() {
            val entry = buffer.toString().trim()
            buffer.setLength(0)
            if (!entry.contains('=')) return
            val alias = entry.substringBefore('=').trim()
            val body = entry.substringAfter('=')
            if (alias.isEmpty() || alias.startsWith("#")) return
            val module = Regex("""module\s*=\s*"([^"]+)"""")
                .find(body)?.groupValues?.get(1)
            val coordinate = module ?: run {
                val group = Regex("""group\s*=\s*"([^"]+)"""")
                    .find(body)?.groupValues?.get(1) ?: return
                val name = Regex("""\bname\s*=\s*"([^"]+)"""")
                    .find(body)?.groupValues?.get(1) ?: return
                "$group:$name"
            }
            // Gradle 访问器把 '-' 转成 '.'：androidx-core-ktx → libs.androidx.core.ktx
            catalog[alias.replace('-', '.')] = coordinate
        }

        toml.readLines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("[") -> {
                    flushEntry()
                    inLibraries = line.equals("[libraries]", ignoreCase = true)
                }

                !inLibraries || line.isEmpty() || line.startsWith("#") -> Unit

                else -> {
                    // 上一行已是完整条目（含成对花括号）时先结算，再起新条目
                    if (buffer.isNotEmpty() && !line.startsWith("{") && buffer.contains('=')) {
                        flushEntry()
                    }
                    buffer.append(line).append('\n')
                    if (buffer.contains('{') && buffer.contains('}')) flushEntry()
                }
            }
        }
        flushEntry()
        return catalog
    }

    /** 所有构建脚本：根目录与各模块的 `*.gradle.kts`。 */
    private fun buildScripts(root: File): List<File> =
        root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" && it.name != ".git" }
            .filter { it.isFile && it.name.endsWith(".gradle.kts") }
            .toList()

    /** 依赖声明块里出现的坐标：版本目录别名 + 字面量坐标。 */
    private fun declaredCoordinates(
        root: File,
        catalog: Map<String, String>,
    ): Pair<Set<String>, List<String>> {
        val coordinates = linkedSetOf<String>()
        val unresolvedAliases = mutableListOf<String>()
        val literalCoordinate = Regex("""^[\w.\-]+:[\w.\-]+(:[\w.\-]+)*$""")

        buildScripts(root).forEach { script ->
            script.readLines().forEach { line ->
                val declaresDependency = dependencyDeclarationKeywords
                    .any { line.trimStart().startsWith("$it(") }
                if (!declaresDependency) return@forEach
                // 版本目录别名：libs.a.b.c（platform(...) 包裹也能取到）
                Regex("""libs\.([A-Za-z0-9_.]+)""").findAll(line).forEach { match ->
                    val accessor = match.groupValues[1]
                    val coordinate = catalog[accessor]
                    if (coordinate == null) {
                        unresolvedAliases += "${script.name}: libs.$accessor"
                    } else {
                        coordinates += coordinate
                    }
                }
                // 字面量坐标："group:name:version"
                Regex(""""([^"]+)"""").findAll(line).forEach { match ->
                    val value = match.groupValues[1]
                    if (literalCoordinate.matches(value)) coordinates += value
                }
            }
        }
        return coordinates to unresolvedAliases
    }

    @Test
    fun `构建依赖坐标必须全部落在已评审白名单内`() {
        val root = repoRoot()
        val catalog = parseVersionCatalog(root)
        assertTrue("QA-依赖白名单：版本目录解析为空，解析逻辑可能已失效", catalog.isNotEmpty())

        val (coordinates, unresolved) = declaredCoordinates(root, catalog)
        assertTrue(
            "QA-依赖白名单：以下 libs.* 别名无法在 gradle/libs.versions.toml 中解析，" +
                "白名单校验会失效：$unresolved",
            unresolved.isEmpty(),
        )
        assertTrue("QA-依赖白名单：未解析到任何依赖声明，检查逻辑失效", coordinates.isNotEmpty())

        val offenders = coordinates.filterNot { coordinate ->
            val group = coordinate.substringBefore(':')
            allowedGroupPrefixes.any { group.startsWith(it) }
        }
        assertTrue(
            "QA-依赖白名单：以下依赖坐标未登记（隐私页承诺「无第三方 SDK」）。" +
                "新增依赖必须先过隐私评审并在 docs/NETWORK_AUDIT.md 说明，再登记到 " +
                "BuildDependencyAllowlistTest.allowedGroupPrefixes。违规坐标：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `不得以本地二进制形式旁路依赖坐标检查`() {
        val root = repoRoot()
        val binaryDirs = listOf(
            File(root, "app/libs"),
            File(root, "libs"),
            File(root, "app/src/main/jniLibs"),
            File(root, "app/src/main/assets"),
        )
        val offenders = binaryDirs
            .filter { it.isDirectory }
            .flatMap { dir ->
                dir.walkTopDown()
                    .filter { it.isFile }
                    .filter { it.extension.lowercase() in setOf("jar", "aar", "so") }
                    .toList()
            }
        assertTrue(
            "QA-依赖白名单：发现未登记的本地二进制（绕过坐标检查的旁路）：$offenders",
            offenders.isEmpty(),
        )
    }

    /**
     * 门禁自检（variation test）：用临时目录构造一份「偷偷引入三方 SDK」的
     * 构建脚本，断言扫描器确实把它们挑出来。
     *
     * 必要性：真实仓库上无法直接跑这条变异（新增坐标会让 Gradle 在依赖
     * 解析阶段先失败，跑不到测试），故用夹具证明检测力——否则「脚本全绿」
     * 可能只是扫描逻辑失效的假象。
     */
    @Test
    fun `门禁自检_三方 SDK 与字面量坐标必须被识别为违规`() {
        val root = tempFolder.newFolder("fake-repo")
        File(root, "settings.gradle.kts").writeText("rootProject.name = \"fake\"\n")
        File(root, "gradle").mkdirs()
        File(root, "gradle/libs.versions.toml").writeText(
            """
            [versions]
            composeBom = "2024.10.01"

            [libraries]
            compose-bom = { group = "androidx.compose", name = "compose-bom" }
            androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version = "1.15.0" }
            androidx-room-testing = { module = "androidx.room:room-testing", version = "2.8.5" }
            """.trimIndent(),
        )
        File(root, "app").mkdirs()
        File(root, "app/build.gradle.kts").writeText(
            """
            dependencies {
                implementation(libs.androidx.core.ktx)
                implementation(platform(libs.compose.bom))
                testImplementation(libs.androidx.room.testing)
                implementation("io.sentry:sentry-android:7.14.1")
                implementation("com.amplitude:analytics-android:1.0.0")
            }
            """.trimIndent(),
        )

        val catalog = parseVersionCatalog(root)
        val (coordinates, unresolved) = declaredCoordinates(root, catalog)

        assertEquals(
            "别名字面量必须都能解析（androidx.core:core-ktx / androidx.room:room-testing）",
            emptyList<String>(),
            unresolved,
        )
        assertTrue(
            "夹具里的白名单坐标应被识别：$coordinates",
            "androidx.core:core-ktx" in coordinates && "androidx.room:room-testing" in coordinates,
        )
        val offenders = coordinates.filterNot { coordinate ->
            val group = coordinate.substringBefore(':')
            allowedGroupPrefixes.any { group.startsWith(it) }
        }.sorted()
        assertEquals(
            "QA-依赖白名单：扫描器未能识别出夹具里的三方 SDK，门禁已失效",
            listOf("com.amplitude:analytics-android:1.0.0", "io.sentry:sentry-android:7.14.1"),
            offenders,
        )
    }
}
