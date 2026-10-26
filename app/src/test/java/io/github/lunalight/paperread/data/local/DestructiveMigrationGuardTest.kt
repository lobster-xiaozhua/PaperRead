package io.github.lunalight.paperread.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迁移门禁的 JVM 侧兜底（QA 补测 · T-006 / G6③⑤）。
 *
 * 与 `scripts/check-room-migration.sh` 的分工：脚本是 CI 的权威门禁，本
 * 测试是同一口径的**快速反馈副本**，跑在 `unit` 作业里，开发者本地
 * `./gradlew test` 就能感知，不必等 room-migration 作业。
 *
 * 为什么需要补这一条：变异验证发现脚本的 destructive fallback 断言是
 * `grep '\.fallbackToDestructiveMigration *('`，只认「不带参数」那一种写法。
 * 换成 `fallbackToDestructiveMigrationFrom(1)`（Room 里同样会**静默清库**、
 * 丢弃旧版本用户数据）即可让脚本全绿通过——这正是 PRD 风险表 #4
 * 「首次 Room 迁移失败 = 升级崩溃或静默丢书」的漏检路径。本测试按
 * API 名前缀扫描，覆盖全部三个变体：
 * `fallbackToDestructiveMigration()` / `(dropAllTables)` / `From(vararg versions)`。
 */
class DestructiveMigrationGuardTest {

    private val destructiveFallback = Regex("""fallbackToDestructiveMigration""")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw AssertionError("QA-迁移门禁：未找到仓库根目录（向上查找 settings.gradle.kts 失败）")
    }

    /** 剔除行注释与 KDoc 行（KDoc 里对本 API 的反例说明不算违规）。 */
    private fun codeLines(text: String): List<String> =
        text.lines()
            .map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    @Test
    fun `主源码不得启用任何 destructive fallback 变体`() {
        val mainSources = File(repoRoot(), "app/src/main").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue("QA-迁移门禁：未扫描到主源码，检查逻辑失效", mainSources.isNotEmpty())

        val offenders = mainSources.flatMap { file ->
            codeLines(file.readText())
                .filter { destructiveFallback.containsMatchIn(it) }
                .map { "${file.relativeTo(repoRoot())}: $it" }
        }
        assertEquals(
            "QA-迁移门禁：发现 destructive fallback（含 From / dropAllTables 变体）。" +
                "书架 / 进度 / 书签是用户数据，宁可升级报错也不许静默清库（G6⑤）。违规行：$offenders",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `schemas 导出与迁移测试夹具逐版本一致且不缺版本`() {
        val root = repoRoot()
        val dbFile = File(
            root,
            "app/src/main/java/io/github/lunalight/paperread/data/local/AppDatabase.kt",
        )
        assertTrue("QA-迁移门禁：找不到 AppDatabase.kt", dbFile.isFile)
        val version = Regex("""version\s*=\s*(\d+)""")
            .find(dbFile.readText())?.groupValues?.get(1)?.toInt()
            ?: throw AssertionError("QA-迁移门禁：未能解析 @Database version")

        val schemaDir = File(
            root,
            "app/schemas/io.github.lunalight.paperread.data.local.AppDatabase",
        )
        val fixtureDir = File(
            root,
            "app/src/debug/assets/io.github.lunalight.paperread.data.local.AppDatabase",
        )
        // 夹具放 src/debug/assets：Robolectric 的 MigrationTestHelper 只读变体
        // 合并后的 assets，src/test/assets 不进合并结果（放了等于空跑）。
        assertTrue("QA-迁移门禁：迁移测试夹具目录缺失 $fixtureDir", fixtureDir.isDirectory)

        val missing = (1..version).filter { !File(schemaDir, "$it.json").isFile }
        assertEquals("QA-迁移门禁：schemas/ 缺版本文件（version=$version）", emptyList<Int>(), missing)
        assertEquals(
            "QA-迁移门禁：schemas/ 文件数必须等于 version",
            version,
            schemaDir.listFiles { f -> f.extension == "json" }?.size ?: 0,
        )

        val drifted = (1..version).mapNotNull { v ->
            val export = File(schemaDir, "$v.json")
            val fixture = File(fixtureDir, "$v.json")
            when {
                !fixture.isFile -> "$v.json(缺失)"
                export.readText() != fixture.readText() -> "$v.json(不一致)"
                else -> null
            }
        }
        assertEquals(
            "QA-迁移门禁：迁移夹具与 schema 导出一致性失败——夹具漂移会让迁移测试" +
                "校验的不是当前 schema，等于空跑。请重新拷贝 app/schemas → src/debug/assets",
            emptyList<String>(),
            drifted,
        )
    }
}
