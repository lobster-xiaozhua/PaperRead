package io.github.lunalight.paperread.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份条目命名的白名单契约（P3-4）。
 *
 * `BackupEntries.isSafeDirName` 的 KDoc 承诺的是 **ASCII** 白名单
 * `[A-Za-z0-9_-]+`，而原实现用 `Char.isLetterOrDigit()`，按 Unicode 放行了
 * 中文 / 全角 / emoji 等字符——文档与实现不符。条目名虽不参与落盘路径
 * （解包只写调用方给的临时文件名，zip slip 无着力点），但 manifest 是
 * 可被第三方构造的输入，校验口径必须与文档逐字符一致，否则「白名单」
 * 只是一句无法验证的声明。
 *
 * 本类把边界钉死：合法值（UUID / 下划线 / 连字符）放行，非 ASCII（中文 /
 * emoji / 全角空格）、空白、路径穿越片段、超长名一律拒绝。
 */
class BackupEntriesTest {

    @Test
    fun `生产值形态的目录名全部放行`() {
        // 生产值是导入时生成的 UUID（含小写十六进制与连字符）
        assertTrue(BackupEntries.isSafeDirName("3f2a1c9e-8b7d-4e6f-9a0b-1c2d3e4f5a6b"))
        assertTrue(BackupEntries.isSafeDirName("dir-a"))
        assertTrue(BackupEntries.isSafeDirName("dir_b"))
        assertTrue(BackupEntries.isSafeDirName("ABC123"))
        assertTrue("单字符也是合法值", BackupEntries.isSafeDirName("a"))
        assertTrue("64 字符是长度上界", BackupEntries.isSafeDirName("a".repeat(64)))
    }

    @Test
    fun `非 ASCII 字符一律拒绝（中文 全角 emoji）`() {
        assertFalse("中文目录名不在 ASCII 白名单内", BackupEntries.isSafeDirName("书籍一"))
        assertFalse("单个中文字符同样拒绝", BackupEntries.isSafeDirName("书"))
        assertFalse("全角空格", BackupEntries.isSafeDirName("ａ"))
        assertFalse("emoji", BackupEntries.isSafeDirName("📚"))
        assertFalse("emoji 与合法字符混排", BackupEntries.isSafeDirName("dir-📚"))
        assertFalse("阿拉伯-印度数字", BackupEntries.isSafeDirName("dir١٢٣"))
        assertFalse("重音字母", BackupEntries.isSafeDirName("café"))
    }

    @Test
    fun `空白与路径片段一律拒绝`() {
        assertFalse("空串", BackupEntries.isSafeDirName(""))
        assertFalse("半角空格", BackupEntries.isSafeDirName("dir a"))
        assertFalse("制表符", BackupEntries.isSafeDirName("dir\ta"))
        assertFalse("换行", BackupEntries.isSafeDirName("dir\na"))
        assertFalse("dot-dot 穿越", BackupEntries.isSafeDirName(".."))
        assertFalse("相对路径穿越", BackupEntries.isSafeDirName("../etc"))
        assertFalse("斜杠", BackupEntries.isSafeDirName("dir/a"))
        assertFalse("反斜杠", BackupEntries.isSafeDirName("dir\\a"))
        assertFalse("点号（不在白名单内）", BackupEntries.isSafeDirName("dir.a"))
        assertFalse("65 字符超上界", BackupEntries.isSafeDirName("a".repeat(65)))
    }

    @Test
    fun `条目路径必须落在约定前缀下且不含穿越片段`() {
        assertTrue(
            BackupEntries.isSafeEntryPath(
                BackupEntries.stateEntry("dir-a"),
                BackupEntries.STATE_PREFIX,
            ),
        )
        assertTrue(
            BackupEntries.isSafeEntryPath(
                BackupEntries.sourceEntry("dir-a", "txt"),
                BackupEntries.BOOKS_PREFIX,
            ),
        )
        assertFalse(
            "前缀不符",
            BackupEntries.isSafeEntryPath("books/dir-a/source.txt", BackupEntries.STATE_PREFIX),
        )
        assertFalse(
            "含穿越片段",
            BackupEntries.isSafeEntryPath("state/../dir-a.json", BackupEntries.STATE_PREFIX),
        )
        assertFalse(
            "含反斜杠",
            BackupEntries.isSafeEntryPath("state\\dir-a.json", BackupEntries.STATE_PREFIX),
        )
    }
}
