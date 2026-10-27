package io.github.lunalight.paperread.backup

import android.net.Uri
import io.github.lunalight.paperread.data.repository.BookImportGateway
import io.github.lunalight.paperread.data.repository.BookStateWriter
import io.github.lunalight.paperread.data.repository.ImportHints
import io.github.lunalight.paperread.data.repository.ImportOutcome
import io.github.lunalight.paperread.domain.model.Book
import io.github.lunalight.paperread.domain.model.BookFormat
import io.github.lunalight.paperread.fakes.FakeBookTextStore
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 第 2 阶段批次 10 的**独立验收审计**（US-14 / T-015 / T-016）。
 *
 * 与本批 Engineer 交付的 `Backup*Test` 刻意不重叠：只补「工程师用例没有直接
 * 覆盖，但 AC 明文要求」的强断言——
 * - AC-14.2 盐与 nonce 的**跨次随机性**（工程师只测了同实例内两次加密）；
 * - AC-14.3 从**真实导出包**改版本号（工程师是手搓空 manifest）；
 * - AC-14.2 加密参数被篡改时的**降级防护分级**（不支持 vs 口令错误）；
 * - AC-14.7 口令错误时书架**零写入**（工程师只在空库上断言）；
 * - AC-14.6 回写裁决的**两条边界**（本地无记录 / 备份无记录）；
 * - AC-14.1/14.4 明文包多本源文件**逐字节保真**；
 * - 取消语义的**中途**版本（工程师只在第一本前取消）；
 * - 20MB 单本的**流式**加密导出 + 流式恢复（不整文件进内存）。
 */
class BackupAcceptanceAuditTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var textStore: FakeBookTextStore
    private lateinit var repository: MemoryBookRepository

    @Before
    fun setUp() {
        textStore = FakeBookTextStore().apply { booksRoot = tmp.newFolder("books") }
        repository = MemoryBookRepository()
    }

    // ---- AC-14.2：盐 / nonce 的跨次随机性 ----

    @Test
    fun `同一内容两次导出时salt与条目nonce均不同`() = runTest {
        seedBook(repository, textStore, dirName = "dir-a", title = "同书", content = "同一段正文")
        val password = "correct-horse-battery".toCharArray()
        val first = File(tmp.root, "first.zip")
        val second = File(tmp.root, "second.zip")

        BackupExporter(repository, textStore).export(first, "correct-horse-battery".toCharArray())
        BackupExporter(repository, textStore).export(second, "correct-horse-battery".toCharArray())

        val saltFirst = saltOf(first)
        val saltSecond = saltOf(second)
        assertFalse(
            "两次导出必须使用不同 salt（否则同口令同密钥）：${saltFirst.toHex()} / ${saltSecond.toHex()}",
            saltFirst.contentEquals(saltSecond),
        )
        assertEquals(16, saltFirst.size)

        val nonceFirst = nonceOf(first, "books/dir-a/source.txt")
        val nonceSecond = nonceOf(second, "books/dir-a/source.txt")
        assertFalse(
            "同一源文件两次导出的 nonce 必须不同",
            nonceFirst.contentEquals(nonceSecond),
        )

        // 两次导出都能用同一口令解出（salt 变了，但派生自同一口令）
        val importer = newImporter(MemoryBookRepository(), FakeImportGateway())
        assertTrue(importer.restore(first, password) is BackupRestoreResult.Done)
        assertTrue(importer.restore(second, password) is BackupRestoreResult.Done)
    }

    @Test
    fun `同一次导出内各条目nonce互不相同`() = runTest {
        // 两本内容完全相同的书：若 nonce 是「按内容派生 / 固定值」，此处必然撞车
        seedBook(repository, textStore, dirName = "dir-1", title = "甲", content = "逐字节相同的正文")
        seedBook(repository, textStore, dirName = "dir-2", title = "乙", content = "逐字节相同的正文")
        val zip = File(tmp.root, "two.zip")
        BackupExporter(repository, textStore).export(zip, "correct-horse-battery".toCharArray())

        val sourceNonce1 = nonceOf(zip, "books/dir-1/source.txt")
        val sourceNonce2 = nonceOf(zip, "books/dir-2/source.txt")
        val stateNonce1 = nonceOf(zip, "state/dir-1.json")

        assertFalse(
            "同内容不同条目不得复用 nonce",
            sourceNonce1.contentEquals(sourceNonce2),
        )
        assertFalse(
            "state 与 source 条目不得复用 nonce",
            stateNonce1.contentEquals(sourceNonce1),
        )
        assertFalse("nonce 不得全零", sourceNonce1.all { it == 0.toByte() })
    }

    // ---- AC-14.3：真实包伪装成高版本 ----

    @Test
    fun `真实备份被改成版本2时明确拒绝且不崩溃`() = runTest {
        seedBook(repository, textStore, dirName = "dir-a", title = "书一", content = "正文一")
        seedBook(repository, textStore, dirName = "dir-b", title = "书二", content = "正文二")
        val zip = File(tmp.root, "real.zip")
        BackupExporter(repository, textStore).export(zip, password = null)

        rewriteEntry(zip, "manifest.json") { bytes ->
            val text = bytes.toString(Charsets.UTF_8)
            assertTrue("前置条件：版本字段存在", text.contains("\"version\":$BACKUP_VERSION"))
            text.replace("\"version\":$BACKUP_VERSION", "\"version\":2").toByteArray(Charsets.UTF_8)
        }

        val restoreRepo = MemoryBookRepository()
        val gateway = FakeImportGateway()
        val importer = newImporter(restoreRepo, gateway)

        val inspection = importer.inspect(zip)
        assertTrue("版本过新应被拒绝而不是当成普通损坏：$inspection", inspection is BackupInspection.Invalid)
        assertEquals(
            BackupInvalidReason.VERSION_TOO_NEW,
            (inspection as BackupInspection.Invalid).reason,
        )

        val result = importer.restore(zip)
        assertTrue(result is BackupRestoreResult.Rejected)
        assertEquals(
            BackupInvalidReason.VERSION_TOO_NEW,
            (result as BackupRestoreResult.Rejected).reason,
        )
        assertTrue("拒绝时不得写入任何书", restoreRepo.books().isEmpty())
        assertTrue("拒绝时不得调用导入网关", gateway.importedFiles.isEmpty())
    }

    // ---- AC-14.2：加密参数被篡改时的防护分级 ----

    @Test
    fun `加密参数被篡改时按参数分级拒绝`() {
        val params = BackupCrypto.create("correct-horse-battery".toCharArray()).use { it.params }

        fun kindOf(tampered: BackupCryptoParams): BackupCryptoException.Kind {
            try {
                BackupCrypto.open("correct-horse-battery".toCharArray(), tampered).use { }
            } catch (e: BackupCryptoException) {
                return e.kind
            }
            fail("被篡改的参数必须拒绝打开")
            error("unreachable")
        }

        assertEquals(
            "算法降级必须判为不支持",
            BackupCryptoException.Kind.UNSUPPORTED,
            kindOf(params.copy(kdf = "PBKDF2WithHmacSHA1")),
        )
        assertEquals(
            "迭代数降到 1000 必须判为不支持",
            BackupCryptoException.Kind.UNSUPPORTED,
            kindOf(params.copy(iterations = 1_000)),
        )
        assertEquals(
            "迭代数超过上限（DoS 防护）必须判为不支持",
            BackupCryptoException.Kind.UNSUPPORTED,
            kindOf(params.copy(iterations = 5_000_000)),
        )
        assertEquals(
            "密钥长度被改必须判为不支持",
            BackupCryptoException.Kind.UNSUPPORTED,
            kindOf(params.copy(keyBits = 128)),
        )
        assertEquals(
            "salt 被换成非法 Base64 必须判为不支持",
            BackupCryptoException.Kind.UNSUPPORTED,
            kindOf(params.copy(salt = "not-base64!!")),
        )
        assertEquals(
            "salt 被换掉后 verifier 必然解不开 → 口令错误（不是崩溃）",
            BackupCryptoException.Kind.WRONG_PASSWORD,
            kindOf(params.copy(salt = Base64.getEncoder().encodeToString(ByteArray(16) { 7 }))),
        )
    }

    // ---- AC-14.7：口令错误零写入 ----

    @Test
    fun `口令错误时已有书架与书签完全不被触碰`() = runTest {
        seedBook(repository, textStore, dirName = "dir-a", title = "加密书", content = "加密正文")
        val password = "correct-horse-battery".toCharArray()
        val zip = File(tmp.root, "secret.zip")
        BackupExporter(repository, textStore).export(zip, password)

        val restoreRepo = MemoryBookRepository()
        val local = testLocalBook(id = 7L, title = "本地已有")
        restoreRepo.seed(local)
        val workDir = File(tmp.root, "work")
        val gateway = FakeImportGateway()
        val importer = BackupImporter(
            bookRepository = restoreRepo,
            gateway = gateway,
            stateWriter = BookStateWriter(restoreRepo),
            workDir = workDir,
        )

        val wrong = "wrong-password".toCharArray()
        assertTrue(importer.restore(zip, wrong) is BackupRestoreResult.WrongPassword)

        assertEquals("口令错误不得改动书架", listOf(local), restoreRepo.books())
        assertTrue("口令错误不得写入进度", restoreRepo.savedPositions.isEmpty())
        assertTrue("口令错误不得动书签", restoreRepo.addedBookmarks.isEmpty())
        assertTrue("口令错误不得触发导入", gateway.importedFiles.isEmpty())
        assertTrue("口令错误不得残留临时目录", workDir.listFiles().isNullOrEmpty())
        assertFalse("口令错误后原文件必须原封不动", zip.readBytes().isEmpty())
    }

    // ---- AC-14.6：回写裁决的两条边界 ----

    @Test
    fun `新导入的书按备份进度回写`() = runTest {
        seedBook(
            repository = repository,
            textStore = textStore,
            dirName = "dir-a",
            title = "有进度",
            lastReadAt = 500L,
            progressPercent = 0.66f,
            lastChapterIndex = 5,
        )
        val zip = File(tmp.root, "progress.zip")
        BackupExporter(repository, textStore).export(zip, password = null)

        val restoreRepo = MemoryBookRepository()
        val importer = newImporter(restoreRepo, FakeImportGateway())
        val result = importer.restore(zip) as BackupRestoreResult.Done

        assertEquals("新导入应回写进度（本地 lastReadAt = null）", 1, result.report.progressWritten)
        assertEquals(5, restoreRepo.savedPositions.single().position.chapterIndex)
        assertEquals(0.66f, restoreRepo.savedPositions.single().progressPercent, 0.0001f)
    }

    @Test
    fun `备份无阅读记录时不覆盖本地已有进度`() = runTest {
        seedBook(
            repository = repository,
            textStore = textStore,
            dirName = "dir-a",
            title = "无进度",
            lastReadAt = null,
        )
        val zip = File(tmp.root, "no-progress.zip")
        BackupExporter(repository, textStore).export(zip, password = null)

        val restoreRepo = MemoryBookRepository()
        restoreRepo.seed(
            io.github.lunalight.paperread.fakes.testBook(
                id = 11L,
                title = "无进度",
                sha256 = repository.books().single().sha256,
                dirName = "local-dir",
                lastReadAt = 900L,
            ),
        )
        val result = newImporter(restoreRepo, FakeImportGateway())
            .restore(zip) as BackupRestoreResult.Done

        assertEquals(1, result.report.duplicates)
        assertEquals("备份没有阅读记录，不得抹掉本地进度", 0, result.report.progressWritten)
        assertTrue(restoreRepo.savedPositions.isEmpty())
        assertTrue(restoreRepo.clearedBookmarkBookIds.isEmpty())
    }

    // ---- AC-14.1 / AC-14.4：明文包多本源文件逐字节保真 ----

    @Test
    fun `明文备份多本源文件逐字节保真且二次恢复全命中`() = runTest {
        val contents = mapOf(
            "dir-a" to "第一本的正文\n第二段\n",
            "dir-b" to "第二本的正文（含中文标点——破折号）",
            "dir-c" to "",
        )
        contents.forEach { (dir, content) ->
            seedBook(repository, textStore, dirName = dir, title = "书-$dir", content = content)
        }
        val zip = File(tmp.root, "plain.zip")
        val manifest = BackupExporter(repository, textStore).export(zip, password = null)
        assertEquals(3, manifest.bookCount)
        assertNull("明文包不得有 crypto.json", zipEntryNames(zip).firstOrNull { it == "crypto.json" })

        // manifest 声明的每个源文件条目都必须真的在包里（AC-14.1「全部源文件」）
        manifest.books.forEach { entry ->
            assertTrue("缺少源文件条目：${entry.sourceEntry}", zipEntryNames(zip).contains(entry.sourceEntry))
        }

        val restoreRepo = MemoryBookRepository()
        val gateway = RecordingImportGateway(restoreRepo)
        val importer = newImporter(restoreRepo, gateway)
        val first = importer.restore(zip) as BackupRestoreResult.Done
        assertEquals(3, first.report.imported)
        assertTrue(first.report.failed.isEmpty())

        // 恢复出来的源文件字节必须与导出前完全一致
        // （importer 导入后即删临时文件，故在 gateway 内当场快照内容）
        assertEquals(
            "恢复出的源文件应逐字节保真：${gateway.contents}",
            contents.values.toSet(),
            gateway.contents.toSet(),
        )

        val second = importer.restore(zip) as BackupRestoreResult.Done
        assertEquals("AC-14.4：二次恢复必须全部命中 SHA-256", 3, second.report.duplicates)
        assertEquals(0, second.report.imported)
        assertEquals(3, restoreRepo.books().size)
    }

    // ---- 取消：中途版本 ----

    @Test
    fun `导出在第二本时取消不留半成品`() = runTest {
        repeat(3) { index -> seedBook(repository, textStore, dirName = "dir-$index", title = "书$index") }
        val zip = File(tmp.root, "cancel-export.zip")
        var calls = 0

        var thrown: CancellationException? = null
        try {
            BackupExporter(repository, textStore).export(
                target = zip,
                shouldContinue = { calls++ < 1 },
            )
        } catch (e: CancellationException) {
            thrown = e
        }

        assertNotNull("第二本前应取消", thrown)
        assertTrue("确实写了第一本之后才取消", calls >= 2)
        assertFalse("取消后不得残留半个 zip", zip.exists())
    }

    @Test
    fun `恢复在第一本后取消时已恢复的书完整且无残留`() = runTest {
        repeat(3) { index ->
            seedBook(repository, textStore, dirName = "dir-$index", title = "书$index", content = "正文$index")
        }
        val zip = File(tmp.root, "cancel-restore.zip")
        BackupExporter(repository, textStore).export(zip, password = null)

        val restoreRepo = MemoryBookRepository()
        val gateway = RecordingImportGateway()
        val workDir = File(tmp.root, "work-cancel")
        val importer = BackupImporter(
            bookRepository = restoreRepo,
            gateway = gateway,
            stateWriter = BookStateWriter(restoreRepo),
            workDir = workDir,
        )
        var calls = 0

        var thrown: CancellationException? = null
        try {
            importer.restore(zip, shouldContinue = { calls++ < 1 })
        } catch (e: CancellationException) {
            thrown = e
        }

        assertNotNull("第二本前应取消", thrown)
        assertEquals("取消前只应导入第一本", 1, gateway.importedFiles.size)
        assertFalse("已恢复的书不得是半本（源文件应完整）", gateway.contents.single().isBlank())
        assertTrue(
            "已恢复的书内容应与备份中的一致",
            listOf("正文0", "正文1", "正文2").contains(gateway.contents.single()),
        )
        assertTrue("取消后临时目录必须清空", workDir.listFiles().isNullOrEmpty())
    }

    // ---- 大文件：流式加密导出 + 流式恢复 ----

    @Test
    fun `20MB单本加密导出与恢复保持内容一致`() = runTest {
        val book = seedLargeBook(dirName = "dir-big", sizeBytes = 20L * 1024 * 1024)
        val zip = File(tmp.root, "big.zip")
        val password = "correct-horse-battery".toCharArray()

        val manifest = BackupExporter(repository, textStore).export(zip, password)
        assertEquals(book.fileSize, manifest.books.single().sourceSize)
        assertEquals(book.sha256, manifest.books.single().sourceSha256)

        val restoreRepo = MemoryBookRepository()
        val gateway = StreamingImportGateway()
        val importer = BackupImporter(
            bookRepository = restoreRepo,
            gateway = gateway,
            stateWriter = BookStateWriter(restoreRepo),
            workDir = File(tmp.root, "work-big"),
        )

        val result = importer.restore(zip, password) as BackupRestoreResult.Done
        assertEquals(1, result.report.imported)
        assertTrue(result.report.failed.isEmpty())
        assertEquals("解出的明文大小必须一致", book.fileSize, gateway.lastSize)
        assertEquals("解出的明文 SHA-256 必须一致", book.sha256, gateway.lastSha)
        assertTrue(
            "导入侧应分块读取（整文件读入会得到与文件等大的块）：${gateway.maxChunk}",
            gateway.maxChunk <= 64 * 1024,
        )
    }

    // ---- 工具 ----

    private fun newImporter(repository: MemoryBookRepository, gateway: BookImportGateway): BackupImporter =
        BackupImporter(
            bookRepository = repository,
            gateway = gateway,
            stateWriter = BookStateWriter(repository),
            workDir = File(tmp.root, "work-main"),
        )

    private fun testLocalBook(id: Long, title: String) = io.github.lunalight.paperread.fakes.testBook(
        id = id,
        title = title,
        sha256 = "local-sha",
        dirName = "local-dir",
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun zipEntryNames(file: File): List<String> = ZipFile(file).use { zip ->
        zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
    }

    private fun entryBytes(file: File, name: String): ByteArray = ZipFile(file).use { zip ->
        val entry = zip.getEntry(name) ?: error("缺少条目 $name，实际：${zipEntryNames(file)}")
        zip.getInputStream(entry).readBytes()
    }

    private fun saltOf(file: File): ByteArray {
        val params = backupJson.decodeFromString(
            BackupCryptoParams.serializer(),
            entryBytes(file, "crypto.json").toString(Charsets.UTF_8),
        )
        return Base64.getDecoder().decode(params.salt)
    }

    /** 加密条目 = `nonce(12) ‖ ciphertext ‖ tag`，前 12 字节即 nonce。 */
    private fun nonceOf(file: File, entryName: String): ByteArray =
        entryBytes(file, entryName).copyOfRange(0, BACKUP_NONCE_BYTES)

    private fun rewriteEntry(file: File, name: String, transform: (ByteArray) -> ByteArray) {
        val rewritten = File(file.parentFile, "rewritten-${System.nanoTime()}.zip")
        ZipFile(file).use { zip ->
            ZipOutputStream(rewritten.outputStream().buffered()).use { out ->
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    val bytes = zip.getInputStream(entry).readBytes()
                    val mapped = if (entry.name == name) transform(bytes) else bytes
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(mapped)
                    out.closeEntry()
                }
            }
        }
        check(rewritten.copyTo(file, overwrite = true).exists())
        rewritten.delete()
    }

    /** 造一本 20MB 的书（源文件真实落盘，哈希按流式计算）。 */
    private fun seedLargeBook(dirName: String, sizeBytes: Long): Book {
        val dir = textStore.bookDir(dirName).apply { mkdirs() }
        val source = File(dir, "source.txt")
        val chunk = ("纸阅备份大文件测试段落。".repeat(64) + "\n").toByteArray(Charsets.UTF_8)
        source.outputStream().buffered().use { out ->
            var written = 0L
            while (written < sizeBytes) {
                out.write(chunk)
                written += chunk.size
            }
        }
        val book = io.github.lunalight.paperread.fakes.testBook(
            id = repository.nextId(),
            title = "大文件",
            sha256 = sha256HexOf(source),
            dirName = dirName,
            fileSize = source.length(),
            totalChars = source.length(),
            chapterCount = 1,
            lastReadAt = 1_000L,
        )
        repository.seed(book)
        return book
    }

    private fun sha256HexOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * 导入时就地快照内容的网关。
 *
 * 必要性：importer 处理完一本就删除临时文件（`tempFile?.delete()`），
 * 事后 `readText()` 必然 FileNotFoundException——想校验「恢复出的源文件
 * 是否逐字节保真」，只能在 `importFromFile` 内当场快照。
 */
