package io.github.lunalight.paperread.backup

import io.github.lunalight.paperread.domain.model.Bookmark
import io.github.lunalight.paperread.fakes.FakeBookTextStore
import io.github.lunalight.paperread.fakes.testBook
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 备份导出单测（T-015 / US-14 / AC-14.1、AC-14.2、AC-14.8）。
 *
 * 覆盖：包结构（manifest / state / source，**不含 book.txt**）、manifest 计数与
 * 内容摘要、SHA-256 与大小、进度回调、取消不留半成品、口令加密后的条目形态
 * （manifest 可读、书名不落在明文、口令不出现在文件字节里）。
 */
class BackupExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var textStore: FakeBookTextStore
    private lateinit var repository: MemoryBookRepository
    private lateinit var exporter: BackupExporter
    private lateinit var outFile: File

    @Before
    fun setUp() {
        textStore = FakeBookTextStore().apply { booksRoot = tmp.newFolder("books") }
        repository = MemoryBookRepository()
        exporter = BackupExporter(
            bookRepository = repository,
            bookTextStore = textStore,
            fontReferencesProvider = { listOf(BackupFontReference("serif", "衬线", builtIn = true)) },
            appVersion = { "1.2.0-test" },
            clock = { 1_700_000_000_000L },
        )
        outFile = File(tmp.root, "backup.zip")
    }

    // ---- 结构（AC-14.1 / AC-14.8） ----

    @Test
    fun `导出包结构含manifest 阅读状态与源文件但不含book txt`() = runTest {
        val first = seedBook(
            repository = repository,
            textStore = textStore,
            dirName = "dir-a",
            title = "第一本",
            content = "第一本的正文",
            bookmarks = listOf(
                Bookmark(
                    bookId = 1L,
                    chapterIndex = 1,
                    paragraphIndex = 2,
                    charOffset = 30,
                    excerpt = "摘录一",
                    createdAt = 555L,
                ),
            ),
        )
        seedBook(repository, textStore, dirName = "dir-b", title = "第二本", lastReadAt = null)
        // book.txt 是派生文件：即便存在也不得进包
        textStore.textFile("dir-a").writeText("派生的正文")

        val manifest = exporter.export(outFile, password = null)

        ZipFile(outFile).use { zip ->
            val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            assertTrue("缺 manifest：$names", names.contains("manifest.json"))
            assertTrue("缺状态条目：$names", names.contains("state/dir-a.json"))
            assertTrue("缺源文件条目：$names", names.contains("books/dir-a/source.txt"))
            assertFalse("book.txt 不应进包：$names", names.any { it.contains("book.txt") })
            assertNull("明文包不应有 crypto.json", zip.getEntry("crypto.json"))

            val stateText = zip.getInputStream(zip.getEntry("state/dir-a.json"))
                .readBytes().toString(Charsets.UTF_8)
            assertTrue(stateText.contains("第一本"))
            assertTrue(stateText.contains("摘录一"))

            val sourceText = zip.getInputStream(zip.getEntry("books/dir-a/source.txt"))
                .readBytes().toString(Charsets.UTF_8)
            assertEquals("第一本的正文", sourceText)
        }

        assertEquals(BACKUP_VERSION, manifest.version)
        assertFalse(manifest.encrypted)
        assertEquals(1_700_000_000_000L, manifest.createdAt)
        assertEquals("1.2.0-test", manifest.appVersion)
        assertEquals(2, manifest.bookCount)
        assertEquals("只有第一本有阅读记录", 1, manifest.progressCount)
        assertEquals(1, manifest.bookmarkCount)
        assertEquals(listOf("serif"), manifest.fontReferences.map { it.name })
        assertEquals(manifest.contentDigest, backupContentDigest(manifest.books))

        val entry = manifest.books.single { it.dirName == "dir-a" }
        assertEquals(first.sha256, entry.sourceSha256)
        assertEquals(File(textStore.bookDir("dir-a"), "source.txt").length(), entry.sourceSize)
        assertEquals("state/dir-a.json", entry.stateEntry)
        assertEquals("第一本", entry.title)
        assertFalse(entry.sourceMissing)
    }

    @Test
    fun `源文件已丢失的书保留条目并标记sourceMissing`() = runTest {
        val book = testBook(id = 5L, title = "空壳书", dirName = "dir-ghost")
        repository.seed(book)

        val manifest = exporter.export(outFile, password = null)

        val entry = manifest.books.single()
        assertTrue(entry.sourceMissing)
        ZipFile(outFile).use { zip ->
            assertNull(zip.getEntry(entry.sourceEntry))
        }
    }

    // ---- 进度与取消 ----

    @Test
    fun `进度回调按本推进并以1结束`() = runTest {
        repeat(3) { index ->
            seedBook(repository, textStore, dirName = "dir-$index", title = "书$index")
        }
        val steps = mutableListOf<BackupProgress>()

        exporter.export(outFile, password = null, onProgress = { steps += it })

        assertTrue("应有 BOOKS 阶段进度", steps.any { it.stage == BackupStage.BOOKS })
        assertEquals(BackupStage.DONE, steps.last().stage)
        assertEquals(1f, steps.last().fraction, 0.0001f)
        assertEquals(3, steps.last().done)
        assertEquals(3, steps.last().total)
    }

    @Test
    fun `取消导出时抛取消且不留半成品`() = runTest {
        repeat(3) { index ->
            seedBook(repository, textStore, dirName = "dir-$index", title = "书$index")
        }

        var thrown: CancellationException? = null
        try {
            exporter.export(outFile, password = null, shouldContinue = { false })
        } catch (e: CancellationException) {
            thrown = e
        }

        assertNotNull("应抛 CancellationException", thrown)
        assertFalse("取消后不得残留半成品 zip", outFile.exists())
    }

    // ---- 加密（AC-14.2 / AC-14.8） ----

    @Test
    fun `口令加密后manifest 仍可读且书名与正文不落明文`() = runTest {
        seedBook(repository, textStore, dirName = "dir-secret", title = "秘密书名", content = "秘密正文")
        val password = "correct-horse-battery".toCharArray()

        val manifest = exporter.export(outFile, password = password)

        assertTrue(manifest.encrypted)
        // manifest 明文可读（AC-14.8 预览），但书名 / 作者置空
        assertNull(manifest.books.single().title)
        assertNull(manifest.books.single().author)
        assertEquals(BACKUP_VERSION, manifest.version)

        val raw = outFile.readBytes()
        assertFalse("口令不得出现在导出文件里", raw.toString(Charsets.ISO_8859_1).contains("correct-horse-battery"))
        assertFalse("书名不得出现在明文条目里", raw.toString(Charsets.ISO_8859_1).contains("秘密书名"))
        assertFalse("正文不得出现在明文条目里", raw.toString(Charsets.ISO_8859_1).contains("秘密正文"))

        ZipFile(outFile).use { zip ->
            assertNotNull("加密包必须有 crypto.json", zip.getEntry("crypto.json"))
            val paramsText = zip.getInputStream(zip.getEntry("crypto.json"))
                .readBytes().toString(Charsets.UTF_8)
            assertFalse("crypto.json 不含口令", paramsText.contains("correct-horse"))
            val params = backupJson.decodeFromString(BackupCryptoParams.serializer(), paramsText)

            BackupCrypto.open(password, params).use { crypto ->
                val statePlain = crypto.decrypt(
                    zip.getInputStream(zip.getEntry("state/dir-secret.json")).readBytes(),
                ).toString(Charsets.UTF_8)
                assertTrue("解密后应还原书名", statePlain.contains("秘密书名"))
                val sourcePlain = crypto.decrypt(
                    zip.getInputStream(zip.getEntry("books/dir-secret/source.txt")).readBytes(),
                ).toString(Charsets.UTF_8)
                assertEquals("秘密正文", sourcePlain)
            }
        }
    }

    @Test
    fun `加密条目每条目独立nonce`() = runTest {
        seedBook(repository, textStore, dirName = "dir-1", title = "甲", content = "同样的正文")
        seedBook(repository, textStore, dirName = "dir-2", title = "乙", content = "同样的正文")
        val password = "another-strong-pass".toCharArray()
        exporter.export(outFile, password = password)

        val params = ZipFile(outFile).use { zip ->
            backupJson.decodeFromString(
                BackupCryptoParams.serializer(),
                zip.getInputStream(zip.getEntry("crypto.json")).readBytes().toString(Charsets.UTF_8),
            )
        }
        BackupCrypto.open(password, params).use { crypto ->
            val first = crypto.encrypt("same".toByteArray())
            val second = crypto.encrypt("same".toByteArray())
            assertFalse("相同明文两次加密的 nonce 必须不同", first.copyOf(12).contentEquals(second.copyOf(12)))
            assertFalse("密文也应不同", first.contentEquals(second))
            assertEquals("same", crypto.decrypt(first).toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `口令强度不足时拒绝导出`() = runTest {
        seedBook(repository, textStore, dirName = "dir-x", title = "书")

        val error = runCatching {
            exporter.export(outFile, password = "short".toCharArray())
        }.exceptionOrNull()

        assertTrue("应抛 IllegalArgumentException：$error", error is IllegalArgumentException)
        assertFalse(outFile.exists())
    }
}
