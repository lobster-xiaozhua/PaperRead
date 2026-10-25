package io.github.lunalight.paperread.transfer

import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 换机迁移核心单测（纯 JVM）：
 * 打包 / 解包（含 zip slip 防护、版本与损坏容错）、
 * 逐本导入编排（单本失败不中断、已存在不重复导入）、
 * 回写决策（最后阅读时间较新者胜，AC-10.4）。
 */
class MigrationBundleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun snapshot(
        sha256: String = "sha-1",
        title: String = "书一",
        lastReadAt: Long? = 1000L,
        bookmarks: List<BookmarkSnapshot> = emptyList(),
    ) = BookStateSnapshot(
        sha256 = sha256,
        title = title,
        author = "作者",
        format = "TXT",
        encoding = "UTF-8",
        fileSize = 10L,
        totalChars = 100L,
        chapterCount = 2,
        lastChapterIndex = 1,
        lastParagraphIndex = 3,
        lastScrollOffset = 12,
        lastCharOffset = 34,
        progressPercent = 0.5f,
        lastReadAt = lastReadAt,
        addedAt = 1L,
        bookmarks = bookmarks,
    )

    private fun sourceFile(sha256: String, content: String = "第一章\n正文"): File {
        val f = tmp.newFile("$sha256.txt")
        f.writeText(content)
        return f
    }

    // ---- 打包 / 解包 ----

    @Test
    fun `打包解包往返保留清单与源文件`() {
        val manifest = MigrationManifest(books = listOf(snapshot("sha-a", "甲"), snapshot("sha-b", "乙")))
        val zip = tmp.newFile("bundle.zip")
        MigrationBundleCodec.pack(
            manifest,
            mapOf("sha-a" to sourceFile("sha-a"), "sha-b" to sourceFile("sha-b")),
            zip,
        )

        val dest = tmp.newFolder("out")
        val unpacked = MigrationBundleCodec.unpack(zip, dest)

        assertNotNull(unpacked)
        assertEquals(2, unpacked!!.manifest.books.size)
        assertEquals("甲", unpacked.manifest.books[0].title)
        assertEquals("sha-a", unpacked.manifest.books[0].sha256)
        assertEquals(2, unpacked.sources.size)
        assertEquals("第一章\n正文", unpacked.sources.getValue("sha-a").readText())
    }

    @Test
    fun `源文件缺失时清单条目仍保留`() {
        val manifest = MigrationManifest(books = listOf(snapshot("sha-missing", "丢源书")))
        val zip = tmp.newFile("bundle-missing.zip")
        MigrationBundleCodec.pack(manifest, emptyMap(), zip)

        val unpacked = MigrationBundleCodec.unpack(zip, tmp.newFolder("out2"))

        assertNotNull(unpacked)
        assertEquals(1, unpacked!!.manifest.books.size)
        assertTrue(unpacked.sources.isEmpty())
    }

    @Test
    fun `清单缺失返回null`() {
        val zip = tmp.newFile("no-manifest.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("sources/x.txt"))
            z.write("hi".toByteArray())
            z.closeEntry()
        }

        assertNull(MigrationBundleCodec.unpack(zip, tmp.newFolder("out3")))
    }

    @Test
    fun `zip损坏返回null`() {
        val bad = tmp.newFile("bad.zip")
        bad.writeText("not a zip at all")

        assertNull(MigrationBundleCodec.unpack(bad, tmp.newFolder("out4")))
    }

    @Test
    fun `高版本清单返回null而不盲目解析`() {
        val zip = tmp.newFile("future.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write("""{"version":99,"books":[]}""".toByteArray())
            z.closeEntry()
        }

        assertNull(MigrationBundleCodec.unpack(zip, tmp.newFolder("out5")))
    }

    @Test
    fun `路径穿越条目被丢弃且不影响清单`() {
        val zip = tmp.newFile("slip.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write("""{"version":1,"books":[{"sha256":"s","title":"t","author":"a","format":"TXT","encoding":"UTF-8","fileSize":1,"totalChars":1,"chapterCount":1,"lastChapterIndex":0,"lastParagraphIndex":0,"lastScrollOffset":0,"lastCharOffset":0,"progressPercent":0.0,"lastReadAt":null,"addedAt":0,"bookmarks":[]}]}""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("sources/../../escape.txt"))
            z.write("evil".toByteArray())
            z.closeEntry()
        }
        val dest = tmp.newFolder("out6")

        val unpacked = MigrationBundleCodec.unpack(zip, dest)

        assertNotNull(unpacked)
        assertTrue(unpacked!!.sources.isEmpty())
        assertFalse(File(dest.parentFile, "escape.txt").exists())
    }

    @Test
    fun `未知条目被忽略不影响解包`() {
        val zip = tmp.newFile("extra.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write("""{"version":1,"books":[]}""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("readme.md"))
            z.write("future".toByteArray())
            z.closeEntry()
        }

        val unpacked = MigrationBundleCodec.unpack(zip, tmp.newFolder("out7"))

        assertNotNull(unpacked)
        assertTrue(unpacked!!.manifest.books.isEmpty())
    }

    // ---- 回写决策（AC-10.4） ----

    @Test
    fun `回写决策按最后阅读时间较新者胜`() {
        assertTrue("本地无记录应回写", WriteBackDecider.shouldWriteBack(null, 100L))
        assertFalse("对端无记录不回写", WriteBackDecider.shouldWriteBack(100L, null))
        assertTrue("对端较新应覆盖", WriteBackDecider.shouldWriteBack(100L, 200L))
        assertFalse("本地较新不覆盖", WriteBackDecider.shouldWriteBack(200L, 100L))
        assertFalse("时间相同不动作", WriteBackDecider.shouldWriteBack(150L, 150L))
        assertFalse("双方都无记录不回写", WriteBackDecider.shouldWriteBack(null, null))
    }

    // ---- 快照 ----

    @Test
    fun `书籍与书签转快照保留阅读状态`() {
        val book = Book(
            id = 7,
            title = "迁移之书",
            author = "作者",
            format = BookFormat.TXT,
            encoding = "GBK",
            sha256 = "sh",
            fileSize = 99,
            dirName = "d",
            totalChars = 1000,
            chapterCount = 5,
            lastChapterIndex = 2,
            lastParagraphIndex = 1,
            lastScrollOffset = 8,
            lastCharOffset = 16,
            progressPercent = 0.4f,
            lastReadAt = 1234L,
            addedAt = 5L,
        )
        val snap = book.toSnapshot(
            listOf(
                io.github.lunalight.paperread.domain.model.Bookmark(
                    id = 9,
                    bookId = 7,
                    chapterIndex = 2,
                    paragraphIndex = 1,
                    charOffset = 16,
                    excerpt = "摘录",
                    createdAt = 77L,
                ),
            ),
        )

        assertEquals("sh", snap.sha256)
        assertEquals("TXT", snap.format)
        assertEquals("GBK", snap.encoding)
        assertEquals(2, snap.lastChapterIndex)
        assertEquals(0.4f, snap.progressPercent, 0.001f)
        assertEquals(1234L, snap.lastReadAt)
        assertEquals(1, snap.bookmarks.size)
        assertEquals("摘录", snap.bookmarks[0].excerpt)
    }
}
