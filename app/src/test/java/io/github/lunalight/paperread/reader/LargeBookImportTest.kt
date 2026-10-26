package io.github.lunalight.paperread.reader

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.github.lunalight.paperread.data.repository.DefaultBookImporter
import io.github.lunalight.paperread.data.repository.PrepareResult
import io.github.lunalight.paperread.data.repository.PendingImport
import io.github.lunalight.paperread.fakes.FakeBookRepository
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * R-05（G5）200MB GBK 大文件验收的 **JVM 自动化口径**（T-007）。
 *
 * ## 边界（务必与 docs/PERFORMANCE_BASELINE.md 对照阅读）
 *
 * 本环境无 KVM、无模拟器、无真机，instrumented 测试跑不了。本类用 Robolectric
 * 直测**真实导入流水线**（[DefaultBookImporter] → 流式拷贝 + SHA-256 → 编码探测 →
 * `TxtChapterStream` 流式切分 → `book.txt` 落盘），能覆盖的部分：
 * - 章节切分数量与独立参考实现一致（GBK 大文件不丢章、不多切）；
 * - 首章 / 末章 GBK 解码无乱码，且**末章不以换行截断**（最常见的边界回归）；
 * - 双偏移（char / byte）全链连续、末章 byteEnd == book.txt 长度（正文零丢失）；
 * - 导入耗时可测（记录，不设性能门禁——JVM 与真机不可比）；
 * - 进程内堆增量可测（**不是** Android PSS，口径不同，见文档）。
 *
 * 不能覆盖的（真机项，见 docs/DEVICE_CHECKLIST.md §7）：ANR、进度对话框流畅度、
 * 真实 PSS 峰值、ART 上的 GC 行为。本类**不声称**覆盖这些。
 *
 * ## 样本策略（体积门控）
 *
 * | 用例 | 体积 | 触发方式 |
 * |------|------|---------|
 * | 中等样本（CI 默认） | `-Dpaperread.largeBook.mb`，缺省 20MB | 无条件执行 |
 * | 200MB 样本 | 200MB | `-Dpaperread.largeBook.200mb=true` |
 * | 脚本产物核对（PARITY） | 脚本生成的真实 200MB | 环境变量 `PAPERREAD_SAMPLE_PATH` 指向样本 |
 *
 * 200MB 用例不进默认 CI：单次约需 200MB 输入 + 300MB 输出磁盘与数十秒 CPU，
 * 在共享 runner 上是纯浪费；改由发版前手动跑（命令见 PERFORMANCE_BASELINE.md）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LargeBookImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var importer: DefaultBookImporter
    private lateinit var textStore: FileBookTextStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        textStore = FileBookTextStore(context)
        // 判重走内存 fake：本类关心的是流水线的解析 / 落盘 / 边界，不关心 Room
        importer = DefaultBookImporter(context, FakeBookRepository(), textStore)
    }

    // ---- 中等样本：CI 默认门禁 ----

    @Test
    fun `imports medium GBK sample with exact chapter count and clean boundaries`() = runTest {
        val targetMb = System.getProperty(PROP_MEDIUM_MB)?.toLongOrNull() ?: DEFAULT_MEDIUM_MB
        val sample = GbkSampleBookGenerator.generate(
            dir = tmp.newFolder("medium"),
            targetBytes = targetMb * 1024L * 1024L,
            fileName = "medium-book-gbk.txt",
        )

        val report = importAndVerify(sample, "medium(${targetMb}MB)")

        // 章节数三方一致：生成器计数、独立逐行扫描、导入流水线
        assertEquals(
            "生成器计数应等于独立参考实现",
            sample.chapters,
            GbkSampleBookGenerator.referenceChapterCount(sample.file),
        )
        assertEquals("导入章节数应与样本已知章节数一致", sample.chapters, report.chapters)

        // 安全上限（防挂死），**不是**性能门禁：JVM 侧耗时与真机不可比
        assertTrue(
            "导入耗时 ${report.elapsedMs}ms 超过 10 分钟安全上限（疑似死循环 / 无进展）",
            report.elapsedMs < SAFETY_CEILING_MS,
        )
        println(report.describe())
    }

    // ---- 200MB 样本：手动门控（发版前执行） ----

    @Test
    fun `imports 200MB GBK sample when manually enabled`() = runTest {
        assumeTrue(
            "200MB 用例默认跳过：-D$PROP_LARGE_200MB=true 手动开启",
            java.lang.Boolean.getBoolean(PROP_LARGE_200MB),
        )
        val sample = GbkSampleBookGenerator.generate(
            dir = tmp.newFolder("large"),
            targetBytes = 200L * 1024L * 1024L,
            fileName = "large-book-200mb-gbk.txt",
        )

        val report = importAndVerify(sample, "large(200MB)")

        assertEquals(
            "200MB 样本章节数应与独立参考实现一致",
            GbkSampleBookGenerator.referenceChapterCount(sample.file),
            report.chapters,
        )
        assertEquals("200MB 样本章节数应与生成器计数一致", sample.chapters, report.chapters)
        assertTrue(
            "导入耗时 ${report.elapsedMs}ms 超过 15 分钟安全上限",
            report.elapsedMs < SAFETY_CEILING_MS * 2,
        )
        println(report.describe())
    }

    // ---- 脚本产物核对：把 scripts/gen-large-book-sample.sh 的真实输出喂进流水线 ----

    @Test
    fun `imports script generated sample and matches its meta`() = runTest {
        val path = System.getProperty(PROP_SAMPLE_PATH) ?: System.getenv(ENV_SAMPLE_PATH)
        assumeTrue(
            "未提供 -D$PROP_SAMPLE_PATH / $ENV_SAMPLE_PATH（指向 gen-large-book-sample.sh 的输出），跳过脚本产物核对",
            !path.isNullOrBlank(),
        )
        val sampleFile = File(path!!)
        assumeTrue("样本文件不存在：$path", sampleFile.isFile)
        val metaFile = File("$path.meta")
        assumeTrue("样本元数据不存在：$metaFile", metaFile.isFile)

        val meta = readMeta(metaFile)
        val chapterCount = meta["CHAPTERS"]?.toIntOrNull()
        val gbkBytes = meta["GBK_BYTES"]?.toLongOrNull()
        val utf8Bytes = meta["UTF8_BYTES"]?.toLongOrNull()
        val firstLine = meta["FIRST_LINE"].orEmpty()
        val lastLine = meta["LAST_LINE"].orEmpty()
        assertTrue("meta 缺少 CHAPTERS", chapterCount != null)
        assertTrue("meta 缺少 GBK_BYTES", gbkBytes != null)
        assertTrue("meta 缺少 UTF8_BYTES", utf8Bytes != null)

        val pending = prepare(sampleFile)
        val bookTxt = textStore.textFile(pending.dirName)

        assertEquals("章节数应与脚本输出一致", chapterCount, pending.chapters.size)
        assertEquals("源文件大小应与脚本输出一致", gbkBytes, pending.fileSize)
        assertEquals("totalChars 应覆盖全文", pending.totalChars, pending.chapters.sumOf { it.charCount })
        // book.txt = 正文按 UTF-8 重编码（脚本的 UTF8_BYTES 少统计了被截掉的末尾换行）
        assertEquals(
            "book.txt 字节数应与脚本 UTF-8 体积一致（正文零改写）",
            utf8Bytes!! - 1,
            bookTxt.length(),
        )

        val first = textStore.readChapter(pending.dirName, pending.chapters.first())
        val last = textStore.readChapter(pending.dirName, pending.chapters.last())
        assertEquals("首章首行应与脚本一致", firstLine, first.lineSequence().first())
        assertTrue(
            "末章末行应与脚本一致（实际结尾：${last.takeLast(40)}）",
            last.trimEnd('\n').endsWith(lastLine),
        )
        assertFalse("首章不应含替换字符", first.contains('\uFFFD'))
        assertFalse("末章不应含替换字符", last.contains('\uFFFD'))
        println(
            "脚本产物核对通过：chapters=${pending.chapters.size} totalChars=${pending.totalChars} " +
                "source=${pending.fileSize}B book.txt=${bookTxt.length()}B",
        )
    }

    // ---- 内部 ----

    /** 一次导入的可测指标（JVM 口径，与真机 PSS 不可比）。 */
    private data class ImportReport(
        val label: String,
        val chapters: Int,
        val totalChars: Long,
        val sourceBytes: Long,
        val bookTxtBytes: Long,
        val elapsedMs: Long,
        val heapBeforeBytes: Long,
        val heapPeakBytes: Long,
    ) {
        fun describe(): String = buildString {
            append("大文件导入指标[$label] 章节数=$chapters 字符数=$totalChars ")
            append("源文件=${sourceBytes}B book.txt=${bookTxtBytes}B ")
            append("耗时=${elapsedMs}ms ")
            append("堆占用(导入前)=${heapBeforeBytes / 1024 / 1024}MB ")
            append("堆占用(20ms 采样峰值)=${heapPeakBytes / 1024 / 1024}MB")
            append("（JVM 堆口径，非 Android PSS，二者不可直接比较）")
        }
    }

    /** 导入 [sample] 并断言与样本元数据一致，返回可测指标。 */
    private suspend fun importAndVerify(sample: GbkSampleBookGenerator.Sample, label: String): ImportReport {
        val runtime = Runtime.getRuntime()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()

        var pending: PendingImport? = null
        var elapsedMs = 0L
        val sampler = HeapSampler()
        try {
            val startNs = System.nanoTime()
            pending = prepare(sample.file)
            elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        } finally {
            sampler.close()
        }
        val result = requireNotNull(pending)

        assertEquals("GBK 样本应探测为 GB18030", "GB18030", result.charsetName)
        assertEquals("源文件大小应与样本一致", sample.bytes, result.fileSize)
        assertEquals("totalChars 应等于样本字符数", sample.chars, result.totalChars)
        assertEquals("章节数应与样本一致", sample.chapters, result.chapters.size)

        val bookTxt = textStore.textFile(result.dirName)
        assertTrue("book.txt 应已落盘", bookTxt.isFile)
        assertEquals("book.txt 字节数应等于 UTF-8 正文体积", sample.utf8Bytes, bookTxt.length())
        assertFalse(
            "本应在流式路径写完即替换，不应残留临时文件",
            File(textStore.bookDir(result.dirName), "book.txt.tmp").exists(),
        )

        // ---- 双偏移连续性：任一环断裂都意味着正文错位 / 丢失 ----
        var expectedCharStart = 0L
        var expectedByteStart = 0L
        var cumulative = 0L
        result.chapters.forEachIndexed { i, chapter ->
            assertEquals("第 $i 章 index 应连续", i, chapter.index)
            assertEquals("第 $i 章 charStart 应与上一章 charEnd 相接", expectedCharStart, chapter.charStart)
            assertEquals("第 $i 章 byteStart 应与上一章 byteEnd 相接", expectedByteStart, chapter.byteStart)
            assertEquals("第 $i 章 charCount 应等于区间长度", chapter.charEnd - chapter.charStart, chapter.charCount)
            assertEquals("第 $i 章 cumulativeChars 应等于前缀和", cumulative, chapter.cumulativeChars)
            assertTrue("第 $i 章区间不应为空", chapter.charEnd > chapter.charStart)
            // QA-逐章标题锚定：上四条都是「相邻自洽」断言（首尾相接 + 前缀和），
            // 区间整体平移一章仍能全部成立——真正钉住边界位置的是「本章区间起点
            // 读出来的标题必须是第 i+1 章」。样本标题由生成器确定性产出，
            // 因此可对全部章（而非仅首末章）做零 I/O 的严格比对。
            assertEquals(
                "第 $i 章标题应为第 ${i + 1} 章标题（边界不得错位到相邻章）",
                GbkSampleBookGenerator.chapterTitle(i + 1),
                chapter.title,
            )
            expectedCharStart = chapter.charEnd
            expectedByteStart = chapter.byteEnd
            cumulative += chapter.charCount
        }
        assertEquals("末章 charEnd 应等于全文字符数", sample.chars, expectedCharStart)
        assertEquals("末章 byteEnd 应等于 book.txt 长度（正文零丢失）", bookTxt.length(), expectedByteStart)
        assertEquals("累计字符数应等于 totalChars", result.totalChars, cumulative)

        // ---- 首章 / 末章 GBK 解码正确性 ----
        val firstChapter = result.chapters.first()
        val firstText = textStore.readChapter(result.dirName, firstChapter)
        assertEquals("首章标题应为第 1 章", GbkSampleBookGenerator.chapterTitle(1), firstChapter.title)
        assertTrue("首章应以章节标题开头", firstText.startsWith(GbkSampleBookGenerator.chapterTitle(1)))
        assertTrue(
            "首章正文应为第一行样本句",
            firstText.contains(GbkSampleBookGenerator.bodyLine(1, 0, GbkSampleBookGenerator.DEFAULT_SEED)),
        )
        assertFalse("首章不应含替换字符（GBK 解码正确）", firstText.contains('\uFFFD'))

        val lastChapter = result.chapters.last()
        val lastText = textStore.readChapter(result.dirName, lastChapter)
        assertEquals(
            "末章标题应为最后一章",
            GbkSampleBookGenerator.chapterTitle(sample.chapters),
            lastChapter.title,
        )
        assertTrue("末章应包含章节标题", lastText.startsWith(GbkSampleBookGenerator.chapterTitle(sample.chapters)))
        assertFalse("末章不应含替换字符（GBK 解码正确）", lastText.contains('\uFFFD'))
        assertFalse("样本末行不以换行截断：末章文本不应以换行结尾", lastText.endsWith("\n"))
        assertFalse("book.txt 末字节不应为换行", lastByteOf(bookTxt) == '\n'.code.toByte())

        // ---- QA-中间章抽读锚定 ----
        // 上方标题锚定已覆盖全部章（零 I/O，比对的是切分产出的 title），
        // 但 title 与「按 byteStart/byteEnd 读出的正文」是两条独立的取值路径：
        // 若 byteStart 有系统性偏移而 charStart 无（或反之），标题断言仍会全绿，
        // readChapter 却读到错位的正文。抽读 1/4、1/2、3/4 三章把两条路径钉在一起。
        if (sample.chapters >= 4) {
            listOf(sample.chapters / 4, sample.chapters / 2, sample.chapters * 3 / 4).forEach { index ->
                val chapter = result.chapters[index]
                val text = textStore.readChapter(result.dirName, chapter)
                val expectedTitle = GbkSampleBookGenerator.chapterTitle(index + 1)
                assertTrue(
                    "第 $index 章按字节偏移读出的正文应以该章标题开头（首行实际：${text.take(24)}）",
                    text.startsWith(expectedTitle),
                )
                assertTrue(
                    "第 $index 章正文应含该章第一行样本句",
                    text.contains(
                        GbkSampleBookGenerator.bodyLine(
                            index + 1, 0, GbkSampleBookGenerator.DEFAULT_SEED,
                        ),
                    ),
                )
                assertFalse("第 $index 章不应含替换字符", text.contains('\uFFFD'))
            }
        }

        return ImportReport(
            label = label,
            chapters = result.chapters.size,
            totalChars = result.totalChars,
            sourceBytes = result.fileSize,
            bookTxtBytes = bookTxt.length(),
            elapsedMs = elapsedMs,
            heapBeforeBytes = heapBefore,
            heapPeakBytes = sampler.peakBytes,
        )
    }

    private suspend fun prepare(sampleFile: File): PendingImport {
        val uri = Uri.parse("content://io.github.lunalight.paperread.test/${sampleFile.name}")
        shadowOf(context.contentResolver).registerInputStream(uri, FileInputStream(sampleFile))
        val result = importer.prepare(uri)
        assertTrue("期望导入成功，实际 $result", result is PrepareResult.Success)
        return (result as PrepareResult.Success).pending
    }

    private fun lastByteOf(file: File): Byte = RandomAccessFile(file, "r").use { raf ->
        raf.seek(raf.length() - 1)
        raf.readByte()
    }

    private fun readMeta(file: File): Map<String, String> =
        file.readLines(Charsets.UTF_8)
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }
            .toMap()

    /**
     * 堆采样器：每 20ms 记录一次 `totalMemory - freeMemory` 的峰值。
     *
     * 局限（写进文档）：G1 的 `-Xmx2g` 分代堆 + 未显式 GC，"已用堆"是**进程内**
     * 观测值，包含测试框架与样本构造的临时对象；Android 的 PSS 是**整进程物理
     * 内存**。两者只可用于「同一环境内的前后对比」，不可换算。
     */
    private class HeapSampler : AutoCloseable {
        private val running = AtomicBoolean(true)
        private val peak = AtomicLong(0L)
        private val thread = Thread(
            {
                val runtime = Runtime.getRuntime()
                while (running.get()) {
                    val used = runtime.totalMemory() - runtime.freeMemory()
                    peak.accumulateAndGet(used) { a, b -> if (a > b) a else b }
                    try {
                        Thread.sleep(SAMPLE_INTERVAL_MS)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                }
            },
            "large-book-heap-sampler",
        ).apply {
            isDaemon = true
            start()
        }

        val peakBytes: Long get() = peak.get()

        override fun close() {
            running.set(false)
            thread.join(2_000L)
        }
    }

    companion object {
        /** CI 默认中等样本体积（MB）。 */
        const val DEFAULT_MEDIUM_MB: Long = 20L

        const val PROP_MEDIUM_MB: String = "paperread.largeBook.mb"
        const val PROP_LARGE_200MB: String = "paperread.largeBook.200mb"
        const val PROP_SAMPLE_PATH: String = "paperread.samplePath"
        const val ENV_SAMPLE_PATH: String = "PAPERREAD_SAMPLE_PATH"

        /** 安全上限（防挂死），非性能门禁。 */
        const val SAFETY_CEILING_MS: Long = 10L * 60L * 1000L

        private const val SAMPLE_INTERVAL_MS: Long = 20L
    }
}
