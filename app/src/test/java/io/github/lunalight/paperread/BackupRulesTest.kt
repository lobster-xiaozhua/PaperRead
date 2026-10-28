package io.github.lunalight.paperread

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * B5 备份策略回归测试（方案甲-B）。
 *
 * 备份策略回归：原方案依赖 Android 云备份 / D2D（allowBackup + 双格式 XML 规则）。
 * v2.0 hardening 关闭了 Android 云备份（android:allowBackup=false），数据迁移统一以
 * 应用内「备份/恢复」（US-20，支持可选加密）为唯一真相源，避免云备份与应用自有备份
 * 两通道并存导致的数据不一致 / adb backup 泄露（docs/SIGNING.md §8）。
 *
 * 因此本测试不再解析已删除的 backup_rules.xml / data_extraction_rules.xml，只固化
 * 契约：Manifest 的 allowBackup 必须为 false（关闭系统云备份 / D2D）。
 *
 * 注：不通过 `resources.openRawResource` 读 XML——Robolectric 的资源通道对含中文注释
 * 的 XML 会做字符集转换导致解析失败，直接读源文件等价且稳定。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRulesTest {

    // ---- 工具 ----

    private fun sourceFile(relativePath: String): File {
        val candidates = listOf(File("src/main/$relativePath"), File("app/src/main/$relativePath"))
        return candidates.firstOrNull { it.exists() }
            ?: error("测试运行目录内找不到 $relativePath：${File("").absolutePath}")
    }

    private fun manifestText(): String = sourceFile("AndroidManifest.xml").readText()

    // ---- Manifest：关闭系统云备份 ----

    @Test
    fun `manifest 关闭 Android 云备份 allowBackup 应为 false`() {
        val manifest = manifestText()

        assertTrue(
            "allowBackup 应置 false（应用自有备份为唯一真相源，关闭 Android 云备份/D2D 避免双重备份冲突）",
            manifest.contains("""android:allowBackup="false""""),
        )
    }
}
