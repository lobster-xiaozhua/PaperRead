package io.github.lunalight.paperread.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 文件名净化直测（G4：sanitizeFileName 文件名穿越）。
 *
 * HTTP 上传通道与 [MigrationBundleCodec] 解包的 zip slip 防护是对称的两条
 * 防线：后者由 MigrationBundleTest 固化，本类固化前者——恶意 / 畸形文件名
 * 不得把落盘位置带出 cacheDir/transfer/。
 *
 * 输入面覆盖评审清单：`../` 穿越、绝对路径、Windows 路径、超长名、
 * 非法字符、控制字符、保留设备名（登记行为）。
 */
class SanitizeFileNameTest {

    @Test
    fun `目录成分被剥离只留末段文件名`() {
        assertEquals("passwd", sanitizeFileName("../../etc/passwd"))
        assertEquals("passwd", sanitizeFileName("/etc/passwd"))
        assertEquals("evil.exe", sanitizeFileName("C:\\Windows\\system32\\evil.exe"))
        assertEquals("evil", sanitizeFileName("..\\..\\evil"))
        assertEquals("book.txt", sanitizeFileName("a/b/book.txt"))
    }

    @Test
    fun `点号保留名被拒绝（穿越残留）`() {
        // ".." / "." 作为文件名会解析到父目录 / 当前目录，必须拒绝而非放行
        assertNull(sanitizeFileName(".."))
        assertNull(sanitizeFileName("."))
        assertNull(sanitizeFileName("../.."))
        assertNull(sanitizeFileName("a/.."))
    }

    @Test
    fun `空白与全非法字符输入被拒绝`() {
        assertNull(sanitizeFileName(""))
        assertNull(sanitizeFileName("   "))
        assertNull(sanitizeFileName("///"))
        assertNull(sanitizeFileName("\\\\"))
        assertNull(sanitizeFileName(":*?\"<>|"))
        // 首尾空白剥离后为空
        assertNull(sanitizeFileName("\t\n "))
    }

    @Test
    fun `非法字符按字符剔除而非整名拒绝`() {
        assertEquals("abcdefgh.txt", sanitizeFileName("a<b>c:d*e?f\"g|h.txt"))
        // 控制字符（0x07 BEL 等）剔除，可打印部分保留
        assertEquals("ctrl", sanitizeFileName("\u0007\u0008ctrl"))
        assertEquals("book.txt", sanitizeFileName("  book.txt  "))
    }

    @Test
    fun `超长名截断到 64 字符`() {
        val long = "a".repeat(100)
        val cleaned = sanitizeFileName(long)!!
        assertEquals(64, cleaned.length)
        assertEquals("a".repeat(64), cleaned)

        // 恰好 64 字符（含扩展名）原样通过
        val exact = "b".repeat(60) + ".txt"
        assertEquals(64, exact.length)
        assertEquals(exact, sanitizeFileName(exact))
    }

    @Test
    fun `Unicode 书名保留（不因非 ASCII 剔除）`() {
        // 传书展示名 = 书名 + 扩展名；剔除非 ASCII 会把中文书名丢成空壳
        assertEquals("测试书名.txt", sanitizeFileName("测试书名.txt"))
        assertEquals("にほンご.txt", sanitizeFileName("にほンご.txt"))
    }

    @Test
    fun `保留设备名按普通文件名放行（Android 文件系统无保留名约束）`() {
        // CON / PRN / AUX / NUL / COM1 / LPT1 是 Windows 语义；Android（Linux）
        // 文件系统上它们是合法文件名，穿越防线不依赖保留名过滤，此处登记行为
        listOf("CON", "PRN", "AUX", "NUL", "COM1", "LPT1.txt").forEach { name ->
            assertEquals(name, sanitizeFileName(name))
        }
    }

    @Test
    fun `纯点串与隐藏文件按普通文件名放行`() {
        // "...." 是字面文件名而非穿越（不等于 ".."），放行
        assertEquals("....", sanitizeFileName("...."))
        assertEquals(".hidden", sanitizeFileName(".hidden"))
        assertEquals("...txt", sanitizeFileName("...txt"))
    }
}
