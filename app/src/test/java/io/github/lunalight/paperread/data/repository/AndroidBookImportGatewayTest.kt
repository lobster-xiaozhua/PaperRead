package io.github.lunalight.paperread.data.repository

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.fakes.FakeBookImporter
import io.github.lunalight.paperread.fakes.FakeBookRepository
import io.github.lunalight.paperread.fakes.FakeBookTextStore
import io.github.lunalight.paperread.fakes.testBook
import io.github.lunalight.paperread.reader.txt.SplitStrategy
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 共享导入网关单测（T-015 / AC-9.5 / AC-14.4）。
 *
 * 迁移与备份恢复都经本类导入，因此这里固化三条契约：
 * 1. 每条 [PrepareResult] 分支的结局与话术（迁移侧行为等价的依据）；
 * 2. `hints` 只覆盖书名 / 作者，不改解析结果（备份恢复保留原书名）；
 * 3. `importFromFile` 真的经 FileProvider 转 content Uri（与传书接收端同路径）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBookImportGatewayTest {

    private lateinit var context: Context
    private lateinit var importer: FakeBookImporter
    private lateinit var repository: FakeBookRepository
    private lateinit var gateway: AndroidBookImportGateway

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // FileProvider 的 PathStrategy 按 authority 静态缓存且绑定首次解析时的
        // cacheDir；Robolectric 每个测试方法重建目录，不清缓存会找不到根。
        runCatching {
            val cache = androidx.core.content.FileProvider::class.java
                .getDeclaredField("sCache")
                .apply { isAccessible = true }
                .get(null)
            (cache as? HashMap<*, *>)?.clear()
        }
        importer = FakeBookImporter()
        repository = FakeBookRepository()
        gateway = AndroidBookImportGateway(context, importer, repository)
    }

    @After
    fun tearDown() {
        runCatching {
            val cache = androidx.core.content.FileProvider::class.java
                .getDeclaredField("sCache")
                .apply { isAccessible = true }
                .get(null)
            (cache as? HashMap<*, *>)?.clear()
        }
    }

    private fun pending(title: String = "解析书名", author: String = "解析作者") = PendingImport(
        dirName = "dir-gateway",
        displayName = "gateway.txt",
        title = title,
        author = author,
        format = BookFormat.TXT,
        charsetName = "UTF-8",
        strategy = SplitStrategy.CONSERVATIVE,
        sha256 = "sha-gateway",
        fileSize = 10L,
        totalChars = 20L,
        chapters = emptyList(),
    )

    private fun tempFile(name: String = "gateway.txt"): File {
        val dir = File(context.cacheDir, "transfer").apply { mkdirs() }
        return File(dir, name).apply { writeText("第一章 起点\n正文") }
    }

    @Test
    fun `成功导入返回书籍并保留解析得到的书名`() = runBlocking {
        importer.prepareResult = PrepareResult.Success(pending())
        importer.confirmResult = 7L
        val imported = testBook(
            id = 7L,
            title = "解析书名",
            author = "解析作者",
            sha256 = "sha-gateway",
        )
        repository.book = imported

        val outcome = gateway.importFromFile(tempFile())

        assertTrue("应导入成功：$outcome", outcome is ImportOutcome.Imported)
        assertEquals(imported, (outcome as ImportOutcome.Imported).book)
        assertEquals("解析书名", importer.confirmed.single().title)
        assertEquals("解析作者", importer.confirmed.single().author)
        val call = importer.prepareCalls.single()
        assertEquals("content", call.uri.scheme)
        assertTrue(
            "应经 FileProvider 转 content Uri：${call.uri}",
            call.uri.authority!!.endsWith(".fileprovider"),
        )
    }

    @Test
    fun `元数据提示覆盖书名作者但不改章节解析结果`() = runBlocking {
        val parsed = pending(title = "解析书名", author = "解析作者")
        importer.prepareResult = PrepareResult.Success(parsed)
        importer.confirmResult = 3L
        repository.book = testBook(id = 3L, sha256 = "sha-gateway")

        gateway.importFromFile(tempFile(), ImportHints(title = "备份书名", author = "备份作者"))

        val confirmed = importer.confirmed.single()
        assertEquals("备份书名", confirmed.title)
        assertEquals("备份作者", confirmed.author)
        assertEquals("章节列表必须沿用解析结果", parsed.chapters, confirmed.chapters)
        assertEquals(parsed.charsetName, confirmed.charsetName)
    }

    @Test
    fun `空提示字段沿用解析结果`() = runBlocking {
        importer.prepareResult = PrepareResult.Success(pending())
        importer.confirmResult = 3L
        repository.book = testBook(id = 3L, sha256 = "sha-gateway")

        gateway.importFromFile(tempFile(), ImportHints(title = "", author = ""))

        assertEquals("解析书名", importer.confirmed.single().title)
        assertEquals("解析作者", importer.confirmed.single().author)
    }

    @Test
    fun `重复内容返回跳过且不调用confirm`() = runBlocking {
        importer.prepareResult = PrepareResult.Duplicate(
            testBook(id = 1L, title = "已有书"),
        )

        val outcome = gateway.importFromUri(Uri.parse("content://test/dup.txt"))

        assertEquals(ImportOutcome.Skipped("内容已存在于书架"), outcome)
        assertTrue(importer.confirmed.isEmpty())
    }

    @Test
    fun `失败与不支持格式翻译为用户话术`() = runBlocking {
        importer.prepareResult = PrepareResult.Failure(ImportError.DrmEncrypted("drm"))
        assertEquals(
            ImportOutcome.Skipped("该 EPUB 含 DRM 加密，无法导入"),
            gateway.importFromUri(Uri.parse("content://test/drm.epub")),
        )

        importer.prepareResult = PrepareResult.Unsupported(ImportError.UnsupportedFormat("pdf"))
        assertEquals(
            ImportOutcome.Skipped("仅支持 TXT / EPUB 格式"),
            gateway.importFromUri(Uri.parse("content://test/a.pdf")),
        )
        assertTrue(importer.confirmed.isEmpty())
    }

    @Test
    fun `入库后读取书籍失败时记失败而非崩溃`() = runBlocking {
        importer.prepareResult = PrepareResult.Success(pending())
        importer.confirmResult = 9L
        repository.book = null

        val outcome = gateway.importFromUri(Uri.parse("content://test/ok.txt"))

        assertEquals(ImportOutcome.Skipped("入库成功但读取失败"), outcome)
        assertTrue(importer.confirmed.isNotEmpty())
    }

    /**
     * 真实流水线探针：FileProvider → contentResolver → PreparePipeline。
     *
     * 若 Robolectric 下 FileProvider 的 openInputStream 不可用，此用例会红——
     * 那意味着备份恢复的端到端只能在真机验证；当前实现下它应当是绿的，
     * 也是 E2E 用例敢直接用 [AndroidBookImportGateway] 的前提。
     */
    @Test
    fun `真实导入流水线经FileProvider读取临时文件`() = runBlocking {
        val textStore = FakeBookTextStore().apply {
            booksRoot = File(context.cacheDir, "e2e-books").apply { mkdirs() }
        }
        val repo = FakeBookRepository().apply { nextImportId = 11L }
        val realImporter = DefaultBookImporter(context, repo, textStore)
        val realGateway = AndroidBookImportGateway(context, realImporter, repo)
        repo.book = testBook(id = 11L, sha256 = "whatever")

        val file = tempFile("real.txt")
        val outcome = realGateway.importFromFile(file)

        assertTrue("真实流水线应能经 FileProvider 读到文件：$outcome", outcome is ImportOutcome.Imported)
        assertEquals(1, repo.savedImports.size)
        assertEquals("real", repo.savedImports.single().book.title)
        assertTrue("book.txt 应已生成", textStore.textFile(repo.savedImports.single().book.dirName).exists())
    }
}