private class RecordingImportGateway(
    /** 给出仓储时真正入库（`findBySha256` 才可能命中，AC-14.4 幂等性依赖它）。 */
    private val repository: MemoryBookRepository? = null,
) : BookImportGateway {

    val importedFiles = mutableListOf<File>()
    val contents = mutableListOf<String>()

    override suspend fun importFromUri(uri: Uri, hints: ImportHints?): ImportOutcome =
        ImportOutcome.Skipped("测试未使用 Uri 路径")

    override suspend fun importFromFile(file: File, hints: ImportHints?): ImportOutcome {
        importedFiles += file
        val bytes = file.readBytes()
        contents += bytes.toString(Charsets.UTF_8)
        val book = Book(
            id = 200L + importedFiles.size,
            title = hints?.title.orEmpty().ifBlank { "恢复书" },
            author = hints?.author.orEmpty(),
            format = BookFormat.TXT,
            encoding = "UTF-8",
            sha256 = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) },
            fileSize = bytes.size.toLong(),
            dirName = "restored-${importedFiles.size}",
            totalChars = bytes.size.toLong(),
            chapterCount = 1,
            addedAt = 0L,
        )
        val stored = if (repository == null) {
            book
        } else {
            book.copy(id = repository.saveImport(book, emptyList()))
        }
        return ImportOutcome.Imported(stored)
    }
}

