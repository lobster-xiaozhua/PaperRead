package io.github.lunalight.paperread.backup

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份包的**隐私静态断言**（T-016 / AC-14.2、AC-14.10）。
 *
 * 运行时断言（`BackupCryptoTest` / `BackupExporterTest`）证明「这次导出的字节
 * 里没有口令」；本类补另一面：备份源码**没有日志出口、没有网络句柄**——
 * 即使将来有人加了一行 `Log.d("pwd", ...)` 或一个 URL，也会在这里变红。
 *
 * 扫描前剥掉注释：`BackupCrypto` 的 KDoc 正文明写「本包不引用 android.util.Log /
 * println」，那不是调用（与 `check-room-migration.sh` 剥注释的做法同源）。
 */
class BackupPrivacyTest {

    private fun backupSources(): List<File> {
        val candidates = listOf(
            File("src/main/java/io/github/lunalight/paperread/backup"),
            File("app/src/main/java/io/github/lunalight/paperread/backup"),
        )
        val dir = candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 backup 源码目录：${File("").absolutePath}")
        return dir.listFiles { file -> file.extension == "kt" }?.sortedBy { it.name }.orEmpty()
    }

    /** 去掉块注释与行注释后的代码（保留字符串字面量，宁可严一些）。 */
    private fun codeOf(file: File): String = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lineSequence()
        .joinToString("\n") { line -> line.substringBefore("//") }

    @Test
    fun `备份源码没有任何日志出口`() {
        val sources = backupSources()
        assertTrue("backup 源码不应为空", sources.isNotEmpty())

        sources.forEach { file ->
            val code = codeOf(file)
            assertFalse("${file.name} 不得引用 android.util.Log", code.contains("android.util.Log"))
            assertFalse("${file.name} 不得 println（口令可能被打印）", code.contains("println("))
            assertFalse("${file.name} 不得 printStackTrace", code.contains("printStackTrace"))
        }
    }

    @Test
    fun `备份源码不含网络 API 与 URL 字面量`() {
        backupSources().forEach { file ->
            val code = codeOf(file)
            assertFalse("${file.name} 不得引用 java.net（AC-14.10 零网络）", code.contains("java.net."))
            assertFalse("${file.name} 不得出现 http(s) URL", Regex("https?://").containsMatchIn(code))
            assertFalse("${file.name} 不得使用 socket", code.contains("Socket("))
        }
    }
}
