package io.github.lunalight.paperread

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element

/**
 * B5 备份策略回归测试（方案甲-B）。
 *
 * 备份规则是纯 XML 配置，没有编译期保护——属性漏接、规则被误删都会静默失效
 * （云备份继续超限失败 / 书籍目录重新上云），故以测试固化契约：
 * Manifest 双格式接线 + 云端排除 books + D2D 保留全量。
 *
 * 注：不通过 `resources.openRawResource` 读 XML——Robolectric 的资源通道
 * 对含中文注释的 XML 会做字符集转换导致解析失败，直接读源文件等价且稳定。
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

    private fun parseXml(relativePath: String): Element {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return builder.parse(sourceFile(relativePath)).documentElement
    }

    private fun Element.children(tag: String): List<Element> =
        childNodes.let { nodes ->
            (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == tag }
        }

    private fun Element.excludes(): List<Element> = children("exclude")

    private fun Element.attr(name: String): String = getAttribute(name)

    private fun manifestText(): String = sourceFile("AndroidManifest.xml").readText()

    // ---- Manifest 接线 ----

    @Test
    fun `manifest 保留 allowBackup 并接线双格式备份规则`() {
        val manifest = manifestText()

        assertTrue("allowBackup 应保持 true（否则备份与 D2D 全关）", manifest.contains("""android:allowBackup="true""""))
        assertTrue(
            "Android 11- 需要 fullBackupContent 接线",
            manifest.contains("""android:fullBackupContent="@xml/backup_rules""""),
        )
        assertTrue(
            "Android 12+ 需要 dataExtractionRules 接线",
            manifest.contains("""android:dataExtractionRules="@xml/data_extraction_rules""""),
        )
    }

    // ---- 旧格式（Android 11-） ----

    @Test
    fun `legacy rules exclude books dir from file domain`() {
        val root = parseXml("res/xml/backup_rules.xml")

        assertEquals("full-backup-content", root.tagName)
        val excludes = root.excludes()
        assertEquals("只应排除 books 一项，实际 ${excludes.size} 项", 1, excludes.size)
        assertEquals("file", excludes[0].attr("domain"))
        assertEquals("books", excludes[0].attr("path"))
    }

    // ---- 新格式（Android 12+） ----

    @Test
    fun `cloud backup excludes books dir`() {
        val root = parseXml("res/xml/data_extraction_rules.xml")

        assertEquals("data-extraction-rules", root.tagName)
        val cloud = root.children("cloud-backup").singleOrNull()
            ?: error("data-extraction-rules 缺少 cloud-backup 配置")
        val excludes = cloud.excludes()
        assertEquals("cloud-backup 只应排除 books 一项，实际 ${excludes.size} 项", 1, excludes.size)
        assertEquals("file", excludes[0].attr("domain"))
        assertEquals("books", excludes[0].attr("path"))
    }

    @Test
    fun `device transfer keeps everything for whole library migration`() {
        val root = parseXml("res/xml/data_extraction_rules.xml")

        val transfer = root.children("device-transfer").singleOrNull()
            ?: error("data-extraction-rules 缺少 device-transfer 配置")
        assertTrue(
            "device-transfer 不应排除任何内容（换机整库迁移）",
            transfer.excludes().isEmpty(),
        )
    }

    @Test
    fun `cloud backup keeps settings and database restorable`() {
        val root = parseXml("res/xml/data_extraction_rules.xml")

        val cloud = root.children("cloud-backup").single()
        // 甲-B 口径：仅排除 books，sharedpref / database 走默认全量包含
        val excludedPaths = cloud.excludes().map { it.attr("path") }
        assertFalse("sharedpref 不得排除", excludedPaths.contains("sharedpref"))
        assertFalse("database 不得排除", excludedPaths.contains("database"))
    }
}