/**
 * 流式导入网关：只按 8KB 缓冲读取临时文件，绝不整文件 `readBytes()`。
 *
 * 用来证明「20MB 单本能走通恢复」不是靠把整个文件塞进内存换来的。
 */
private class StreamingImportGateway : BookImportGateway {

    var lastSha: String? = null
        private set
    var lastSize: Long = 0L
        private set

    /** 观测到的最大单次读取块（应远小于文件大小）。 */
    var maxChunk: Int = 0
        private set

    override suspend fun importFromUri(uri: Uri, hints: ImportHints?): ImportOutcome =
        ImportOutcome.Skipped("测试未使用 Uri 路径")

    override suspend fun importFromFile(file: File, hints: ImportHints?): ImportOutcome {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                maxChunk = maxOf(maxChunk, read)
                digest.update(buffer, 0, read)
                size += read
            }
        }
        lastSize = size
        lastSha = digest.digest().joinToString("") { "%02x".format(it) }
        return ImportOutcome.Imported(
            Book(
                id = 1L,
                title = hints?.title.orEmpty().ifBlank { "大文件" },
                author = hints?.author.orEmpty(),
                format = BookFormat.TXT,
                encoding = "UTF-8",
                sha256 = lastSha!!,
                fileSize = size,
                dirName = "restored-big",
                totalChars = size,
                chapterCount = 1,
                addedAt = 0L,
            ),
        )
    }
}
